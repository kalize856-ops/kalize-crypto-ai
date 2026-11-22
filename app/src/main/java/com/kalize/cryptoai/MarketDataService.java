package com.kalize.cryptoai;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
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
    private long lastMessageAt = 0L;
    private static final long STALE_FEED_MS = 90000L;
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
            if (socket != null && lastMessageAt > 0L && System.currentTimeMillis() - lastMessageAt > STALE_FEED_MS) {
                WebSocket stale = socket;
                socket = null;
                connecting = false;
                stale.cancel();
                broadcast("Market feed stopped responding · reconnecting");
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
        broadcast("Connecting");
        Request request = new Request.Builder().url(STREAM_URL).build();
        socket = client.newWebSocket(request, new WebSocketListener() {
            @Override public void onOpen(WebSocket webSocket, Response response) {
                if (socket != webSocket || stoppedByUser) { webSocket.cancel(); return; }
                connecting = false;
                lastMessageAt = System.currentTimeMillis();
                retrySeconds = 2;
                broadcast("Connected · waiting for completed 1m candles");
                updateNotification("Connected · 1-minute candles");
            }

            @Override public void onMessage(WebSocket webSocket, String text) {
                lastMessageAt = System.currentTimeMillis();
                handleMessage(text);
            }

            @Override public void onMessage(WebSocket webSocket, ByteString bytes) {
                lastMessageAt = System.currentTimeMillis();
                handleMessage(bytes.utf8());
            }

            @Override public void onClosing(WebSocket webSocket, int code, String reason) {
                webSocket.close(1000, null);
            }

            @Override public void onClosed(WebSocket webSocket, int code, String reason) {
                if (socket == webSocket) {
                    socket = null;
                    connecting = false;
                    scheduleReconnect();
                }
            }

            @Override public void onFailure(WebSocket webSocket, Throwable error, Response response) {
                if (socket == webSocket) {
                    socket = null;
                    connecting = false;
                    String detail = error == null ? "Unknown network error" :
                            error.getClass().getSimpleName() +
                            (error.getMessage() == null ? "" : ": " + error.getMessage());
                    if (response != null) detail += " · HTTP " + response.code();
                    broadcast("Market connection failed · " + detail + " · reconnecting");
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
                .connectionSpecs(java.util.Collections.singletonList(ConnectionSpec.MODERN_TLS));
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

    /** Downloads up to 1 hour of completed one-minute candles without blocking the feed. */
    private void startHistoricalBackfill() {
        if (stoppedByUser || (backfillThread != null && backfillThread.isAlive())) return;
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
                    broadcast("1-hour history ready · AI learning active");
                    updateNotification("1-hour history ready · live feed active");
                } catch (Exception error) {
                    broadcast("Historical backfill paused · will retry in 5 minutes · live feed remains active");
                    handler.removeCallbacks(backfillRetry);
                    handler.postDelayed(backfillRetry, BACKFILL_RETRY_MS);
                }
            }
        }, "Kalize-History-Backfill");
        backfillThread.setDaemon(true);
        backfillThread.start();
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
                if (last > 0 && !Double.isNaN(change)) broadcastQuote(symbol, last, change, high, low, quoteVolume);
                return;
            }
            if (!"kline".equals(event)) return;
            JSONObject k = data.optJSONObject("k");
            if (k == null || !k.optBoolean("x", false)) return; // Ignore unfinished candles.

            String symbol = data.optString("s");
            long openTime = k.optLong("t", 0L);
            double open = number(k.optString("o"));
            double high = number(k.optString("h"));
            double low = number(k.optString("l"));
            double close = number(k.optString("c"));
            double candleLow = number(k.optString("l"));
            double volume = number(k.optString("v"));
            if (openTime <= 0 || !database.saveCandleIfNew(
                    symbol, "1m", openTime, open, high, low, close, volume)) return;

            // The just-closed candle supplies the next label for the prior candle.
            learner.learn(symbol, "1m");
            String automation = autoTrader.onCompletedCandle(symbol, "1m", close, candleLow);
            broadcast(symbol + " · 1m candle saved\n" + automation);
            updateNotification("Live · " + symbol + " candle saved");
        } catch (Exception ignored) {
            // Malformed or unexpected packets are discarded; the stream stays alive.
        }
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

    private void broadcast(String status) {
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
        handler.removeCallbacks(cleanupTask);
        handler.removeCallbacks(staleFeedCheck);
        handler.removeCallbacks(backfillRetry);
        if (socket != null) socket.close(1000, "Service stopped");
        if (client != null) client.dispatcher().executorService().shutdown();
        if (backfillThread != null) backfillThread.interrupt();
        if (database != null) database.closeStore();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
