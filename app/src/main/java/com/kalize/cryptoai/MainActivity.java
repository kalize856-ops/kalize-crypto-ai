package com.kalize.cryptoai;

import android.app.Activity;
import android.app.AlertDialog;
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
import java.util.ArrayList;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.BufferedWriter;
import java.io.IOException;
import android.widget.Toast;

/** Responsive-by-width paper dashboard; API 19+ and simulation only. */
public class MainActivity extends Activity {
    private LinearLayout content;
    private PaperDatabaseHelper database;
    private PaperTradingEngine tradingEngine;
    private OnDeviceLearningEngine learningEngine;
    private AutoPaperTradingController autoTrader;
    private TextView marketStatus, marketHealthPanel, marketConditionPanel, marketQuotes, portfolioPanel, modelPanel, learningReportPanel, openTradesPanel, historyPanel, performancePanel;
    private CandlestickChartView priceChart;
    private SimpleLineChartView equityChart;
    private int selectedRangeMinutes = 360;
    private int selectedCandleIntervalMinutes = 1;
    private String selectedSymbol = "BTCUSDT";
    private boolean receiverRegistered = false;
    private String historyFilter = "ALL";
    private int performanceRangeDays = 0;
    private static final int REQUEST_EXPORT_JOURNAL = 513;

