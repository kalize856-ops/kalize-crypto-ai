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

/** Responsive-by-width paper dashboard; API 19+ and simulation only. */
public class MainActivity extends Activity {
    private LinearLayout content;
    private PaperDatabaseHelper database;
    private PaperTradingEngine tradingEngine;
    private OnDeviceLearningEngine learningEngine;
    private AutoPaperTradingController autoTrader;
    private TextView marketStatus, marketQuotes, portfolioPanel, modelPanel, learningReportPanel, openTradesPanel, historyPanel, performancePanel;
    private Button autoButton;
    private CandlestickChartView priceChart;
    private SimpleLineChartView equityChart;
    private int selectedRangeMinutes = 360;
    private int selectedCandleIntervalMinutes = 1;
    private String selectedSymbol = "BTCUSDT";
    private boolean receiverRegistered = false;
    private String historyFilter = "ALL";

    private final BroadcastReceiver marketReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String quotes = intent.getStringExtra(MarketDataService.EXTRA_QUOTES);
            if (quotes != null && marketQuotes != null) { updateQuotePanel(quotes); return; }
            if (marketStatus != null) {
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
        addText("LITE  •  PAPER TRADING DASHBOARD", 12, Color.rgb(22, 192, 140), true);
        addSpace(18);

        portfolioPanel = addPanel("VIRTUAL PORTFOLIO", "Loading portfolio…");
        marketStatus = addPanel("LIVE MARKET WATCH",
                "Starting persistent WebSocket connection…\nBTC / USDT    waiting\n" +
                "ETH / USDT    waiting\nSOL / USDT    waiting\nCandle interval: 1 minute");
        marketQuotes = addPanel("LIVE QUOTES · 24-HOUR SNAPSHOT",
                "Waiting for live ticker updates…\nLast price, daily change, high, low and quote volume.");
        addText("MARKET PRICE CHART", 12, Color.rgb(22, 192, 140), true);
        addText("Choose a pair, candle interval and history range. Touch or drag across candles to inspect OHLC values.", 12, Color.LTGRAY, false);
        addPairSelector();
        addIntervalSelector();
        addRangeSelector();
        priceChart = new CandlestickChartView(this);
        priceChart.setBackgroundColor(Color.rgb(27, 39, 54));
        content.addView(priceChart, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(220)));
        addSpace(14);

