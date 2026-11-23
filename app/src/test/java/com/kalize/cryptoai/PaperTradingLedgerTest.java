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
}
