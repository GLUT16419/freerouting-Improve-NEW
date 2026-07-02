package app.freerouting.autoroute;

import app.freerouting.board.BasicBoard;
import app.freerouting.geometry.planar.FloatPoint;
import app.freerouting.logger.FRLogger;

import java.io.Serializable;
import java.util.*;

/**
 * UTPR Spatio-Temporal Occupancy Map (STOM) — the core reservation-based
 * conflict-avoidance data structure.
 * <p>
 * In the city-traffic metaphor, this is the <b>reservation control centre</b>
 * that issues time-slot permits for every intersection of the road network.
 * When a vehicle (net) wants to travel through an intersection (grid cell),
 * it must first check that the slot is free and then reserve it.
 * <p>
 * <b>4D structure:</b> STOM[x][y][layer][timeStep]
 * <ul>
 *   <li>x, y — grid cell coordinates (mapped from board coordinates)</li>
 *   <li>layer — PCB signal layer</li>
 *   <li>timeStep — logical routing time (not physical time). Each routed
 *       network occupies a time step slot.</li>
 * </ul>
 * <p>
 * <b>Operations:</b>
 * <ul>
 *   <li>{@code reserve(path, timeStep)} — mark cells as occupied at time t</li>
 *   <li>{@code query(x, y, layer, t)} — check if cell is free at time t</li>
 *   <li>{@code getOccupancyCount(x, y, layer)} — total reservations at cell</li>
 *   <li>{@code release(path, timeStep)} — free cells (for ripup/re-route)</li>
 * </ul>
 * <p>
 * Uses sparse storage: only cells with non-zero occupancy are stored.
 */
public class SpatioTemporalOccupancyMap implements Serializable {

  private static final long serialVersionUID = 1L;

  // ── Parameters ─────────────────────────────────────────────────────────

  /** Default grid cell size in internal board units (~2 mm). */
  static final double DEFAULT_CELL_SIZE = 200_000.0;

  /** Maximum logical time steps supported. */
  static final int MAX_TIME_STEPS = 1000;

  // ═══════════════════════════════════════════════════════════════════════
  //  Fields
  // ═══════════════════════════════════════════════════════════════════════

  private final double cellSize;
  private final double boardMinX;
  private final double boardMinY;
  private final int gridCols;
  private final int gridRows;
  private final int layerCount;

  /**
   * Sparse 4D occupancy map.
   * Key = long encoding (layer, row, col)
   * Value = BitSet of time steps that are occupied at this cell.
   */
  private final Map<Long, BitSet> occupancyMap;

  /**
   * Track which nets are reserved at which time steps.
   * Key = timeStep, Value = set of (layer, row, col) keys reserved.
   */
  private final Map<Integer, Set<Long>> reservationLog;

  /** Current time step counter (incremented after each reservation). */
  private int currentTimeStep;

  /** Total number of reservations made. */
  private int totalReservations;

  // ═══════════════════════════════════════════════════════════════════════
  //  Construction
  // ═══════════════════════════════════════════════════════════════════════

