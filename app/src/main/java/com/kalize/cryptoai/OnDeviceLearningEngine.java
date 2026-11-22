package com.kalize.cryptoai;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.util.Locale;

/** Lightweight online learner designed for Android 4.4+; local simulation only. */
public final class OnDeviceLearningEngine {
    private static final int MAX_CANDLES = 1200;
    private static final double LEARNING_RATE = 0.04;
    private final PaperDatabaseHelper helper;

    public static final class Assessment {
        public final String symbol, signal;
        public final double confidence, accuracy;
        public final int samples;
        Assessment(String s, String g, double c, int n, double a) {
            symbol=s; signal=g; confidence=c; samples=n; accuracy=a;
        }
    }
    private static final class Model {
        double momentum=0.10, trend=0.10, range=0, bias=0;
        int samples=0, correct=0;
    }
    public OnDeviceLearningEngine(PaperDatabaseHelper h) {
        if (h==null) throw new IllegalArgumentException("Database required");
        helper=h;
    }

    /** Learns sequentially from stored OHLCV candles, without future-data leakage. */
    public int learn(String symbol, String interval) {
        if (empty(symbol)||empty(interval)) return 0;
        SQLiteDatabase db=helper.getWritableDatabase();
        String modelKey=key(symbol,interval);
        Model m=load(db,modelKey);
        Cursor c=db.rawQuery("SELECT close,high,low,volume FROM candles WHERE symbol=? AND interval=? ORDER BY open_time ASC LIMIT "+MAX_CANDLES,
                new String[]{symbol,interval});
        double[] close=new double[MAX_CANDLES], high=new double[MAX_CANDLES],
                low=new double[MAX_CANDLES], volume=new double[MAX_CANDLES];
        int n=0;
        try {
            while(c.moveToNext() && n<MAX_CANDLES) {
                close[n]=c.getDouble(0); high[n]=c.getDouble(1);
                low[n]=c.getDouble(2); volume[n]=c.getDouble(3); n++;
            }
        } finally { c.close(); }
        int start=Math.max(6,Math.min(n-1,m.samples+6));
        int added=0;
        for(int i=start;i<n-1;i++) {
            double[] x=features(close,high,low,volume,i);
            int label=close[i+1]>close[i]?1:0;
            double p=probability(m,x);
            if((p>=0.5?1:0)==label) m.correct++;
            double error=label-p;
            m.momentum=clamp(m.momentum+LEARNING_RATE*error*x[0],-4,4);
            m.trend=clamp(m.trend+LEARNING_RATE*error*x[1],-4,4);
            m.range=clamp(m.range+LEARNING_RATE*error*x[2],-4,4);
            m.bias=clamp(m.bias+LEARNING_RATE*error,-3,3);
            m.samples++; added++;
        }
        save(db,modelKey,m);
        return added;
    }

    public Assessment assess(String symbol,String interval) {
        if(empty(symbol)||empty(interval)) return new Assessment("","INSUFFICIENT DATA",0,0,0);
        SQLiteDatabase db=helper.getReadableDatabase();
        Model m=load(db,key(symbol,interval));
        Cursor c=db.rawQuery("SELECT close,high,low,volume FROM candles WHERE symbol=? AND interval=? ORDER BY open_time DESC LIMIT 7",
                new String[]{symbol,interval});
        double[] close=new double[7],high=new double[7],low=new double[7],volume=new double[7];
        int n=0;
        try { while(c.moveToNext()&&n<7) {
            close[n]=c.getDouble(0);high[n]=c.getDouble(1);low[n]=c.getDouble(2);volume[n]=c.getDouble(3);n++;
        }} finally {c.close();}
        if(n<7||m.samples<20) return new Assessment(symbol,"LEARNING",0,m.samples,
                m.samples==0?0:100.0*m.correct/m.samples);
        double[] x=features(reverse(close),reverse(high),reverse(low),reverse(volume),6);
        double p=probability(m,x);
        String signal=p>0.58?"UPWARD BIAS":(p<0.42?"DOWNWARD BIAS":"NEUTRAL");
        return new Assessment(symbol,signal,Math.abs(p-0.5)*200,m.samples,
                m.samples==0?0:100.0*m.correct/m.samples);
    }
    private double[] features(double[] c,double[] h,double[] l,double[] v,int i) {
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
        Cursor c=db.rawQuery("SELECT w_momentum,w_trend,w_range,bias,samples,correct FROM ai_models WHERE model_key=?",new String[]{k});
        try {if(c.moveToFirst()){m.momentum=c.getDouble(0);m.trend=c.getDouble(1);m.range=c.getDouble(2);m.bias=c.getDouble(3);m.samples=c.getInt(4);m.correct=c.getInt(5);}}
        finally {c.close();}
        return m;
    }
    private void save(SQLiteDatabase db,String k,Model m) {
        ContentValues v=new ContentValues();v.put("model_key",k);v.put("w_momentum",m.momentum);
        v.put("w_trend",m.trend);v.put("w_range",m.range);v.put("bias",m.bias);
        v.put("samples",m.samples);v.put("correct",m.correct);v.put("updated_at",System.currentTimeMillis());
        db.insertWithOnConflict("ai_models",null,v,SQLiteDatabase.CONFLICT_REPLACE);
    }
    private String key(String s,String i){return s.trim().toUpperCase(Locale.US)+"|"+i.trim();}
    private double[] reverse(double[] a){double[] b=new double[a.length];for(int i=0;i<a.length;i++)b[i]=a[a.length-1-i];return b;}
    private double clamp(double v,double lo,double hi){return Math.max(lo,Math.min(hi,v));}
    private boolean empty(String s){return s==null||s.trim().length()==0;}
}