package com.kalize.cryptoai;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Color;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.Locale;

/** Responsive-by-width paper dashboard; API 19+ and simulation only. */
public class MainActivity extends Activity {
    private LinearLayout content;
    private PaperDatabaseHelper database;
    private PaperTradingEngine tradingEngine;
    private OnDeviceLearningEngine learningEngine;
    private AutoPaperTradingController autoTrader;
    private TextView marketStatus, portfolioPanel, modelPanel, openTradesPanel, historyPanel;
    private Button autoButton;
    private boolean receiverRegistered = false;

    private final BroadcastReceiver marketReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (marketStatus != null && intent != null) {
                String status = intent.getStringExtra(MarketDataService.EXTRA_STATUS);
                marketStatus.setText((status == null ? "Waiting for market feed" : status) +
                        "\nCompleted 1m candles stored: " + database.getStoredCandleCount());
                refreshDashboard();
            }
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        database = new PaperDatabaseHelper(this);
        tradingEngine = new PaperTradingEngine(database);
        learningEngine = new OnDeviceLearningEngine(database);
        autoTrader = new AutoPaperTradingController(database);
        database.cleanExpiredCandles();
        MarketStreamStarter.start(this);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(Color.rgb(16, 24, 32));
            getWindow().setNavigationBarColor(Color.rgb(16, 24, 32));
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int widthDp = (int)(getResources().getDisplayMetrics().widthPixels /
                getResources().getDisplayMetrics().density);
        int side = widthDp >= 600 ? 32 : 18;
        content.setPadding(dp(side), dp(22), dp(side), dp(28));
        content.setBackgroundColor(Color.rgb(13, 19, 29));
        scroll.addView(content);

        addText("KALIZE CRYPTO AI", 24, Color.WHITE, true);
        addText("LITE  •  PAPER TRADING DASHBOARD", 12, Color.rgb(22, 192, 140), true);
        addSpace(18);

        portfolioPanel = addPanel("VIRTUAL PORTFOLIO", "Loading portfolio…");
        marketStatus = addPanel("LIVE MARKET WATCH",
                "Starting persistent WebSocket connection…\nBTC / USDT    waiting\n" +
                "ETH / USDT    waiting\nSOL / USDT    waiting\nCandle interval: 1 minute");
        modelPanel = addPanel("AI LEARNING & DECISION EXPLANATIONS", "Loading local model…");
        openTradesPanel = addPanel("OPEN SIMULATED POSITIONS", "No open positions.");
        historyPanel = addPanel("RECENT PAPER-TRADE HISTORY", "No closed trades yet.");

        addPanel("HISTORICAL DATA",
                "Learning window: 30 days\nStored candles: " + database.getStoredCandleCount() +
                "\nExpired candles: automatically cleaned");
        addPanel("RISK CONTROLS",
                "Simulation only\nRisk per trade: 1% maximum\nDaily loss pause: 2%\n" +
                "Maximum open positions: 3\nMaximum position size: 25% of virtual equity\n" +
                "Fees and slippage are simulated\nReal-money orders: disabled");

