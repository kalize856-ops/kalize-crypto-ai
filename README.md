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
- Selectable history windows from 1 hour to 30 days; the chart displays up to 300 recent aggregated candles.
- Automatic cleanup of candles older than the latest 30 days.
- Paper-trade ledger table and app-settings table.
- On-device incremental learning from completed 1-minute candles and simulated trade outcomes.
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
