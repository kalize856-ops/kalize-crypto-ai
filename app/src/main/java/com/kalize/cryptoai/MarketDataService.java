package com.kalize.cryptoai;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.database.Cursor;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import okhttp3.OkHttpClient;
import okhttp3.ConnectionSpec;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.net.InetAddress;
import java.net.Socket;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.io.IOException;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.json.JSONObject;
import java.util.concurrent.TimeUnit;

/**
 * Foreground WebSocket market feed. Stores only completed 1-minute candles.
 * Public market data only; this service cannot submit exchange orders.
 */
public final class MarketDataService extends Service {
    public static final String ACTION_STATUS = "com.kalize.cryptoai.MARKET_STATUS";
    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_QUOTES = "quotes";
    public static final String ACTION_STOP = "com.kalize.cryptoai.STOP_MARKET_STREAM";
    private static final String CHANNEL_ID = "kalize_market_stream";
    private static final int NOTIFICATION_ID = 4101;
    private static final String STREAM_URL =
            "wss://stream.binance.com:443/stream?streams=" +
            "btcusdt@kline_1m/ethusdt@kline_1m/solusdt@kline_1m/" +
            "btcusdt@ticker/ethusdt@ticker/solusdt@ticker";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private OkHttpClient client;
    private WebSocket socket;
    private PaperDatabaseHelper database;
    private OnDeviceLearningEngine learner;
    private AutoPaperTradingController autoTrader;
    private boolean stoppedByUser = false;
    private boolean connecting = false;
    private int retrySeconds = 2;
    private Thread backfillThread;
    private volatile boolean backfillRequested = false;
    private boolean connectivityReceiverRegistered = false;

