# Kalize Crypto AI Lite

A lightweight Android paper-trading and market research app initially targeting the Coolpad 3300A (Android 4.4 / API 19).

## Goals
- Virtual starting balance: **$100 USD**
- Historical candlestick window: **30 days**
- Public cryptocurrency market data, subject to device/network compatibility
- Paper trading only: no real exchange order placement or real-money credentials
- Simulated trading fees, spread and slippage
- Risk limits and emergency pause
- Local SQLite storage with automatic retention cleanup
- Lightweight statistical strategy evaluation; no large on-device AI model

## Device constraints
The initial target is Android 4.4 (API 19), with limited RAM and internal storage. The app uses native Java and Android SDK APIs, avoids AndroidX and large ML dependencies, and keeps market history bounded. Actual performance and secure HTTPS/API connectivity must be tested on the phone.

## Build
GitHub Actions builds the APK. Open **Actions**, select **Build Android APK**, and download the APK artifact from a successful workflow run.

Build environment: JDK 17, Gradle 8.7, Android Gradle Plugin 8.6.1. App minimum SDK: 19.

## Safety
This is a research and paper-trading project. It does not submit real exchange orders. Simulated results do not guarantee future performance or prevent losses in real markets.

## Initial status
The foundation commit adds the Android project, starter dashboard and CI workflow. Market-data ingestion, candle storage, fee-aware simulation, backtesting and strategy evaluation are planned follow-on work.
