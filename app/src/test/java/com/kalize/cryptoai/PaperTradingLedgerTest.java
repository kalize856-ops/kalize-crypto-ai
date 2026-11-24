package com.kalize.cryptoai;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class PaperTradingLedgerTest {
    private Context context;
    private PaperDatabaseHelper database;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("kalize_paper.db");
        database = new PaperDatabaseHelper(context);
    }

    @After
    public void tearDown() {
        if (database != null) database.closeStore();
        context.deleteDatabase("kalize_paper.db");
    }

    @Test
    public void dailyLossWindowStartsAtDeviceLocalMidnight() {
        java.util.Calendar expected = java.util.Calendar.getInstance();
        expected.set(java.util.Calendar.HOUR_OF_DAY, 0);
        expected.set(java.util.Calendar.MINUTE, 0);
        expected.set(java.util.Calendar.SECOND, 0);
        expected.set(java.util.Calendar.MILLISECOND, 0);

        assertEquals(expected.getTimeInMillis(),
                PaperTradingEngine.startOfLocalDayMillis());
    }

    @Test
    public void openPositionValuationIncludesExitSlippageAndFee() {
        double quantity = 2.0;
        double marketPrice = 100.0;
        double entryPrice = 99.0;
        double entryFee = 0.198;
        double expectedExit = marketPrice * (1.0 - PaperTradingEngine.SLIPPAGE_RATE);
        double expectedNetProceeds = quantity * expectedExit
                * (1.0 - PaperTradingEngine.FEE_RATE);

        double liquidation = PaperTradingEngine.estimateNetLiquidationValue(quantity, marketPrice);
        double unrealized = PaperTradingEngine.estimateUnrealizedPnl(
                quantity, entryPrice, entryFee, marketPrice);

        assertEquals(expectedNetProceeds, liquidation, 0.000001);
        assertEquals(expectedNetProceeds - quantity * entryPrice - entryFee,
                unrealized, 0.000001);
    }

    @Test
    public void invalidMarketPriceCannotCreatePositiveLiquidationValue() {
        assertEquals(0.0,
                PaperTradingEngine.estimateNetLiquidationValue(1.0, Double.NaN), 0.0);
        assertEquals(0.0,
                PaperTradingEngine.estimateNetLiquidationValue(1.0, -1.0), 0.0);
    }

    @Test
    public void buyAndSellUpdateVirtualWalletAndCloseTrade() {
        PaperTradingEngine engine = new PaperTradingEngine(database);
        double startingCash = database.getCashBalance();

        PaperTradingEngine.Result opened = engine.buy("TESTUSDT", 0.9, 10.0, 9.0);
        assertTrue(opened.message, opened.success);
        assertTrue(opened.tradeId > 0);
        assertTrue(database.getCashBalance() < startingCash);
        assertEquals(1, engine.getOpenPositionCount());

        PaperTradingEngine.Result closed = engine.sell(opened.tradeId, 11.0);
        assertTrue(closed.message, closed.success);
        assertEquals(0, engine.getOpenPositionCount());
        assertEquals(1, database.getClosedTradeCount());
        assertTrue("Winning simulated trade should increase cash after fees",
                database.getCashBalance() > startingCash);

        SQLiteDatabase db = database.getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT status, realized_pnl, exit_price FROM paper_trades WHERE id=?",
                new String[]{String.valueOf(opened.tradeId)});
        try {
            assertTrue(cursor.moveToFirst());
            assertEquals("CLOSED", cursor.getString(0));
            assertTrue(cursor.getDouble(1) > 0.0);
            assertTrue(cursor.getDouble(2) > 0.0);
        } finally {
            cursor.close();
        }
    }

    @Test
    public void simulatorRejectsOversizedPositionWithoutChangingWallet() {
        PaperTradingEngine engine = new PaperTradingEngine(database);
        double startingCash = database.getCashBalance();

        PaperTradingEngine.Result result = engine.buy("TESTUSDT", 100.0, 10.0, 9.0);

        assertFalse(result.success);
        assertEquals(startingCash, database.getCashBalance(), 0.000001);
        assertEquals(0, engine.getOpenPositionCount());
    }

    @Test
    public void simulatorIncludesBothSideFeesAndSlippageInStopRiskLimit() {
        PaperTradingEngine engine = new PaperTradingEngine(database);
        double equity = database.getCashBalance();
        double startingCash = database.getCashBalance();
        double entry = 10.0 * (1.0 + PaperTradingEngine.SLIPPAGE_RATE);
        double stopExit = 9.5 * (1.0 - PaperTradingEngine.SLIPPAGE_RATE);
        double fullCostLossPerUnit = entry * (1.0 + PaperTradingEngine.FEE_RATE)
                - stopExit * (1.0 - PaperTradingEngine.FEE_RATE);
        // Old price-only risk accounting would accept this; full execution-cost
        // accounting correctly rejects it while the notional remains below 25%.
        double quantity = equity * PaperTradingEngine.MAX_RISK_PER_TRADE
                * 1.01 / fullCostLossPerUnit;

        PaperTradingEngine.Result result = engine.buy("TESTUSDT", quantity, 10.0, 9.5);

        assertFalse("Trade whose fee/slippage-adjusted stop loss exceeds 1% must be rejected",
                result.success);
        assertEquals(startingCash, database.getCashBalance(), 0.000001);
        assertEquals(0, engine.getOpenPositionCount());
    }

    @Test
    public void simulatorRejectsSecondOpenPositionForSameSymbol() {
        PaperTradingEngine engine = new PaperTradingEngine(database);
        PaperTradingEngine.Result first = engine.buy("BTCUSDT", 0.1, 100.0, 98.0);
        assertTrue(first.message, first.success);
        double cashAfterFirst = database.getCashBalance();

        PaperTradingEngine.Result second = engine.buy("btcusdt", 0.1, 100.0, 98.0);

        assertFalse("A second open position for the same market must be rejected",
                second.success);
        assertTrue(second.message, second.message.contains("already exists"));
        assertEquals(cashAfterFirst, database.getCashBalance(), 0.000001);
        assertEquals(1, engine.getOpenPositionCount());
    }

    @Test
    public void automationCandleClaimIsIdempotentAcrossReconnectsAndOlderBackfill() {
        long first = System.currentTimeMillis() - 120000L;
        long next = first + 60000L;

        assertTrue(database.claimAutomationCandle("btcusdt", "1m", first));
        assertFalse("Repeated websocket delivery must not trigger a second evaluation",
                database.claimAutomationCandle("BTCUSDT", "1m", first));
        assertFalse("A delayed older backfill row must not rewind the processed watermark",
                database.claimAutomationCandle("BTCUSDT", "1m", first - 60000L));
        assertTrue("The next completed candle must remain eligible",
                database.claimAutomationCandle("BTCUSDT", "1m", next));
        PaperDatabaseHelper reopened = new PaperDatabaseHelper(context);
        try {
            assertFalse("A service restart must retain the latest processed timestamp",
                    reopened.claimAutomationCandle("BTCUSDT", "1m", next));
        } finally {
            reopened.closeStore();
        }
    }

    @Test
    public void backfilledCandleCanStillReceiveItsFirstAutomationEvaluation() {
        seedStrongUpwardCandles("BTCUSDT");
        Cursor latest = database.getReadableDatabase().rawQuery(
                "SELECT open_time, open, high, low, close, volume FROM candles " +
                        "WHERE symbol='BTCUSDT' AND interval='1m' ORDER BY open_time DESC LIMIT 1",
                null);
        long openTime;
        double open, high, low, close, volume;
        try {
            assertTrue(latest.moveToFirst());
            openTime = latest.getLong(0);
            open = latest.getDouble(1);
            high = latest.getDouble(2);
            low = latest.getDouble(3);
            close = latest.getDouble(4);
            volume = latest.getDouble(5);
        } finally {
            latest.close();
        }

        // Simulate the history backfill winning the race against the live close event.
        assertFalse(database.saveCandleIfNew(
                "BTCUSDT", "1m", openTime, open, high, low, close, volume));
        assertTrue(database.hasCandle("BTCUSDT", "1m", openTime));
        assertFalse("Stored history must not consume the live automation watermark",
                database.isAutomationCandleProcessed("BTCUSDT", "1m", openTime));

        String decision = new AutoPaperTradingController(database).onCompletedCandle(
                "BTCUSDT", "1m", close, low, high);
        assertTrue("Expected the backfilled candle's first paper evaluation: " + decision,
                decision.contains("PAPER TRADE OPENED"));
        assertTrue("Evaluation completion should advance the durable watermark",
                database.claimAutomationCandle("BTCUSDT", "1m", openTime));
        assertTrue("A retry after completion must see the candle as processed",
                database.isAutomationCandleProcessed("BTCUSDT", "1m", openTime));
        assertFalse("The same candle must not advance the watermark twice",
                database.claimAutomationCandle("BTCUSDT", "1m", openTime));
    }

    @Test
    public void duplicateCompletedCandleIsIgnored() {
        long openTime = System.currentTimeMillis() - 60000L;
        assertTrue(database.saveCandleIfNew(
                "BTCUSDT", "1m", openTime, 100.0, 102.0, 99.0, 101.0, 5.0));
        assertFalse(database.saveCandleIfNew(
                "BTCUSDT", "1m", openTime, 100.0, 102.0, 99.0, 101.0, 5.0));
        assertEquals(1, database.getStoredCandleCount());
    }

    @Test
    public void expiredCandlesAreRemovedButRecentCandlesRemain() {
        long now = System.currentTimeMillis();
        assertTrue(database.saveCandle(
                "BTCUSDT", "1m", now - 2L * 60L * 60L * 1000L,
                100.0, 102.0, 99.0, 101.0, 5.0));
        assertTrue(database.saveCandle(
                "BTCUSDT", "1m", now - 60000L,
                101.0, 103.0, 100.0, 102.0, 6.0));

        int removed = database.cleanExpiredCandles();

        assertEquals(1, removed);
        assertEquals(1, database.getStoredCandleCount());
    }
    @Test
    public void disabledAutoModeBlocksNewEntriesAndPersistsAcrossControllerInstances() {
        seedStrongUpwardCandles("BTCUSDT");
        AutoPaperTradingController controller = new AutoPaperTradingController(database);
        controller.setEnabled(false);

        AutoPaperTradingController afterRestart = new AutoPaperTradingController(database);
        assertFalse(afterRestart.isEnabled());
        double price = latestClose("BTCUSDT");
        String decision = afterRestart.onCompletedCandle(
                "BTCUSDT", "1m", price, price * 0.999, price * 1.001);

        assertTrue("Expected OFF status, got: " + decision,
                decision.contains("AUTO PAPER TRADING OFF"));
        assertEquals(0, new PaperTradingEngine(database).getOpenPositionCount());
        assertEquals("false", database.getSetting("auto_paper_trading", "missing"));
    }

    @Test
    public void enablingAutoModePersistsAndAllowsFreshSignalEntry() {
        seedStrongUpwardCandles("BTCUSDT");
        AutoPaperTradingController controller = new AutoPaperTradingController(database);
        controller.setEnabled(false);
        controller.setEnabled(true);

        AutoPaperTradingController afterRestart = new AutoPaperTradingController(database);
        assertTrue(afterRestart.isEnabled());
        double price = latestClose("BTCUSDT");
        String decision = afterRestart.onCompletedCandle(
                "BTCUSDT", "1m", price, price * 0.999, price * 1.001);

        assertTrue("Expected a paper entry after re-enabling, got: " + decision,
                decision.contains("PAPER TRADE OPENED"));
        assertEquals(1, new PaperTradingEngine(database).getOpenPositionCount());
    }

    @Test
    public void executionDecisionHistoryPersistsAndIsBounded() {
        seedStrongUpwardCandles("BTCUSDT");
        AutoPaperTradingController controller = new AutoPaperTradingController(database);
        double price = latestClose("BTCUSDT");
        String result = controller.onCompletedCandle(
                "BTCUSDT", "1m", price, price * 0.999, price * 1.001);
        assertTrue(result, result.contains("PAPER TRADE OPENED"));
        Cursor audit = database.getReadableDatabase().rawQuery(
                "SELECT symbol, interval, decision FROM execution_audit ORDER BY id DESC LIMIT 1", null);
        try {
            assertTrue(audit.moveToFirst());
            assertEquals("BTCUSDT", audit.getString(0));
            assertEquals("1m", audit.getString(1));
            assertTrue(audit.getString(2).contains("PAPER TRADE OPENED"));
        } finally { audit.close(); }
        for (int i = 0; i < 110; i++) {
            database.recordExecutionDecision("ETHUSDT", "1m", "TEST AUDIT " + i);
        }
        Cursor count = database.getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM execution_audit", null);
        try { assertTrue(count.moveToFirst()); assertEquals(100, count.getInt(0)); }
        finally { count.close(); }
    }

    @Test
    public void automaticPaperControllerOpensPositionFromFreshUpwardSignal() {
        seedStrongUpwardCandles("BTCUSDT");
        AutoPaperTradingController controller = new AutoPaperTradingController(database);
        double price = latestClose("BTCUSDT");

        String decision = controller.onCompletedCandle(
                "BTCUSDT", "1m", price, price * 0.999, price * 1.001);

        assertTrue("Expected an automatic simulated entry, got: " + decision,
                decision.contains("PAPER TRADE OPENED"));
        assertEquals(1, new PaperTradingEngine(database).getOpenPositionCount());
        assertTrue(database.getCashBalance() < 100.0);
        assertTrue(database.getSetting("auto_last_decision_BTCUSDT", "").contains("PAPER TRADE OPENED"));
    }

    @Test
    public void pausedAutoModeStillMonitorsAndClosesExistingPosition() {
        seedStrongUpwardCandles("BTCUSDT");
        AutoPaperTradingController controller = new AutoPaperTradingController(database);
        double price = latestClose("BTCUSDT");
        String opened = controller.onCompletedCandle(
                "BTCUSDT", "1m", price, price * 0.999, price * 1.001);
        assertTrue(opened, opened.contains("PAPER TRADE OPENED"));

        Cursor position = database.getReadableDatabase().rawQuery(
                "SELECT stop_loss FROM paper_trades WHERE symbol='BTCUSDT' AND status='OPEN'", null);
        double stop;
        try {
            assertTrue(position.moveToFirst());
            stop = position.getDouble(0);
        } finally {
            position.close();
        }

        controller.setEnabled(false);
        String exit = controller.onCompletedCandle(
                "BTCUSDT", "1m", stop * 0.99, stop * 0.99, stop * 0.995);

        assertTrue("Paused mode must still execute existing protective exits: " + exit,
                exit.contains("STOP EXECUTED"));
        assertEquals(0, new PaperTradingEngine(database).getOpenPositionCount());
        assertFalse(controller.isEnabled());
    }

    @Test
    public void automaticPaperControllerClosesPositionWhenStopIsReached() {
        seedStrongUpwardCandles("BTCUSDT");
        AutoPaperTradingController controller = new AutoPaperTradingController(database);
        double price = latestClose("BTCUSDT");
        String opened = controller.onCompletedCandle(
                "BTCUSDT", "1m", price, price * 0.999, price * 1.001);
        assertTrue(opened, opened.contains("PAPER TRADE OPENED"));

        SQLiteDatabase db = database.getReadableDatabase();
        Cursor position = db.rawQuery(
                "SELECT stop_loss FROM paper_trades WHERE symbol='BTCUSDT' AND status='OPEN'",
                null);
        double stop;
        try {
            assertTrue(position.moveToFirst());
            stop = position.getDouble(0);
        } finally {
            position.close();
        }

        String exit = controller.onCompletedCandle(
                "BTCUSDT", "1m", stop * 0.99, stop * 0.99, stop * 0.995);

        assertTrue("Expected stop execution, got: " + exit, exit.contains("STOP EXECUTED"));
        assertEquals(0, new PaperTradingEngine(database).getOpenPositionCount());
        assertEquals(1, database.getClosedTradeCount());
    }

    @Test
    public void automaticPaperControllerClosesPositionWhenTakeProfitIsReached() {
        seedStrongUpwardCandles("BTCUSDT");
        AutoPaperTradingController controller = new AutoPaperTradingController(database);
        double price = latestClose("BTCUSDT");
        String opened = controller.onCompletedCandle(
                "BTCUSDT", "1m", price, price * 0.999, price * 1.001);
        assertTrue(opened, opened.contains("PAPER TRADE OPENED"));

        SQLiteDatabase db = database.getReadableDatabase();
        Cursor position = db.rawQuery(
                "SELECT entry_price, stop_loss FROM paper_trades WHERE symbol='BTCUSDT' AND status='OPEN'",
                null);
        double entry;
        double stop;
        try {
            assertTrue(position.moveToFirst());
            entry = position.getDouble(0);
            stop = position.getDouble(1);
        } finally {
            position.close();
        }
        double target = entry + ((entry - stop) * 2.0);

        String exit = controller.onCompletedCandle(
                "BTCUSDT", "1m", target, target * 0.999, target * 1.001);

        assertTrue("Expected take-profit execution, got: " + exit,
                exit.contains("TAKE-PROFIT EXECUTED"));
        assertEquals(0, new PaperTradingEngine(database).getOpenPositionCount());
        assertEquals(1, database.getClosedTradeCount());
        assertTrue("Take-profit should record positive net P/L",
                new PaperTradingEngine(database).getTodayRealizedPnl() > 0.0);
    }

    @Test
    public void stopIsChosenConservativelyWhenOneCandleTouchesBothBarriers() {
        seedStrongUpwardCandles("BTCUSDT");
        AutoPaperTradingController controller = new AutoPaperTradingController(database);
        double price = latestClose("BTCUSDT");
        String opened = controller.onCompletedCandle(
                "BTCUSDT", "1m", price, price * 0.999, price * 1.001);
        assertTrue(opened, opened.contains("PAPER TRADE OPENED"));

        SQLiteDatabase db = database.getReadableDatabase();
        Cursor position = db.rawQuery(
                "SELECT entry_price, stop_loss FROM paper_trades WHERE symbol='BTCUSDT' AND status='OPEN'",
                null);
        double entry;
        double stop;
        try {
            assertTrue(position.moveToFirst());
            entry = position.getDouble(0);
            stop = position.getDouble(1);
        } finally {
            position.close();
        }
        double target = entry + ((entry - stop) * 2.0);

        String exit = controller.onCompletedCandle(
                "BTCUSDT", "1m", target, stop * 0.99, target * 1.001);

        assertTrue("When both barriers are touched, stop must win: " + exit,
                exit.contains("STOP EXECUTED"));
        assertFalse(exit.contains("TAKE-PROFIT EXECUTED"));
        assertEquals(0, new PaperTradingEngine(database).getOpenPositionCount());
    }

    @Test
    public void manualCloseStartsAutomaticReentryCooldown() {
        seedStrongUpwardCandles("BTCUSDT");
        PaperTradingEngine engine = new PaperTradingEngine(database);
        PaperTradingEngine.Result manual = engine.buy("BTCUSDT", 0.1, 100.0, 98.0);
        assertTrue(manual.message, manual.success);
        PaperTradingEngine.Result closed = engine.sell(manual.tradeId, 101.0);
        assertTrue(closed.message, closed.success);

        AutoPaperTradingController controller = new AutoPaperTradingController(database);
        double price = latestClose("BTCUSDT");
        String decision = controller.onCompletedCandle(
                "BTCUSDT", "1m", price, price * 0.999, price * 1.001);

        assertTrue("Manual close must prevent an immediate automatic re-entry: " + decision,
                decision.contains("re-entry cooldown"));
        assertEquals(0, engine.getOpenPositionCount());
    }

    @Test
    public void resettingPracticeAccountClearsOldAutomationCooldownAndDecision() {
        database.putSetting("auto_last_exit_BTCUSDT", String.valueOf(System.currentTimeMillis()));
        database.putSetting("auto_last_decision_BTCUSDT", "OLD SESSION DECISION");
        database.putSetting("auto_last_decision_at_BTCUSDT", "123");
        database.putSetting("auto_last_processed_candle_BTCUSDT_1m", "123456789");

        database.resetPaperSimulation();

        assertEquals("0", database.getSetting("auto_last_exit_BTCUSDT", "0"));
        assertEquals("", database.getSetting("auto_last_decision_BTCUSDT", ""));
        assertEquals("", database.getSetting("auto_last_decision_at_BTCUSDT", ""));
        assertEquals("", database.getSetting(
                "auto_last_processed_candle_BTCUSDT_1m", ""));
        assertEquals(100.0, database.getCashBalance(), 0.000001);
    }


    @Test
    public void ledgerReconciliationMatchesWalletWithOpenAndClosedPositions() {
        PaperTradingEngine engine = new PaperTradingEngine(database);
        PaperTradingEngine.Result first = engine.buy("AAAUSDT", 0.1, 100.0, 98.0);
        assertTrue(first.message, first.success);
        PaperTradingEngine.LedgerReconciliation afterOpen = engine.reconcileLedger();
        assertTrue("Open entry must reconcile: " + afterOpen.difference, afterOpen.consistent);

        PaperTradingEngine.Result closed = engine.sell(first.tradeId, 103.0);
        assertTrue(closed.message, closed.success);
        PaperTradingEngine.LedgerReconciliation afterWin = engine.reconcileLedger();
        assertTrue("Closed winning trade must reconcile: " + afterWin.difference, afterWin.consistent);

        PaperTradingEngine.Result second = engine.buy("BBBUSDT", 0.1, 100.0, 98.0);
        assertTrue(second.message, second.success);
        PaperTradingEngine.LedgerReconciliation mixed = engine.reconcileLedger();
        assertTrue("Closed history plus open position must reconcile: " + mixed.difference, mixed.consistent);

        PaperTradingEngine.Result loss = engine.sell(second.tradeId, 95.0);
        assertTrue(loss.message, loss.success);
        PaperTradingEngine.LedgerReconciliation afterLoss = engine.reconcileLedger();
        assertTrue("Winning and losing closes must reconcile: " + afterLoss.difference, afterLoss.consistent);
        assertEquals(afterLoss.expectedCash, database.getCashBalance(), 0.01);
    }

    private void seedStrongUpwardCandles(String symbol) {
        long now = System.currentTimeMillis();
        double previous = 100.0;
        for (int i = 0; i < 7; i++) {
            double close = 100.0 * Math.pow(1.05, i);
            long openTime = now - (6L - i) * 60000L - 10000L;
            assertTrue(database.saveCandle(symbol, "1m", openTime,
                    previous, Math.max(previous, close) * 1.001,
                    Math.min(previous, close) * 0.999, close, 10.0 + i));
            previous = close;
        }
    }

    private double latestClose(String symbol) {
        Cursor c = database.getReadableDatabase().rawQuery(
                "SELECT close FROM candles WHERE symbol=? AND interval='1m' ORDER BY open_time DESC LIMIT 1",
                new String[]{symbol});
        try {
            assertTrue(c.moveToFirst());
            return c.getDouble(0);
        } finally {
            c.close();
        }
    }

    @Test
    public void firstRunNeutralAiStillOpensClearlyLabelledLowRiskPaperProbe() {
        long now = System.currentTimeMillis();
        double price = 100.0;
        for (int i = 0; i < 3; i++) {
            long openTime = now - (2L - i) * 60000L - 10000L;
            assertTrue(database.saveCandle("BTCUSDT", "1m", openTime,
                    price, price * 1.001, price * 0.999, price, 100.0));
        }

        AutoPaperTradingController controller = new AutoPaperTradingController(database);
        String decision = controller.onCompletedCandle(
                "BTCUSDT", "1m", price, price * 0.999, price * 1.001);

        assertTrue("Expected a first-run diagnostic paper trade, got: " + decision,
                decision.contains("STARTUP PAPER TEST TRADE OPENED"));
        assertTrue(decision.contains("not an AI signal"));
        assertEquals(1, new PaperTradingEngine(database).getOpenPositionCount());
        assertTrue("Probe must use only a small part of the practice wallet",
                database.getCashBalance() > 85.0);
        assertEquals("true", database.getSetting("auto_startup_probe_completed", "false"));
    }

}