    private final BroadcastReceiver marketReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String quotes = intent.getStringExtra(MarketDataService.EXTRA_QUOTES);
            if (quotes != null && marketQuotes != null) { updateQuotePanel(quotes); return; }
            if (marketStatus != null) {
                String status = intent.getStringExtra(MarketDataService.EXTRA_STATUS);
                marketStatus.setText((status == null ? "Waiting for market feed" : status) +
                        "\nCompleted 1m candles stored: " + database.getStoredCandleCount());
                if (marketHealthPanel != null) marketHealthPanel.setText(loadMarketHealth());
                if (marketConditionPanel != null) marketConditionPanel.setText(loadMarketConditions());
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
        int widthDp = (int)(getResources().getDisplayMetrics().widthPixels /
                getResources().getDisplayMetrics().density);
        boolean wideDashboard = widthDp >= 700;

        LinearLayout dashboardRoot = new LinearLayout(this);
        dashboardRoot.setOrientation(wideDashboard
                ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        dashboardRoot.setBackgroundColor(Color.rgb(13, 19, 29));

        LinearLayout leftColumn = new LinearLayout(this);
        leftColumn.setOrientation(LinearLayout.VERTICAL);
        leftColumn.setPadding(dp(wideDashboard ? 12 : 18), dp(22),
                dp(wideDashboard ? 8 : 18), dp(28));
        leftColumn.setBackgroundColor(Color.rgb(13, 19, 29));

        if (wideDashboard) {
            LinearLayout rightColumn = new LinearLayout(this);
            rightColumn.setOrientation(LinearLayout.VERTICAL);
            rightColumn.setPadding(dp(8), dp(22), dp(12), dp(28));
            rightColumn.setBackgroundColor(Color.rgb(13, 19, 29));
            dashboardRoot.addView(leftColumn, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
            dashboardRoot.addView(rightColumn, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
            content = leftColumn;
        } else {
            dashboardRoot.addView(leftColumn, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            content = leftColumn;
        }
        scroll.addView(dashboardRoot);

        addText("KALIZE CRYPTO AI", 24, Color.WHITE, true);
        addText("PRACTICE TRADING · VIRTUAL MONEY ONLY", 12, Color.rgb(22, 192, 140), true);
        addSpace(18);

        portfolioPanel = addPanel("YOUR VIRTUAL MONEY", "Loading your balance…");
        marketStatus = addPanel("MARKET CONNECTION",
                "Starting persistent WebSocket connection…\nBTC / USDT    waiting\n" +
                "ETH / USDT    waiting\nSOL / USDT    waiting\nCandle interval: 1 minute");
        marketQuotes = addPanel("COIN PRICES",
                "Waiting for live coin prices…");
        marketHealthPanel = addPanel("MARKET DATA STATUS", loadMarketHealth());
        marketConditionPanel = addPanel("HOW THE MARKET HAS BEEN MOVING", loadMarketConditions());
        addText("PRICE CHART", 12, Color.rgb(22, 192, 140), true);
        addText("Choose a coin and time range. Drag the chart to look back at prices.", 12, Color.LTGRAY, false);
        addPairSelector();
        addIntervalSelector();
        addRangeSelector();
        priceChart = new CandlestickChartView(this);
        priceChart.setBackgroundColor(Color.rgb(27, 39, 54));
        content.addView(priceChart, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(220)));
        addSpace(14);

        addText("VIRTUAL ACCOUNT HISTORY", 12, Color.rgb(22, 192, 140), true);
        addText("See how your practice balance has changed. Past results do not predict future results.", 12, Color.LTGRAY, false);
        equityChart = new SimpleLineChartView(this);
        equityChart.setBackgroundColor(Color.rgb(27, 39, 54));
        content.addView(equityChart, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(190)));
        addSpace(14);

        if (wideDashboard) {
            content = (LinearLayout) dashboardRoot.getChildAt(1);
            addText("YOUR AI PRACTICE BOT", 12, Color.rgb(22, 192, 140), true);
            addSpace(8);
        }

        modelPanel = addPanel("WHAT THE AI THINKS", "Loading local model…");
        learningReportPanel = addPanel("HOW THE AI IS LEARNING", "Collecting learning statistics…");
        openTradesPanel = addPanel("TRADES THE AI IS WATCHING", "No open positions.");
        performancePanel = addPanel("TRADE RESULTS", "Waiting for closed paper trades…");
        addPerformanceRangeSelector();
        historyPanel = addPanel("PAST TRADES", "No paper trades recorded yet.");
        addHistoryFilterSelector();

        addPanel("SAVED MARKET HISTORY",
                "Learning window: 30 days\nStored candles: " + database.getStoredCandleCount() +
                "\nExpired candles: automatically cleaned");
        addPanel("SAFETY LIMITS",
                "Simulation only\nRisk per trade: 1% maximum\nDaily loss pause: 2%\n" +
                "Maximum open positions: 3\nMaximum position size: 25% of virtual equity\n" +
                "Fees and slippage are simulated\nReal-money orders: disabled");

        addText("AUTOMATIC PRACTICE MODE", 12, Color.rgb(22, 192, 140), true);
        addText("The bot makes practice trades by itself using live coin prices. You cannot place trades manually. It never uses real money or places real exchange orders.",
                13, Color.LTGRAY, false);
        Button resetSimulation = new Button(this);
        resetSimulation.setText("Start over · reset practice money to $100");
        resetSimulation.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Start the practice account over?")
                        .setMessage("This clears your practice trades and restores the virtual balance to $100. Saved market prices and AI learning progress will stay. This cannot be undone.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Start over", new android.content.DialogInterface.OnClickListener() {
                            @Override public void onClick(android.content.DialogInterface dialog, int which) {
                                database.resetPaperSimulation();
                                refreshDashboard();
                            }
                        }).show();
            }
        });
        content.addView(resetSimulation, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        Button refresh = new Button(this);
        refresh.setText("Refresh prices and account");
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { refreshDashboard(); }
        });
        content.addView(refresh, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        addSpace(12);
        addText("Prices are supplied by the live market feed. All trades use practice money.",
                11, Color.GRAY, false);
        setContentView(scroll);
        refreshDashboard();
    }

    private void updateQuotePanel(String quote) {
        if (marketQuotes == null || quote == null) return;
        String[] parts = quote.split("\\|");
        if (parts.length != 6) return;
        try {
            String symbol = parts[0];
            double last = Double.parseDouble(parts[1]);
            double change = Double.parseDouble(parts[2]);
            double high = Double.parseDouble(parts[3]);
            double low = Double.parseDouble(parts[4]);
            double volume = Double.parseDouble(parts[5]);
            String formatted = symbol + "  ·  " + money(last) +
                    "\n24h change: " + (change >= 0 ? "+" : "") + String.format(Locale.US, "%.2f%%", change) +
                    "\n24h high / low: " + money(high) + " / " + money(low) +
                    "\n24h quote volume: " + money(volume);
            String existing = marketQuotes.getText().toString();
            String[] blocks = existing.split("\n\n");
            StringBuilder combined = new StringBuilder();
            boolean replaced = false;
            for (String block : blocks) {
                if (block.startsWith(symbol + "  ·")) { block = formatted; replaced = true; }
                if (combined.length() > 0) combined.append("\n\n");
                combined.append(block);
            }
            if (!replaced) {
                if (existing.startsWith("Waiting")) combined.setLength(0);
                else if (combined.length() > 0) combined.append("\n\n");
                combined.append(formatted);
            }
            marketQuotes.setText(combined.toString());
        } catch (NumberFormatException ignored) { }
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
                open.append(symbol.replace("USDT", "/USDT"))
                        .append("\nStatus: AI is monitoring this trade")
                        .append("\nCurrent result: ").append(pnl > 0.0 ? "Profit " :
                                (pnl < 0.0 ? "Loss " : "Around break-even "))
                        .append(money(pnl));
            }
        } finally { positions.close(); }
        if (open.length() == 0) open.append("No open positions.");
        double equity = cash + marketValue;
        double realized = scalar(db,
                "SELECT COALESCE(SUM(realized_pnl),0) FROM paper_trades WHERE status='CLOSED'");
        // Persist the highest observed virtual equity so drawdown survives app restarts.
        double equityPeak = readPositiveSetting("equity_high_water_mark", 100.0);
        if (equity > equityPeak) {
            equityPeak = equity;
            database.putSetting("equity_high_water_mark", String.format(Locale.US, "%.8f", equityPeak));
        }
        double drawdown = Math.max(0.0, equityPeak - equity);
        double drawdownPercent = equityPeak > 0.0 ? (drawdown / equityPeak) * 100.0 : 0.0;
        boolean cashDepleted = cash <= 0.005;
        if (cashDepleted && autoTrader.isEnabled()) autoTrader.setEnabled(false);
        portfolioPanel.setText((cashDepleted ? "VIRTUAL CASH DEPLETED · NEW TRADES PAUSED\n" : "") +
                "Total virtual equity    " + money(equity) +
                "\nVirtual cash    " + money(cash) +
                "\nOpen position value    " + money(marketValue) +
                "\nUnrealized P/L    " + money(unrealized) +
                "\nRealized P/L (all time)    " + money(realized) +
                "\nToday's realized P/L    " + money(tradingEngine.getTodayRealizedPnl()) +
                "\nStarting virtual balance    $100.00" +
                "\nHighest virtual equity    " + money(equityPeak) +
                "\nCurrent drawdown    " + money(drawdown) + " (" +
                String.format(Locale.US, "%.2f%%", drawdownPercent) + ")" +
                (cashDepleted ? "\nStatus: no virtual cash available. Use Reset simulation to begin a new account." : ""));

        openTradesPanel.setText(open.toString());
        if (marketHealthPanel != null) marketHealthPanel.setText(loadMarketHealth());
        if (marketConditionPanel != null) marketConditionPanel.setText(loadMarketConditions());
        refreshCharts(db);
        historyPanel.setText(loadHistory(db));
        if (performancePanel != null) performancePanel.setText(loadPerformanceReport(db));
        modelPanel.setText(loadModels());
        if (learningReportPanel != null) learningReportPanel.setText(loadLearningReport(db));
    }


    private void addPairSelector() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        String[] symbols = new String[]{"BTCUSDT", "ETHUSDT", "SOLUSDT"};
        for (final String symbol : symbols) {
            Button button = new Button(this);
            button.setText(symbol.replace("USDT", "/USDT"));
            button.setTextSize(10);
            button.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    selectedSymbol = symbol;
                    refreshDashboard();
                }
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(46), 1.0f);
            params.setMargins(dp(2), dp(4), dp(2), dp(8));
            row.addView(button, params);
        }
        content.addView(row);
    }


    private void addIntervalSelector() {
        addText("PRICE TIMEFRAME", 11, Color.rgb(175, 187, 202), true);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        final int[] intervals = new int[]{1, 5, 15, 60, 240, 1440};
        final String[] labels = new String[]{"1m", "5m", "15m", "1h", "4h", "1d"};
        for (int i = 0; i < intervals.length; i++) {
            final int interval = intervals[i];
            Button button = new Button(this);
            button.setText(labels[i]);
            button.setTextSize(10);
            button.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    selectedCandleIntervalMinutes = interval;
                    refreshDashboard();
                }
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(42), 1f);
            params.setMargins(dp(1), dp(2), dp(1), dp(8));
            row.addView(button, params);
        }
        content.addView(row);
    }

    private void addRangeSelector() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        final int[] ranges = new int[]{60, 360, 1440, 10080, 43200};
        final String[] labels = new String[]{"1H", "6H", "24H", "7D", "30D"};
        for (int i = 0; i < ranges.length; i++) {
            final int range = ranges[i];
            Button button = new Button(this);
            button.setText(labels[i]);
            button.setTextSize(10);
            button.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    selectedRangeMinutes = range;
                    refreshDashboard();
                }
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(42), 1f);
            params.setMargins(dp(1), dp(2), dp(1), dp(8));
            row.addView(button, params);
        }
        content.addView(row);
    }

    private String rangeLabel(int minutes) {
        if (minutes == 60) return "1 hour";
        if (minutes == 360) return "6 hours";
        if (minutes == 1440) return "24 hours";
        if (minutes == 10080) return "7 days";
        return "30 days";
    }

    private String intervalLabel(int minutes) {
        if (minutes == 1) return "1m";
        if (minutes == 5) return "5m";
        if (minutes == 15) return "15m";
        if (minutes == 60) return "1h";
        if (minutes == 240) return "4h";
        return "1d";
    }

    private void refreshCharts(SQLiteDatabase db) {
        if (priceChart != null) {
            Cursor candles = db.rawQuery(
                    "SELECT open_time,open,high,low,close FROM candles WHERE symbol=? AND interval='1m' " +
                    "ORDER BY open_time DESC LIMIT " + selectedRangeMinutes,
                    new String[]{selectedSymbol});
            ArrayList<double[]> newestFirst = new ArrayList<double[]>();
            try {
                while (candles.moveToNext()) {
                    newestFirst.add(new double[]{candles.getLong(0), candles.getDouble(1),
                            candles.getDouble(2), candles.getDouble(3), candles.getDouble(4)});
                }
            } finally { candles.close(); }

            // Aggregate completed 1-minute source candles into genuine OHLC intervals.
            ArrayList<double[]> aggregated = new ArrayList<double[]>();
            long intervalMs = selectedCandleIntervalMinutes * 60000L;
            double[] current = null;
            for (int i = newestFirst.size() - 1; i >= 0; i--) {
                double[] source = newestFirst.get(i);
                long bucket = ((long) source[0]) / intervalMs;
                if (current == null || (long) current[0] != bucket) {
                    current = new double[]{bucket, source[1], source[2], source[3], source[4]};
                    aggregated.add(current);
                } else {
                    current[2] = Math.max(current[2], source[2]);
                    current[3] = Math.min(current[3], source[3]);
                    current[4] = source[4];
                }
            }

            int plotted = Math.min(300, aggregated.size());
            double[] o = new double[plotted], h = new double[plotted];
            double[] l = new double[plotted], close = new double[plotted];
            int start = aggregated.size() - plotted;
            for (int i = 0; i < plotted; i++) {
                double[] bar = aggregated.get(start + i);
                o[i] = bar[1];
                h[i] = bar[2];
                l[i] = bar[3];
                close[i] = bar[4];
            }
            priceChart.setCandles(o, h, l, close, selectedSymbol + " · " +
                    intervalLabel(selectedCandleIntervalMinutes) + " · " +
                    rangeLabel(selectedRangeMinutes) + " · " + plotted + " candles");
        }
        if (equityChart != null) {
            Cursor trades = db.rawQuery(
                    "SELECT realized_pnl FROM (SELECT realized_pnl,closed_at FROM paper_trades " +
                    "WHERE status='CLOSED' ORDER BY closed_at DESC LIMIT 50) ORDER BY closed_at ASC",
                    null);
            double[] values = new double[trades.getCount() + 1];
            double equity = 100.0;
            values[0] = equity;
            int index = 1;
            try {
                while (trades.moveToNext() && index < values.length) {
                    equity += trades.getDouble(0);
                    values[index++] = equity;
                }
            } finally { trades.close(); }
            if (index < values.length) {
                double[] compact = new double[index];
                System.arraycopy(values, 0, compact, 0, index);
                values = compact;
            }
            equityChart.setSeries(values, "Virtual equity after closed trades · starting balance $100",
                    Color.rgb(93, 160, 255));
        }
    }

    private String loadModels() {
        String[] symbols = new String[]{"BTCUSDT", "ETHUSDT", "SOLUSDT"};
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < symbols.length; i++) {
            OnDeviceLearningEngine.Assessment a = learningEngine.assess(symbols[i], "1m");
            if (i > 0) result.append("\n\n");
            result.append(symbols[i].replace("USDT", "/USDT")).append("\n");
            if (a.candleSamples < 20) result.append("AI status: Still learning from price history");
            else if ("UPWARD BIAS".equals(a.signal)) result.append("AI view: Price may be moving up");
            else if ("DOWNWARD BIAS".equals(a.signal)) result.append("AI view: Price may be moving down");
            else result.append("AI view: No clear direction yet");
            result.append("\nLearning examples: ").append(a.candleSamples)
                    .append("\nConfidence: ").append(String.format(Locale.US, "%.0f%%", a.confidence))
                    .append("\nThis is an estimate, not a promise.");
        }
        return result.toString();
    }

    /**
     * Reports training-set statistics and outcomes from AI-tagged paper trades.
     * These are historical diagnostics, not out-of-sample performance guarantees.
     */
    private String loadLearningReport(SQLiteDatabase db) {
        String[] symbols = new String[]{"BTCUSDT", "ETHUSDT", "SOLUSDT"};
        StringBuilder report = new StringBuilder();
        for (int i = 0; i < symbols.length; i++) {
            String symbol = symbols[i];
            OnDeviceLearningEngine.Assessment a = learningEngine.assess(symbol, "1m");
            OnDeviceLearningEngine.Validation v = learningEngine.validateWalkForward(symbol, "1m");
            Cursor trades = db.rawQuery(
                    "SELECT COUNT(*), COALESCE(SUM(CASE WHEN realized_pnl>0 THEN 1 ELSE 0 END),0), COALESCE(SUM(realized_pnl),0) " +
                    "FROM paper_trades WHERE symbol=? AND status='CLOSED'", new String[]{symbol});
            int total = 0, wins = 0; double result = 0.0;
            try { if (trades.moveToFirst()) { total = trades.getInt(0); wins = trades.getInt(1); result = trades.getDouble(2); } }
            finally { trades.close(); }
            if (i > 0) report.append("\n\n");
            report.append(symbol.replace("USDT", "/USDT"))
                    .append("\nPrice examples studied: ").append(a.candleSamples)
                    .append("\nPast test score: ").append(v.samples == 0 ? "Not enough test data" : String.format(Locale.US, "%.0f%%", v.accuracy))
                    .append("\nPractice trades finished: ").append(total)
                    .append("\nProfitable trades: ").append(wins)
                    .append("\nPractice result: ").append(money(result));
        }
        report.append("\n\nThe AI learns from saved price history. Past test scores cannot tell us what prices will do next.");
        return report.toString();
    }

    private void addHistoryFilterSelector() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        final String[] filters = new String[]{"ALL", "OPEN", "CLOSED"};
        final String[] labels = new String[]{"All trades", "Open", "Closed"};
        for (int i = 0; i < filters.length; i++) {
            final String filter = filters[i];
            Button button = new Button(this);
            button.setText(labels[i]);
            button.setTextSize(11);
            button.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    historyFilter = filter;
                    if (historyPanel != null)
                        historyPanel.setText(loadHistory(database.getReadableDatabase()));
                }
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(44), 1f);
            params.setMargins(dp(2), dp(2), dp(2), dp(10));
            row.addView(button, params);
        }
        content.addView(row);
        Button exportButton = new Button(this);
        exportButton.setText("Export journal as CSV");
        exportButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("text/csv");
                intent.putExtra(Intent.EXTRA_TITLE, "kalize-paper-trade-journal.csv");
                startActivityForResult(intent, REQUEST_EXPORT_JOURNAL);
            }
        });
        content.addView(exportButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46)));
    }

    private void addPerformanceRangeSelector() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        final int[] ranges = new int[]{7, 30, 0};
        final String[] labels = new String[]{"7 DAYS", "30 DAYS", "ALL TIME"};
        for (int i = 0; i < ranges.length; i++) {
            final int range = ranges[i];
            Button button = new Button(this);
            button.setText(labels[i]);
            button.setTextSize(10);
            button.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    performanceRangeDays = range;
                    if (performancePanel != null)
                        performancePanel.setText(loadPerformanceReport(database.getReadableDatabase()));
                }
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(44), 1f);
            params.setMargins(dp(2), dp(2), dp(2), dp(10));
            row.addView(button, params);
        }
        content.addView(row);
    }

    private String loadPerformanceReport(SQLiteDatabase db) {
        long cutoff = performanceRangeDays > 0 ? System.currentTimeMillis() - performanceRangeDays * 86400000L : 0L;
        String where = performanceRangeDays > 0 ? " WHERE status='CLOSED' AND closed_at>=?" : " WHERE status='CLOSED'";
        String[] args = performanceRangeDays > 0 ? new String[]{String.valueOf(cutoff)} : null;
        Cursor c = db.rawQuery(
                "SELECT COUNT(*),COALESCE(SUM(CASE WHEN realized_pnl>0 THEN 1 ELSE 0 END),0)," +
                "COALESCE(SUM(CASE WHEN realized_pnl<0 THEN 1 ELSE 0 END),0),COALESCE(SUM(realized_pnl),0)," +
                "COALESCE(MAX(realized_pnl),0),COALESCE(MIN(realized_pnl),0) FROM paper_trades" + where, args);
        try {
            String period = performanceRangeDays == 0 ? "All time" : "Last " + performanceRangeDays + " days";
            if (!c.moveToFirst() || c.getInt(0) == 0) return period + "\nNo finished practice trades yet.";
            int total = c.getInt(0), wins = c.getInt(1), losses = c.getInt(2);
            double net = c.getDouble(3), best = c.getDouble(4), worst = c.getDouble(5);
            double winRate = total > 0 ? wins * 100.0 / total : 0.0;
            return period + "\nFinished trades: " + total +
                    "\nProfitable trades: " + wins + "\nTrades that lost money: " + losses +
                    "\nWin rate: " + String.format(Locale.US, "%.0f%%", winRate) +
                    "\nTotal result: " + money(net) + "\nBest trade: " + money(best) +
                    "\nWorst trade: " + money(worst) + "\n\nThese are practice results, not real earnings.";
        } finally { c.close(); }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_EXPORT_JOURNAL && resultCode == RESULT_OK &&
                data != null && data.getData() != null) {
            exportTradeJournal(data.getData());
        }
    }

    private void exportTradeJournal(android.net.Uri uri) {
        Cursor cursor = database.getReadableDatabase().rawQuery(
                "SELECT id,symbol,side,quantity,entry_price,exit_price,stop_loss," +
                "realized_pnl,fees,status,opened_at,closed_at,ai_probability,ai_interval " +
                "FROM paper_trades" +
                ("ALL".equals(historyFilter) ? "" : " WHERE status=?") +
                " ORDER BY opened_at DESC",
                "ALL".equals(historyFilter) ? null : new String[]{historyFilter});
        OutputStream output = null;
        BufferedWriter writer = null;
        try {
            output = getContentResolver().openOutputStream(uri);
            if (output == null) throw new IOException("Unable to open selected file");
            writer = new BufferedWriter(new OutputStreamWriter(output, "UTF-8"));
            writer.write("trade_id,symbol,side,quantity,entry_price,exit_price,stop_loss,realized_pnl,fees,status,opened_at_epoch_ms,closed_at_epoch_ms,ai_probability,ai_interval");
            writer.newLine();
            while (cursor.moveToNext()) {
                StringBuilder line = new StringBuilder();
                for (int i = 0; i < cursor.getColumnCount(); i++) {
                    if (i > 0) line.append(',');
                    String value = cursor.isNull(i) ? "" : cursor.getString(i);
                    line.append(csvCell(value));
                }
                writer.write(line.toString());
                writer.newLine();
            }
            writer.flush();
            Toast.makeText(this, "Paper-trade journal exported", Toast.LENGTH_LONG).show();
        } catch (Exception error) {
            Toast.makeText(this, "Export failed: " + error.getMessage(), Toast.LENGTH_LONG).show();
        } finally {
            cursor.close();
            if (writer != null) {
                try { writer.close(); } catch (IOException ignored) { }
            } else if (output != null) {
                try { output.close(); } catch (IOException ignored) { }
            }
        }
    }

    private String csvCell(String value) {
        if (value == null) return "";
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private String loadHistory(SQLiteDatabase db) {
        String where = "ALL".equals(historyFilter) ? "" : " WHERE status=?";
        Cursor c = db.rawQuery(
                "SELECT id,symbol,status,realized_pnl,opened_at,closed_at FROM paper_trades" + where +
                " ORDER BY opened_at DESC LIMIT 100",
                "ALL".equals(historyFilter) ? null : new String[]{historyFilter});
        StringBuilder b = new StringBuilder();
        int count = 0;
        java.text.SimpleDateFormat dateFormat =
                new java.text.SimpleDateFormat("MMM d, HH:mm", Locale.US);
        try {
            while (c.moveToNext()) {
                count++;
                if (b.length() > 0) b.append("\n\n");
                String status = c.getString(2);
                String symbol = c.getString(1).replace("USDT", "/USDT");
                b.append(symbol).append("  ·  ")
                        .append("OPEN".equals(status) ? "In progress" : "Finished");
                if ("CLOSED".equals(status)) {
                    double result = c.getDouble(3);
                    b.append("\nResult: ").append(result > 0.0 ? "Profit " :
                            (result < 0.0 ? "Loss " : "Break-even "))
                            .append(money(result));
                } else {
                    b.append("\nResult: Not final yet");
                }
                b.append("\nAI action: ").append("CLOSED".equals(status)
                        ? "The AI finished this simulated trade."
                        : "The AI is still monitoring this simulated trade.");
                b.append("\nDate: ").append(dateFormat.format(new java.util.Date(c.getLong(4))));
            }
        } finally { c.close(); }
        if (count == 0) return "No " + historyFilter.toLowerCase(Locale.US) +
                " trades yet. The AI will list them here as it makes simulated trades.";
        String header = "Your latest " + count + (count == 100 ? " trades (latest 100)" : " trades") +
                " · " + historyFilter.toLowerCase(Locale.US) + " filter";
        return header + "\n\n" + b.toString() +
                "\n\nAll trades use virtual money only.";
    }

    private String formatDuration(long durationMs) {
        if (durationMs < 0L) durationMs = 0L;
        long totalMinutes = durationMs / 60000L;
        long days = totalMinutes / 1440L;
        long hours = (totalMinutes % 1440L) / 60L;
        long minutes = totalMinutes % 60L;
        if (days > 0L) return days + "d " + hours + "h";
        if (hours > 0L) return hours + "h " + minutes + "m";
        return minutes + "m";
    }

    private double readPositiveSetting(String key, double fallback) {
        try {
            double value = Double.parseDouble(database.getSetting(key, String.valueOf(fallback)));
            return value > 0.0 && !Double.isNaN(value) && !Double.isInfinite(value)
                    ? value : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    /** Reports freshness of the latest completed 1-minute candle for each tracked pair. */
    private String loadMarketHealth() {
        if (database == null) return "Waiting for price updates…";
        SQLiteDatabase db = database.getReadableDatabase();
        String[] symbols = new String[]{"BTCUSDT", "ETHUSDT", "SOLUSDT"};
        StringBuilder out = new StringBuilder(); long now = System.currentTimeMillis();
        for (String symbol : symbols) {
            Cursor c = db.rawQuery("SELECT MAX(open_time) FROM candles WHERE symbol=? AND interval='1m'", new String[]{symbol});
            long latest = 0L;
            try { if (c.moveToFirst() && !c.isNull(0)) latest = c.getLong(0); } finally { c.close(); }
            if (out.length() > 0) out.append("\n");
            out.append(symbol.replace("USDT", "/USDT")).append(": ");
            if (latest <= 0L) out.append("Waiting for prices");
            else {
                long age = Math.max(0L, now - (latest + 60000L)) / 1000L;
                if (age <= 180L) out.append("Up to date");
                else if (age <= 300L) out.append("A little delayed");
                else out.append("Price updates are late");
            }
        }
        out.append("\n\nIf prices are delayed, the AI may wait before making a new practice trade.");
        return out.toString();
    }

    /**
     * Descriptive recent range statistics from up to 60 completed one-minute candles.
     * These measurements describe recent movement and are not trading signals.
     */
    private String loadMarketConditions() {
        if (database == null) return "Waiting for market prices…";
        SQLiteDatabase db = database.getReadableDatabase();
        String[] symbols = new String[]{"BTCUSDT", "ETHUSDT", "SOLUSDT"};
        StringBuilder out = new StringBuilder();
        for (String symbol : symbols) {
            Cursor c = db.rawQuery("SELECT high,low,close FROM candles WHERE symbol=? AND interval='1m' ORDER BY open_time DESC LIMIT 30", new String[]{symbol});
            double first = -1.0, latest = -1.0, highest = 0.0, lowest = Double.MAX_VALUE; int count = 0;
            try {
                while (c.moveToNext()) {
                    double high = c.getDouble(0), low = c.getDouble(1), close = c.getDouble(2);
                    if (count == 0) latest = close;
                    first = close; highest = Math.max(highest, high); lowest = Math.min(lowest, low); count++;
                }
            } finally { c.close(); }
            if (out.length() > 0) out.append("\n\n");
            out.append(symbol.replace("USDT", "/USDT")).append(": ");
            if (count < 2 || first <= 0.0) out.append("Not enough price history yet");
            else {
                double change = ((latest - first) / first) * 100.0;
                out.append(change > 0.05 ? "Moving up recently" : (change < -0.05 ? "Moving down recently" : "Mostly moving sideways"));
                out.append("\nRecent high: ").append(money(highest)).append("\nRecent low: ").append(money(lowest));
            }
        }
        out.append("\n\nThis describes recent price movement. It cannot predict the next move.");
        return out.toString();
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
