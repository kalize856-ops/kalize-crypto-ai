import unittest
from kalize_analytics import summarize_closed_trades


class PerformanceAnalyticsTests(unittest.TestCase):
    def test_metrics_and_drawdown(self):
        result = summarize_closed_trades(
            [{"pnl": 10}, {"pnl": -5}, {"pnl": 5}], starting_capital=100
        )
        self.assertEqual(result["closed_trades"], 3)
        self.assertAlmostEqual(result["win_rate_pct"], 200 / 3)
        self.assertAlmostEqual(result["net_pnl"], 10)
        self.assertAlmostEqual(result["ending_equity"], 110)
        self.assertAlmostEqual(result["max_drawdown_pct"], 5 / 110 * 100)

    def test_empty_journal_is_safe(self):
        result = summarize_closed_trades([], starting_capital=100)
        self.assertEqual(result["closed_trades"], 0)
        self.assertEqual(result["win_rate_pct"], 0)
        self.assertEqual(result["equity_curve"], [100.0])

    def test_rejects_invalid_pnl(self):
        with self.assertRaises(ValueError):
            summarize_closed_trades([{"pnl": float("nan")}])

    def test_rejects_bad_starting_capital(self):
        with self.assertRaises(ValueError):
            summarize_closed_trades([], starting_capital=0)


if __name__ == "__main__":
    unittest.main()
