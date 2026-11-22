package com.kalize.cryptoai;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;
import java.util.Locale;

/** Interactive OHLC chart with touch inspection, horizontal pan and pinch zoom; API 19+. */
public final class CandlestickChartView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private double[] open = new double[0], high = new double[0], low = new double[0], close = new double[0];
    private String caption = "Waiting for candles";
    private int selected = -1;
    private int visibleCount = 80;
    private int endOffset = 0;
    private float downX, lastX, downY;
    private float pinchStartDistance;
    private int pinchStartCount;
    private boolean dragging;
    private boolean pinching;
    private static final int MIN_VISIBLE = 10;
    private static final int MAX_VISIBLE = 300;

    public CandlestickChartView(Context context) { super(context); }

    public void setCandles(double[] o, double[] h, double[] l, double[] c, String label) {
        open = o == null ? new double[0] : o.clone();
        high = h == null ? new double[0] : h.clone();
        low = l == null ? new double[0] : l.clone();
        close = c == null ? new double[0] : c.clone();
        caption = label == null ? "" : label;
        endOffset = Math.min(endOffset, Math.max(0, open.length - 1));
        selected = -1;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float left=dp(12), right=getWidth()-dp(12), top=dp(29), bottom=getHeight()-dp(38);
        paint.setStyle(Paint.Style.FILL);
        paint.setTextSize(dp(11));
        paint.setColor(Color.rgb(175,187,202));
        canvas.drawText(caption,left,dp(16),paint);
        int n=Math.min(Math.min(open.length,high.length),Math.min(low.length,close.length));
        if(n<2 || right<=left || bottom<=top){
            canvas.drawText("Waiting for completed 1-minute candles…",left,top+dp(22),paint);
            return;
        }

        int count=Math.min(n,Math.max(MIN_VISIBLE,visibleCount));
        int end=Math.max(0,n-endOffset);
        int start=Math.max(0,end-count);
        int plotted=end-start;
        if(plotted<1)return;

        double min=low[start], max=high[start];
        for(int i=start+1;i<end;i++){ min=Math.min(min,low[i]); max=Math.max(max,high[i]); }
        double span=Math.max(max-min,Math.max(Math.abs(max)*0.0001,0.0000001));
        min-=span*0.08; max+=span*0.08; span=max-min;

        paint.setColor(Color.rgb(52,66,83)); paint.setStrokeWidth(dp(1));
        for(int i=0;i<4;i++){
            float y=top+(bottom-top)*i/3f;
            canvas.drawLine(left,y,right,y,paint);
            double value=max-(span*i/3.0);
            paint.setTextSize(dp(9));
            canvas.drawText(String.format(Locale.US,"%.6g",value),left,y-dp(3),paint);
        }

        float slot=(right-left)/plotted;
        float body=Math.max(dp(2),Math.min(dp(10),slot*0.64f));
        for(int p=0;p<plotted;p++){
            int i=start+p;
            float x=left+slot*(p+0.5f);
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

        paint.setColor(Color.rgb(175,187,202)); paint.setTextSize(dp(9));
        canvas.drawText("Older  ←  Drag to scroll  ·  Pinch to zoom  →  Newer",
                left,getHeight()-dp(21),paint);
        if(selected>=start && selected<end){
            String detail=String.format(Locale.US,"O %.7g  H %.7g  L %.7g  C %.7g",
                    open[selected],high[selected],low[selected],close[selected]);
            paint.setTextSize(dp(10));
            canvas.drawText(detail,left,getHeight()-dp(4),paint);
        } else {
            paint.setTextSize(dp(9));
            canvas.drawText(endOffset==0?"Latest candles":"Historical view",
                    right-dp(92),getHeight()-dp(4),paint);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        int n=Math.min(Math.min(open.length,high.length),Math.min(low.length,close.length));
        if(n<2)return true;

        int action=event.getActionMasked();
        if(action==MotionEvent.ACTION_POINTER_DOWN && event.getPointerCount()>=2){
            pinching=true;
            dragging=false;
            pinchStartDistance=distance(event);
            pinchStartCount=visibleCount;
            return true;
        }
        if(action==MotionEvent.ACTION_MOVE && pinching && event.getPointerCount()>=2){
            float current=distance(event);
            if(pinchStartDistance>0f){
                float factor=pinchStartDistance/Math.max(1f,current);
                visibleCount=Math.max(MIN_VISIBLE,Math.min(MAX_VISIBLE,(int)(pinchStartCount*factor)));
                endOffset=Math.min(endOffset,Math.max(0,n-visibleCount));
                invalidate();
            }
            return true;
        }
        if(action==MotionEvent.ACTION_POINTER_UP){
            pinching=false;
            if(event.getPointerCount()>1) lastX=event.getX(0);
            return true;
        }
        if(action==MotionEvent.ACTION_DOWN){
            downX=lastX=event.getX();
            downY=event.getY();
            dragging=false;
            selectAt(downX,n);
            return true;
        }
        if(action==MotionEvent.ACTION_MOVE && !pinching){
            float x=event.getX();
            float delta=x-lastX;
            if(Math.abs(x-downX)>dp(7)) dragging=true;
            if(dragging){
                float plotWidth=Math.max(1f,getWidth()-dp(24));
                int visible=Math.min(n,Math.max(MIN_VISIBLE,visibleCount));
                int shift=Math.round((-delta/plotWidth)*visible);
                if(shift!=0){
                    endOffset=Math.max(0,Math.min(n-visibleCount,endOffset+shift));
                    lastX=x;
                    selected=-1;
                    invalidate();
                }
            } else {
                selectAt(x,n);
            }
            return true;
        }
        if(action==MotionEvent.ACTION_UP){
            if(!dragging && !pinching) selectAt(event.getX(),n);
            pinching=false;
            return true;
        }
        if(action==MotionEvent.ACTION_CANCEL){
            dragging=false;
            pinching=false;
            return true;
        }
        return true;
    }

    private void selectAt(float x,int n){
        float left=dp(12),right=getWidth()-dp(12);
        float ratio=Math.max(0f,Math.min(0.9999f,(x-left)/Math.max(1f,right-left)));
        int count=Math.min(n,Math.max(MIN_VISIBLE,visibleCount));
        int end=Math.max(0,n-endOffset);
        int start=Math.max(0,end-count);
        selected=start+(int)(ratio*Math.max(1,end-start));
        selected=Math.min(end-1,selected);
        invalidate();
    }

    private float distance(MotionEvent event){
        float dx=event.getX(0)-event.getX(1);
        float dy=event.getY(0)-event.getY(1);
        return (float)Math.sqrt(dx*dx+dy*dy);
    }

    private float dp(float value){return value*getResources().getDisplayMetrics().density;}
}
