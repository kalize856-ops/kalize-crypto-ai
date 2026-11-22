package com.kalize.cryptoai;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

/**
 * Local-only paper trading ledger. Prices are supplied by the caller;
 * this class has no network or exchange-order functionality.
 */
public final class PaperTradingEngine {
    public static final double FEE_RATE = 0.0010;       // 0.10% per side
    public static final double SLIPPAGE_RATE = 0.0005;  // conservative simulation
    public static final double MAX_RISK_PER_TRADE = 0.01;
    public static final double DAILY_LOSS_LIMIT = 0.02;
    public static final int MAX_OPEN_POSITIONS = 3;
    public static final double MAX_POSITION_VALUE = 0.25;

    private final PaperDatabaseHelper helper;

    public PaperTradingEngine(PaperDatabaseHelper helper) {
        if (helper == null) throw new IllegalArgumentException("Database is required");
        this.helper = helper;
    }

    public static final class Result {
        public final boolean success;
        public final String message;
        public final long tradeId;
        public final double amount;
        Result(boolean success, String message, long tradeId, double amount) {
            this.success = success; this.message = message;
            this.tradeId = tradeId; this.amount = amount;
        }
    }

    /** Opens a simulated long position only when all educational risk checks pass. */
    public Result buy(String symbol, double quantity, double marketPrice, double stopLoss) {
        if (symbol == null || symbol.trim().length() == 0 ||
                !positive(quantity) || !positive(marketPrice) ||
                !positive(stopLoss) || stopLoss >= marketPrice) {
            return fail("Enter a symbol, positive quantity and price, and a stop below entry.");
        }
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            double cash = scalar(db, "SELECT cash_balance FROM wallet WHERE id=1");
            double equity = cash + scalar(db,
                    "SELECT COALESCE(SUM(quantity*entry_price),0) FROM paper_trades WHERE status='OPEN'");
            int openCount = (int) scalar(db,
                    "SELECT COUNT(*) FROM paper_trades WHERE status='OPEN'");
            if (openCount >= MAX_OPEN_POSITIONS) return fail("Limit reached: 3 open simulated positions.");
            double execution = marketPrice * (1.0 + SLIPPAGE_RATE);
            double notional = quantity * execution;
            double fee = notional * FEE_RATE;
            if (notional > equity * MAX_POSITION_VALUE) {
                return fail("Position exceeds the 25% simulated portfolio-size limit.");
            }
            if (quantity * (execution - stopLoss) > equity * MAX_RISK_PER_TRADE) {
                return fail("Estimated stop-loss risk exceeds 1% of simulated portfolio equity.");
            }
            if (notional + fee > cash) return fail("Insufficient virtual cash.");
            ContentValues wallet = new ContentValues();
            wallet.put("cash_balance", cash - notional - fee);
            wallet.put("updated_at", System.currentTimeMillis());
            db.update("wallet", wallet, "id=1", null);

            ContentValues trade = new ContentValues();
            trade.put("symbol", symbol.trim().toUpperCase(java.util.Locale.US));
            trade.put("side", "BUY");
            trade.put("quantity", quantity);
            trade.put("entry_price", execution);
            trade.put("fees", fee);
            trade.put("realized_pnl", 0);
            trade.put("status", "OPEN");
            trade.put("opened_at", System.currentTimeMillis());
            trade.put("stop_loss", stopLoss);
            long id = db.insertOrThrow("paper_trades", null, trade);
            db.setTransactionSuccessful();
            return new Result(true, "Simulated position opened. No real order was sent.", id, notional);
        } catch (RuntimeException ex) {
            return fail("Simulation could not complete: " + ex.getMessage());
        } finally {
            db.endTransaction();
        }
    }

    /** Closes an entire simulated position at the supplied market price. */
    public Result sell(long tradeId, double marketPrice) {
        if (tradeId <= 0 || !positive(marketPrice)) return fail("Invalid trade or market price.");
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            Cursor c = db.rawQuery("SELECT quantity,entry_price,fees,status FROM paper_trades WHERE id=?",
                    new String[]{String.valueOf(tradeId)});
            double qty, entry, entryFee;
            try {
                if (!c.moveToFirst()) return fail("Position not found.");
                qty = c.getDouble(0); entry = c.getDouble(1);
                entryFee = c.getDouble(2);
                if (!"OPEN".equals(c.getString(3))) return fail("This position is already closed.");
            } finally { c.close(); }

            double exit = marketPrice * (1.0 - SLIPPAGE_RATE);
            double gross = qty * exit;
            double exitFee = gross * FEE_RATE;
            double pnl = (gross - exitFee) - (qty * entry + entryFee);
            ContentValues wallet = new ContentValues();
            wallet.put("cash_balance", scalar(db,
                    "SELECT cash_balance FROM wallet WHERE id=1") + gross - exitFee);
            wallet.put("updated_at", System.currentTimeMillis());
            db.update("wallet", wallet, "id=1", null);

            ContentValues trade = new ContentValues();
            trade.put("exit_price", exit);
            trade.put("fees", entryFee + exitFee);
            trade.put("realized_pnl", pnl);
            trade.put("status", "CLOSED");
            trade.put("closed_at", System.currentTimeMillis());
            db.update("paper_trades", trade, "id=? AND status='OPEN'",
                    new String[]{String.valueOf(tradeId)});
            db.setTransactionSuccessful();
            return new Result(true, "Simulated position closed. Net P/L: " +
                    String.format(java.util.Locale.US, "$%.2f", pnl), tradeId, pnl);
        } catch (RuntimeException ex) {
            return fail("Simulation could not complete: " + ex.getMessage());
        } finally { db.endTransaction(); }
    }

    public int getOpenPositionCount() {
        return (int) scalar(helper.getReadableDatabase(),
                "SELECT COUNT(*) FROM paper_trades WHERE status='OPEN'");
    }

    public double getOpenEntryValue() {
        return scalar(helper.getReadableDatabase(),
                "SELECT COALESCE(SUM(quantity*entry_price),0) FROM paper_trades WHERE status='OPEN'");
    }

    public double getTodayRealizedPnl() {
        long start = System.currentTimeMillis() -
                (System.currentTimeMillis() % 86400000L);
        return scalar(helper.getReadableDatabase(),
                "SELECT COALESCE(SUM(realized_pnl),0) FROM paper_trades WHERE status='CLOSED' AND closed_at>= " + start);
    }

    public boolean isDailyLossLimitReached() {
        double starting = scalar(helper.getReadableDatabase(),
                "SELECT starting_balance FROM wallet WHERE id=1");
        return getTodayRealizedPnl() <= -(starting * DAILY_LOSS_LIMIT);
    }

    private double scalar(SQLiteDatabase db, String sql) {
        Cursor c = db.rawQuery(sql, null);
        try { return c.moveToFirst() ? c.getDouble(0) : 0.0; }
        finally { c.close(); }
    }
    private boolean positive(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v) && v > 0;
    }
    private Result fail(String message) { return new Result(false, message, -1, 0); }
}
