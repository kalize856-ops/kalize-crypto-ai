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
        public final int samples, candleSamples, tradeSamples;
        public final double tradeAccuracy;
        Assessment(String s,String g,double c,int n,double a,double p,int cs,int ts,double ta) {
            symbol=s;signal=g;confidence=c;samples=n;accuracy=a;probability=p;
            candleSamples=cs;tradeSamples=ts;tradeAccuracy=ta;
        }
    }
    public static final class Validation {
        public final int samples, correct, baselineCorrect, truePositive, trueNegative, falsePositive, falseNegative;
        public final int[] confidenceBucketCounts;
        public final double[] confidenceBucketMeanConfidence, confidenceBucketAccuracy;
        public final double accuracy, baselineAccuracy, brierScore, precision, recall, specificity, balancedAccuracy;
        public final double calibrationError;
        Validation(int n,int c,int b,double a,double ba,double bs,int tp,int tn,int fp,int fn) {
            this(n,c,b,a,ba,bs,tp,tn,fp,fn,new int[5],new double[5],new double[5]);
        }
        Validation(int n,int c,int b,double a,double ba,double bs,int tp,int tn,int fp,int fn,
                   int[] counts,double[] meanConfidence,double[] bucketAccuracy) {
            samples=n;correct=c;baselineCorrect=b;accuracy=a;
            baselineAccuracy=ba;brierScore=bs;truePositive=tp;trueNegative=tn;
            falsePositive=fp;falseNegative=fn;
            confidenceBucketCounts=counts;
            confidenceBucketMeanConfidence=meanConfidence;
            confidenceBucketAccuracy=bucketAccuracy;
            precision=(tp+fp)==0?0:100.0*tp/(tp+fp);
            recall=(tp+fn)==0?0:100.0*tp/(tp+fn);
            specificity=(tn+fp)==0?0:100.0*tn/(tn+fp);
            balancedAccuracy=(recall+specificity)/2.0;
            double ece=0.0;
            for(int i=0;i<counts.length;i++)
                if(n>0) ece+=(counts[i]/(double)n)*Math.abs(meanConfidence[i]-bucketAccuracy[i]);
            calibrationError=100.0*ece;
        }
    }

    /**
     * Prequential walk-forward test: predict the next candle first, score it,
     * then update a temporary model using that completed outcome. No future
     * candle is used to make an earlier prediction. The live model is untouched.
     */
    public synchronized Validation validateWalkForward(String symbol,String interval) {
        if(empty(symbol)||empty(interval)) return new Validation(0,0,0,0,0,0,0,0,0,0);
        SQLiteDatabase db=helper.getReadableDatabase();
        Cursor c=db.rawQuery("SELECT open_time,close,high,low,volume FROM candles " +
                "WHERE symbol=? AND interval=? ORDER BY open_time DESC LIMIT 10080",
                new String[]{symbol,interval});
        int count=c.getCount();
        long[] times=new long[count];
        double[] close=new double[count],high=new double[count],low=new double[count],volume=new double[count];
        int n=0;
        try {
            while(c.moveToNext()) {
                int j=count-1-n;
                times[j]=c.getLong(0);close[j]=c.getDouble(1);
                high[j]=c.getDouble(2);low[j]=c.getDouble(3);volume[j]=c.getDouble(4);
                n++;
            }
        } finally { c.close(); }
        Model test=new Model();
        int scored=0,correct=0,baselineCorrect=0;
        int tp=0,tn=0,fp=0,fn=0;
        int[] bucketCounts=new int[5];
        double[] bucketConfidenceTotals=new double[5],bucketCorrectTotals=new double[5];
        double brier=0.0;
        for(int i=6;i<n-1;i++) {
            if(times[i+1]<=times[i]) continue;
            double[] x=features(close,high,low,volume,i);
            double p=probability(test,x);
            int label=close[i+1]>close[i]?1:0;
            int predicted=p>=0.5?1:0;
            if(predicted==label) correct++;
            double confidence=Math.max(p,1.0-p);
            int bucket=Math.min(4,Math.max(0,(int)Math.floor((confidence-0.5)*10.0+1.0e-9)));
            bucketCounts[bucket]++;
            bucketConfidenceTotals[bucket]+=confidence;
            if(predicted==label) bucketCorrectTotals[bucket]++;
            if(predicted==1&&label==1) tp++;
            else if(predicted==0&&label==0) tn++;
            else if(predicted==1) fp++;
            else fn++;
            if((close[i]>close[i-1]?1:0)==label) baselineCorrect++;
            double error=label-p;
            brier+=error*error;
            double learningError=label-p;
            test.momentum=clamp(test.momentum+LEARNING_RATE*learningError*x[0],-4,4);
            test.trend=clamp(test.trend+LEARNING_RATE*learningError*x[1],-4,4);
            test.range=clamp(test.range+LEARNING_RATE*learningError*x[2],-4,4);
            test.bias=clamp(test.bias+LEARNING_RATE*learningError,-3,3);
            scored++;
        }
        if(scored==0) return new Validation(0,0,0,0,0,0,0,0,0,0);
        double[] bucketMeanConfidence=new double[5],bucketAccuracy=new double[5];
        for(int i=0;i<5;i++) {
            if(bucketCounts[i]>0) {
                bucketMeanConfidence[i]=bucketConfidenceTotals[i]/bucketCounts[i];
                bucketAccuracy[i]=bucketCorrectTotals[i]/bucketCounts[i];
            }
        }
        return new Validation(scored,correct,baselineCorrect,
                100.0*correct/scored,100.0*baselineCorrect/scored,brier/scored,tp,tn,fp,fn,
                bucketCounts,bucketMeanConfidence,bucketAccuracy);
    }

    private static final class Model {
        double momentum=0.10,trend=0.10,range=0,bias=0;
        int samples=0,correct=0,candleSamples=0,tradeSamples=0,tradeCorrect=0;
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
    public synchronized int learn(String symbol,String interval) {
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
    public synchronized Assessment assess(String symbol,String interval) {
        if(empty(symbol)||empty(interval))
            return new Assessment("","INSUFFICIENT DATA",0,0,0,0.5,0,0,0);
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
        int candleCorrect=Math.max(0,m.correct-m.tradeCorrect);
        double candleAccuracy=m.candleSamples==0?0:100.0*candleCorrect/m.candleSamples;
        double tradeAccuracy=m.tradeSamples==0?0:100.0*m.tradeCorrect/m.tradeSamples;
        if(n<7)
            return new Assessment(symbol,"LEARNING",0,m.samples,candleAccuracy,0.5,
                    m.candleSamples,m.tradeSamples,tradeAccuracy);
        // Provisional estimates can be used once the seven-candle feature window exists.
        // Incremental training continues independently as each new completed candle arrives.
        reverse(close);reverse(high);reverse(low);reverse(volume);
        double p=probability(m,features(close,high,low,volume,6));
        String signal=p>0.58?"UPWARD BIAS":(p<0.42?"DOWNWARD BIAS":"NEUTRAL");
        return new Assessment(symbol,signal,Math.abs(p-0.5)*200,m.samples,
                candleAccuracy,p,m.candleSamples,m.tradeSamples,tradeAccuracy);
    }

    /** Updates the model with the outcome of an AI-tagged simulated trade. */
    public synchronized boolean learnFromPaperOutcome(String symbol,String interval,
                                         double predictedProbability,double realizedPnl) {
        if(empty(symbol)||empty(interval)||!finite(predictedProbability)||
                predictedProbability<0||predictedProbability>1||!finite(realizedPnl)) return false;
        SQLiteDatabase db=helper.getWritableDatabase();
        String modelKey=key(symbol,interval);
        Model m=load(db,modelKey);
        int actual=realizedPnl>0?1:0;
        int predicted=predictedProbability>=0.5?1:0;
        if(actual==predicted){m.correct++;m.tradeCorrect++;}
        m.tradeSamples++;
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
                "candle_samples,last_candle_time,trade_samples,trade_correct FROM ai_models WHERE model_key=?",new String[]{k});
        try { if(c.moveToFirst()) {
            m.momentum=c.getDouble(0);m.trend=c.getDouble(1);m.range=c.getDouble(2);
            m.bias=c.getDouble(3);m.samples=c.getInt(4);m.correct=c.getInt(5);
            m.candleSamples=c.getInt(6);m.lastCandleTime=c.getLong(7);
            m.tradeSamples=c.getInt(8);m.tradeCorrect=c.getInt(9);
        }} finally { c.close(); }
        return m;
    }
    private void save(SQLiteDatabase db,String k,Model m) {
        ContentValues v=new ContentValues();v.put("model_key",k);
        v.put("w_momentum",m.momentum);v.put("w_trend",m.trend);v.put("w_range",m.range);
        v.put("bias",m.bias);v.put("samples",m.samples);v.put("correct",m.correct);
        v.put("candle_samples",m.candleSamples);v.put("last_candle_time",m.lastCandleTime);
        v.put("trade_samples",m.tradeSamples);v.put("trade_correct",m.tradeCorrect);
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