  public SpatioTemporalOccupancyMap(BasicBoard board, double cellSize) {
    this.cellSize = cellSize > 0 ? cellSize : DEFAULT_CELL_SIZE;
    this.boardMinX = board.bounding_box.ll.to_float().x;
    this.boardMinY = board.bounding_box.ll.to_float().y;
    double bw = board.bounding_box.width();
    double bh = board.bounding_box.height();
    this.gridCols = Math.max(4, (int) Math.ceil(bw / this.cellSize));
    this.gridRows = Math.max(4, (int) Math.ceil(bh / this.cellSize));
    this.layerCount = board.get_layer_count();

    this.occupancyMap = new HashMap<>();
    this.reservationLog = new HashMap<>();
    this.currentTimeStep = 0;
    this.totalReservations = 0;
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Reservation
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Reserve a path at the current time step.
   * <p>
   * Automatically increments the time step counter after reservation.
   *
   * @param cellKeys list of cell keys (layer, row, col) to reserve
   * @return the time step assigned to this reservation
   */
  public int reserve(List<Long> cellKeys) {
    int t = currentTimeStep;
    if (t >= MAX_TIME_STEPS) {
      FRLogger.warn("STOM: time step limit reached (" + MAX_TIME_STEPS + ")");
      return -1;
    }

    Set<Long> thisReservation = reservationLog.computeIfAbsent(t, k -> new HashSet<>());

    for (long key : cellKeys) {
      BitSet bs = occupancyMap.computeIfAbsent(key, k -> new BitSet(MAX_TIME_STEPS));
      bs.set(t);
      thisReservation.add(key);
      totalReservations++;
    }

    currentTimeStep++;
    return t;
  }

  /**
   * Reserve a path at a specific time step.
   *
   * @param cellKeys list of cell keys to reserve
   * @param timeStep the time step to reserve at
   */
  public void reserveAt(List<Long> cellKeys, int timeStep) {
    if (timeStep < 0 || timeStep >= MAX_TIME_STEPS) return;

    Set<Long> thisReservation = reservationLog.computeIfAbsent(timeStep, k -> new HashSet<>());

    for (long key : cellKeys) {
      BitSet bs = occupancyMap.computeIfAbsent(key, k -> new BitSet(MAX_TIME_STEPS));
      bs.set(timeStep);
      thisReservation.add(key);
      totalReservations++;
    }
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Query
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Check if a grid cell is occupied at a specific time step.
   *
   * @param layer   PCB layer index
   * @param row     grid row
   * @param col     grid column
   * @param timeStep logical time step
   * @return true if occupied
   */
  public boolean isOccupied(int layer, int row, int col, int timeStep) {
    long key = cellKey(layer, row, col);
    BitSet bs = occupancyMap.get(key);
    return bs != null && bs.get(timeStep);
  }

  /**
   * Check if a board position is occupied at a given time step.
   *
   * @param x        board X coordinate
   * @param y        board Y coordinate
   * @param layer    PCB layer
   * @param timeStep logical time step
   * @return true if occupied
   */
  public boolean isOccupied(double x, double y, int layer, int timeStep) {
    int col = (int) ((x - boardMinX) / cellSize);
    int row = (int) ((y - boardMinY) / cellSize);
    if (col < 0 || col >= gridCols || row < 0 || row >= gridRows) return true;
    return isOccupied(layer, row, col, timeStep);
  }

  /**
   * Get the total occupancy count at a cell across ALL time steps.
   * Used for congestion-aware cost calculation.
   */
  public int getOccupancyCount(int layer, int row, int col) {
    long key = cellKey(layer, row, col);
    BitSet bs = occupancyMap.get(key);
    return bs != null ? bs.cardinality() : 0;
  }

  /**
   * Get occupancy count at a board position.
   */
  public int getOccupancyCount(double x, double y, int layer) {
    int col = (int) ((x - boardMinX) / cellSize);
    int row = (int) ((y - boardMinY) / cellSize);
    if (col < 0 || col >= gridCols || row < 0 || row >= gridRows) return Integer.MAX_VALUE;
    return getOccupancyCount(layer, row, col);
  }

  /**
   * Get the maximum occupancy across a bounding box region.
   * Used to estimate congestion in a candidate routing area.
   */
  public int getMaxOccupancyInRegion(int layer,
                                      int rowMin, int rowMax,
                                      int colMin, int colMax) {
    int max = 0;
    for (int r = Math.max(0, rowMin); r <= Math.min(gridRows - 1, rowMax); r++) {
      for (int c = Math.max(0, colMin); c <= Math.min(gridCols - 1, colMax); c++) {
        max = Math.max(max, getOccupancyCount(layer, r, c));
      }
    }
    return max;
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Release
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Release all reservations at a given time step.
   * Used when ripping up a previously routed net.
   */
  public void release(int timeStep) {
    Set<Long> keys = reservationLog.remove(timeStep);
    if (keys == null) return;

    for (long key : keys) {
      BitSet bs = occupancyMap.get(key);
      if (bs != null) {
        bs.clear(timeStep);
        totalReservations--;
        // Clean up empty entries
        if (bs.isEmpty()) {
          occupancyMap.remove(key);
        }
      }
    }
  }

  /**
   * Release a specific set of cell keys at a time step.
   */
  public void release(List<Long> cellKeys, int timeStep) {
    Set<Long> keys = reservationLog.get(timeStep);
    if (keys == null) return;

    for (long key : cellKeys) {
      keys.remove(key);
      BitSet bs = occupancyMap.get(key);
      if (bs != null) {
        bs.clear(timeStep);
        totalReservations--;
        if (bs.isEmpty()) {
          occupancyMap.remove(key);
        }
      }
    }
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Cell key encoding
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Encode (layer, row, col) into a single long key for the sparse map.
   */
  public static long cellKey(int layer, int row, int col) {
    return ((long) layer << 40) | ((long) row << 20) | (col & 0xFFFFF);
  }

  /**
   * Decode a long key back to {layer, row, col}.
   */
  public static int[] decodeCellKey(long key) {
    int layer = (int) (key >> 40);
    int row = (int) ((key >> 20) & 0xFFFFF);
    int col = (int) (key & 0xFFFFF);
    return new int[]{layer, row, col};
  }

  /**
   * Convert board coordinates to cell keys along a path segment.
   */
  public List<Long> pathToCellKeys(double x1, double y1, double x2, double y2, int layer) {
    Set<Long> keys = new HashSet<>();
    int c0 = clampCol((int) ((x1 - boardMinX) / cellSize));
    int r0 = clampRow((int) ((y1 - boardMinY) / cellSize));
    int c1 = clampCol((int) ((x2 - boardMinX) / cellSize));
    int r1 = clampRow((int) ((y2 - boardMinY) / cellSize));

    // Bresenham
    int dc = Math.abs(c1 - c0), dr = Math.abs(r1 - r0);
    int sc = c0 < c1 ? 1 : -1, sr = r0 < r1 ? 1 : -1;
    int err = dc - dr;
    int r = r0, c = c0;

    while (true) {
      keys.add(cellKey(layer, r, c));
      if (r == r1 && c == c1) break;
      int e2 = 2 * err;
      if (e2 > -dr) { err -= dr; c += sc; }
      if (e2 < dc) { err += dc; r += sr; }
    }
    return new ArrayList<>(keys);
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  时间窗软化 (V7.x) — Softened temporal occupancy query
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Soft occupancy query with temporal window.
   * <p>
   * Returns a conflict level rather than a boolean:
   * <ul>
   *   <li>0 = free (no occupancy within the time window)</li>
   *   <li>1 = soft conflict (occupied within [t-w, t+w] but NOT at exact t)</li>
   *   <li>2 = hard conflict (occupied exactly at time step t)</li>
   * </ul>
   * <p>
   * This allows networks to "time-share" a channel by offsetting their
   * reservations by a few time steps, reducing unnecessary detours.
   *
   * @param layer     PCB layer index
   * @param row       grid row
   * @param col       grid column
   * @param timeStep  the time step to check
   * @param windowSize the softening window (± steps)
   * @return conflict level (0=free, 1=soft, 2=hard)
   */
  public int querySoft(int layer, int row, int col, int timeStep, int windowSize) {
    long key = cellKey(layer, row, col);
    BitSet bs = occupancyMap.get(key);
    if (bs == null) return 0;

    // Hard conflict: exact time step occupied
    if (bs.get(timeStep)) return 2;

    // Soft conflict: check within [timeStep - windowSize, timeStep + windowSize]
    int tMin = Math.max(0, timeStep - windowSize);
    int tMax = Math.min(MAX_TIME_STEPS - 1, timeStep + windowSize);
    for (int t = bs.nextSetBit(tMin); t >= 0 && t <= tMax; t = bs.nextSetBit(t + 1)) {
      return 1; // occupied within window
    }

    return 0; // free
  }

  /**
   * Get the soft sharing cost for a conflict level.
   *
   * @param conflictLevel result from {@link #querySoft}
   * @param softSharingCost the cost for soft sharing
   * @param hardPenaltyMultiplier multiplier for hard conflicts
   * @return the cost to add
   */
  public static double getSoftSharingCost(int conflictLevel,
                                           double softSharingCost,
                                           double hardPenaltyMultiplier) {
    switch (conflictLevel) {
      case 0: return 0.0;  // free
      case 1: return softSharingCost; // soft sharing allowed
      case 2: return softSharingCost + 100.0 * hardPenaltyMultiplier; // hard conflict — high penalty
      default: return Double.MAX_VALUE;
    }
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Stats
  // ═══════════════════════════════════════════════════════════════════════

  /** Current time step. */
  public int getCurrentTimeStep() { return currentTimeStep; }

  /** Total number of cell-reservations across all time. */
  public int getTotalReservations() { return totalReservations; }

  /** Number of distinct cells that have at least one reservation. */
  public int getOccupiedCellCount() { return occupancyMap.size(); }

  /** Number of distinct time steps used. */
  public int getTimeStepCount() { return reservationLog.size(); }

  /** Clear all reservations (reset STOM to empty). */
  public void clear() {
    occupancyMap.clear();
    reservationLog.clear();
    currentTimeStep = 0;
    totalReservations = 0;
  }

  /** Check if time step limit is reached. */
  public boolean isFull() { return currentTimeStep >= MAX_TIME_STEPS; }

  public void logSummary() {
    FRLogger.info(String.format("STOM: %d cells, %d time steps, %d total reservations",
        occupancyMap.size(), reservationLog.size(), totalReservations));
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  时间步分配策略 — Strategic time-step allocation
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Allocate a time step strategically, trying to minimise congestion.
   *
   * Strategy 0 — FIRST_FIT: return the lowest available time step.
   * Strategy 1 — SPARSE_FIT: scan for the step with fewest overlapping
   *              reservations on the given cell keys.
   * Strategy 2 — PRIORITY_FIT: if reservedSteps is non-empty, try to use
   *              a step close to those already taken (for diff-pair coupling).
   *
   * @param cellKeys     the cells this net will occupy
   * @param priority     higher priority nets get earlier (lower) time steps
   * @param reservedSteps already reserved steps (may be empty)
   * @return assigned time step, or -1 if full
   */
  public int allocateTimeStep(List<Long> cellKeys, int priority,
                               Set<Integer> reservedSteps) {
    if (currentTimeStep >= MAX_TIME_STEPS) return -1;

    // Priority-based: high-priority nets get earlier time slots
    if (priority >= 4) {
      // Critical nets: try to use the next available slot
      int t = currentTimeStep;
      if (!isCellOverlap(cellKeys, t)) return reserveAtStep(cellKeys, t);
    }

    // Try to use a previously reserved step (for diff-pair coupling)
    if (reservedSteps != null && !reservedSteps.isEmpty()) {
      for (int t : reservedSteps) {
        if (!isCellOverlap(cellKeys, t)) {
          return reserveAtStep(cellKeys, t);
        }
      }
    }

    // SPARSE_FIT: find the step with fewest overlapping cell reservations
    int bestStep = currentTimeStep;
    int minOverlap = Integer.MAX_VALUE;
    int searchWindow = Math.min(MAX_TIME_STEPS, currentTimeStep + 20);

    for (int t = 0; t < searchWindow; t++) {
      int overlap = countCellOverlap(cellKeys, t);
      if (overlap == 0) {
        bestStep = t;
        break;
      }
      if (overlap < minOverlap) {
        minOverlap = overlap;
        bestStep = t;
      }
    }

    return reserveAtStep(cellKeys, bestStep);
  }

  /** Check if any cell in the list is occupied at time step t. */
  private boolean isCellOverlap(List<Long> cellKeys, int timeStep) {
    for (long key : cellKeys) {
      BitSet bs = occupancyMap.get(key);
      if (bs != null && bs.get(timeStep)) return true;
    }
    return false;
  }

  /** Count how many cells in the list are occupied at time step t. */
  private int countCellOverlap(List<Long> cellKeys, int timeStep) {
    int count = 0;
    for (long key : cellKeys) {
      BitSet bs = occupancyMap.get(key);
      if (bs != null && bs.get(timeStep)) count++;
    }
    return count;
  }

  /** Internal: reserve cells at a specific step without incrementing. */
  private int reserveAtStep(List<Long> cellKeys, int timeStep) {
    if (timeStep >= MAX_TIME_STEPS) return -1;
    Set<Long> thisRes = reservationLog.computeIfAbsent(timeStep, k -> new HashSet<>());
    for (long key : cellKeys) {
      BitSet bs = occupancyMap.computeIfAbsent(key, k -> new BitSet(MAX_TIME_STEPS));
      bs.set(timeStep);
      thisRes.add(key);
      totalReservations++;
    }
    if (timeStep >= currentTimeStep) {
      currentTimeStep = timeStep + 1;
    }
    return timeStep;
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  并行支持 — Snapshot / Copy / Merge for district-parallel routing
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Create a deep copy (snapshot) of the current STOM state.
   * Used when spawning independent district routing tasks.
   */
  public SpatioTemporalOccupancyMap snapshot() {
    SpatioTemporalOccupancyMap copy = new SpatioTemporalOccupancyMap(
        boardMinX, boardMinY, gridCols, gridRows, layerCount, cellSize);
    // Deep-copy occupancy map
    for (Map.Entry<Long, BitSet> e : occupancyMap.entrySet()) {
      copy.occupancyMap.put(e.getKey(), (BitSet) e.getValue().clone());
    }
    // Deep-copy reservation log
    for (Map.Entry<Integer, Set<Long>> e : reservationLog.entrySet()) {
      copy.reservationLog.put(e.getKey(), new HashSet<>(e.getValue()));
    }
    copy.currentTimeStep = currentTimeStep;
    copy.totalReservations = totalReservations;
    return copy;
  }

  /**
   * Merge another STOM's reservations into this one.
   * Used after parallel district routing to combine results.
   * Conflicts are reported but not resolved (Phase 3 handles them).
   *
   * @param other the STOM to merge from
   * @return number of newly conflicting cells after merge
   */
  public int merge(SpatioTemporalOccupancyMap other) {
    int newConflicts = 0;
    for (Map.Entry<Long, BitSet> e : other.occupancyMap.entrySet()) {
      long key = e.getKey();
      BitSet otherBS = e.getValue();
      BitSet thisBS = occupancyMap.get(key);
      if (thisBS == null) {
        occupancyMap.put(key, (BitSet) otherBS.clone());
      } else {
        // Count newly set bits that were not set before
        for (int t = otherBS.nextSetBit(0); t >= 0; t = otherBS.nextSetBit(t + 1)) {
          if (!thisBS.get(t)) {
            thisBS.set(t);
          } else {
            newConflicts++;
          }
        }
      }
    }
    // Merge reservation logs
    for (Map.Entry<Integer, Set<Long>> e : other.reservationLog.entrySet()) {
      int t = e.getKey();
      reservationLog.computeIfAbsent(t, k -> new HashSet<>()).addAll(e.getValue());
    }
    totalReservations += other.totalReservations;
    currentTimeStep = Math.max(currentTimeStep, other.currentTimeStep);
    return newConflicts;
  }

  /**
   * Isolate a subregion of the STOM for a single district.
   * Returns a compact copy containing only cells within [rowMin..rowMax, colMin..colMax].
   */
  public SpatioTemporalOccupancyMap isolate(int layer, int rowMin, int rowMax,
                                             int colMin, int colMax) {
    SpatioTemporalOccupancyMap isolated = new SpatioTemporalOccupancyMap(
        boardMinX, boardMinY, gridCols, gridRows, layerCount, cellSize);
    for (int r = Math.max(0, rowMin); r <= Math.min(gridRows - 1, rowMax); r++) {
      for (int c = Math.max(0, colMin); c <= Math.min(gridCols - 1, colMax); c++) {
        long key = cellKey(layer, r, c);
        BitSet bs = occupancyMap.get(key);
        if (bs != null) {
          isolated.occupancyMap.put(key, (BitSet) bs.clone());
        }
      }
    }
    return isolated;
  }

  // ═══════════════════════════════════════════════════════════════════════

  public int getGridCols() { return gridCols; }
  public int getGridRows() { return gridRows; }
  public double getCellSize() { return cellSize; }

  /** Constructor used internally for snapshot/isolate. */
  private SpatioTemporalOccupancyMap(double boardMinX, double boardMinY,
                                      int gridCols, int gridRows,
                                      int layerCount, double cellSize) {
    this.cellSize = cellSize;
    this.boardMinX = boardMinX;
    this.boardMinY = boardMinY;
    this.gridCols = gridCols;
    this.gridRows = gridRows;
    this.layerCount = layerCount;
    this.occupancyMap = new HashMap<>();
    this.reservationLog = new HashMap<>();
    this.currentTimeStep = 0;
    this.totalReservations = 0;
  }

  private int clampCol(int c) { return Math.max(0, Math.min(gridCols - 1, c)); }
  private int clampRow(int r) { return Math.max(0, Math.min(gridRows - 1, r)); }
}
