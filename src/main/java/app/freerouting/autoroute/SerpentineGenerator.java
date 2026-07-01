package app.freerouting.autoroute;

import app.freerouting.board.BasicBoard;
import app.freerouting.geometry.planar.FloatPoint;
import app.freerouting.logger.FRLogger;
import app.freerouting.rules.Net;

import java.io.Serializable;
import java.util.*;

/**
 * UTPR — Serpentine / trombone delay-line generator for length-matched groups.
 * <p>
 * In the city-traffic metaphor, this is the <b>detour construction crew</b>
 * that builds extra loops in a road (rising/falling serpentines) when a
 * vehicle needs to extend its travel distance to match other vehicles in
 * the same convoy.
 * <p>
 * <b>Algorithm:</b>
 * <ol>
 *   <li>Identify the <i>reference net</i> — the longest naturally-routed net
 *       in the matched-length group.</li>
 *   <li>For each shorter net, compute the <i>length deficit</i> = reference
 *       length − net length.</li>
 *   <li>Generate serpentine segments (trombone loops) to make up the deficit:
 *       <ul>
 *         <li>Each loop adds 2 × amplitude × numLoops of extra length.</li>
 *         <li>Amplitude is constrained by available track clearance.</li>
 *         <li>Loops are placed in regions with spare routing capacity.</li>
 *       </ul></li>
 *   <li>Inject the serpentine segments into the net's existing path.</li>
 * </ol>
 */
public class SerpentineGenerator implements Serializable {

  private static final long serialVersionUID = 1L;

  // ── Parameters ─────────────────────────────────────────────────────────

  /** Minimum amplitude for a serpentine loop (internal board units). */
  static final double MIN_AMPLITUDE = 100_000.0;

  /** Maximum amplitude for a serpentine loop (constrained by track pitch). */
  static final double MAX_AMPLITUDE = 500_000.0;

  /** Minimum spacing between adjacent loops. */
  static final double LOOP_SPACING = 50_000.0;

  /** Maximum number of loops in a single serpentine segment. */
  static final int MAX_LOOPS_PER_SEGMENT = 6;

  /** Length added by one loop at unit amplitude (approximate: 2 × amplitude). */
  static final double LENGTH_PER_LOOP_UNIT = 2.0;

  /** Fraction of spare channel width that one serpentine may use. */
  static final double CHANNEL_USAGE_FRACTION = 0.7;

  // ═══════════════════════════════════════════════════════════════════════
  //  SerpentineSegment
  // ═══════════════════════════════════════════════════════════════════════

  /** A serpentine (trombone) segment to be inserted into a net's path. */
  public static class SerpentineSegment implements Serializable {
    private static final long serialVersionUID = 1L;

    /** Row of the insertion point on the grid. */
    public final int gridRow;
    /** Column of the insertion point. */
    public final int gridCol;
    /** PCB layer for the segment. */
    public final int layer;
    /** Amplitude of the loops (board units). */
    public final double amplitude;
    /** Number of loops (each loop = 2 × amplitude extra length). */
    public final int loopCount;
    /** Total added length from this segment. */
    public final double addedLength;
    /** Direction: 0 = horizontal, 1 = vertical. */
    public final int direction;

    public SerpentineSegment(int gridRow, int gridCol, int layer,
                             double amplitude, int loopCount,
                             double addedLength, int direction) {
      this.gridRow = gridRow;
      this.gridCol = gridCol;
      this.layer = layer;
      this.amplitude = amplitude;
      this.loopCount = loopCount;
      this.addedLength = addedLength;
      this.direction = direction;
    }

    @Override
    public String toString() {
      return String.format("Serpentine@(%d,%d) L%d amp=%.0f loops=%d addLen=%.0f %s",
          gridRow, gridCol, layer, amplitude, loopCount, addedLength,
          direction == 0 ? "HORZ" : "VERT");
    }
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Fields
  // ═══════════════════════════════════════════════════════════════════════

  private final BasicBoard board;
  private final SpatioTemporalOccupancyMap stom;
  private final double cellSize;

  // ═══════════════════════════════════════════════════════════════════════
  //  Construction
  // ═══════════════════════════════════════════════════════════════════════

