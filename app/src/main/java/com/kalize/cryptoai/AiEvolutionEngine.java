package com.kalize.cryptoai;

import java.util.Locale;

/**
 * Evidence gate for continuous AI evolution. The active learner keeps training
 * online; this class checks walk-forward results before any future challenger
 * model or strategy is allowed to replace it. Simulation only.
 */
public final class AiEvolutionEngine {
    private static final String[] SYMBOLS = new String[]{"BTCUSDT", "ETHUSDT", "SOLUSDT"};
    private static final int MIN_VALIDATION_SAMPLES = 100;
    private static final double MIN_BASELINE_EDGE = 2.0;
    private static final double MAX_CALIBRATION_ERROR = 15.0;

    private AiEvolutionEngine() { }

    public static String buildReport(OnDeviceLearningEngine learner) {
        if (learner == null) return "Evolution monitor unavailable.";
        StringBuilder out = new StringBuilder();
        int ready = 0;
        int tested = 0;
        for (int i = 0; i < SYMBOLS.length; i++) {
            OnDeviceLearningEngine.Validation v =
                    learner.validateWalkForward(SYMBOLS[i], "1m");
            if (i > 0) out.append("\n\n");
            out.append(SYMBOLS[i].replace("USDT", "/USDT"));
            if (v.samples == 0) {
                out.append("\nStatus: collecting enough history for a fair replay");
                continue;
            }
            tested++;
            double edge = v.balancedAccuracy - v.baselineAccuracy;
            boolean enough = v.samples >= MIN_VALIDATION_SAMPLES;
            boolean better = edge >= MIN_BASELINE_EDGE;
            boolean calibrated = v.calibrationError <= MAX_CALIBRATION_ERROR;
            boolean candidateReady = enough && better && calibrated;
            if (candidateReady) ready++;
            out.append("\nWalk-forward examples: ").append(v.samples)
                    .append("\nBalanced score: ").append(String.format(Locale.US, "%.1f%%", v.balancedAccuracy))
                    .append("\nBaseline difference: ").append(String.format(Locale.US, "%+.1f points", edge))
                    .append("\nConfidence calibration error: ").append(String.format(Locale.US, "%.1f%%", v.calibrationError))
                    .append("\nEvolution gate: ").append(candidateReady ? "Evidence threshold met" :
                            (!enough ? "Need more unseen test examples" :
                            (!better ? "No proven improvement over baseline" :
                            "Confidence needs better calibration")));
        }
        out.append("\n\nMODE: CONTINUOUS LEARNING");
        out.append("\nThe active model keeps learning from new completed candles and paper-trade outcomes.");
        out.append("\nPromotion rule: a future challenger must have at least ")
                .append(MIN_VALIDATION_SAMPLES)
                .append(" walk-forward examples, beat the simple baseline by ")
                .append(String.format(Locale.US, "%.0f", MIN_BASELINE_EDGE))
                .append(" points, and keep calibration error at or below ")
                .append(String.format(Locale.US, "%.0f%%", MAX_CALIBRATION_ERROR)).append(".");
        out.append("\nCurrent evidence: ").append(ready).append(" of ").append(tested)
                .append(" tested markets meet the gate.");
        out.append("\nNo strategy is silently replaced. Candidate changes must first pass replay and paper-only observation.");
        out.append("\nThis monitor cannot guarantee profits or eliminate losses.");
        return out.toString();
    }
}
