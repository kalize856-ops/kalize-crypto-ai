# Kalize Crypto AI Lite

A lightweight Android paper-trading research app. It is a simulator only: it does not connect to exchange accounts, hold funds, or submit real orders.

## Compatibility
- Minimum Android version: 4.4 / API 19.
- Built with Java 8 language compatibility.
- Newer Android versions are supported by the same APK.
- Android 4.4 devices may have trouble connecting to modern HTTPS services because their TLS and certificate support is old. The app will not disable certificate checks to work around this.

## Dashboard layout
- Opens in landscape orientation for a wide trading-style workspace.
- Uses two side-by-side columns on wider displays, with a single-column fallback on compact screens.
- Market charts and virtual portfolio are grouped separately from AI diagnostics, paper-trade history and simulation controls.

## Current foundation
- Local SQLite database.
- $100 simulated starting wallet, initialized once and retained between launches.
- Candle table for OHLCV market history, with duplicate candle replacement.
- Separate WebSocket ticker streams show live last price, 24-hour change, high, low and quote volume for BTC, ETH and SOL.
- Interactive candlestick chart with 1m, 5m, 15m, 1h, 4h and 1d views aggregated from completed 1-minute candles.
- Selectable chart history windows from 15 minutes to 1 hour; the chart displays up to 300 recent aggregated candles.
- Automatic cleanup of candles older than the latest 1 hour.
- Paper-trade ledger table and app-settings table.
- On-device incremental learning from completed 1-minute candles and simulated trade outcomes, with paper trading allowed to run while learning continues.
- Optional automatic AI paper trading, OFF by default, with local-only simulated entries and exits.
- Simulated risk limits: up to 1% estimated risk per trade, 2% daily loss pause, and 25% maximum position size.
- Real-money trading and exchange order submission are intentionally not included.

## Automatic paper trading
The dashboard includes an explicit ON/OFF control. It is OFF by default. When enabled, the app evaluates each completed candle and may open a virtual position only when the learned model has sufficient samples and an upward-bias signal. It closes virtual positions when the stop level is reached or the model reports downward bias. Trading decisions are educational simulations, not investment advice. No exchange credentials, real-money balances, or order endpoints are used.

## Build
GitHub Actions builds a debug APK using JDK 17 and Gradle 8.7. Open the Actions tab and download the `kalize-crypto-ai-lite-debug` artifact after a successful run.

## Important
This is an educational simulator. Backtests and paper results do not predict or guarantee future market results.


## Finite virtual wallet (0.5.4)
- Simulated purchases, fees and realized losses reduce the finite virtual cash balance.
- The simulator never silently tops up or resets the wallet.
- When virtual cash reaches zero, automatic paper trading is disabled and new positions are blocked.
- The dashboard provides an explicit **Reset virtual account** action. It warns that resetting clears simulated positions and trade history and restores the wallet to $100; market candles and AI learning data are preserved.
- Educational simulation only; no real funds, exchange accounts or live orders.


## Automatic stop-loss monitoring (0.5.5)
- Every completed 1-minute candle checks stop-loss levels for all open simulated positions, including manually opened positions.
- Monitoring continues even when automatic AI paper trading is switched off.
- If the candle low crosses a stop, the simulator closes the virtual position. If the candle closes below the stop, the close is modeled at the worse candle-close reference, with simulated slippage and fees.
- This uses completed candle OHLC data; it is an educational approximation and cannot reproduce every intraminute execution detail.


## Portfolio drawdown tracking (0.5.6)
- The dashboard records the highest observed total virtual equity (high-water mark) in local settings.
- It displays the current dollar drawdown and percentage below that peak, including unrealized position value marked from the latest stored candle.
- The high-water mark survives app restarts and resets to $100 only when the user explicitly resets the virtual account.
- This is a historical simulator metric, not a forecast or a measure of real funds.


