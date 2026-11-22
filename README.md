# Kalize Crypto AI Lite

A lightweight Android paper-trading research app. It is a simulator only: it does not connect to exchange accounts, hold funds, or submit real orders.

## Compatibility
- Minimum Android version: 4.4 / API 19.
- Built with Java 8 language compatibility.
- Newer Android versions are supported by the same APK.
- Android 4.4 devices may have trouble connecting to modern HTTPS services because their TLS and certificate support is old. The app will not disable certificate checks to work around this.

## Current foundation
- Local SQLite database.
- $100 simulated starting wallet, initialized once and retained between launches.
- Candle table for OHLCV market history, with duplicate candle replacement.
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
