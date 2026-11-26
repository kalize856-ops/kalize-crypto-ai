import json
import logging
import time
from datetime import datetime, timezone
from pathlib import Path

import ccxt
import pandas as pd

# ============================================================
# KALIZE BTC/USDT PAPER-TRADING BOT
# Public market data only. No real orders are submitted.
# ============================================================

SYMBOL = "BTC/USDT"
TIMEFRAME = "1m"

STARTING_CAPITAL = 100.0
RISK_PERCENT = 0.01
STOP_LOSS_PERCENT = 0.02
TAKE_PROFIT_PERCENT = 0.04
TRADING_FEE = 0.001  # Simulated 0.1% per side

RSI_PERIOD = 14
EMA_FAST = 20
EMA_SLOW = 50
CHECK_INTERVAL = 60

STATE_FILE = Path("paper_state.json")

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s | %(levelname)s | %(message)s",
    handlers=[
        logging.StreamHandler(),
        logging.FileHandler("kalize_bot.log", encoding="utf-8"),
    ],
)
log = logging.getLogger("KALIZE")

exchange = ccxt.binance({
    "enableRateLimit": True,
    "timeout": 15000,
})


def load_state():
    if not STATE_FILE.exists():
        return {
            "cash": STARTING_CAPITAL,
            "position": None,
            "realized_pnl": 0.0,
            "trades": 0,
        }

    try:
        with STATE_FILE.open("r", encoding="utf-8") as file:
            state = json.load(file)

        if not isinstance(state, dict):
            raise ValueError("State must be a JSON object")
        if not {"cash", "position", "realized_pnl", "trades"} <= state.keys():
            raise ValueError("Account state is missing required fields")
        if not isinstance(state["cash"], (int, float)) or state["cash"] < 0:
            raise ValueError("Invalid cash balance")

        return state
    except (OSError, ValueError, TypeError, json.JSONDecodeError) as error:
        log.exception("Cannot safely load paper account state")
        raise RuntimeError(
            "paper_state.json needs inspection; refusing to reset the balance"
        ) from error


account = load_state()


def save_state():
    temporary_file = STATE_FILE.with_suffix(".tmp")
    with temporary_file.open("w", encoding="utf-8") as file:
        json.dump(account, file, indent=4)
    temporary_file.replace(STATE_FILE)


def get_equity(price):
    position = account["position"]
    if position:
        return account["cash"] + position["amount"] * price
    return account["cash"]


def calculate_indicators(candles):
    df = pd.DataFrame(
        candles,
        columns=["timestamp", "open", "high", "low", "close", "volume"],
    )

    df["ema20"] = df["close"].ewm(span=EMA_FAST, adjust=False).mean()
    df["ema50"] = df["close"].ewm(span=EMA_SLOW, adjust=False).mean()

    delta = df["close"].diff()
    gains = delta.clip(lower=0)
    losses = -delta.clip(upper=0)

    avg_gain = gains.ewm(
        alpha=1 / RSI_PERIOD, adjust=False, min_periods=RSI_PERIOD
    ).mean()
    avg_loss = losses.ewm(
        alpha=1 / RSI_PERIOD, adjust=False, min_periods=RSI_PERIOD
    ).mean()

    rs = avg_gain / avg_loss.replace(0, float("nan"))
    df["rsi"] = 100 - (100 / (1 + rs))
    df.loc[(avg_loss == 0) & (avg_gain > 0), "rsi"] = 100
    df.loc[(avg_gain == 0) & (avg_loss > 0), "rsi"] = 0

    return df


def get_market_data():
    candles = exchange.fetch_ohlcv(SYMBOL, timeframe=TIMEFRAME, limit=100)
    if len(candles) < 60:
        raise RuntimeError("Not enough candle history")

    # Ignore the currently forming candle for indicator signals.
    df = calculate_indicators(candles[:-1])
    latest = df.iloc[-1]

    if pd.isna(latest["rsi"]):
        raise RuntimeError("RSI is not ready")

    ticker = exchange.fetch_ticker(SYMBOL)
    price = ticker.get("last")
    if price is None or price <= 0:
        raise RuntimeError("Exchange returned an invalid BTC price")

    return {
        "price": float(price),
        "rsi": float(latest["rsi"]),
        "ema20": float(latest["ema20"]),
        "ema50": float(latest["ema50"]),
        "candle_time": int(latest["timestamp"]),
    }


