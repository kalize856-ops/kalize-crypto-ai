package com.kalize.cryptoai;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.util.ArrayList;

/**
 * Autonomous paper trader. Provisional model estimates can be used while learning continues.
 * Every result explains why an entry was accepted or rejected. Virtual ledger only.
 */
public final class AutoPaperTradingController {
    private static final String STOP_DISTANCE = "0.02";
    private static final double MIN_CONFIDENCE = 5.0;
    // One completed 1-minute candle between entries: prevents instant churn without blocking the paper simulator for 5 minutes.
    private static final long REENTRY_COOLDOWN_MS = 60L * 1000L;

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

    /** Automation is automatic; this method cannot enable real-money trading. */
    public synchronized void setEnabled(boolean enabled) {
        database.putSetting("auto_paper_trading", "true");
    }

    /** Called after each newly stored, completed one-minute candle. */
    public synchronized String onCompletedCandle(String symbol, String interval,
                                                   double marketPrice, double candleLow) {
        String decision = evaluateCompletedCandle(symbol, interval, marketPrice, candleLow);
        if (symbol != null && symbol.trim().length() > 0) {
            database.putSetting("auto_last_decision_" + symbol,
                    decision == null ? "No decision returned." : decision);
            database.putSetting("auto_last_decision_at_" + symbol,
                    String.valueOf(System.currentTimeMillis()));
        }
        return decision;
    }

    private String evaluateCompletedCandle(String symbol, String interval,
                                            double marketPrice, double candleLow) {
        if (!finitePositive(marketPrice) || !finitePositive(candleLow))
            return "SIMULATION CHECK: skipped · invalid candle price.";

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
            stopReport.append(closed.success ? "STOP EXECUTED: " : "STOP ERROR: ")
                    .append(closed.message);
        }

        if (database.isVirtualCashDepleted()) {
            return append(stopReport, "ENTRY BLOCKED: virtual cash is depleted; reset the practice account.");
        }
        if (stopReport.length() > 0) {
            return append(stopReport, "No replacement entry on the same candle as a stop-loss.");
        }

        OnDeviceLearningEngine.Assessment assessment = learner.assess(symbol, interval);
        SQLiteDatabase db = database.getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT id FROM paper_trades WHERE symbol=? AND status='OPEN' AND ai_probability>=0 ORDER BY opened_at ASC LIMIT 1",
                new String[]{symbol});
        long openId = -1L;
        try {
            if (cursor.moveToFirst()) openId = cursor.getLong(0);
        } finally {
            cursor.close();
        }

        if (openId > 0) {
            if ("DOWNWARD BIAS".equals(assessment.signal)) {
                PaperTradingEngine.Result closed = trading.sell(openId, marketPrice);
                if (closed.success) recordExit(symbol);
                return "POSITION MANAGEMENT · " + closed.message;
            }
            return "POSITION MANAGEMENT · holding " + symbol +
                    " · AI " + assessment.signal + " · confidence " +
                    format(assessment.confidence) + "%.";
        }

        if (!hasHealthyRecentCandles(symbol, interval)) {
            return "ENTRY BLOCKED · waiting for 3 fresh consecutive candles.";
        }

        long cooldownRemaining = getCooldownRemaining(symbol);
        if (cooldownRemaining > 0L) {
            return "ENTRY BLOCKED · re-entry cooldown · " +
                    Math.max(1L, (cooldownRemaining + 59999L) / 60000L) + " min remaining.";
        }

        if (!"UPWARD BIAS".equals(assessment.signal) ||
                assessment.confidence < MIN_CONFIDENCE) {
            return "NO ENTRY · AI signal=" + assessment.signal +
                    " · confidence=" + format(assessment.confidence) +
                    "% · required=5% · waiting for qualifying upward signal.";
        }

        // Do not require an additional 2-of-3 rising-candle pattern here.
        // The model signal plus fresh-data validation is the entry trigger.
        double cash = database.getCashBalance();
        if (cash <= 0.005) {
            return "ENTRY BLOCKED · virtual cash depleted.";
        }
        double equity = cash + trading.getOpenEntryValue();
        double stop = marketPrice * (1.0 - Double.parseDouble(STOP_DISTANCE));
        double riskBudget = equity * PaperTradingEngine.MAX_RISK_PER_TRADE;
        double quantityByRisk = riskBudget / (marketPrice - stop);
        double maxNotional = equity * PaperTradingEngine.MAX_POSITION_VALUE;
        double quantityBySize = maxNotional / marketPrice;
        double quantity = Math.min(quantityByRisk, quantityBySize);

        if (!finitePositive(quantity)) return "ENTRY BLOCKED · calculated position size is invalid.";

        PaperTradingEngine.Result opened =
                trading.buyWithAiSignal(symbol, interval, quantity, marketPrice, stop);
        if (opened.success) {
            return "PAPER TRADE OPENED · " + symbol +
                    " · confidence=" + format(assessment.confidence) +
                    "% · virtual value=$" + format(opened.amount) +
                    " · trade #" + opened.tradeId + ".";
        }
        return "ENTRY REJECTED BY SIMULATOR · AI confidence=" +
                format(assessment.confidence) + "% · " + opened.message;
    }

    private boolean hasHealthyRecentCandles(String symbol, String interval) {
        SQLiteDatabase db = database.getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT open_time FROM candles WHERE symbol=? AND interval=? ORDER BY open_time DESC LIMIT 3",
                new String[]{symbol, interval});
        long[] times = new long[3];
        int count = 0;
        try {
            while (cursor.moveToNext() && count < times.length) times[count++] = cursor.getLong(0);
        } finally {
            cursor.close();
        }
        if (count < 3) return false;
        long latestAge = System.currentTimeMillis() - times[0];
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

    private String append(StringBuilder existing, String message) {
        if (existing.length() == 0) return message;
        return existing.append("\n").append(message).toString();
    }

    private String format(double value) {
        return String.format(java.util.Locale.US, "%.1f", value);
    }

    private boolean finitePositive(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value) && value > 0.0;
    }
}