  public SerpentineGenerator(BasicBoard board, SpatioTemporalOccupancyMap stom) {
    this.board = board;
    this.stom = stom;
    this.cellSize = stom != null ? stom.getCellSize()
        : SpatioTemporalOccupancyMap.DEFAULT_CELL_SIZE;
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Main entry point
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Generate serpentine segments for a matched-length group.
   * <p>
   * The reference length is taken from the longest net's estimated pin-to-pin
   * Manhatten distance. All shorter nets receive serpentine loops to match it.
   *
   * @param groupNets       net numbers in the matched-length group
   * @param netLengths      measured or estimated lengths for each net
   * @return map of netNo → list of serpentine segments to insert
   */
  public Map<Integer, List<SerpentineSegment>> generateForGroup(
      Set<Integer> groupNets, Map<Integer, Double> netLengths) {

    Map<Integer, List<SerpentineSegment>> result = new HashMap<>();
    if (groupNets == null || groupNets.size() < 2) return result;

    // 1. Find reference length (longest net in the group)
    double referenceLength = 0;
    int referenceNet = -1;
    for (int netNo : groupNets) {
      double len = netLengths.getOrDefault(netNo, 0.0);
      if (len > referenceLength) {
        referenceLength = len;
        referenceNet = netNo;
      }
    }

    if (referenceLength <= 0 || referenceNet < 0) return result;

    FRLogger.info("SerpentineGenerator: group of " + groupNets.size()
        + " nets, reference net=" + referenceNet + " len=" + referenceLength);

    // 2. For each shorter net, compute deficit and generate loops
    for (int netNo : groupNets) {
      if (netNo == referenceNet) continue;
      double netLen = netLengths.getOrDefault(netNo, 0.0);
      double deficit = referenceLength - netLen;

      if (deficit <= 0) continue;

      // Find a good insertion location along the net's path
      List<SerpentineSegment> segments = generateSegmentsForNet(
          netNo, deficit);
      if (!segments.isEmpty()) {
        result.put(netNo, segments);
      }
    }

    int totalSegments = result.values().stream().mapToInt(List::size).sum();
    FRLogger.info("SerpentineGenerator: generated " + totalSegments
        + " serpentine segments for " + result.size() + " nets");
    return result;
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Per-net segment generation
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Generate serpentine segments to make up a given length deficit.
   * Tries to place segments in low-congestion areas.
   */
  private List<SerpentineSegment> generateSegmentsForNet(
      int netNo, double deficit) {

    List<SerpentineSegment> segments = new ArrayList<>();
    Net net = board.rules.nets.get(netNo);
    if (net == null) return segments;

    double remaining = deficit;
    int attempt = 0;
    int maxAttempts = 10;

    while (remaining > MIN_AMPLITUDE * LENGTH_PER_LOOP_UNIT
        && attempt < maxAttempts) {

      // Find a candidate grid cell for the serpentine
      int[] candidate = findSerpentineLocation(net, attempt);
      if (candidate == null) break;

      int row = candidate[0], col = candidate[1], layer = candidate[2];
      int dir = candidate[3];
      double availSpacing = estimateAvailableSpacing(row, col, layer, dir);

      if (availSpacing < MIN_AMPLITUDE * 2) {
        attempt++;
        continue;
      }

      // Compute amplitude: bounded by available space and max amplitude
      double amplitude = Math.min(
          MAX_AMPLITUDE,
          Math.max(MIN_AMPLITUDE, availSpacing * CHANNEL_USAGE_FRACTION));

      // Number of loops needed to make up remaining deficit
      double lenPerLoop = 2.0 * amplitude;
      int loops = Math.min(MAX_LOOPS_PER_SEGMENT,
          (int) Math.ceil(remaining / lenPerLoop));
      double addedLength = loops * lenPerLoop;

      segments.add(new SerpentineSegment(row, col, layer,
          amplitude, loops, addedLength, dir));
      remaining -= addedLength;

      // Mark cells in this region as occupied in STOM (to avoid overlap)
      if (stom != null) {
        markSerpentineOccupied(row, col, layer, amplitude, loops, dir);
      }
      attempt++;
    }

    return segments;
  }

  /**
   * Find a suitable grid location for placing a serpentine segment.
   * Prefers areas with low congestion and near the net's pin bounding box.
   */
  private int[] findSerpentineLocation(Net net, int attempt) {
    // Compute net bounding box
    double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
    double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
    for (app.freerouting.board.Pin pin : net.get_pins()) {
      FloatPoint c = pin.get_center().to_float();
      minX = Math.min(minX, c.x); maxX = Math.max(maxX, c.x);
      minY = Math.min(minY, c.y); maxY = Math.max(maxY, c.y);
    }

    double cx = (minX + maxX) / 2.0;
    double cy = (minY + maxY) / 2.0;

    // Offset slightly for each attempt
    double offsetX = (attempt % 3 - 1) * cellSize * 2;
    double offsetY = ((attempt / 3) % 3 - 1) * cellSize * 2;

    double px = cx + offsetX;
    double py = cy + offsetY;

    int col = (int) ((px - board.bounding_box.ll.to_float().x) / cellSize);
    int row = (int) ((py - board.bounding_box.ll.to_float().y) / cellSize);

    if (col < 0 || col >= (stom != null ? stom.getGridCols() : 10)
        || row < 0 || row >= (stom != null ? stom.getGridRows() : 10)) {
      return null;
    }

    // Determine direction alternating by attempt
    int direction = attempt % 2; // 0=horizontal, 1=vertical
    int layer = 0; // default top layer

    return new int[]{row, col, layer, direction};
  }

  /**
   * Estimate the available spacing at a grid cell (in board units).
   * Checks STOM occupancy and congestion estimate.
   */
  private double estimateAvailableSpacing(int row, int col,
                                           int layer, int direction) {
    if (stom == null) return MAX_AMPLITUDE;

    int occCount = stom.getOccupancyCount(layer, row, col);
    if (occCount > 0) {
      // Occupied cell — very limited space
      return Math.max(MIN_AMPLITUDE, cellSize * 0.5);
    }

    // Check neighbouring cells in the serpentine direction
    int checkRadius = (int) Math.ceil(MAX_AMPLITUDE / cellSize);
    int maxOcc = 0;
    if (direction == 0) { // horizontal serpentine → check vertical neighbours
      for (int dr = -checkRadius; dr <= checkRadius; dr++) {
        int nr = row + dr;
        if (nr >= 0 && nr < stom.getGridRows()) {
          maxOcc = Math.max(maxOcc,
              stom.getOccupancyCount(layer, nr, col));
        }
      }
    } else { // vertical serpentine → check horizontal neighbours
      for (int dc = -checkRadius; dc <= checkRadius; dc++) {
        int nc = col + dc;
        if (nc >= 0 && nc < stom.getGridCols()) {
          maxOcc = Math.max(maxOcc,
              stom.getOccupancyCount(layer, row, nc));
        }
      }
    }

    // More occupancy → less available spacing
    double occupancyRatio = Math.min(1.0, maxOcc / 5.0);
    return MAX_AMPLITUDE * (1.0 - occupancyRatio * 0.5);
  }

  /**
   * Mark serpentine cells as occupied in STOM to prevent overlap.
   */
  private void markSerpentineOccupied(int row, int col, int layer,
                                       double amplitude, int loops,
                                       int direction) {
    int ampCells = (int) Math.ceil(amplitude / cellSize);
    int loopCells = (int) Math.ceil(LOOP_SPACING / cellSize);

    List<Long> cells = new ArrayList<>();
    for (int l = 0; l < loops; l++) {
      int offset = l * loopCells;
      if (direction == 0) { // horizontal: zigzag in Y
        for (int dr = -ampCells; dr <= ampCells; dr++) {
          cells.add(SpatioTemporalOccupancyMap.cellKey(
              layer, row + dr, col + offset));
        }
      } else { // vertical: zigzag in X
        for (int dc = -ampCells; dc <= ampCells; dc++) {
          cells.add(SpatioTemporalOccupancyMap.cellKey(
              layer, row, col + offset + dc));
        }
      }
    }
    if (!cells.isEmpty()) {
      stom.reserve(cells);
    }
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Query
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Compute total serpentine added length for a set of segments.
   */
  public static double totalAddedLength(
      Map<Integer, List<SerpentineSegment>> segments) {
    return segments.values().stream()
        .flatMap(List::stream)
        .mapToDouble(s -> s.addedLength)
        .sum();
  }

  /**
   * Estimate how many loops are needed for a given deficit and amplitude.
   */
  public static int estimateLoopCount(double deficit, double amplitude) {
    if (amplitude <= 0) return 0;
    double lenPerLoop = 2.0 * amplitude;
    return (int) Math.ceil(deficit / lenPerLoop);
  }
}
