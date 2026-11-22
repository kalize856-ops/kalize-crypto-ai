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
- Automatic cleanup of candles older than the latest 30 days.
- Paper-trade ledger table and app-settings table.
- Real-money trading and exchange order submission are intentionally not included.

## Build
GitHub Actions builds a debug APK using JDK 17 and Gradle 8.7. Open the Actions tab and download the `kalize-crypto-ai-lite-debug` artifact after a successful run.

## Important
This is an educational simulator. Backtests and paper results do not predict or guarantee future market results.
