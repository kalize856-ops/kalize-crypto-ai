package com.kalize.cryptoai;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.util.ArrayList;

/**
 * Autonomous paper trader. Provisional model estimates can be used while learning continues. Decisions stay in the local virtual ledger;
 * there is no user-facing manual mode or exchange-account order API.
 */
public final class AutoPaperTradingController {
    private static final String SETTING = "auto_paper_trading";
    private static final double STOP_DISTANCE = 0.02;
    private static final double MIN_CONFIDENCE = 5.0;
    private static final long REENTRY_COOLDOWN_MS = 5L * 60L * 1000L;

    private final PaperDatabaseHelper database;
    private final PaperTradingEngine trading;
    private final OnDeviceLearningEngine learner;

    public AutoPaperTradingController(PaperDatabaseHelper db) {
        if (db == null) throw new IllegalArgumentException("Database required");
        database = db;
        trading = new PaperTradingEngine(db);
        learner = new OnDeviceLearningEngine(db);
    }

    public synchronized boolean isEnabled() {
        return !database.isVirtualCashDepleted();
    }

    /** Automation is mandatory; callers cannot switch into manual trading mode. */
    public synchronized void setEnabled(boolean enabled) {
        database.putSetting(SETTING, "true");
    }

    /** Called only after a completed candle has been stored. */
    public synchronized String onCompletedCandle(String symbol, String interval,
                                                   double marketPrice, double candleLow) {
        if (!finitePositive(marketPrice) || !finitePositive(candleLow))
            return "Skipped: invalid candle price.";

        // Stop-loss monitoring is independent of the AI auto-trading switch.
        SQLiteDatabase monitorDb = database.getReadableDatabase();
        Cursor stops = monitorDb.rawQuery(
                "SELECT id, stop_loss FROM paper_trades WHERE symbol=? AND status='OPEN' AND stop_loss>0 ORDER BY opened_at ASC",
                new String[]{symbol});
        ArrayList<long[]> triggered = new ArrayList<long[]>();
        try {
            while (stops.moveToNext()) {
                long id = stops.getLong(0);
                double stop = stops.getDouble(1);
                if (candleLow <= stop) {
                    double exitReference = marketPrice < stop ? marketPrice : stop;
                    triggered.add(new long[]{id, Double.doubleToLongBits(exitReference)});
                }
            }
        } finally {
            stops.close();
        }

        StringBuilder stopReport = new StringBuilder();
        for (long[] item : triggered) {
            double exitReference = Double.longBitsToDouble(item[1]);
            PaperTradingEngine.Result closed = trading.sell(item[0], exitReference);
            if (closed.success) recordExit(symbol);
            if (stopReport.length() > 0) stopReport.append("\n");
            stopReport.append(closed.success ? "Stop-loss executed: " : "Stop-loss error: ")
                    .append(closed.message);
        }
        if (database.isVirtualCashDepleted()) {
            return stopReport.length() > 0 ? stopReport.toString() :
                    "Virtual cash depleted. Automatic entries are locked until the virtual account is reset.";
        }
        // Do not open a replacement position on the same candle that stopped one.
        if (stopReport.length() > 0) return stopReport.toString();
        if (database.isVirtualCashDepleted()) {
            return "Virtual cash depleted. Automatic entries are locked until the virtual account is reset.";
        }

        OnDeviceLearningEngine.Assessment assessment = learner.assess(symbol, interval);
        SQLiteDatabase db = database.getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT id, stop_loss FROM paper_trades WHERE symbol=? AND status='OPEN' AND ai_probability>=0 ORDER BY opened_at ASC LIMIT 1",
                new String[]{symbol});
        long openId = -1L;
        try {
            if (cursor.moveToFirst()) {
                openId = cursor.getLong(0);
            }
        } finally {
            cursor.close();
        }

        if (openId > 0) {
            if ("DOWNWARD BIAS".equals(assessment.signal)) {
                PaperTradingEngine.Result closed = trading.sell(openId, marketPrice);
                if (closed.success) recordExit(symbol);
                return closed.message;
            }
            return "Holding simulated " + symbol + " position.";
        }

        // Only open new virtual positions when the latest three 1m candles
        // are fresh and consecutive. Existing positions remain managed above.
        if (!hasHealthyRecentCandles(symbol, interval)) {
            return "Entry paused: waiting for three fresh, consecutive market candles.";
        }

