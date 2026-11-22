package com.kalize.cryptoai;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.View;
import java.util.Locale;

/** Lightweight touch-inspectable line chart; no external chart library required. */
public final class SimpleLineChartView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private double[] values = new double[0];
    private String caption = "Waiting for candles";
    private int lineColor = Color.rgb(22, 192, 140);
    private int selectedIndex = -1;

    public SimpleLineChartView(Context context) {
        super(context);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    public void setSeries(double[] points, String label, int color) {
        values = points == null ? new double[0] : points.clone();
        caption = label == null ? "" : label;
        lineColor = color;
        selectedIndex = -1;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float left = dp(12), right = getWidth() - dp(12);
        float top = dp(22), bottom = getHeight() - dp(28);
        paint.setColor(Color.rgb(160, 173, 190));
        paint.setTextSize(dp(11));
        paint.setStyle(Paint.Style.FILL);
        canvas.drawText(caption, left, dp(14), paint);
        if (values.length < 2 || right <= left || bottom <= top) {
            paint.setColor(Color.LTGRAY);
            canvas.drawText("Waiting for enough completed 1-minute candles…", left, top + dp(22), paint);
            return;
        }
        double min = values[0], max = values[0];
        for (double value : values) {
            if (value < min) min = value;
            if (value > max) max = value;
        }
        double range = Math.max(max - min, Math.max(Math.abs(max) * 0.0001, 0.0000001));
        min -= range * 0.12;
        max += range * 0.12;
        range = max - min;
        paint.setColor(Color.rgb(55, 68, 84));
        paint.setStrokeWidth(dp(1));
        for (int i = 0; i < 4; i++) {
            float y = top + (bottom - top) * i / 3f;
            canvas.drawLine(left, y, right, y, paint);
        }
        Path path = new Path();
        float step = (right - left) / (values.length - 1);
        for (int i = 0; i < values.length; i++) {
            float x = left + step * i;
            float y = bottom - (float)((values[i] - min) / range) * (bottom - top);
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        paint.setColor(lineColor);
        paint.setStrokeWidth(dp(2));
        paint.setStyle(Paint.Style.STROKE);
        canvas.drawPath(path, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.rgb(180, 192, 207));
        paint.setTextSize(dp(10));
        canvas.drawText(String.format(Locale.US, "%.6g", max), left, top - dp(4), paint);
        canvas.drawText(String.format(Locale.US, "%.6g", min), left, bottom + dp(16), paint);
        if (selectedIndex >= 0 && selectedIndex < values.length) {
            float x = left + step * selectedIndex;
            float y = bottom - (float)((values[selectedIndex] - min) / range) * (bottom - top);
            paint.setColor(Color.rgb(255, 190, 80));
            paint.setStrokeWidth(dp(1));
            canvas.drawLine(x, top, x, bottom, paint);
            canvas.drawCircle(x, y, dp(4), paint);
            canvas.drawText(String.format(Locale.US, "Selected close: %.8g", values[selectedIndex]),
                    left, getHeight() - dp(4), paint);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (values.length < 2) return true;
        if (event.getAction() == MotionEvent.ACTION_DOWN ||
                event.getAction() == MotionEvent.ACTION_MOVE) {
            float left = dp(12), right = getWidth() - dp(12);
            float ratio = Math.max(0f, Math.min(1f,
                    (event.getX() - left) / Math.max(1f, right - left)));
            selectedIndex = Math.round(ratio * (values.length - 1));
            invalidate();
            return true;
        }
        return event.getAction() == MotionEvent.ACTION_UP || super.onTouchEvent(event);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
