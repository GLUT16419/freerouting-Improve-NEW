package app.freerouting.core.scoring;

import app.freerouting.settings.RouterScoringSettings;

/**
 * An immutable, per-component breakdown of a single board-score calculation.
 *
 * <p>Use {@link app.freerouting.core.scoring.ScoringWeightComparison} to produce instances, or call
 * {@link #of(BoardStatistics, RouterScoringSettings)} directly.
 *
 * <p>The score formula is:
 * <pre>
 *   rawScore = maximumScore
 *            - unroutedConnectionsPenalty
 *            - clearanceViolationsPenalty
 *            - bendsPenalty
 *            - traceLengthCost
 *            - viasCost
 * </pre>
 *
 * <p>The {@link #normalizedScore} maps {@code rawScore} onto the {@code [0, 1000]} range, where
 * 1000 represents a fully-routed board with no vias and no traces (the theoretical maximum).
 * Scores below zero are clamped to zero.
 */
public final class BoardScoreBreakdown {

  /** The theoretical maximum score: {@code maximumConnections × unroutedNetPenalty}. */
  public final float maximumScore;

  /**
   * Penalty contribution from unrouted connections.
   * Equals {@code signalIncomplete × unroutedNetPenalty + powerIncomplete × powerNetPenalty + groundIncomplete × groundNetPenalty}.
   */
  public final float unroutedConnectionsPenalty;

  /**
   * Penalty contribution from DRC clearance violations.
   * Equals {@code violationCount × clearanceViolationPenalty}.
   */
  public final float clearanceViolationsPenalty;

  /**
   * Penalty contribution from trace bends (direction-change corners).
   * Equals {@code bendCount × bendPenalty}.
   */
  public final float bendsPenalty;

  /**
   * Absolute cost contribution from trace length.
   * Equals {@code totalLengthMm × defaultPreferredDirectionTraceCost}.
   */
  public final float traceLengthCost;

  /**
   * Absolute cost contribution from via count.
   * Equals {@code viaCount × viaCosts}.
   */
  public final float viasCost;

  /** Sum of all penalty terms. */
  public final float totalPenalties;

  /** Sum of all cost terms. */
  public final float totalCosts;

  /**
   * Raw (un-normalised) score.
   * {@code rawScore = maximumScore - totalPenalties - totalCosts}
   */
  public final float rawScore;

  /**
   * Score mapped to the {@code [0, 1000]} range.
   * {@code normalizedScore = max(0, rawScore / maximumScore) × 1000}
   */
  public final float normalizedScore;

  /** The weight configuration used to compute this breakdown. */
  public final RouterScoringSettings weights;

  // Statistics used as input — kept for reference / formatted output.
  public final int maxConnections;
  public final int incompleteConnections;
  /** Signal-net incomplete connections (full penalty). */
  public final int signalIncompleteConnections;
  /** Power-net incomplete connections (reduced penalty). */
  public final int powerIncompleteConnections;
  /** Ground-net incomplete connections (zero penalty when fanout is sufficient). */
  public final int groundIncompleteConnections;
  public final int clearanceViolations;
  public final int bendCount;
  public final float totalTraceLengthMm;
  public final int viaCount;

  private BoardScoreBreakdown(
      RouterScoringSettings weights,
      int maxConnections,
      int incompleteConnections,
      int signalIncomplete,
      int powerIncomplete,
      int groundIncomplete,
      int clearanceViolations,
      int bendCount,
      float totalTraceLengthMm,
      int viaCount) {

    this.weights = weights;
    this.maxConnections = maxConnections;
    this.incompleteConnections = incompleteConnections;
    this.signalIncompleteConnections = signalIncomplete;
    this.powerIncompleteConnections = powerIncomplete;
    this.groundIncompleteConnections = groundIncomplete;
    this.clearanceViolations = clearanceViolations;
    this.bendCount = bendCount;
    this.totalTraceLengthMm = totalTraceLengthMm;
    this.viaCount = viaCount;

    this.maximumScore = maxConnections * weights.unroutedNetPenalty;
    float powerPenaltyPer = weights.powerNetPenalty != null
        ? weights.powerNetPenalty : weights.unroutedNetPenalty;
    float groundPenaltyPer = weights.groundNetPenalty != null
        ? weights.groundNetPenalty : 0f;
    this.unroutedConnectionsPenalty = signalIncomplete * weights.unroutedNetPenalty
        + powerIncomplete * powerPenaltyPer
        + groundIncomplete * groundPenaltyPer;
    this.clearanceViolationsPenalty = clearanceViolations * weights.clearanceViolationPenalty;
    this.bendsPenalty = bendCount * weights.bendPenalty;
    this.traceLengthCost = (float) (totalTraceLengthMm * weights.defaultPreferredDirectionTraceCost);
    this.viasCost = viaCount * weights.viaCosts;

    this.totalPenalties = unroutedConnectionsPenalty + clearanceViolationsPenalty + bendsPenalty;
    this.totalCosts = traceLengthCost + viasCost;

    this.rawScore = maximumScore - totalPenalties - totalCosts;
    this.normalizedScore = maximumScore > 0
        ? Math.max(0f, rawScore / maximumScore) * 1000f
        : 0f;
  }

