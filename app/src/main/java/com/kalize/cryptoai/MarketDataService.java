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
    public static final String ACTION_STOP = "com.kalize.cryptoai.STOP_MARKET_STREAM";
    private static final String CHANNEL_ID = "kalize_market_stream";
    private static final int NOTIFICATION_ID = 4101;
    private static final String STREAM_URL =
            "wss://stream.binance.com:9443/stream?streams=" +
            "btcusdt@kline_1m/ethusdt@kline_1m/solusdt@kline_1m";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private OkHttpClient client;
    private WebSocket socket;
    private PaperDatabaseHelper database;
    private OnDeviceLearningEngine learner;
    private boolean stoppedByUser = false;
    private boolean connecting = false;
    private int retrySeconds = 2;
    private Thread backfillThread;

    private final Runnable reconnect = new Runnable() {
        @Override public void run() { connect(); }
    };

    @Override public void onCreate() {
        super.onCreate();
        database = new PaperDatabaseHelper(getApplicationContext());
        learner = new OnDeviceLearningEngine(database);
        client = new OkHttpClient.Builder()
                .pingInterval(20, TimeUnit.SECONDS)
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build();
        createNotificationChannel();
        startHistoricalBackfill();
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
                connecting = false;
                retrySeconds = 2;
                broadcast("Connected · waiting for completed 1m candles");
                updateNotification("Connected · 1-minute candles");
            }

            @Override public void onMessage(WebSocket webSocket, String text) {
                handleMessage(text);
            }

            @Override public void onMessage(WebSocket webSocket, ByteString bytes) {
                handleMessage(bytes.utf8());
            }

            @Override public void onClosing(WebSocket webSocket, int code, String reason) {
                webSocket.close(1000, null);
            }

            @Override public void onClosed(WebSocket webSocket, int code, String reason) {
                socket = null;
                connecting = false;
                scheduleReconnect();
            }

            @Override public void onFailure(WebSocket webSocket, Throwable error, Response response) {
                socket = null;
                connecting = false;
                broadcast("Connection lost · reconnecting");
                scheduleReconnect();
            }
        });
    }

    /** Downloads up to 30 days of completed one-minute candles without blocking the feed. */
    private void startHistoricalBackfill() {
        backfillThread = new Thread(new Runnable() {
            @Override public void run() {
                String[] symbols = new String[]{"BTCUSDT", "ETHUSDT", "SOLUSDT"};
                try {
                    long end = System.currentTimeMillis();
                    long start = end - 30L * 24L * 60L * 60L * 1000L;
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
                                    if (openTime <= 0) continue;
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
                    broadcast("30-day history ready · AI training caught up");
                    updateNotification("30-day history ready · live feed active");
                } catch (Exception error) {
                    broadcast("Historical backfill paused · live feed remains active");
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
            if (data == null || !"kline".equals(data.optString("e"))) return;
            JSONObject k = data.optJSONObject("k");
            if (k == null || !k.optBoolean("x", false)) return; // Ignore unfinished candles.

            String symbol = data.optString("s");
            long openTime = k.optLong("t", 0L);
            double open = number(k.optString("o"));
            double high = number(k.optString("h"));
            double low = number(k.optString("l"));
            double close = number(k.optString("c"));
            double volume = number(k.optString("v"));
            if (openTime <= 0 || !database.saveCandle(
                    symbol, "1m", openTime, open, high, low, close, volume)) return;

            // The just-closed candle supplies the next label for the prior candle.
            learner.learn(symbol, "1m");
            broadcast(symbol + " · 1m candle saved");
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
        if (socket != null) socket.close(1000, "Service stopped");
        if (client != null) client.dispatcher().executorService().shutdown();
        if (backfillThread != null) backfillThread.interrupt();
        if (database != null) database.closeStore();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
