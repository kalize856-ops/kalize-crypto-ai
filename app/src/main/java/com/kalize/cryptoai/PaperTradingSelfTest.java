package com.kalize.cryptoai;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

/** Runs the production paper engine against a disposable database. */
public final class PaperTradingSelfTest {
    private static final double EPSILON = 0.01;

    private PaperTradingSelfTest() { }

    public static String run(Context context) {
        if (context == null) return "FAIL\nNo application context was available.";
        String databaseName = "kalize_self_test_" + System.currentTimeMillis() + ".db";
        PaperDatabaseHelper helper = null;
        StringBuilder report = new StringBuilder();
        int passed = 0;
        int total = 8;
        try {
            context.deleteDatabase(databaseName);
            helper = new PaperDatabaseHelper(context, databaseName);
            PaperTradingEngine engine = new PaperTradingEngine(helper);
            SQLiteDatabase sql = helper.getWritableDatabase();

            double startingCash = scalar(sql, "SELECT cash_balance FROM wallet WHERE id=1");
            passed += record(report, "Temporary $100 wallet initialized",
                    Math.abs(startingCash - 100.0) <= EPSILON);

            PaperTradingEngine.Result opened = engine.buy("SELFTESTUSDT", 0.1, 10.0, 9.9);
            boolean entryOpened = opened.success && engine.getOpenPositionCount() == 1;
            passed += record(report, "Production engine opens a virtual position", entryOpened);
            if (!entryOpened) return finish(report, passed, total, "Entry failed: " + opened.message);

            double cashAfterEntry = scalar(sql, "SELECT cash_balance FROM wallet WHERE id=1");
            double expectedEntryCost = 0.1 * 10.0 * (1.0 + PaperTradingEngine.SLIPPAGE_RATE)
                    * (1.0 + PaperTradingEngine.FEE_RATE);
            passed += record(report, "Entry cost and fee deducted from virtual cash",
                    cashAfterEntry < startingCash &&
                            Math.abs((startingCash - cashAfterEntry) - expectedEntryCost) <= EPSILON);

            Cursor trade = sql.rawQuery(
                    "SELECT quantity,entry_price,fees FROM paper_trades WHERE id=?",
                    new String[]{String.valueOf(opened.tradeId)});
            double quantity, entryPrice, entryFee;
            try {
                if (!trade.moveToFirst())
                    return finish(report, passed, total, "Opened trade row was missing.");
                quantity = trade.getDouble(0);
                entryPrice = trade.getDouble(1);
                entryFee = trade.getDouble(2);
            } finally { trade.close(); }

            double markedPnl = PaperTradingEngine.estimateUnrealizedPnl(
                    quantity, entryPrice, entryFee, 10.5);
            passed += record(report, "Mark-to-market P/L updates from a new price",
                    !Double.isNaN(markedPnl) && !Double.isInfinite(markedPnl) && markedPnl > 0.0);

            PaperTradingEngine.Result closed = engine.sell(opened.tradeId, 10.5);
            boolean closeSucceeded = closed.success && engine.getOpenPositionCount() == 0;
            passed += record(report, "Production engine closes the virtual position", closeSucceeded);
            if (!closeSucceeded) return finish(report, passed, total, "Close failed: " + closed.message);

            Cursor closedTrade = sql.rawQuery(
                    "SELECT fees,realized_pnl,status,exit_price FROM paper_trades WHERE id=?",
                    new String[]{String.valueOf(opened.tradeId)});
            double totalFees, realizedPnl, exitPrice;
            String status;
            try {
                if (!closedTrade.moveToFirst())
                    return finish(report, passed, total, "Closed trade row was missing.");
                totalFees = closedTrade.getDouble(0);
                realizedPnl = closedTrade.getDouble(1);
                status = closedTrade.getString(2);
                exitPrice = closedTrade.getDouble(3);
            } finally { closedTrade.close(); }
            double expectedExitFee = quantity * exitPrice * PaperTradingEngine.FEE_RATE;
            passed += record(report, "Entry and exit fees recorded exactly once",
                    "CLOSED".equals(status) && totalFees > entryFee &&
                            Math.abs(totalFees - (entryFee + expectedExitFee)) <= EPSILON);

            double finalCash = scalar(sql, "SELECT cash_balance FROM wallet WHERE id=1");
            passed += record(report, "Final virtual cash matches realized net P/L",
                    Math.abs(finalCash - (startingCash + realizedPnl)) <= EPSILON);

            PaperTradingEngine.LedgerReconciliation reconciliation = engine.reconcileLedger();
            passed += record(report, "Wallet and trade ledger reconcile", reconciliation.consistent);
        } catch (RuntimeException error) {
            report.append("\nUnexpected test error: ").append(error.getMessage());
        } finally {
            if (helper != null) helper.closeStore();
            context.deleteDatabase(databaseName);
        }
        return finish(report, passed, total, null);
    }

    private static int record(StringBuilder report, String label, boolean success) {
        report.append(success ? "\nPASS · " : "\nFAIL · ").append(label);
        return success ? 1 : 0;
    }

    private static double scalar(SQLiteDatabase db, String query) {
        Cursor cursor = db.rawQuery(query, null);
        try { return cursor.moveToFirst() ? cursor.getDouble(0) : 0.0; }
        finally { cursor.close(); }
    }

    private static String finish(StringBuilder report, int passed, int total, String detail) {
        if (detail != null) report.append("\n").append(detail);
        return (passed == total ? "PASS" : "FAIL") + " · " + passed + "/" + total +
                " checks\nTemporary test ledger only; your saved practice account was not changed." +
                report.toString();
    }
}