        long cooldownRemaining = getCooldownRemaining(symbol);
        if (cooldownRemaining > 0L) {
            return "Entry paused briefly after the previous exit · " +
                    Math.max(1L, (cooldownRemaining + 59999L) / 60000L) + " min remaining.";
        }
        if (!"UPWARD BIAS".equals(assessment.signal) ||
                assessment.confidence < MIN_CONFIDENCE) {
            return "No simulated entry: AI signal or confidence threshold not met.";
        }
        if (!hasShortTermPriceConfirmation(symbol, interval)) {
            return "Entry paused: the recent candles do not yet confirm the AI signal.";
        }

        double cash = database.getCashBalance();
        if (cash <= 0.005) {
            return "Virtual cash depleted. Automatic entries are locked until the virtual account is reset.";
        }
        double equity = cash + trading.getOpenEntryValue();
        double stop = marketPrice * (1.0 - STOP_DISTANCE);
        double riskBudget = equity * PaperTradingEngine.MAX_RISK_PER_TRADE;
        double quantityByRisk = riskBudget / (marketPrice - stop);
        double maxNotional = equity * PaperTradingEngine.MAX_POSITION_VALUE;
        double quantityBySize = maxNotional / marketPrice;
        double quantity = Math.min(quantityByRisk, quantityBySize);

        if (!finitePositive(quantity)) return "No simulated entry: position size is invalid.";
        PaperTradingEngine.Result opened =
                trading.buyWithAiSignal(symbol, interval, quantity, marketPrice, stop);
        return (stopReport.length() > 0 ? stopReport.toString() + "\n" : "") + opened.message;
    }

    /**
     * Requires at least two rising moves among the latest three completed
     * candle-to-candle changes, and rejects a sharp immediate pullback.
     * This is a confirmation gate, not a prediction.
     */
    private boolean hasShortTermPriceConfirmation(String symbol, String interval) {
        SQLiteDatabase db = database.getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT close FROM candles WHERE symbol=? AND interval=? ORDER BY open_time DESC LIMIT 4",
                new String[]{symbol, interval});
        double[] closes = new double[4];
        int count = 0;
        try {
            while (cursor.moveToNext() && count < closes.length) {
                closes[count++] = cursor.getDouble(0);
            }
        } finally {
            cursor.close();
        }
        if (count < 4) return false;

        int risingMoves = 0;
        for (int i = 0; i < 3; i++) {
            if (!finitePositive(closes[i]) || !finitePositive(closes[i + 1])) return false;
            if (closes[i] > closes[i + 1]) risingMoves++;
        }
        // Do not enter if the newest close has sharply reversed against the prior close.
        double latestPullback = (closes[1] - closes[0]) / closes[1];
        return risingMoves >= 2 && latestPullback <= 0.002;
    }

    private boolean hasHealthyRecentCandles(String symbol, String interval) {
        SQLiteDatabase db = database.getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT open_time FROM candles WHERE symbol=? AND interval=? ORDER BY open_time DESC LIMIT 3",
                new String[]{symbol, interval});
        long[] times = new long[3];
        int count = 0;
        try {
            while (cursor.moveToNext() && count < times.length) {
                times[count++] = cursor.getLong(0);
            }
        } finally {
            cursor.close();
        }
        if (count < 3) return false;

        long now = System.currentTimeMillis();
        long latestAge = now - times[0];
        if (latestAge < 0L || latestAge > 180000L) return false;

        for (int i = 0; i < 2; i++) {
            long gap = times[i] - times[i + 1];
            if (gap < 45000L || gap > 90000L) return false;
        }
        return true;
    }

    private void recordExit(String symbol) {
        database.putSetting("auto_last_exit_" + symbol, String.valueOf(System.currentTimeMillis()));
    }

    private long getCooldownRemaining(String symbol) {
        String raw = database.getSetting("auto_last_exit_" + symbol, "0");
        long lastExit;
        try { lastExit = Long.parseLong(raw); }
        catch (Exception ignored) { lastExit = 0L; }
        return Math.max(0L, REENTRY_COOLDOWN_MS - (System.currentTimeMillis() - lastExit));
    }

    private boolean finitePositive(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value) && value > 0.0;
    }
}