## Filterable paper-trade journal (0.5.7)
- Journal filters show all trades, currently open positions, or closed positions.
- Each journal entry includes its local trade ID, market pair, status, open time, quantity, entry price and stop-loss.
- Closed entries also show exit price, net simulated P/L and close time.
- The journal displays the latest 100 matching entries to keep the dashboard responsive on older Android devices.
- All entries are local virtual records; no real orders or funds are involved.


## Build 0.5.8 — paper-trade performance analytics

- Adds an all-time closed-trade performance report: total wins/losses/break-even trades, win rate, average win/loss, best/worst trade, gross simulated profit/loss, and profit factor.
- Statistics are calculated from the local paper-trade ledger using recorded net realized P/L after simulated costs. No live orders or real funds are involved.
- Compatible with the existing Android 4.4.4+ target.


## Build 0.5.9 — per-coin paper-trade breakdown

- Extends the performance report with one section per traded symbol, showing closed-trade count, win rate, net simulated P/L, and average P/L per trade.
- Uses only the local virtual trade ledger and recorded net realized P/L after simulated costs. Results are descriptive history, not predictions or trading recommendations.


## Build 0.5.10 — time-filtered performance analytics

- Adds performance-report filters for the last 7 days, last 30 days, and all-time history.
- The overall statistics and per-coin breakdown use the same selected period, based on each closed virtual trade's close timestamp.
- The report refreshes locally when a period is selected; no network request or real-money trading is involved.


### Build 0.5.11 — paper-trade streak analysis
- Adds selected-period current win/loss sequence and longest winning/losing sequences to performance analytics.
- Uses closed virtual trades in chronological order; break-even outcomes reset a sequence.
- Streaks are historical summaries only and are not forecasts or trading recommendations.


### Build 0.5.12 — daily paper-trade performance breakdown
- Adds a daily summary for the selected performance period, showing closed trades, wins and net simulated P/L.
- Displays up to the latest 14 days in the selected period, ordered newest first.
- Uses closed virtual trades only; the report is historical and not a forecast.


### Build 0.5.13 — live market-data health monitor
- Adds a dashboard panel that checks the newest completed 1-minute candle for BTC, ETH and SOL.
- Labels each symbol's feed as fresh (up to 3 minutes), delayed (over 3 and up to 5 minutes), stale (over 5 minutes), or missing.
- Shows the approximate age of each latest candle and a combined status count.
- This is a data-quality warning only; it does not create trading signals or place orders.


### Build 0.5.14 — trade-duration analytics
- Adds average, shortest and longest holding duration to the performance report.
- Uses only completed virtual trades in the currently selected 7-day, 30-day or all-time reporting range.
- Displays durations in days, hours and minutes; this is descriptive history, not a recommendation or prediction.


### Build 0.5.15 — exportable paper-trade journal
- Adds an Export journal as CSV action below the All/Open/Closed journal filters.
- Uses Android's document picker so the user chooses where to save the file (API 19+).
- Exports the selected filter's records with trade ID, symbol, side, quantity, entry/exit prices, stop-loss, realized P/L, fees, status, epoch-millisecond timestamps and AI metadata.
- CSV values are escaped safely; export remains strictly virtual and does not connect to an exchange.


### Build 0.5.16 — recent candle-gap integrity scan
- Extends Market Data Health with a bounded scan of up to the latest 61 one-minute candle timestamps per supported market.
- Reports recent gap events, estimated missing one-minute candles, and approximate age of the latest detected gap.
- Keeps the scan local and read-only; it does not create synthetic candles or treat data gaps as trading signals.
- Older-device impact is bounded to at most 61 timestamp rows per coin during a health refresh.


### Build 0.5.17 — candle-history coverage percentage
- Adds an observed one-minute candle coverage estimate beside each symbol's recent gap scan.
- Coverage is calculated from observed candle timestamps and estimated missing-minute intervals within the bounded scan.
- The percentage describes historical data continuity only; it is not a forecast or trade signal.


