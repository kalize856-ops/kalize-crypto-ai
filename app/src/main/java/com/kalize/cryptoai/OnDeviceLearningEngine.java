package com.kalize.cryptoai;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.util.Locale;

/** Lightweight incremental on-device learner for Android 4.4+; paper trading only. */
public final class OnDeviceLearningEngine {
    private static final int MAX_CANDLES = 1200;
    private static final double LEARNING_RATE = 0.04;
    private static final double TRADE_LEARNING_RATE = 0.08;
    private final PaperDatabaseHelper helper;

    public static final class Assessment {
        public final String symbol, signal;
        public final double confidence, accuracy, probability;
        public final int samples;
        Assessment(String s,String g,double c,int n,double a,double p) {
            symbol=s;signal=g;confidence=c;samples=n;accuracy=a;probability=p;
        }
    }
    private static final class Model {
        double momentum=0.10,trend=0.10,range=0,bias=0;
        int samples=0,correct=0,candleSamples=0;
        long lastCandleTime=0L;
    }

    public OnDeviceLearningEngine(PaperDatabaseHelper h) {
        if(h==null) throw new IllegalArgumentException("Database required");
        helper=h;
    }

    /**
     * Incrementally learns from completed OHLCV candles. Each candle is trained
     * only after the following candle closes, so the label is not future-leaked.
     */
    public int learn(String symbol,String interval) {
        if(empty(symbol)||empty(interval)) return 0;
        SQLiteDatabase db=helper.getWritableDatabase();
        String modelKey=key(symbol,interval);
        Model m=load(db,modelKey);
        Cursor c=db.rawQuery("SELECT open_time,close,high,low,volume FROM candles " +
                "WHERE symbol=? AND interval=? AND open_time>=COALESCE((SELECT open_time FROM candles WHERE symbol=? AND interval=? AND open_time<=? ORDER BY open_time DESC LIMIT 1 OFFSET 5),0) ORDER BY open_time ASC LIMIT "+MAX_CANDLES,
                new String[]{symbol,interval,symbol,interval,String.valueOf(m.lastCandleTime)});
        long[] times=new long[MAX_CANDLES];
        double[] close=new double[MAX_CANDLES],high=new double[MAX_CANDLES],
                low=new double[MAX_CANDLES],volume=new double[MAX_CANDLES];
        int n=0;
        try {
            while(c.moveToNext()&&n<MAX_CANDLES) {
                times[n]=c.getLong(0);close[n]=c.getDouble(1);
                high[n]=c.getDouble(2);low[n]=c.getDouble(3);volume[n]=c.getDouble(4);n++;
            }
        } finally { c.close(); }
        
        int added=0;
        for(int i=6;i<n-1;i++) {
            if(times[i]<=m.lastCandleTime) continue;
            double[] x=features(close,high,low,volume,i);
            int label=close[i+1]>close[i]?1:0;
            double p=probability(m,x);
            if((p>=0.5?1:0)==label) m.correct++;
            double error=label-p;
            m.momentum=clamp(m.momentum+LEARNING_RATE*error*x[0],-4,4);
            m.trend=clamp(m.trend+LEARNING_RATE*error*x[1],-4,4);
            m.range=clamp(m.range+LEARNING_RATE*error*x[2],-4,4);
            m.bias=clamp(m.bias+LEARNING_RATE*error,-3,3);
            m.samples++;m.candleSamples++;m.lastCandleTime=times[i];added++;
        }
        if(added>0) save(db,modelKey,m);
        return added;
    }

    /** Current model estimate from the latest seven completed 1-minute candles. */
    public Assessment assess(String symbol,String interval) {
        if(empty(symbol)||empty(interval))
            return new Assessment("","INSUFFICIENT DATA",0,0,0,0.5);
        SQLiteDatabase db=helper.getReadableDatabase();
        Model m=load(db,key(symbol,interval));
        Cursor c=db.rawQuery("SELECT close,high,low,volume FROM candles " +
                "WHERE symbol=? AND interval=? ORDER BY open_time DESC LIMIT 7",
                new String[]{symbol,interval});
        double[] close=new double[7],high=new double[7],low=new double[7],volume=new double[7];
        int n=0;
        try { while(c.moveToNext()&&n<7) {
            close[n]=c.getDouble(0);high[n]=c.getDouble(1);
            low[n]=c.getDouble(2);volume[n]=c.getDouble(3);n++;
        }} finally { c.close(); }
        if(n<7||m.samples<20)
            return new Assessment(symbol,"LEARNING",0,m.samples,
                    m.samples==0?0:100.0*m.correct/m.samples,0.5);
        reverse(close);reverse(high);reverse(low);reverse(volume);
        double p=probability(m,features(close,high,low,volume,6));
        String signal=p>0.58?"UPWARD BIAS":(p<0.42?"DOWNWARD BIAS":"NEUTRAL");
        return new Assessment(symbol,signal,Math.abs(p-0.5)*200,m.samples,
                100.0*m.correct/m.samples,p);
    }