def paper_buy(price):
    if account["position"] is not None:
        return

    equity = get_equity(price)
    risk_amount = equity * RISK_PERCENT
    stop_price = price * (1 - STOP_LOSS_PERCENT)
    take_price = price * (1 + TAKE_PROFIT_PERCENT)

    risk_per_btc = price - stop_price
    amount = risk_amount / risk_per_btc

    # Never spend more than available virtual cash, including entry fee.
    maximum_amount = account["cash"] / (price * (1 + TRADING_FEE))
    amount = min(amount, maximum_amount)

    if amount <= 0:
        log.warning("Insufficient virtual cash; buy skipped")
        return

    notional = amount * price
    entry_fee = notional * TRADING_FEE
    total_cost = notional + entry_fee
    account["cash"] -= total_cost

    account["position"] = {
        "amount": amount,
        "entry_price": price,
        "entry_fee": entry_fee,
        "entry_cost": total_cost,
        "stop_loss": stop_price,
        "take_profit": take_price,
        "entry_time": datetime.now(timezone.utc).isoformat(),
    }
    save_state()

    log.info(
        "PAPER BUY | amount=%.8f BTC | entry=%.2f | stop=%.2f | "
        "target=%.2f | planned_risk=%.2f USDT",
        amount, price, stop_price, take_price, risk_amount,
    )


def paper_sell(price, reason):
    position = account["position"]
    if position is None:
        return

    proceeds = position["amount"] * price
    exit_fee = proceeds * TRADING_FEE
    net_proceeds = proceeds - exit_fee
    pnl = net_proceeds - position["entry_cost"]

    account["cash"] += net_proceeds
    account["realized_pnl"] += pnl
    account["trades"] += 1
    account["position"] = None
    save_state()

    log.info(
        "PAPER SELL | reason=%s | price=%.2f | net_pnl=%+.4f USDT | "
        "cash=%.2f USDT",
        reason, price, pnl, account["cash"],
    )


def process_market(market):
    price = market["price"]
    rsi = market["rsi"]
    ema20 = market["ema20"]
    ema50 = market["ema50"]

    log.info(
        "BTC=%.2f | RSI=%.2f | EMA20=%.2f | EMA50=%.2f",
        price, rsi, ema20, ema50,
    )

    position = account["position"]
    if position:
        if price <= position["stop_loss"]:
            paper_sell(price, "STOP LOSS")
        elif price >= position["take_profit"]:
            paper_sell(price, "TAKE PROFIT")
        elif rsi > 70:
            paper_sell(price, "RSI OVERBOUGHT")
        else:
            log.info("Holding simulated BTC position")
        return

    if rsi < 30 and ema20 > ema50:
        log.info("BUY SIGNAL CONFIRMED")
        paper_buy(price)
    elif rsi < 30:
        log.info("BUY BLOCKED: EMA20 has not confirmed bullish trend")
    else:
        log.info("No entry signal; waiting")


def main():
    log.info("KALIZE BTC/USDT PAPER BOT STARTED")
    log.info(
        "Mode=PAPER | Capital=%.2f USDT | Risk=%.1f%% | Interval=%ss",
        STARTING_CAPITAL, RISK_PERCENT * 100, CHECK_INTERVAL,
    )

    exchange.load_markets()
    if SYMBOL not in exchange.markets:
        raise RuntimeError(f"{SYMBOL} market is unavailable")

    last_processed_candle = None

    while True:
        cycle_start = time.monotonic()
        try:
            market = get_market_data()
            position_exists = account["position"] is not None

            # Manage open positions on every polling cycle.
            if position_exists:
                process_market(market)
            elif market["candle_time"] != last_processed_candle:
                last_processed_candle = market["candle_time"]
                process_market(market)
            else:
                log.info("Waiting for the next completed one-minute candle")

            log.info(
                "ACCOUNT | cash=%.2f USDT | equity=%.2f USDT | "
                "realized_pnl=%+.4f USDT | closed_trades=%d",
                account["cash"],
                get_equity(market["price"]),
                account["realized_pnl"],
                account["trades"],
            )

        except ccxt.NetworkError as error:
            log.warning("Network problem: %s", error)
        except ccxt.ExchangeError as error:
            log.error("Exchange error: %s", error)
        except Exception:
            log.exception("Unexpected bot error")

        elapsed = time.monotonic() - cycle_start
        time.sleep(max(1, CHECK_INTERVAL - elapsed))


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        log.info("Bot stopped by user")
    finally:
        exchange.close()
