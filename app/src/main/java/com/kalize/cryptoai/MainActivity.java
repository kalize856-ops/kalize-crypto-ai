package com.kalize.cryptoai;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.graphics.Color;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Lightweight dashboard; platform-specific window APIs are guarded for API 19+. */
public class MainActivity extends Activity {
    private LinearLayout content;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);

        // These window-color methods were introduced in API 21.
        // Guard them so the same APK starts safely on Android 4.4.4 (API 19).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(Color.rgb(16, 24, 32));
            getWindow().setNavigationBarColor(Color.rgb(16, 24, 32));
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(22), dp(18), dp(24));
        content.setBackgroundColor(Color.rgb(13, 19, 29));
        scroll.addView(content);

        addText("KALIZE CRYPTO AI", 22, Color.WHITE, true);
        addText("LITE  •  PAPER TRADING", 12, Color.rgb(22, 192, 140), true);
        addSpace(22);
        addText("VIRTUAL PORTFOLIO", 12, Color.LTGRAY, true);
        addText("$100.00", 34, Color.WHITE, true);
        addText("Starting balance · simulated funds only", 12, Color.LTGRAY, false);
        addSpace(20);
        addPanel("MARKET WATCH", "Live market connection: not configured yet\nBTC / USD    —\nETH / USD    —\nSOL / USD    —");
        addPanel("PAPER TRADING", "Open positions    0\nClosed trades      0\nNet profit/loss    $0.00");
        addPanel("HISTORICAL DATA", "Learning window: 30 days\nCandlestick storage: local database\nAutomatic retention cleanup: planned");
        addPanel("RISK CONTROLS", "Paper mode only\nFee and slippage simulation: planned\nEmergency pause: planned");
        addText("Build 0.1.1 · API 19+ compatibility", 11, Color.GRAY, false);
        setContentView(scroll);
    }

    private void addPanel(String title, String body) {
        TextView heading = new TextView(this);
        heading.setText(title);
        heading.setTextColor(Color.rgb(22, 192, 140));
        heading.setTextSize(12);
        heading.setPadding(dp(14), dp(14), dp(14), dp(5));
        heading.setBackgroundColor(Color.rgb(27, 39, 54));
        content.addView(heading);

        TextView details = new TextView(this);
        details.setText(body);
        details.setTextColor(Color.rgb(225, 231, 239));
        details.setTextSize(14);
        details.setLineSpacing(dp(5), 1.0f);
        details.setPadding(dp(14), dp(8), dp(14), dp(16));
        details.setBackgroundColor(Color.rgb(27, 39, 54));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(14);
        content.addView(details, params);
    }

    private void addText(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(null, android.graphics.Typeface.BOLD);
        content.addView(view);
    }

    private void addSpace(int height) {
        View space = new View(this);
        content.addView(space, new LinearLayout.LayoutParams(1, dp(height)));
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