        addText("AUTOMATIC AI PAPER TRADING", 12, Color.rgb(22, 192, 140), true);
        addText("Optional and OFF by default. The model can open or close virtual positions using completed 1-minute candles. No real funds or exchange orders are involved.",
                13, Color.LTGRAY, false);
        autoButton = new Button(this);
        updateAutoButton();
        autoButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                autoTrader.setEnabled(!autoTrader.isEnabled());
                updateAutoButton();
            }
        });
        LinearLayout.LayoutParams autoParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        autoParams.bottomMargin = dp(12);
        content.addView(autoButton, autoParams);

        Button refresh = new Button(this);
        refresh.setText("Refresh dashboard");
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { refreshDashboard(); }
        });
        content.addView(refresh, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        addSpace(12);
        addText("Build 0.1.6 · Android 4.4.4+ · Binance public data · Local SQLite",
                11, Color.GRAY, false);
        setContentView(scroll);
        refreshDashboard();
    }

    private void refreshDashboard() {
        if (database == null || portfolioPanel == null) return;
        SQLiteDatabase db = database.getReadableDatabase();
        double cash = database.getCashBalance();
        double marketValue = 0.0;
        double unrealized = 0.0;
        Cursor positions = db.rawQuery(
                "SELECT symbol,quantity,entry_price,fees FROM paper_trades WHERE status='OPEN' ORDER BY opened_at DESC",
                null);
        StringBuilder open = new StringBuilder();
        try {
            while (positions.moveToNext()) {
                String symbol = positions.getString(0);
                double quantity = positions.getDouble(1);
                double entry = positions.getDouble(2);
                double entryFees = positions.getDouble(3);
                double price = latestPrice(db, symbol);
                if (price <= 0) price = entry;
                double value = quantity * price;
                double pnl = quantity * (price - entry) - entryFees - value * PaperTradingEngine.FEE_RATE;
                marketValue += value;
                unrealized += pnl;
                if (open.length() > 0) open.append("\n\n");
                open.append(symbol).append("\nQuantity: ").append(fmt(quantity, 6))
                        .append("\nEntry: ").append(money(entry))
                        .append("   Mark: ").append(money(price))
                        .append("\nUnrealized P/L: ").append(money(pnl));
            }
        } finally { positions.close(); }
        if (open.length() == 0) open.append("No open positions.");
        double equity = cash + marketValue;
        double realized = scalar(db,
                "SELECT COALESCE(SUM(realized_pnl),0) FROM paper_trades WHERE status='CLOSED'");
        portfolioPanel.setText("Total virtual equity    " + money(equity) +
                "\nVirtual cash    " + money(cash) +
                "\nOpen position value    " + money(marketValue) +
                "\nUnrealized P/L    " + money(unrealized) +
                "\nRealized P/L (all time)    " + money(realized) +
                "\nToday's realized P/L    " + money(tradingEngine.getTodayRealizedPnl()) +
                "\nStarting virtual balance    $100.00");

        openTradesPanel.setText(open.toString());
        historyPanel.setText(loadHistory(db));
        modelPanel.setText(loadModels());
        if (autoButton != null) updateAutoButton();
    }

    private String loadModels() {
        String[] symbols = new String[]{"BTCUSDT", "ETHUSDT", "SOLUSDT"};
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < symbols.length; i++) {
            OnDeviceLearningEngine.Assessment a = learningEngine.assess(symbols[i], "1m");
            if (i > 0) result.append("\n\n");
            result.append(symbols[i]).append("  ·  ").append(a.signal)
                    .append("\nTraining examples: ").append(a.samples)
                    .append("\nHistorical directional accuracy: ")
                    .append(String.format(Locale.US, "%.1f%%", a.accuracy))
                    .append("\nSignal confidence: ")
                    .append(String.format(Locale.US, "%.1f%%", a.confidence))
                    .append("\nEstimated upward probability: ")
                    .append(String.format(Locale.US, "%.1f%%", a.probability * 100.0));
            if (a.samples < 20) result.append("\nExplanation: gathering enough completed candles before issuing a trained signal.");
            else if ("UPWARD BIAS".equals(a.signal))
                result.append("\nExplanation: recent momentum/trend features currently lean upward; this is not a prediction guarantee.");
            else if ("DOWNWARD BIAS".equals(a.signal))
                result.append("\nExplanation: recent momentum/trend features currently lean downward; this is not a prediction guarantee.");
            else result.append("\nExplanation: the model does not currently see a strong directional edge.");
        }
        return result.toString();
    }

    private String loadHistory(SQLiteDatabase db) {
        Cursor c = db.rawQuery(
                "SELECT symbol,status,entry_price,exit_price,realized_pnl,opened_at FROM paper_trades ORDER BY opened_at DESC LIMIT 8",
                null);
        StringBuilder b = new StringBuilder();
        try {
            while (c.moveToNext()) {
                if (b.length() > 0) b.append("\n\n");
                b.append(c.getString(0)).append("  ·  ").append(c.getString(1))
                        .append("\nEntry: ").append(money(c.getDouble(2)));
                if (!c.isNull(3)) b.append("   Exit: ").append(money(c.getDouble(3)));
                if ("CLOSED".equals(c.getString(1)))
                    b.append("\nNet P/L: ").append(money(c.getDouble(4)));
            }
        } finally { c.close(); }
        return b.length() == 0 ? "No paper trades recorded yet." : b.toString();
    }

    private double latestPrice(SQLiteDatabase db, String symbol) {
        Cursor c = db.rawQuery(
                "SELECT close FROM candles WHERE symbol=? AND interval='1m' ORDER BY open_time DESC LIMIT 1",
                new String[]{symbol});
        try { return c.moveToFirst() ? c.getDouble(0) : 0.0; }
        finally { c.close(); }
    }

    private double scalar(SQLiteDatabase db, String sql) {
        Cursor c = db.rawQuery(sql, null);
        try { return c.moveToFirst() ? c.getDouble(0) : 0.0; }
        finally { c.close(); }
    }

    private void updateAutoButton() {
        if (autoButton == null || autoTrader == null) return;
        autoButton.setText(autoTrader.isEnabled()
                ? "Turn automatic paper trading OFF"
                : "Turn automatic paper trading ON");
    }

    private String money(double value) {
        return String.format(Locale.US, "%s$%,.2f",
                value < 0 ? "-" : "", Math.abs(value));
    }
    private String fmt(double value, int decimals) {
        return String.format(Locale.US, "%,." + decimals + "f", value);
    }

    @Override protected void onResume() {
        super.onResume();
        if (!receiverRegistered) {
            IntentFilter filter = new IntentFilter(MarketDataService.ACTION_STATUS);
            if (Build.VERSION.SDK_INT >= 33)
                registerReceiver(marketReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(marketReceiver, filter);
            receiverRegistered = true;
        }
        refreshDashboard();
    }

    @Override protected void onPause() {
        if (receiverRegistered) {
            unregisterReceiver(marketReceiver);
            receiverRegistered = false;
        }
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (database != null) database.closeStore();
        super.onDestroy();
    }

    private TextView addPanel(String title, String body) {
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
        return details;
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
        return (int)(value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