        addText("SIMULATED PERFORMANCE", 12, Color.rgb(22, 192, 140), true);
        addText("Cumulative realized results from closed paper trades; not a return forecast.", 12, Color.LTGRAY, false);
        equityChart = new SimpleLineChartView(this);
        equityChart.setBackgroundColor(Color.rgb(27, 39, 54));
        content.addView(equityChart, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(190)));
        addSpace(14);

        if (wideDashboard) {
            content = (LinearLayout) dashboardRoot.getChildAt(1);
            addText("AI & PAPER-TRADING CONTROLS", 12, Color.rgb(22, 192, 140), true);
            addSpace(8);
        }

        modelPanel = addPanel("AI LEARNING & DECISION EXPLANATIONS", "Loading local model…");
        learningReportPanel = addPanel("AI MODEL PERFORMANCE REPORT", "Collecting learning statistics…");
        openTradesPanel = addPanel("OPEN SIMULATED POSITIONS", "No open positions.");
        performancePanel = addPanel("PAPER-TRADE PERFORMANCE", "Waiting for closed paper trades…");
        historyPanel = addPanel("PAPER-TRADE JOURNAL", "No paper trades recorded yet.");
        addHistoryFilterSelector();

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
        Button resetSimulation = new Button(this);
        resetSimulation.setText("Reset virtual account · restore $100");
        resetSimulation.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Reset paper simulation?")
                        .setMessage("This clears all simulated positions and trade history and restores the virtual wallet to $100. Market candles and AI learning data will be kept. This cannot be undone.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Reset simulation", new android.content.DialogInterface.OnClickListener() {
                            @Override public void onClick(android.content.DialogInterface dialog, int which) {
                                database.resetPaperSimulation();
                                refreshDashboard();
                                updateAutoButton();
                            }
                        }).show();
            }
        });
        content.addView(resetSimulation, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

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
        addText("Build 0.5.9 · Per-coin performance breakdown · Paper-trade performance analytics · Filterable paper-trade journal · Portfolio drawdown tracking · Automatic stop-loss monitoring · Finite virtual wallet · Live ticker quotes · Landscape dashboard · Android 4.4.4+ · Public market data · Local SQLite",
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
        refreshCharts(db);
        historyPanel.setText(loadHistory(db));
        if (performancePanel != null) performancePanel.setText(loadPerformanceReport(db));
        modelPanel.setText(loadModels());
        if (learningReportPanel != null) learningReportPanel.setText(loadLearningReport(db));
        if (autoButton != null) updateAutoButton();
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
        addText("CANDLE INTERVAL", 11, Color.rgb(175, 187, 202), true);
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
            result.append(symbols[i]).append("  ·  ").append(a.signal)
                    .append("\nCandle training examples: ").append(a.candleSamples)
                    .append("\nTraining-set directional accuracy: ")
                    .append(String.format(Locale.US, "%.1f%%", a.accuracy))
                    .append("\nSignal confidence: ")
                    .append(String.format(Locale.US, "%.1f%%", a.confidence))
                    .append("\nEstimated upward probability: ")
                    .append(String.format(Locale.US, "%.1f%%", a.probability * 100.0));
            if (a.candleSamples < 20) result.append("\nExplanation: gathering enough completed candles before issuing a trained signal.");
            else if ("UPWARD BIAS".equals(a.signal))
                result.append("\nExplanation: recent momentum/trend features currently lean upward; this is not a prediction guarantee.");
            else if ("DOWNWARD BIAS".equals(a.signal))
                result.append("\nExplanation: recent momentum/trend features currently lean downward; this is not a prediction guarantee.");
            else result.append("\nExplanation: the model does not currently see a strong directional edge.");
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
                    "SELECT COUNT(*), COALESCE(SUM(CASE WHEN realized_pnl>0 THEN 1 ELSE 0 END),0), " +
                    "COALESCE(SUM(realized_pnl),0), COALESCE(AVG(realized_pnl),0) " +
                    "FROM paper_trades WHERE symbol=? AND status='CLOSED'",
                    new String[]{symbol});
            int totalTrades = 0, winningTrades = 0;
            double netPnl = 0, averagePnl = 0;
            try {
                if (trades.moveToFirst()) {
                    totalTrades = trades.getInt(0);
                    winningTrades = trades.getInt(1);
                    netPnl = trades.getDouble(2);
                    averagePnl = trades.getDouble(3);
                }
            } finally { trades.close(); }
            if (i > 0) report.append("\n\n");
            report.append(symbol)
                    .append("\nCandle examples learned: ").append(a.candleSamples)
                    .append("\nTraining-set directional accuracy: ")
                    .append(a.candleSamples == 0 ? "Not available" :
                            String.format(Locale.US, "%.1f%%", a.accuracy))
                    .append("\nWalk-forward test candles: ").append(v.samples)
                    .append("\nWalk-forward directional accuracy: ").append(v.samples == 0 ? "Not available" : String.format(Locale.US, "%.1f%%", v.accuracy))
                    .append("\nSimple momentum baseline: ").append(v.samples == 0 ? "Not available" : String.format(Locale.US, "%.1f%%", v.baselineAccuracy))
                    .append("\nProbability Brier score (lower is better): ").append(v.samples == 0 ? "Not available" : String.format(Locale.US, "%.4f", v.brierScore))
                    .append("\nBalanced accuracy: ").append(v.samples == 0 ? "Not available" : String.format(Locale.US, "%.1f%%", v.balancedAccuracy))
                    .append("\nUpward precision / recall: ").append(v.samples == 0 ? "Not available" : String.format(Locale.US, "%.1f%% / %.1f%%", v.precision, v.recall))
                    .append("\nDownward specificity: ").append(v.samples == 0 ? "Not available" : String.format(Locale.US, "%.1f%%", v.specificity))
                    .append("\nConfusion counts (TP/TN/FP/FN): ").append(v.truePositive).append("/").append(v.trueNegative).append("/").append(v.falsePositive).append("/").append(v.falseNegative)
                    .append("\nConfidence calibration error (lower is better): ").append(v.samples == 0 ? "Not available" : String.format(Locale.US, "%.2f%%", v.calibrationError))
                    .append("\nConfidence buckets (range: predictions / mean confidence / observed accuracy):");
            String[] bucketLabels = new String[]{"50-59%", "60-69%", "70-79%", "80-89%", "90-100%"};
            for (int bucket = 0; bucket < bucketLabels.length; bucket++) {
                report.append("\n").append(bucketLabels[bucket]).append(": ")
                        .append(v.confidenceBucketCounts[bucket]).append(" / ");
                if (v.confidenceBucketCounts[bucket] == 0) report.append("— / —");
                else report.append(String.format(Locale.US, "%.1f%% / %.1f%%",
                        v.confidenceBucketMeanConfidence[bucket] * 100.0,
                        v.confidenceBucketAccuracy[bucket] * 100.0));
            }
            report.append("\nAI-tagged paper outcomes learned: ").append(a.tradeSamples)
                    .append("\nAI paper-outcome accuracy: ")
                    .append(a.tradeSamples == 0 ? "Not available" :
                            String.format(Locale.US, "%.1f%%", a.tradeAccuracy))
                    .append("\nAll closed paper trades: ").append(totalTrades)
                    .append(" · Wins: ").append(winningTrades)
                    .append("\nPaper-trade net P/L: ").append(money(netPnl))
                    .append(" · Average: ").append(money(averagePnl));
        }
        report.append("\n\nWalk-forward scores are sequential historical simulations, not independent live forecasts. They can be affected by market regime changes, fees, slippage and limited data; they do not guarantee future results.");
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
    }

    private String loadPerformanceReport(SQLiteDatabase db) {
        Cursor c = db.rawQuery(
                "SELECT COUNT(*)," +
                "COALESCE(SUM(CASE WHEN realized_pnl > 0 THEN 1 ELSE 0 END),0)," +
                "COALESCE(SUM(CASE WHEN realized_pnl < 0 THEN 1 ELSE 0 END),0)," +
                "COALESCE(SUM(CASE WHEN realized_pnl = 0 THEN 1 ELSE 0 END),0)," +
                "COALESCE(AVG(CASE WHEN realized_pnl > 0 THEN realized_pnl END),0)," +
                "COALESCE(AVG(CASE WHEN realized_pnl < 0 THEN realized_pnl END),0)," +
                "COALESCE(MAX(realized_pnl),0),COALESCE(MIN(realized_pnl),0)," +
                "COALESCE(SUM(CASE WHEN realized_pnl > 0 THEN realized_pnl ELSE 0 END),0)," +
                "COALESCE(SUM(CASE WHEN realized_pnl < 0 THEN -realized_pnl ELSE 0 END),0) " +
                "FROM paper_trades WHERE status='CLOSED'", null);
        try {
            if (!c.moveToFirst() || c.getInt(0) == 0)
                return "No closed paper trades yet. Performance statistics will appear after a simulated position is closed.\n\nThese figures describe the virtual account only.";
            int total = c.getInt(0);
            int wins = c.getInt(1);
            int losses = c.getInt(2);
            int breakeven = c.getInt(3);
            double averageWin = c.getDouble(4);
            double averageLoss = c.getDouble(5);
            double best = c.getDouble(6);
            double worst = c.getDouble(7);
            double grossProfit = c.getDouble(8);
            double grossLoss = c.getDouble(9);
            double winRate = total > 0 ? wins * 100.0 / total : 0.0;
            String profitFactor = grossLoss > 0.0
                    ? String.format(Locale.US, "%.2f", grossProfit / grossLoss)
                    : (grossProfit > 0.0 ? "No losing trades" : "—");
            StringBuilder report = new StringBuilder();
            report.append("Closed trades: ").append(total)
                    .append("\nWinning trades: ").append(wins)
                    .append("\nLosing trades: ").append(losses)
                    .append("\nBreak-even trades: ").append(breakeven)
                    .append("\nWin rate: ").append(String.format(Locale.US, "%.1f%%", winRate))
                    .append("\nAverage winning trade: ").append(money(averageWin))
                    .append("\nAverage losing trade: ").append(money(averageLoss))
                    .append("\nBest closed trade: ").append(money(best))
                    .append("\nWorst closed trade: ").append(money(worst))
                    .append("\nGross simulated profit: ").append(money(grossProfit))
                    .append("\nGross simulated loss: ").append(money(grossLoss))
                    .append("\nProfit factor: ").append(profitFactor)
                    .append("\n\nPER-COIN BREAKDOWN");
            Cursor bySymbol = db.rawQuery(
                    "SELECT symbol,COUNT(*)," +
                    "SUM(CASE WHEN realized_pnl > 0 THEN 1 ELSE 0 END)," +
                    "COALESCE(SUM(realized_pnl),0),COALESCE(AVG(realized_pnl),0) " +
                    "FROM paper_trades WHERE status='CLOSED' GROUP BY symbol ORDER BY symbol ASC",
                    null);
            try {
                while (bySymbol.moveToNext()) {
                    int symbolTrades = bySymbol.getInt(1);
                    int symbolWins = bySymbol.getInt(2);
                    double symbolWinRate = symbolTrades > 0 ? symbolWins * 100.0 / symbolTrades : 0.0;
                    report.append("\n\n").append(bySymbol.getString(0))
                            .append(" · ").append(symbolTrades).append(" closed")
                            .append("\nWin rate: ").append(String.format(Locale.US, "%.1f%%", symbolWinRate))
                            .append("\nNet simulated P/L: ").append(money(bySymbol.getDouble(3)))
                            .append("\nAverage P/L per trade: ").append(money(bySymbol.getDouble(4)));
                }
            } finally { bySymbol.close(); }
            report.append("\n\nStatistics use all closed virtual trades and include recorded net P/L after simulated costs. They are historical summaries, not predictions.");
            return report.toString();
        } finally { c.close(); }
    }

    private String loadHistory(SQLiteDatabase db) {
        String where = "ALL".equals(historyFilter) ? "" : " WHERE status=?";
        Cursor c = db.rawQuery(
                "SELECT id,symbol,status,quantity,entry_price,exit_price,stop_loss," +
                "realized_pnl,opened_at,closed_at FROM paper_trades" + where +
                " ORDER BY opened_at DESC LIMIT 100",
                "ALL".equals(historyFilter) ? null : new String[]{historyFilter});
        StringBuilder b = new StringBuilder();
        int count = 0;
        java.text.SimpleDateFormat dateFormat =
                new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
        try {
            while (c.moveToNext()) {
                count++;
                if (b.length() > 0) b.append("\n\n");
                String status = c.getString(2);
                b.append("#").append(c.getLong(0)).append("  ·  ")
                        .append(c.getString(1)).append("  ·  ").append(status)
                        .append("\nOpened: ").append(dateFormat.format(new java.util.Date(c.getLong(8))))
                        .append("\nQuantity: ").append(fmt(c.getDouble(3), 6))
                        .append("\nEntry: ").append(money(c.getDouble(4)))
                        .append("\nStop-loss: ").append(c.getDouble(6) > 0
                                ? money(c.getDouble(6)) : "Not set");
                if (!c.isNull(5)) b.append("\nExit: ").append(money(c.getDouble(5)));
                if ("CLOSED".equals(status)) {
                    b.append("\nNet P/L: ").append(money(c.getDouble(7)));
                    if (!c.isNull(9))
                        b.append("\nClosed: ").append(dateFormat.format(new java.util.Date(c.getLong(9))));
                }
            }
        } finally { c.close(); }
        if (count == 0) return "No " + historyFilter.toLowerCase(Locale.US) +
                " paper trades recorded yet.";
        String header = "Showing " + count + (count == 100 ? " (latest 100)" : "") +
                " · Filter: " + historyFilter;
        return header + "\n\n" + b.toString();
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
        boolean depleted = database.isVirtualCashDepleted();
        autoButton.setEnabled(!depleted || autoTrader.isEnabled());
        autoButton.setText(depleted ? "Virtual cash depleted · reset to restart" :
                (autoTrader.isEnabled()
                ? "Turn automatic paper trading OFF"
                : "Turn automatic paper trading ON"));
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