  /**
   * Computes a {@code BoardScoreBreakdown} for {@code stats} using {@code weights}.
   *
   * @param stats   board statistics (must not be null; lengths assumed to be in millimetres)
   * @param weights scoring weight configuration (must not be null, all scoring fields must be set)
   * @return a fully populated breakdown
   * @throws NullPointerException     if either argument is null
   * @throws IllegalArgumentException if a required weight field is null
   */
  public static BoardScoreBreakdown of(BoardStatistics stats, RouterScoringSettings weights) {
    if (stats == null) {
      throw new NullPointerException("stats must not be null");
    }
    if (weights == null) {
      throw new NullPointerException("weights must not be null");
    }
    validateWeights(weights);

    int totalIncomplete = stats.connections.incompleteCount != null
        ? stats.connections.incompleteCount : 0;
    int sigInc = stats.connections.signalIncompleteCount != null
        ? stats.connections.signalIncompleteCount : totalIncomplete;
    int pwrInc = stats.connections.powerIncompleteCount != null
        ? stats.connections.powerIncompleteCount : 0;
    int gndInc = stats.connections.groundIncompleteCount != null
        ? stats.connections.groundIncompleteCount : 0;

    return new BoardScoreBreakdown(
        weights,
        stats.connections.maximumCount != null ? stats.connections.maximumCount : 0,
        totalIncomplete,
        sigInc,
        pwrInc,
        gndInc,
        stats.clearanceViolations.totalCount != null ? stats.clearanceViolations.totalCount : 0,
        stats.bends.totalCount != null ? stats.bends.totalCount : 0,
        stats.traces.totalLength != null ? stats.traces.totalLength : 0f,
        stats.vias.totalCount != null ? stats.vias.totalCount : 0);
  }

  /**
   * Returns a concise human-readable summary of this breakdown, useful for logging
   * and test output.
   */
  public String toSummaryString() {
    if (powerIncompleteConnections > 0 || groundIncompleteConnections > 0) {
      return String.format(
          "score=%.1f/1000 (raw=%.0f/%.0f) | "
              + "unrouted=signal:%d×%.0f + power:%d×%.0f + gnd:%d×%.0f =%.0f | "
              + "violations=%d×%.0f=%.0f | bends=%d×%.1f=%.0f | "
              + "length=%.1fmm×%.2f=%.0f | vias=%d×%.0f=%.0f",
          normalizedScore, rawScore, maximumScore,
          signalIncompleteConnections, weights.unroutedNetPenalty,
          powerIncompleteConnections,
          weights.powerNetPenalty != null ? weights.powerNetPenalty : weights.unroutedNetPenalty,
          groundIncompleteConnections,
          weights.groundNetPenalty != null ? weights.groundNetPenalty : 0f,
          unroutedConnectionsPenalty,
          clearanceViolations, weights.clearanceViolationPenalty, clearanceViolationsPenalty,
          bendCount, weights.bendPenalty, bendsPenalty,
          totalTraceLengthMm, weights.defaultPreferredDirectionTraceCost, traceLengthCost,
          viaCount, (double) weights.viaCosts, viasCost);
    }
    // Fallback: simple format when all incompletes are signal
    return String.format(
        "score=%.1f/1000 (raw=%.0f/%.0f) | "
            + "unrouted=%d×%.0f=%.0f | violations=%d×%.0f=%.0f | bends=%d×%.1f=%.0f | "
            + "length=%.1fmm×%.2f=%.0f | vias=%d×%.0f=%.0f",
        normalizedScore, rawScore, maximumScore,
        signalIncompleteConnections, weights.unroutedNetPenalty, unroutedConnectionsPenalty,
        clearanceViolations, weights.clearanceViolationPenalty, clearanceViolationsPenalty,
        bendCount, weights.bendPenalty, bendsPenalty,
        totalTraceLengthMm, weights.defaultPreferredDirectionTraceCost, traceLengthCost,
        viaCount, (double) weights.viaCosts, viasCost);
  }

  private static void validateWeights(RouterScoringSettings w) {
    if (w.unroutedNetPenalty == null) {
      throw new IllegalArgumentException("weights.unroutedNetPenalty must not be null");
    }
    if (w.clearanceViolationPenalty == null) {
      throw new IllegalArgumentException("weights.clearanceViolationPenalty must not be null");
    }
    if (w.bendPenalty == null) {
      throw new IllegalArgumentException("weights.bendPenalty must not be null");
    }
    if (w.defaultPreferredDirectionTraceCost == null) {
      throw new IllegalArgumentException("weights.defaultPreferredDirectionTraceCost must not be null");
    }
    if (w.viaCosts == null) {
      throw new IllegalArgumentException("weights.viaCosts must not be null");
    }
  }
}