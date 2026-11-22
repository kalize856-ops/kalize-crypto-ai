package com.kalize.cryptoai;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;
import java.util.Locale;

/** Compact OHLC candlestick chart, compatible with Android API 19+. */
public final class CandlestickChartView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private double[] open = new double[0], high = new double[0], low = new double[0], close = new double[0];
    private String caption = "Waiting for candles";
    private int selected = -1;

    public CandlestickChartView(Context context) { super(context); }

    public void setCandles(double[] o, double[] h, double[] l, double[] c, String label) {
        open = o == null ? new double[0] : o.clone();
        high = h == null ? new double[0] : h.clone();
        low = l == null ? new double[0] : l.clone();
        close = c == null ? new double[0] : c.clone();
        caption = label == null ? "" : label;
        selected = -1;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float left=dp(12), right=getWidth()-dp(12), top=dp(26), bottom=getHeight()-dp(30);
        paint.setStyle(Paint.Style.FILL);
        paint.setTextSize(dp(11));
        paint.setColor(Color.rgb(175,187,202));
        canvas.drawText(caption,left,dp(16),paint);
        int n=Math.min(Math.min(open.length,high.length),Math.min(low.length,close.length));
        if(n<2 || right<=left || bottom<=top){
            canvas.drawText("Waiting for completed 1-minute candles…",left,top+dp(22),paint);
            return;
        }
        double min=low[0], max=high[0];
        for(int i=1;i<n;i++){ min=Math.min(min,low[i]); max=Math.max(max,high[i]); }
        double span=Math.max(max-min,Math.max(Math.abs(max)*0.0001,0.0000001));
        min-=span*0.08; max+=span*0.08; span=max-min;
        paint.setColor(Color.rgb(52,66,83)); paint.setStrokeWidth(dp(1));
        for(int i=0;i<4;i++){
            float y=top+(bottom-top)*i/3f;
            canvas.drawLine(left,y,right,y,paint);
        }
        float slot=(right-left)/n;
        float body=Math.max(dp(2),Math.min(dp(9),slot*0.62f));
        for(int i=0;i<n;i++){
            float x=left+slot*(i+0.5f);
            float yh=bottom-(float)((high[i]-min)/span)*(bottom-top);
            float yl=bottom-(float)((low[i]-min)/span)*(bottom-top);
            float yo=bottom-(float)((open[i]-min)/span)*(bottom-top);
            float yc=bottom-(float)((close[i]-min)/span)*(bottom-top);
            int color=close[i]>=open[i]?Color.rgb(22,192,140):Color.rgb(238,91,104);
            paint.setColor(color); paint.setStrokeWidth(dp(1.2f));
            canvas.drawLine(x,yh,x,yl,paint);
            float y1=Math.min(yo,yc), y2=Math.max(yo,yc);
            if(y2-y1<dp(1.5f)) y2=y1+dp(1.5f);
            canvas.drawRect(x-body/2,y1,x+body/2,y2,paint);
            if(i==selected){
                paint.setColor(Color.rgb(255,190,80)); paint.setStrokeWidth(dp(1));
                canvas.drawLine(x,top,x,bottom,paint);
                canvas.drawCircle(x,(yh+yl)/2,dp(2.5f),paint);
            }
        }
        paint.setColor(Color.rgb(175,187,202)); paint.setTextSize(dp(10));
        canvas.drawText(String.format(Locale.US,"High %.7g",max),left,bottom+dp(14),paint);
        if(selected>=0 && selected<n){
            String detail=String.format(Locale.US,"O %.7g  H %.7g  L %.7g  C %.7g",
                    open[selected],high[selected],low[selected],close[selected]);
            canvas.drawText(detail,left,getHeight()-dp(3),paint);
        } else {
            canvas.drawText(String.format(Locale.US,"Low %.7g",min),right-dp(90),bottom+dp(14),paint);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        int n=Math.min(Math.min(open.length,high.length),Math.min(low.length,close.length));
        if(n<2)return true;
        if(event.getAction()==MotionEvent.ACTION_DOWN || event.getAction()==MotionEvent.ACTION_MOVE){
            float left=dp(12),right=getWidth()-dp(12);
            float ratio=Math.max(0f,Math.min(0.9999f,(event.getX()-left)/Math.max(1f,right-left)));
            selected=(int)(ratio*n); invalidate(); return true;
        }
        return event.getAction()==MotionEvent.ACTION_UP || super.onTouchEvent(event);
    }
    private float dp(float value){return value*getResources().getDisplayMetrics().density;}
}
