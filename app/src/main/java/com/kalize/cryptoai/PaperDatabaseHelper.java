package com.kalize.cryptoai;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/** Small local store designed for Android 4.4+; simulation data only. */
public final class PaperDatabaseHelper extends SQLiteOpenHelper {
    private static final String DB_NAME = "kalize_paper.db";
    private static final int DB_VERSION = 8;
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
        createTradesTable(db);
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
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE paper_trades ADD COLUMN ai_probability REAL NOT NULL DEFAULT -1");
            db.execSQL("ALTER TABLE paper_trades ADD COLUMN ai_interval TEXT");
        }
        if (oldVersion < 5 && !hasColumn(db, "ai_models", "candle_samples")) {
            db.execSQL("ALTER TABLE ai_models ADD COLUMN candle_samples INTEGER NOT NULL DEFAULT 0");
        }
        if (oldVersion < 6 && !hasColumn(db, "ai_models", "last_candle_time")) {
            db.execSQL("ALTER TABLE ai_models ADD COLUMN last_candle_time INTEGER NOT NULL DEFAULT 0");
        }
        if (oldVersion < 7) {
            db.execSQL("CREATE TABLE IF NOT EXISTS app_settings (" +
                    "setting_key TEXT PRIMARY KEY," +
                    "setting_value TEXT NOT NULL)");
            ContentValues mode = new ContentValues();
            mode.put("setting_key", "trading_mode");
            mode.put("setting_value", "paper_only");
            db.insertWithOnConflict("app_settings", null, mode, SQLiteDatabase.CONFLICT_IGNORE);
        }
        if (oldVersion < 8) {
            if (!hasColumn(db, "ai_models", "trade_samples"))
                db.execSQL("ALTER TABLE ai_models ADD COLUMN trade_samples INTEGER NOT NULL DEFAULT 0");
            if (!hasColumn(db, "ai_models", "trade_correct"))
                db.execSQL("ALTER TABLE ai_models ADD COLUMN trade_correct INTEGER NOT NULL DEFAULT 0");
            // Old versions mixed candle and paper-trade correctness counters. Reset only
            // report counters; learned weights and the last processed candle are preserved.
            db.execSQL("UPDATE ai_models SET samples=0, correct=0, candle_samples=0, " +
                    "trade_samples=0, trade_correct=0");
        }
        // Existing virtual balances, candles and trades are preserved.
    }

    private void createTradesTable(SQLiteDatabase db) {
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
                "stop_loss REAL NOT NULL DEFAULT 0," +
                "ai_probability REAL NOT NULL DEFAULT -1," +
                "ai_interval TEXT)");
        db.execSQL("CREATE INDEX paper_trades_status_idx ON paper_trades(status)");
    }

    private void createLearningTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS ai_models (" +
                "model_key TEXT PRIMARY KEY, w_momentum REAL NOT NULL, " +
                "w_trend REAL NOT NULL, w_range REAL NOT NULL, bias REAL NOT NULL, " +
                "samples INTEGER NOT NULL DEFAULT 0, correct INTEGER NOT NULL DEFAULT 0, " +
                "candle_samples INTEGER NOT NULL DEFAULT 0, last_candle_time INTEGER NOT NULL DEFAULT 0, " +
                "trade_samples INTEGER NOT NULL DEFAULT 0, trade_correct INTEGER NOT NULL DEFAULT 0, " +
                "updated_at INTEGER NOT NULL)");
    }

    private boolean hasColumn(SQLiteDatabase db, String table, String column) {
        Cursor c = db.rawQuery("PRAGMA table_info(" + table + ")", null);
        try {
            while (c.moveToNext()) if (column.equals(c.getString(1))) return true;
            return false;
        } finally { c.close(); }
    }

    public double getCashBalance() {
        Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT cash_balance FROM wallet WHERE id = 1", null);
        try { return cursor.moveToFirst() ? cursor.getDouble(0) : 100.0; }
        finally { cursor.close(); }
    }

    /** Starts a fresh virtual account while retaining candles and AI learning. */
    public void resetPaperSimulation() {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("paper_trades", null, null);
            ContentValues wallet = new ContentValues();
            wallet.put("starting_balance", 100.0);
            wallet.put("cash_balance", 100.0);
            wallet.put("updated_at", System.currentTimeMillis());
            db.update("wallet", wallet, "id=1", null);
            ContentValues auto = new ContentValues();
            auto.put("setting_key", "auto_paper_trading");
            auto.put("setting_value", "true");
            db.insertWithOnConflict("app_settings", null, auto, SQLiteDatabase.CONFLICT_REPLACE);
            ContentValues peak = new ContentValues();
            peak.put("setting_key", "equity_high_water_mark");
            peak.put("setting_value", "100.0");
            db.insertWithOnConflict("app_settings", null, peak, SQLiteDatabase.CONFLICT_REPLACE);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public boolean isVirtualCashDepleted() {
        return getCashBalance() <= 0.005;
    }

    public int getClosedTradeCount() {
        Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM paper_trades WHERE status = 'CLOSED'", null);
        try { return cursor.moveToFirst() ? cursor.getInt(0) : 0; }
        finally { cursor.close(); }
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
        Cursor cursor = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM candles", null);
        try { return cursor.moveToFirst() ? cursor.getInt(0) : 0; }
        finally { cursor.close(); }
    }

    public String getSetting(String key, String defaultValue) {
        Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT setting_value FROM app_settings WHERE setting_key=?",
                new String[]{key});
        try { return cursor.moveToFirst() ? cursor.getString(0) : defaultValue; }
        finally { cursor.close(); }
    }

    public void putSetting(String key, String value) {
        if (key == null || value == null) return;
        ContentValues values = new ContentValues();
        values.put("setting_key", key);
        values.put("setting_value", value);
        getWritableDatabase().insertWithOnConflict(
                "app_settings", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public void closeStore() { close(); }

    private boolean valid(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }
}