    // Android can emit several CONNECTIVITY_ACTION broadcasts during one
    // hotspot handoff. Debounce them so a healthy socket is not repeatedly killed.
    private final Runnable networkRecovery = new Runnable() {
        @Override public void run() {
            if (stoppedByUser) return;
            ConnectivityManager manager = (ConnectivityManager)
                    getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo active = manager == null ? null : manager.getActiveNetworkInfo();
            boolean available = active != null && active.isConnected();
            if (!available) {
                if (socket != null) socket.cancel();
                socket = null;
                connecting = false;
                connectStartedAt = 0L;
                database.putSetting("market_feed_stale", "true");
                database.putSetting("market_feed_stale_reason", "Internet connection unavailable");
                broadcast("Internet unavailable · waiting for network");
                updateNotification("Waiting for internet connection");
                return;
            }
            // A duplicate connectivity broadcast must not interrupt a working feed.
            if (socket != null && lastMessageAt > 0L &&
                    System.currentTimeMillis() - lastMessageAt <= STALE_FEED_MS) return;
            handler.removeCallbacks(reconnect);
            if (socket != null) socket.cancel();
            socket = null;
            connecting = false;
            connectStartedAt = 0L;
            retrySeconds = 2;
            database.putSetting("market_feed_stale", "false");
            database.putSetting("market_feed_stale_reason", "");
            broadcast("Network restored · reconnecting and syncing missed candles");
            updateNotification("Network restored · reconnecting");
            connect();
        }
    };
    private final BroadcastReceiver connectivityReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (stoppedByUser) return;
            handler.removeCallbacks(networkRecovery);
            handler.postDelayed(networkRecovery, 1500L);
        }
    };
    private volatile long lastMessageAt = 0L;
    private volatile long connectStartedAt = 0L;
    private long lastMessagePersistedAt = 0L;
    private long lastPayloadPersistedAt = 0L;
    private boolean staleFeedReported = false;
    private static final long STALE_FEED_MS = 90000L;
    private static final long CONNECTING_TIMEOUT_MS = 30000L;
    private static final long MESSAGE_DIAGNOSTIC_WRITE_MS = 30000L;
    private static final long CLEANUP_INTERVAL_MS = 6L * 60L * 60L * 1000L;
    private static final long BACKFILL_RETRY_MS = 5L * 60L * 1000L;

    private final Runnable cleanupTask = new Runnable() {
        @Override public void run() {
            if (stoppedByUser) return;
            try { database.cleanExpiredCandles(); } catch (Exception ignored) { }
            handler.postDelayed(this, CLEANUP_INTERVAL_MS);
        }
    };
    private final Runnable backfillRetry = new Runnable() {
        @Override public void run() { if (!stoppedByUser) startHistoricalBackfill(); }
    };
    private final Runnable staleFeedCheck = new Runnable() {
        @Override public void run() {
            if (stoppedByUser) return;
            long now = System.currentTimeMillis();
            if (connecting && connectStartedAt > 0L &&
                    now - connectStartedAt > CONNECTING_TIMEOUT_MS && socket != null) {
                WebSocket stalled = socket;
                socket = null;
                connecting = false;
                connectStartedAt = 0L;
                stalled.cancel();
                database.putSetting("market_feed_stale", "true");
                database.putSetting("market_feed_stale_reason", "Connection handshake did not complete within 30 seconds");
                broadcast("Connection attempt timed out · retrying");
                updateNotification("Connection timed out · retrying");
                scheduleReconnect();
            } else if (socket != null && lastMessageAt > 0L &&
                    now - lastMessageAt > STALE_FEED_MS && !staleFeedReported) {
                staleFeedReported = true;
                database.putSetting("market_feed_stale", "true");
                database.putSetting("market_feed_stale_reason", "No WebSocket messages received for over 90 seconds");
                WebSocket stale = socket;
                socket = null;
                connecting = false;
                stale.cancel();
                broadcast("Market feed silent for over 90 seconds · reconnecting");
                updateNotification("Feed silent · reconnecting");
                scheduleReconnect();
            }
            handler.postDelayed(this, 30000L);
        }
    };

    private final Runnable reconnect = new Runnable() {
        @Override public void run() { connect(); }
    };

    @Override public void onCreate() {
        super.onCreate();
        database = new PaperDatabaseHelper(getApplicationContext());
        learner = new OnDeviceLearningEngine(database);
        autoTrader = new AutoPaperTradingController(database);
        client = buildCompatibleClient();
        createNotificationChannel();
        try {
            registerReceiver(connectivityReceiver,
                    new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
            connectivityReceiverRegistered = true;
        } catch (Exception ignored) { }
        startHistoricalBackfill();
        handler.postDelayed(cleanupTask, CLEANUP_INTERVAL_MS);
        handler.postDelayed(staleFeedCheck, 30000L);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stoppedByUser = true;
            handler.removeCallbacks(reconnect);
            if (socket != null) socket.close(1000, "Stopped by user");
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID, buildNotification("Connecting to market feed"));
        stoppedByUser = false;
        connect();
        return START_STICKY;
    }

    private void connect() {
        if (stoppedByUser || connecting || socket != null) return;
        connecting = true;
        connectStartedAt = System.currentTimeMillis();
        broadcast("Connecting");
        Request request = new Request.Builder().url(STREAM_URL).build();
        socket = client.newWebSocket(request, new WebSocketListener() {
            @Override public void onOpen(WebSocket webSocket, Response response) {
                if (socket != webSocket || stoppedByUser) { webSocket.cancel(); return; }
                connecting = false;
                connectStartedAt = 0L;
                lastMessageAt = System.currentTimeMillis();
                lastMessagePersistedAt = 0L;
                staleFeedReported = false;
                database.putSetting("market_feed_stale", "false");
                database.putSetting("market_feed_stale_reason", "");
                persistMessageFreshness(lastMessageAt);
                retrySeconds = 2;
                broadcast("Connected · checking for candles missed during connection gap");
                updateNotification("Connected · syncing recent candles");
                // A reconnect may follow a hotspot/Wi-Fi handoff. Refresh the
                // retained one-hour window so missed completed candles are restored.
                // startHistoricalBackfill() prevents overlapping backfill workers.
                startHistoricalBackfill();
            }

            @Override public void onMessage(WebSocket webSocket, String text) {
                // Ignore late frames from a socket that was replaced during reconnect.
                if (socket != webSocket || stoppedByUser) return;
                lastMessageAt = System.currentTimeMillis();
                persistMessageFreshness(lastMessageAt);
                handleMessage(text);
            }

            @Override public void onMessage(WebSocket webSocket, ByteString bytes) {
                // Ignore late frames from a socket that was replaced during reconnect.
                if (socket != webSocket || stoppedByUser) return;
                lastMessageAt = System.currentTimeMillis();
                persistMessageFreshness(lastMessageAt);
                handleMessage(bytes.utf8());
            }

            @Override public void onClosing(WebSocket webSocket, int code, String reason) {
                if (socket == webSocket && !stoppedByUser)
                    webSocket.close(1000, null);
            }

            @Override public void onClosed(WebSocket webSocket, int code, String reason) {
                if (socket == webSocket) {
                    socket = null;
                    connecting = false;
                    connectStartedAt = 0L;
                    scheduleReconnect();
                }
            }

            @Override public void onFailure(WebSocket webSocket, Throwable error, Response response) {
                if (socket == webSocket) {
                    socket = null;
                    connecting = false;
                    connectStartedAt = 0L;
                    String detail = describeNetworkFailure(error, response);
                    broadcast("Market connection failed · " + detail + " · retrying automatically");
                    updateNotification("Connection failed · retrying");
                    scheduleReconnect();
                }
            }
        });
    }

    /**
     * Android 4.4 devices may support TLS 1.2 but leave it disabled on default
     * SSLSockets. Enable TLS 1.2 explicitly while retaining the platform trust
     * store and OkHttp hostname verification.
     */
    private OkHttpClient buildCompatibleClient() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .pingInterval(20, TimeUnit.SECONDS)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(true)
                .connectionSpecs(java.util.Arrays.asList(
                        ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS));
        try {
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((java.security.KeyStore) null);
            TrustManager[] managers = tmf.getTrustManagers();
            X509TrustManager trustManager = null;
            for (TrustManager manager : managers) {
                if (manager instanceof X509TrustManager) {
                    trustManager = (X509TrustManager) manager;
                    break;
                }
            }
            if (trustManager != null) {
                SSLContext context = SSLContext.getInstance("TLSv1.2");
                context.init(null, new TrustManager[]{trustManager}, new SecureRandom());
                builder.sslSocketFactory(new Tls12SocketFactory(context.getSocketFactory()), trustManager);
            }
        } catch (Exception compatibilityError) {
            // Fall back to the platform TLS configuration; never bypass certificate checks.
        }
        return builder.build();
    }

    private static final class Tls12SocketFactory extends SSLSocketFactory {
        private final SSLSocketFactory delegate;
        Tls12SocketFactory(SSLSocketFactory delegate) { this.delegate = delegate; }

        private Socket enableTls12(Socket socket) {
            if (socket instanceof SSLSocket) {
                SSLSocket ssl = (SSLSocket) socket;
                java.util.List<String> supported = java.util.Arrays.asList(ssl.getSupportedProtocols());
                if (supported.contains("TLSv1.2")) ssl.setEnabledProtocols(new String[]{"TLSv1.2"});
            }
            return socket;
        }
        @Override public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
        @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }
        @Override public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
            return enableTls12(delegate.createSocket(s, host, port, autoClose));
        }
        @Override public Socket createSocket(String host, int port) throws IOException {
            return enableTls12(delegate.createSocket(host, port));
        }
        @Override public Socket createSocket(String host, int port, InetAddress local, int localPort) throws IOException {
            return enableTls12(delegate.createSocket(host, port, local, localPort));
        }
        @Override public Socket createSocket(InetAddress host, int port) throws IOException {
            return enableTls12(delegate.createSocket(host, port));
        }
        @Override public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort) throws IOException {
            return enableTls12(delegate.createSocket(host, port, local, localPort));
        }
        @Override public Socket createSocket() throws IOException { return enableTls12(delegate.createSocket()); }
    }

    /** Converts common Android network failures into actionable, non-technical status text. */
    private String describeNetworkFailure(Throwable error, Response response) {
        if (response != null) return "server returned HTTP " + response.code();
        if (error == null) return "unknown network error";
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        if (cause instanceof java.net.UnknownHostException)
            return "DNS lookup failed; check internet or hotspot DNS";
        if (cause instanceof javax.net.ssl.SSLHandshakeException ||
                cause instanceof javax.net.ssl.SSLPeerUnverifiedException)
            return "secure TLS handshake failed; device certificates or TLS support may be outdated";
        if (cause instanceof java.security.cert.CertificateException)
            return "server certificate could not be verified; check device date and system certificates";
        if (cause instanceof java.net.SocketTimeoutException)
            return "connection timed out; network may be slow or blocking the feed";
        if (cause instanceof java.net.ConnectException)
            return "server connection was refused or unreachable";
        if (cause instanceof java.net.UnknownServiceException)
            return "secure WebSocket protocol is not supported by this device";
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() +
                (message == null || message.length() == 0 ? "" : ": " + message);
    }

    /** Downloads up to 1 hour of completed one-minute candles without blocking the feed. */
    private void startHistoricalBackfill() {
        if (stoppedByUser) return;
        if (backfillThread != null && backfillThread.isAlive()) {
            // Preserve a reconnect request made while an older backfill is still running.
            backfillRequested = true;
            return;
        }
        backfillRequested = false;
        handler.removeCallbacks(backfillRetry);
        backfillThread = new Thread(new Runnable() {
            @Override public void run() {
                String[] symbols = new String[]{"BTCUSDT", "ETHUSDT", "SOLUSDT"};
                try {
                    long end = System.currentTimeMillis();
                    long start = end - 60L * 60L * 1000L;
                    for (String symbol : symbols) {
                        long cursor = start;
                        while (!Thread.currentThread().isInterrupted() && cursor < end) {
                            String url = "https://api.binance.com/api/v3/klines?symbol=" +
                                    symbol + "&interval=1m&startTime=" + cursor +
                                    "&endTime=" + end + "&limit=1000";
                            java.net.HttpURLConnection connection =
                                    (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                            connection.setConnectTimeout(15000);
                            connection.setReadTimeout(20000);
                            connection.setRequestMethod("GET");
                            try {
                                int code = connection.getResponseCode();
                                if (code != 200) throw new java.io.IOException("HTTP " + code);
                                java.io.BufferedReader reader = new java.io.BufferedReader(
                                        new java.io.InputStreamReader(connection.getInputStream(), "UTF-8"));
                                StringBuilder body = new StringBuilder();
                                String line;
                                while ((line = reader.readLine()) != null) body.append(line);
                                reader.close();
                                org.json.JSONArray rows = new org.json.JSONArray(body.toString());
                                if (rows.length() == 0) break;
                                long last = cursor;
                                for (int i = 0; i < rows.length(); i++) {
                                    org.json.JSONArray row = rows.getJSONArray(i);
                                    long openTime = row.getLong(0);
                                    if (openTime <= 0 || row.getLong(6) >= System.currentTimeMillis()) continue;
                                    database.saveCandle(symbol, "1m", openTime,
                                            row.getDouble(1), row.getDouble(2),
                                            row.getDouble(3), row.getDouble(4), row.getDouble(5));
                                    last = Math.max(last, openTime);
                                }
                                if (last < cursor || rows.length() < 1000) break;
                                cursor = last + 60000L;
                            } finally {
                                connection.disconnect();
                            }
                            try { Thread.sleep(120L); }
                            catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                            }
                        }
                        broadcast("Historical candles loaded · " + symbol);
                    }
                    database.cleanExpiredCandles();
                    for (String symbol : symbols) {
                        while (!Thread.currentThread().isInterrupted()) {
                            int learned = learner.learn(symbol, "1m");
                            if (learned == 0) break;
                        }
                    }
                    // Evaluate the latest completed candle immediately after backfill.
                    // Previously automation waited for the NEXT websocket candle, so
                    // an already-populated dashboard could show AI signals but no
                    // execution decision until another live kline event arrived.
                    evaluateLatestStoredCandles(symbols);
                    broadcast("1-hour history ready · AI learning active");
                    updateNotification("1-hour history ready · live feed active");
                } catch (Exception error) {
                    broadcast("History download failed · " + describeNetworkFailure(error, null) +
                            " · retry in 5 minutes; live feed will continue");
                    handler.removeCallbacks(backfillRetry);
                    handler.postDelayed(backfillRetry, BACKFILL_RETRY_MS);
                } finally {
                    if (backfillRequested && !stoppedByUser) {
                        backfillRequested = false;
                        handler.post(new Runnable() {
                            @Override public void run() {
                                if (!stoppedByUser) startHistoricalBackfill();
                            }
                        });
                    }
                }
            }
        }, "Kalize-History-Backfill");
        backfillThread.setDaemon(true);
        backfillThread.start();
    }

    /** Runs one paper-entry evaluation for each pair after historical candles load. */
    private void evaluateLatestStoredCandles(String[] symbols) {
        StringBuilder report = new StringBuilder("STARTUP PAPER-TRADING CHECK");
        for (String symbol : symbols) {
            Cursor cursor = database.getReadableDatabase().rawQuery(
                    "SELECT open_time, close, low, high FROM candles WHERE symbol=? AND interval='1m' " +
                    "ORDER BY open_time DESC LIMIT 1",
                    new String[]{symbol});
            long openTime = 0L;
            double close = 0.0;
            double low = 0.0;
            double high = 0.0;
            boolean found = false;
            try {
                if (cursor.moveToFirst()) {
                    openTime = cursor.getLong(0);
                    close = cursor.getDouble(1);
                    low = cursor.getDouble(2);
                    high = cursor.getDouble(3);
                    found = true;
                }
            } finally {
                cursor.close();
            }
            report.append("\\n\\n").append(symbol).append(": ");
            if (found) {
                if (database.claimAutomationCandle(symbol, "1m", openTime)) {
                    report.append(autoTrader.onCompletedCandle(symbol, "1m", close, low, high));
                } else {
                    report.append("SKIPPED · latest completed candle was already evaluated.");
                }
            } else {
                report.append("ENTRY WAITING · no completed candle is stored.");
            }
        }
        broadcast(report.toString());
    }

    private void handleMessage(String raw) {
        try {
            JSONObject root = new JSONObject(raw);
            JSONObject data = root.optJSONObject("data");
            if (data == null) return;
            String event = data.optString("e");
            if ("24hrTicker".equals(event)) {
                String symbol = data.optString("s");
                double last = number(data.optString("c"));
                double change = number(data.optString("P"));
                double high = number(data.optString("h"));
                double low = number(data.optString("l"));
                double quoteVolume = number(data.optString("q"));
                if (!isTrackedSymbol(symbol) || !validPositive(last) ||
                        !validNumber(change) || !validPositive(high) ||
                        !validPositive(low) || high < low || !validNumber(quoteVolume) ||
                        quoteVolume < 0.0) return;
                markValidMarketPayload("24hrTicker");
                broadcastQuote(symbol, last, change, high, low, quoteVolume);
                return;
            }
            if (!"kline".equals(event)) return;
            JSONObject k = data.optJSONObject("k");
            if (k == null) return;

            String symbol = data.optString("s");
            long openTime = k.optLong("t", 0L);
            double open = number(k.optString("o"));
            double high = number(k.optString("h"));
            double low = number(k.optString("l"));
            double close = number(k.optString("c"));
            double volume = number(k.optString("v"));
            if (!isTrackedSymbol(symbol) || openTime <= 0L ||
                    !validPositive(open) || !validPositive(high) ||
                    !validPositive(low) || !validPositive(close) ||
                    !validNumber(volume) || volume < 0.0 ||
                    high < low || high < open || high < close ||
                    low > open || low > close) return;
            markValidMarketPayload("kline");
            if (!k.optBoolean("x", false)) return; // Valid live update, not a completed candle.
            double candleLow = low;
            double candleHigh = high;
            // Historical backfill can insert this exact candle before the live
            // WebSocket close event arrives. Confirm it exists, but do not let
            // storage deduplication suppress its first automation evaluation.
            database.saveCandleIfNew(
                    symbol, "1m", openTime, open, high, low, close, volume);
            if (!database.hasCandle(symbol, "1m", openTime)) return;

            database.putSetting("market_feed_last_candle_at", String.valueOf(System.currentTimeMillis()));
            database.putSetting("market_feed_last_candle_symbol", symbol);

            String automation;
            if (database.claimAutomationCandle(symbol, "1m", openTime)) {
                // The just-closed candle supplies the next label for the prior candle.
                learner.learn(symbol, "1m");
                automation = autoTrader.onCompletedCandle(
                        symbol, "1m", close, candleLow, candleHigh);
            } else {
                automation = "DUPLICATE / OLDER CANDLE · paper evaluation already completed.";
            }
            broadcast(symbol + " · 1m candle processed\n" + automation);
            updateNotification("Live · " + symbol + " candle processed");
        } catch (Exception ignored) {
            // Malformed or unexpected packets are discarded; the stream stays alive.
        }
    }

    private boolean isTrackedSymbol(String symbol) {
        return "BTCUSDT".equals(symbol) || "ETHUSDT".equals(symbol) ||
                "SOLUSDT".equals(symbol);
    }

    private boolean validNumber(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private boolean validPositive(double value) {
        return validNumber(value) && value > 0.0;
    }

    /** Persist successful payload parsing separately from raw socket activity. */
    private void markValidMarketPayload(String type) {
        long now = System.currentTimeMillis();
        if (now - lastPayloadPersistedAt < MESSAGE_DIAGNOSTIC_WRITE_MS) return;
        lastPayloadPersistedAt = now;
        try {
            database.putSetting("market_feed_last_payload_at", String.valueOf(now));
            database.putSetting("market_feed_last_payload_type", type);
        } catch (Exception ignored) { }
    }

    private double number(String value) {
        try { return Double.parseDouble(value); }
        catch (Exception ignored) { return Double.NaN; }
    }

    private void scheduleReconnect() {
        if (stoppedByUser) return;
        int delay = retrySeconds;
        retrySeconds = Math.min(60, retrySeconds * 2);
        broadcast("Disconnected · retrying in " + delay + "s");
        handler.removeCallbacks(reconnect);
        handler.postDelayed(reconnect, delay * 1000L);
    }

    /** Persist WebSocket activity at most twice per minute to avoid flash wear on older phones. */
    private void persistMessageFreshness(long timestamp) {
        if (timestamp - lastMessagePersistedAt < MESSAGE_DIAGNOSTIC_WRITE_MS) return;
        lastMessagePersistedAt = timestamp;
        try {
            database.putSetting("market_feed_last_message_at", String.valueOf(timestamp));
            database.putSetting("market_feed_stale", "false");
            database.putSetting("market_feed_stale_reason", "");
        } catch (Exception ignored) { }
    }

    private void broadcast(String status) {
        // Persist the latest feed state so diagnostics survive Activity recreation.
        try {
            database.putSetting("market_feed_status", status);
            database.putSetting("market_feed_status_at", String.valueOf(System.currentTimeMillis()));
            if (status != null && status.startsWith("Connected")) {
                database.putSetting("market_feed_connected_at", String.valueOf(System.currentTimeMillis()));
            }
        } catch (Exception ignored) { }
        Intent update = new Intent(ACTION_STATUS);
        update.setPackage(getPackageName());
        update.putExtra(EXTRA_STATUS, status);
        sendBroadcast(update);
    }
    private void broadcastQuote(String symbol, double last, double change, double high, double low, double quoteVolume) {
        Intent update = new Intent(ACTION_STATUS);
        update.setPackage(getPackageName());
        update.putExtra(EXTRA_QUOTES, symbol + "|" + last + "|" + change + "|" + high + "|" + low + "|" + quoteVolume);
        sendBroadcast(update);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Market data connection",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Persistent 1-minute public market-data stream");
            NotificationManager manager =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String message) {
        Intent stop = new Intent(this, MarketDataService.class);
        stop.setAction(ACTION_STOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent stopPending = PendingIntent.getService(this, 4102, stop, flags);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Kalize Crypto AI · Market feed")
                .setContentText(message)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_media_pause, "Stop stream", stopPending);
        return builder.build();
    }

    private void updateNotification(String message) {
        NotificationManager manager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification(message));
    }

    @Override public void onDestroy() {
        stoppedByUser = true;
        handler.removeCallbacks(reconnect);
        handler.removeCallbacks(networkRecovery);
        handler.removeCallbacks(cleanupTask);
        handler.removeCallbacks(staleFeedCheck);
        handler.removeCallbacks(backfillRetry);
        if (socket != null) socket.close(1000, "Service stopped");
        if (connectivityReceiverRegistered) {
            try { unregisterReceiver(connectivityReceiver); } catch (Exception ignored) { }
            connectivityReceiverRegistered = false;
        }
        if (client != null) client.dispatcher().executorService().shutdown();
        if (backfillThread != null) backfillThread.interrupt();
        if (database != null) database.closeStore();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