    /** Updates the model with the outcome of an AI-tagged simulated trade. */
    public boolean learnFromPaperOutcome(String symbol,String interval,
                                         double predictedProbability,double realizedPnl) {
        if(empty(symbol)||empty(interval)||!finite(predictedProbability)||
                predictedProbability<0||predictedProbability>1||!finite(realizedPnl)) return false;
        SQLiteDatabase db=helper.getWritableDatabase();
        String modelKey=key(symbol,interval);
        Model m=load(db,modelKey);
        int actual=realizedPnl>0?1:0;
        int predicted=predictedProbability>=0.5?1:0;
        if(actual==predicted)m.correct++;
        m.bias=clamp(m.bias+TRADE_LEARNING_RATE*(actual-predictedProbability),-3,3);
        m.samples++;
        save(db,modelKey,m);
        return true;
    }

    private double[] features(double[] c,double[] h,double[] l,double[] v,int i) {
        if(c[i-3]==0||c[i-6]==0||c[i]==0)return new double[]{0,0,0};
        double momentum=(c[i]-c[i-3])/c[i-3];
        double trend=(c[i]-c[i-6])/c[i-6];
        double range=(h[i]-l[i])/c[i];
        return new double[]{clamp(momentum*8,-2,2),clamp(trend*4,-2,2),clamp(range*8,0,2)};
    }
    private double probability(Model m,double[] x) {
        double z=clamp(m.bias+m.momentum*x[0]+m.trend*x[1]+m.range*x[2],-12,12);
        return 1.0/(1.0+Math.exp(-z));
    }
    private Model load(SQLiteDatabase db,String k) {
        Model m=new Model();
        Cursor c=db.rawQuery("SELECT w_momentum,w_trend,w_range,bias,samples,correct,"+
                "candle_samples,last_candle_time FROM ai_models WHERE model_key=?",new String[]{k});
        try { if(c.moveToFirst()) {
            m.momentum=c.getDouble(0);m.trend=c.getDouble(1);m.range=c.getDouble(2);
            m.bias=c.getDouble(3);m.samples=c.getInt(4);m.correct=c.getInt(5);
            m.candleSamples=c.getInt(6);m.lastCandleTime=c.getLong(7);
        }} finally { c.close(); }
        return m;
    }
    private void save(SQLiteDatabase db,String k,Model m) {
        ContentValues v=new ContentValues();v.put("model_key",k);
        v.put("w_momentum",m.momentum);v.put("w_trend",m.trend);v.put("w_range",m.range);
        v.put("bias",m.bias);v.put("samples",m.samples);v.put("correct",m.correct);
        v.put("candle_samples",m.candleSamples);v.put("last_candle_time",m.lastCandleTime);
        v.put("updated_at",System.currentTimeMillis());
        db.insertWithOnConflict("ai_models",null,v,SQLiteDatabase.CONFLICT_REPLACE);
    }
    private String key(String s,String i){return s.trim().toUpperCase(Locale.US)+"|"+i.trim();}
    private void reverse(double[] a){for(int i=0,j=a.length-1;i<j;i++,j--){double t=a[i];a[i]=a[j];a[j]=t;}}
    private void reverse(long[] a){for(int i=0,j=a.length-1;i<j;i++,j--){long t=a[i];a[i]=a[j];a[j]=t;}}
    private double clamp(double v,double lo,double hi){return Math.max(lo,Math.min(hi,v));}
    private boolean finite(double v){return !Double.isNaN(v)&&!Double.isInfinite(v);}
    private boolean empty(String s){return s==null||s.trim().length()==0;}
}
