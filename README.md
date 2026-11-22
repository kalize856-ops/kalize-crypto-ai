# Kalize Crypto AI Lite

A lightweight Android paper-trading and market research app designed to support Android 4.4.4 (API 19) and newer Android versions.

## Compatibility strategy

- **Minimum SDK:** API 19 (Android 4.4 / 4.4.4)
- **Compile SDK:** 35
- **Target SDK:** 28 for the initial legacy-compatible development build
- **Language/runtime:** Java 8 language features, Android framework APIs
- **UI:** Native Android views; no AndroidX dependency in the foundation
- **Version-specific UI:** Base resources for API 19, enhanced resources in `values-v21`
- **Version-specific calls:** Newer framework methods must be protected by `Build.VERSION.SDK_INT` checks

The same APK is intended to install on Android 4.4.4 and newer devices. Features that require newer Android APIs must have a compatible fallback or be disabled on older versions. Runtime compatibility still needs testing on physical devices/emulators across API levels.

## Project goals

- Virtual starting balance: **$100 USD**
- Historical candlestick window: **30 days**
- Public cryptocurrency market data, subject to device/network compatibility
- Paper trading only: no real exchange order placement or real-money credentials
- Simulated trading fees, spread and slippage
- Risk limits and emergency pause
- Local SQLite storage with automatic retention cleanup
- Lightweight statistical strategy evaluation; no large on-device AI model

## Legacy device constraints

The initial target device is the Coolpad 3300A on Android 4.4.4, with limited RAM and internal storage. The app avoids large ML dependencies and keeps market history bounded. Modern HTTPS endpoints may not work reliably on every Android 4.4.4 installation because of old TLS and certificate support; this must be tested, and no security downgrade should be used to force a connection.

## Build

GitHub Actions builds the APK. Open **Actions**, select **Build Android APK**, and download the APK artifact from a successful workflow run.

Build environment: JDK 17, Gradle 8.7, Android Gradle Plugin 8.6.1.

## Safety

This is a research and paper-trading project. It does not submit real exchange orders. Simulated results do not guarantee future performance or prevent losses in real markets.

## Initial status

The foundation adds the Android project, starter dashboard and CI workflow. Market-data ingestion, candle storage, fee-aware simulation, backtesting and strategy evaluation are follow-on work.
