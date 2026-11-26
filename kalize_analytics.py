"""Performance analytics for Kalize BTC paper-trading journals.

This module is side-effect free: it never places orders or changes balances.
Pass closed-trade records containing a numeric pnl field.
"""
import math


def summarize_closed_trades(trades, starting_capital=100.0):
    """Return auditable metrics from closed trades in chronological order."""
    starting_capital = float(starting_capital)
    if not math.isfinite(starting_capital) or starting_capital <= 0:
        raise ValueError("starting_capital must be a positive finite number")

    pnls = []
    for trade in trades:
        try:
            pnl = float(trade["pnl"])
        except (KeyError, TypeError, ValueError) as exc:
            raise ValueError("each trade must contain a numeric pnl field") from exc
        if not math.isfinite(pnl):
            raise ValueError("trade PnL values must be finite")
        pnls.append(pnl)

    equity = starting_capital
    peak = equity
    max_drawdown = 0.0
    curve = [equity]
    for pnl in pnls:
        equity += pnl
        peak = max(peak, equity)
        drawdown = (peak - equity) / peak if peak > 0 else 0.0
        max_drawdown = max(max_drawdown, drawdown)
        curve.append(equity)

    wins = [p for p in pnls if p > 0]
    losses = [p for p in pnls if p < 0]
    gross_profit = sum(wins)
    gross_loss = abs(sum(losses))
    count = len(pnls)
    return {
        "closed_trades": count,
        "wins": len(wins),
        "losses": len(losses),
        "win_rate_pct": 100.0 * len(wins) / count if count else 0.0,
        "net_pnl": sum(pnls),
        "gross_profit": gross_profit,
        "gross_loss": gross_loss,
        "profit_factor": gross_profit / gross_loss if gross_loss else (float("inf") if gross_profit else 0.0),
        "expectancy_per_trade": sum(pnls) / count if count else 0.0,
        "ending_equity": equity,
        "return_pct": 100.0 * (equity - starting_capital) / starting_capital,
        "max_drawdown_pct": 100.0 * max_drawdown,
        "equity_curve": curve,
    }
