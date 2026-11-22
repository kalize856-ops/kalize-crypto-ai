package com.kalize.cryptoai;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.util.ArrayList;

/**
 * Opt-in automatic paper trader. All decisions and orders stay in the local
 * virtual ledger; this class has no exchange-account or order API.
 */
public final class AutoPaperTradingController {
    private static final String SETTING = "auto_paper_trading";
    private static final double STOP_DISTANCE = 0.02;
    private static final double MIN_CONFIDENCE = 16.0;

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
        return "true".equals(database.getSetting(SETTING, "false"));
    }

    public synchronized void setEnabled(boolean enabled) {
        database.putSetting(SETTING, enabled ? "true" : "false");
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
            if (stopReport.length() > 0) stopReport.append("\n");
            stopReport.append(closed.success ? "Stop-loss executed: " : "Stop-loss error: ")
                    .append(closed.message);
        }
        if (!isEnabled()) {
            return stopReport.length() > 0 ? stopReport.toString() :
                    "Automatic paper trading is off. Stop-loss monitoring remains active.";
        }
        // Do not open a replacement position on the same candle that stopped one.
        if (stopReport.length() > 0) return stopReport.toString();
        if (database.isVirtualCashDepleted()) {
            setEnabled(false);
            return "Virtual cash depleted. Automatic paper trading has been paused; reset the simulation to restart.";
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
                return closed.message;
            }
            return "Holding simulated " + symbol + " position.";
        }

        if (!"UPWARD BIAS".equals(assessment.signal) ||
                assessment.samples < 50 || assessment.confidence < MIN_CONFIDENCE) {
            return "No simulated entry: AI signal or confidence threshold not met.";
        }

        double cash = database.getCashBalance();
        if (cash <= 0.005) {
            setEnabled(false);
            return "Virtual cash depleted. Automatic paper trading has been paused.";
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

    private boolean finitePositive(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value) && value > 0.0;
    }
}
