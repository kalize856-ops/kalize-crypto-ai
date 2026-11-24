package com.kalize.cryptoai;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

/** Local-only paper ledger. It never sends exchange orders or uses real funds. */
public final class PaperTradingEngine {
    public static final double FEE_RATE = 0.0010;
    public static final double SLIPPAGE_RATE = 0.0005;
    public static final double MAX_RISK_PER_TRADE = 0.01;
    public static final double DAILY_LOSS_LIMIT = 0.02;
    public static final int MAX_OPEN_POSITIONS = 3;
    public static final double MAX_POSITION_VALUE = 0.25;

    private final PaperDatabaseHelper helper;
    private final OnDeviceLearningEngine learner;

    public PaperTradingEngine(PaperDatabaseHelper helper) {
        if (helper == null) throw new IllegalArgumentException("Database is required");
        this.helper = helper;
        this.learner = new OnDeviceLearningEngine(helper);
    }

    public static final class Result {
        public final boolean success;
        public final String message;
        public final long tradeId;
        public final double amount;
        Result(boolean success, String message, long tradeId, double amount) {
            this.success=success; this.message=message; this.tradeId=tradeId; this.amount=amount;
        }
    }

    /**
     * Estimates cash received if an open position were liquidated now,
     * including adverse exit slippage and the exit fee.
     */
    public static double estimateNetLiquidationValue(double quantity, double marketPrice) {
        if (!finitePositiveValue(quantity) || !finitePositiveValue(marketPrice)) return 0.0;
        double exitPrice = marketPrice * (1.0 - SLIPPAGE_RATE);
        return quantity * exitPrice * (1.0 - FEE_RATE);
    }

    /** Net unrealized P/L after entry and estimated liquidation costs. */
    public static double estimateUnrealizedPnl(double quantity, double entryPrice,
                                                double entryFee, double marketPrice) {
        if (!finitePositiveValue(quantity) || !finitePositiveValue(entryPrice) ||
                !finiteNonNegativeValue(entryFee) || !finitePositiveValue(marketPrice)) return 0.0;
        return estimateNetLiquidationValue(quantity, marketPrice)
                - (quantity * entryPrice) - entryFee;
    }

