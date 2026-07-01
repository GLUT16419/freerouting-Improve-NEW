package app.freerouting.autoroute;

import app.freerouting.logger.FRLogger;
import java.io.Serializable;

/**
 * UTPR V7 — Degradation Event Record.
 * <p>
 * Records a single degradation step for a net or group, capturing
 * the transition from one degradation level to another, the reason,
 * and the phase in which it occurred. Used by
 * {@link GracefulDegradationManager} for logging and reporting.
 */
public class DegradationEvent implements Serializable {

  private static final long serialVersionUID = 1L;

  // ═══════════════════════════════════════════════════════════════════════
  //  Fields
  // ═══════════════════════════════════════════════════════════════════════

  /** The net number affected (only relevant if not part of a group). */
  private final int netNumber;

  /** The group ID affected (or -1 if this is a per-net degradation). */
  private final int groupId;

  /** The degradation level before this event. */
  private final NetClass.DegradationLevel fromLevel;

  /** The degradation level after this event. */
  private final NetClass.DegradationLevel toLevel;

  /** Human-readable reason for the degradation. */
  private final String reason;

  /** Phase ID in which the degradation occurred (0…3). */
  private final int phaseId;

  /** Whether the user can override this degradation. */
  private final boolean isUserOverride;

  /** Timestamp (ms offset from pipeline start, for ordering). */
  private final long timestampMs;

  // ═══════════════════════════════════════════════════════════════════════
  //  Construction
  // ═══════════════════════════════════════════════════════════════════════

  public DegradationEvent(int netNumber, int groupId,
                          NetClass.DegradationLevel fromLevel,
                          NetClass.DegradationLevel toLevel,
                          String reason, int phaseId,
                          boolean isUserOverride) {
    this.netNumber = netNumber;
    this.groupId = groupId;
    this.fromLevel = fromLevel;
    this.toLevel = toLevel;
    this.reason = reason;
    this.phaseId = phaseId;
    this.isUserOverride = isUserOverride;
    this.timestampMs = System.currentTimeMillis();
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Getters
  // ═══════════════════════════════════════════════════════════════════════

  public int getNetNumber() { return netNumber; }
  public int getGroupId() { return groupId; }
  public NetClass.DegradationLevel getFromLevel() { return fromLevel; }
  public NetClass.DegradationLevel getToLevel() { return toLevel; }
  public String getReason() { return reason; }
  public int getPhaseId() { return phaseId; }
  public boolean isUserOverride() { return isUserOverride; }
  public long getTimestampMs() { return timestampMs; }

  // ═══════════════════════════════════════════════════════════════════════
  //  Logging
  // ═══════════════════════════════════════════════════════════════════════

  /** Log this event through FRLogger. */
  public void log() {
    String target = groupId >= 0
        ? "Group#" + groupId
        : "Net#" + netNumber;
    FRLogger.info("  [降级] " + target + ": " + fromLevel + " → " + toLevel
        + " (Phase" + phaseId + ") " + reason);
  }

  @Override
  public String toString() {
    String target = groupId >= 0
        ? "Group#" + groupId
        : "Net#" + netNumber;
    return target + ": " + fromLevel + "→" + toLevel + " [" + reason + "]";
  }
}
