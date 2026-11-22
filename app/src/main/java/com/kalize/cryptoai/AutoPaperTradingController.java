package com.kalize.cryptoai;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

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
                                                   double marketPrice) {
        if (!isEnabled()) return "Automatic paper trading is off.";
        if (!finitePositive(marketPrice)) return "Skipped: invalid market price.";

        OnDeviceLearningEngine.Assessment assessment = learner.assess(symbol, interval);
        SQLiteDatabase db = database.getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT id, stop_loss FROM paper_trades WHERE symbol=? AND status='OPEN' AND ai_probability>=0 ORDER BY opened_at ASC LIMIT 1",
                new String[]{symbol});
        long openId = -1L;
        double stopLoss = 0.0;
        try {
            if (cursor.moveToFirst()) {
                openId = cursor.getLong(0);
                stopLoss = cursor.getDouble(1);
            }
        } finally {
            cursor.close();
        }

        if (openId > 0) {
            if (marketPrice <= stopLoss || "DOWNWARD BIAS".equals(assessment.signal)) {
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
        return opened.message;
    }

    private boolean finitePositive(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value) && value > 0.0;
    }
}