### Build 0.5.18 — descriptive recent market conditions
- Adds a separate market-conditions panel for BTCUSDT, ETHUSDT and SOLUSDT.
- Measures average high-low range percentage and average absolute close-to-close percentage change over up to 60 completed one-minute candles.
- Shows the number of candles measured and clearly labels the figures as descriptive historical statistics, not forecasts or trading instructions.
- Uses bounded SQLite reads and refreshes with the existing dashboard/feed updates.


### Build 0.5.26 — duplicate live-candle protection
- Live completed candles are inserted with a conflict-ignore operation instead of replacing an existing candle.
- If the market WebSocket delivers the same symbol, interval and candle timestamp again, the duplicate is ignored before AI learning and paper-trade evaluation run.
- Historical backfill keeps its existing replace behavior so refreshed historical data can still correct previously stored candle values.
- This protects the virtual ledger from processing the same live candle event more than once; it does not guarantee trading performance or predict market outcomes.


## One-hour data window and concurrent paper learning
- Historical backfill and retained one-minute candle data are limited to the latest hour.
- Older candles are cleaned automatically.
- Paper trading can use a provisional model estimate after the seven-candle feature window is available; incremental AI learning continues as new completed candles arrive.
- Entries still require an upward-bias signal and the configured confidence threshold. All activity remains virtual and local.


### Build 0.5.28 — per-market re-entry cooldown
- Adds a five-minute cooldown after an AI-managed simulated position closes, whether it closes from a downward-bias signal or its stop-loss.
- The cooldown is tracked separately for BTCUSDT, ETHUSDT and SOLUSDT and survives app restarts using local app settings.
- Prevents rapid close-and-reopen churn while allowing other markets to be evaluated independently.
- Does not delay stop-loss monitoring or interfere with existing open-position management.
- Paper trading only; no real orders or funds.


### Build 0.5.29 — fresh-candle entry validation
- New simulated positions are paused unless the latest three one-minute candles are present, fresh (latest candle no older than three minutes), and spaced consistently.
- This avoids opening virtual positions from stale or gapped market data after a connection interruption.
- Existing simulated positions continue to be monitored and managed; the validation only gates new entries.
- Paper trading only; no real orders or funds.


### Build 0.5.30 — short-term signal confirmation
- Before opening a new virtual position, the controller checks the latest four completed candles.
- At least two of the latest three candle-to-candle moves must be upward, and the newest close must not show a sharp pullback beyond the configured 0.2% tolerance.
- The check is applied only to new entries; stop-loss monitoring and existing position management continue unchanged.
- This may reduce the number of simulated entries and does not predict outcomes. Paper trading only.


## AI Evolution Mode (0.5.31)
- Adds an AI Evolution Mode dashboard monitor alongside the existing learning report.
- The active on-device learner continues incremental learning from completed candles and AI-tagged paper-trade outcomes.
- Uses walk-forward validation to check whether a future challenger model has enough unseen examples, beats a simple previous-move baseline by at least 2 percentage points in balanced accuracy, and has confidence calibration error no higher than 15%.
- The monitor reports evidence and promotion readiness; it does not silently replace the active model or loosen trading safeguards.
- Any future model/strategy candidate must be tested in replay and paper-only observation before promotion.
- All trading remains simulated. No real exchange orders, funds or credentials are supported, and no performance is guaranteed.


## Build 0.5.32 — exploratory 5% paper-trade threshold
- The provisional on-device model may generate upward/downward bias signals at a 5% confidence margin from 50% (model probability at least 52.5% or at most 47.5%).
- The automatic paper-entry confidence floor is 5%; learning continues while paper evaluation runs.
- Fresh consecutive candles, short-term price confirmation, per-market cooldown, virtual-wallet limits, stop-loss monitoring and daily risk controls remain in place. Therefore 5% confidence does not guarantee an immediate trade.
- This deliberately low threshold can produce many false signals. It is strictly for virtual simulation and is not evidence of a reliable edge. No exchange orders or real funds are supported.
