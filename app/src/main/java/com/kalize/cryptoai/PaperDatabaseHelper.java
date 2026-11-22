package com.kalize.cryptoai;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/**
 * Small local store designed for Android 4.4+.
 * This database is exclusively for simulation; it never places exchange orders.
 */
public final class PaperDatabaseHelper extends SQLiteOpenHelper {
    private static final String DB_NAME = "kalize_paper.db";
    private static final int DB_VERSION = 3
    private static final long DAY_MS = 24L * 60L * 60L * 1000L;
    private static final long CANDLE_WINDOW_MS = 30L * DAY_MS;

    public PaperDatabaseHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE candles (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "symbol TEXT NOT NULL," +
                "interval TEXT NOT NULL," +
                "open_time INTEGER NOT NULL," +
                "open REAL NOT NULL, high REAL NOT NULL, low REAL NOT NULL," +
                "close REAL NOT NULL, volume REAL NOT NULL," +
                "UNIQUE(symbol, interval, open_time))");
        db.execSQL("CREATE INDEX candles_time_idx ON candles(open_time)");
        db.execSQL("CREATE TABLE wallet (" +
                "id INTEGER PRIMARY KEY CHECK(id = 1)," +
                "starting_balance REAL NOT NULL," +
                "cash_balance REAL NOT NULL," +
                "updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE paper_trades (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "symbol TEXT NOT NULL," +
                "side TEXT NOT NULL," +
                "quantity REAL NOT NULL," +
                "entry_price REAL NOT NULL," +
                "exit_price REAL," +
                "fees REAL NOT NULL DEFAULT 0," +
                "realized_pnl REAL NOT NULL DEFAULT 0," +
                "status TEXT NOT NULL," +
                "opened_at INTEGER NOT NULL," +
                "closed_at INTEGER," +
                "stop_loss REAL NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX paper_trades_status_idx ON paper_trades(status)");
        createLearningTable(db);
        db.execSQL("CREATE TABLE app_settings (" +
                "setting_key TEXT PRIMARY KEY," +
                "setting_value TEXT NOT NULL)");
        ContentValues wallet = new ContentValues();
        wallet.put("id", 1);
        wallet.put("starting_balance", 100.0);
        wallet.put("cash_balance", 100.0);
        wallet.put("updated_at", System.currentTimeMillis());
        db.insertOrThrow("wallet", null, wallet);
        ContentValues mode = new ContentValues();
        mode.put("setting_key", "trading_mode");
        mode.put("setting_value", "paper_only");
        db.insertOrThrow("app_settings", null, mode);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE paper_trades ADD COLUMN stop_loss REAL NOT NULL DEFAULT 0");
        }
        if (oldVersion < 3) createLearningTable(db);
        // Migrations preserve the virtual wallet, candle history and all trades.
    }

    private void createLearningTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS ai_models (" +
                "model_key TEXT PRIMARY KEY, w_momentum REAL NOT NULL, " +
                "w_trend REAL NOT NULL, w_range REAL NOT NULL, bias REAL NOT NULL, " +
                "samples INTEGER NOT NULL DEFAULT 0, correct INTEGER NOT NULL DEFAULT 0, " +
                "updated_at INTEGER NOT NULL)");
    }

    public double getCashBalance() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.rawQuery("SELECT cash_balance FROM wallet WHERE id = 1", null);
        try {
            return cursor.moveToFirst() ? cursor.getDouble(0) : 100.0;
        } finally {
            cursor.close();
        }
    }

    public int getClosedTradeCount() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT COUNT(*) FROM paper_trades WHERE status = 'CLOSED'", null);
        try {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        } finally {
            cursor.close();
        }
    }

    public boolean saveCandle(String symbol, String interval, long openTime,
                              double open, double high, double low,
                              double close, double volume) {
        if (symbol == null || interval == null || symbol.length() == 0 ||
                interval.length() == 0 || openTime <= 0 || !valid(open) ||
                !valid(high) || !valid(low) || !valid(close) || !valid(volume) ||
                high < low || high < open || high < close || low > open || low > close ||
                volume < 0) return false;

        ContentValues values = new ContentValues();
        values.put("symbol", symbol);
        values.put("interval", interval);
        values.put("open_time", openTime);
        values.put("open", open);
        values.put("high", high);
        values.put("low", low);
        values.put("close", close);
        values.put("volume", volume);
        getWritableDatabase().insertWithOnConflict(
                "candles", null, values, SQLiteDatabase.CONFLICT_REPLACE);
        return true;
    }

    /** Keeps the active candle dataset bounded to the most recent 30 days. */
    public int cleanExpiredCandles() {
        long cutoff = System.currentTimeMillis() - CANDLE_WINDOW_MS;
        return getWritableDatabase().delete("candles", "open_time < ?",
                new String[]{String.valueOf(cutoff)});
    }

    public int getStoredCandleCount() {
        Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM candles", null);
        try {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        } finally {
            cursor.close();
        }
    }

    public void closeStore() {
        close();
    }

    private boolean valid(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }
}