    private static boolean finitePositiveValue(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value) && value > 0.0;
    }

    private static boolean finiteNonNegativeValue(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value) && value >= 0.0;
    }

    /** Existing manual paper-trade entry point; no AI feedback is attached. */
    public Result buy(String symbol,double quantity,double marketPrice,double stopLoss) {
        return buyInternal(symbol,quantity,marketPrice,stopLoss,-1,null);
    }

    /**
     * Opens a simulated position using the on-device model's current estimate.
     * Only an UPWARD BIAS assessment is eligible; real orders are never sent.
     */
    public Result buyWithAiSignal(String symbol,String interval,double quantity,
                                  double marketPrice,double stopLoss) {
        OnDeviceLearningEngine.Assessment a=learner.assess(symbol,interval);
        if(!"UPWARD BIAS".equals(a.signal))
            return fail("AI paper entry skipped: model signal is "+a.signal+".");
        return buyInternal(symbol,quantity,marketPrice,stopLoss,a.probability,interval);
    }

    private Result buyInternal(String symbol,double quantity,double marketPrice,
                               double stopLoss,double aiProbability,String aiInterval) {
        if(symbol==null||symbol.trim().length()==0||!positive(quantity)||
                !positive(marketPrice)||!positive(stopLoss)||stopLoss>=marketPrice)
            return fail("Enter a symbol, positive quantity and price, and a stop below entry.");
        SQLiteDatabase db=helper.getWritableDatabase();
        db.beginTransaction();
        try {
            double cash=scalar(db,"SELECT cash_balance FROM wallet WHERE id=1");
            double dailyPnl=scalar(db,"SELECT COALESCE(SUM(realized_pnl),0) FROM paper_trades WHERE status='CLOSED' AND closed_at >= "+
                    startOfLocalDayMillis());
            double startingBalance=scalar(db,"SELECT starting_balance FROM wallet WHERE id=1");
            if(cash<=0.005) return fail("Virtual cash is depleted. Reset the simulation to start a new paper account.");
            if(dailyPnl<=-(startingBalance*DAILY_LOSS_LIMIT))
                return fail("Daily simulated loss limit reached. New positions are paused until tomorrow.");
            double equity=cash+scalar(db,"SELECT COALESCE(SUM(quantity*entry_price),0) FROM paper_trades WHERE status='OPEN'");
            String normalizedSymbol = symbol.trim().toUpperCase(java.util.Locale.US);
            Cursor sameSymbol = db.rawQuery(
                    "SELECT COUNT(*) FROM paper_trades WHERE symbol=? AND status='OPEN'",
                    new String[]{normalizedSymbol});
            try {
                if (sameSymbol.moveToFirst() && sameSymbol.getInt(0) > 0)
                    return fail("An open simulated position already exists for " + normalizedSymbol + ". Close it before opening another.");
            } finally { sameSymbol.close(); }
            int openCount=(int)scalar(db,"SELECT COUNT(*) FROM paper_trades WHERE status='OPEN'");
            if(openCount>=MAX_OPEN_POSITIONS) return fail("Limit reached: 3 open simulated positions.");
            double execution=marketPrice*(1.0+SLIPPAGE_RATE);
            double notional=quantity*execution, fee=notional*FEE_RATE;
            if(notional>equity*MAX_POSITION_VALUE) return fail("Position exceeds the 25% simulated portfolio-size limit.");
            double expectedStopExit = stopLoss * (1.0 - SLIPPAGE_RATE);
            double entryCostPerUnit = execution * (1.0 + FEE_RATE);
            double stopProceedsPerUnit = expectedStopExit * (1.0 - FEE_RATE);
            double worstCaseLossPerUnit = entryCostPerUnit - stopProceedsPerUnit;
            if(worstCaseLossPerUnit <= 0.0 ||
                    quantity * worstCaseLossPerUnit > equity * MAX_RISK_PER_TRADE)
                return fail("Estimated stop-loss risk including entry/exit fees and slippage exceeds 1% of simulated portfolio equity.");
            if(notional+fee>cash) return fail("Insufficient virtual cash. Close an open position or reset the simulation.");
            ContentValues wallet=new ContentValues();
            wallet.put("cash_balance",cash-notional-fee); wallet.put("updated_at",System.currentTimeMillis());
            db.update("wallet",wallet,"id=1",null);
            ContentValues trade=new ContentValues();
            trade.put("symbol",symbol.trim().toUpperCase(java.util.Locale.US));
            trade.put("side","BUY"); trade.put("quantity",quantity); trade.put("entry_price",execution);
            trade.put("fees",fee); trade.put("realized_pnl",0); trade.put("status","OPEN");
            trade.put("opened_at",System.currentTimeMillis()); trade.put("stop_loss",stopLoss);
            trade.put("ai_probability",aiProbability); trade.put("ai_interval",aiInterval);
            long id=db.insertOrThrow("paper_trades",null,trade);
            db.setTransactionSuccessful();
            return new Result(true,"Simulated position opened. No real order was sent.",id,notional);
        } catch(RuntimeException ex) {
            return fail("Simulation could not complete: "+ex.getMessage());
        } finally { db.endTransaction(); }
    }

    /** Closes a virtual position and trains the originating AI model on its outcome. */
    public Result sell(long tradeId,double marketPrice) {
        if(tradeId<=0||!positive(marketPrice)) return fail("Invalid trade or market price.");
        SQLiteDatabase db=helper.getWritableDatabase();
        boolean transaction=true;
        String symbol=null, interval=null;
        double probability=-1, pnl=0;
        db.beginTransaction();
        try {
            Cursor c=db.rawQuery("SELECT symbol,quantity,entry_price,fees,status,ai_probability,ai_interval FROM paper_trades WHERE id=?",
                    new String[]{String.valueOf(tradeId)});
            double qty,entry,entryFee;
            try {
                if(!c.moveToFirst()) return fail("Position not found.");
                symbol=c.getString(0); qty=c.getDouble(1); entry=c.getDouble(2);
                entryFee=c.getDouble(3);
                if(!"OPEN".equals(c.getString(4))) return fail("This position is already closed.");
                probability=c.getDouble(5); interval=c.isNull(6)?null:c.getString(6);
            } finally { c.close(); }
            double exit=marketPrice*(1.0-SLIPPAGE_RATE);
            double gross=qty*exit, exitFee=gross*FEE_RATE;
            pnl=(gross-exitFee)-(qty*entry+entryFee);
            ContentValues wallet=new ContentValues();
            double availableCash=scalar(db,"SELECT cash_balance FROM wallet WHERE id=1");
            wallet.put("cash_balance",Math.max(0.0,availableCash+gross-exitFee));
            wallet.put("updated_at",System.currentTimeMillis());
            db.update("wallet",wallet,"id=1",null);
            ContentValues trade=new ContentValues();
            trade.put("exit_price",exit); trade.put("fees",entryFee+exitFee);
            trade.put("realized_pnl",pnl); trade.put("status","CLOSED");
            trade.put("closed_at",System.currentTimeMillis());
            db.update("paper_trades",trade,"id=? AND status='OPEN'",new String[]{String.valueOf(tradeId)});
            db.setTransactionSuccessful();
        } catch(RuntimeException ex) {
            return fail("Simulation could not complete: "+ex.getMessage());
        } finally {
            if(transaction) db.endTransaction();
        }
        // Manual and automatic closes both start the same re-entry cooldown.
        // Without this, a manual close could be followed by an immediate auto-entry.
        helper.putSetting("auto_last_exit_" + symbol,
                String.valueOf(System.currentTimeMillis()));
        if(probability>=0&&interval!=null) learner.learnFromPaperOutcome(symbol,interval,probability,pnl);
        return new Result(true,"Simulated position closed. Net P/L: "+
                String.format(java.util.Locale.US,"$%.2f",pnl)+
                (probability>=0?" AI model updated from this result.":""),tradeId,pnl);
    }

    public int getOpenPositionCount() {
        return (int)scalar(helper.getReadableDatabase(),"SELECT COUNT(*) FROM paper_trades WHERE status='OPEN'");
    }
    public double getOpenEntryValue() {
        return scalar(helper.getReadableDatabase(),"SELECT COALESCE(SUM(quantity*entry_price),0) FROM paper_trades WHERE status='OPEN'");
    }

    /** Cash invariant: starting balance + closed net P/L - open entry costs. */
    public static final class LedgerReconciliation {
        public final double actualCash;
        public final double expectedCash;
        public final double difference;
        public final boolean consistent;
        LedgerReconciliation(double actualCash, double expectedCash) {
            this.actualCash = actualCash;
            this.expectedCash = expectedCash;
            this.difference = actualCash - expectedCash;
            this.consistent = Math.abs(difference) <= 0.01;
        }
    }

    public LedgerReconciliation reconcileLedger() {
        SQLiteDatabase db = helper.getReadableDatabase();
        double actual = scalar(db, "SELECT cash_balance FROM wallet WHERE id=1");
        double starting = scalar(db, "SELECT starting_balance FROM wallet WHERE id=1");
        double closedPnl = scalar(db,
                "SELECT COALESCE(SUM(realized_pnl),0) FROM paper_trades WHERE status='CLOSED'");
        double openCosts = scalar(db,
                "SELECT COALESCE(SUM(quantity*entry_price+fees),0) FROM paper_trades WHERE status='OPEN'");
        return new LedgerReconciliation(actual, starting + closedPnl - openCosts);
    }

    /** Uses the device's local calendar day so daily limits reset at local midnight. */
    static long startOfLocalDayMillis() {
        java.util.Calendar calendar = java.util.Calendar.getInstance();
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0);
        calendar.set(java.util.Calendar.MINUTE, 0);
        calendar.set(java.util.Calendar.SECOND, 0);
        calendar.set(java.util.Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    public double getTodayRealizedPnl() {
        long start=startOfLocalDayMillis();
        return scalar(helper.getReadableDatabase(),"SELECT COALESCE(SUM(realized_pnl),0) FROM paper_trades WHERE status='CLOSED' AND closed_at>="+start);
    }
    public boolean isDailyLossLimitReached() {
        double starting=scalar(helper.getReadableDatabase(),"SELECT starting_balance FROM wallet WHERE id=1");
        return getTodayRealizedPnl()<=-(starting*DAILY_LOSS_LIMIT);
    }
    private double scalar(SQLiteDatabase db,String sql) {
        Cursor c=db.rawQuery(sql,null);
        try { return c.moveToFirst()?c.getDouble(0):0.0; }
        finally { c.close(); }
    }
    private boolean positive(double v) { return !Double.isNaN(v)&&!Double.isInfinite(v)&&v>0; }
    private Result fail(String message) { return new Result(false,message,-1,0); }
}