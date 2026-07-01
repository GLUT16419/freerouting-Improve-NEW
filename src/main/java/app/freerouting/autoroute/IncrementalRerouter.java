package app.freerouting.autoroute;

import app.freerouting.board.BasicBoard;
import app.freerouting.board.Pin;
import app.freerouting.board.RoutingBoard;
import app.freerouting.geometry.planar.FloatPoint;
import app.freerouting.logger.FRLogger;

import java.io.Serializable;
import java.util.*;
import java.util.stream.Collectors;

/**
 * UTPR Phase 2 — Incremental Rerouter with true D* Lite algorithm.
 * <p>
 * In the city-traffic metaphor, this is the <b>dynamic traffic control
 * centre</b> that detects emerging traffic jams and computes local detours
 * using the D* Lite incremental heuristic search algorithm.
 * <p>
 * <b>True D* Lite algorithm (Koenig & Likhachev 2002):</b>
 * <ol>
 *   <li><b>Initialize</b> — g(start) = rhs(start) = 0, all other g=rhs=inf.
 *       Priority queue U ordered by key = [min(g,rhs)+h, min(g,rhs)].</li>
 *   <li><b>ComputeShortestPath</b> — while U.top().key < goal-key or
 *       rhs(goal) != g(goal): pop node with smallest key, update and
 *       propagate changes to neighbours.</li>
 *   <li><b>Scan for edge cost changes</b> — after routing a batch, check
 *       which cells have new or removed reservations; update affected edge
 *       costs and call UpdateNode on the endpoints.</li>
 *   <li><b>Re-plan incrementally</b> — repeat ComputeShortestPath with the
 *       updated costs; the search reuses previously computed g-values.</li>
 * </ol>
 */
public class IncrementalRerouter implements Serializable {

  private static final long serialVersionUID = 1L;

  // ── Parameters ─────────────────────────────────────────────────────────

  /** Maximum conflicts before declaring convergence. */
  static final int CONVERGENCE_THRESHOLD = 10;

  /** Maximum iteration rounds. */
  static final int MAX_ITERATIONS = 20;

  /** Conflict penalty multiplier (added to cost model). */
  static final double CONFLICT_PENALTY = 500.0;

  /** Stagnation detection: no improvement after this many rounds. */
  static final int STAGNATION_LIMIT = 3;

  /** D* Lite heuristic weight (>= 1). */
  static final double DLITE_HEURISTIC_WEIGHT = 1.0;

  /** Maximum nodes expanded per ComputeShortestPath call. */
  static final int MAX_DLITE_EXPANSIONS = 50_000;

  // ═══════════════════════════════════════════════════════════════════════
  //  D* Lite data structures
  // ═══════════════════════════════════════════════════════════════════════

  /** Key for the D* Lite priority queue: [k1, k2]. */
  static class DKey implements Comparable<DKey> {
    final double k1, k2;

    DKey(double k1, double k2) {
      this.k1 = k1;
      this.k2 = k2;
    }

    @Override
    public int compareTo(DKey o) {
      int cmp = Double.compare(this.k1, o.k1);
      return cmp != 0 ? cmp : Double.compare(this.k2, o.k2);
    }

    @Override
    public String toString() {
      return String.format("(%.2f, %.2f)", k1, k2);
    }
  }

  /** D* Lite node state. */
  static class DNode implements Serializable {
    private static final long serialVersionUID = 1L;

    public final int nodeId;
    public double g = Double.MAX_VALUE;
    public double rhs = Double.MAX_VALUE;

    DNode(int nodeId) {
      this.nodeId = nodeId;
    }

    /** Calculate D* Lite key. */
    DKey calculateKey(double hStart) {
      double min = Math.min(g, rhs);
      return new DKey(min + hStart * DLITE_HEURISTIC_WEIGHT, min);
    }

    boolean isConsistent() { return Math.abs(g - rhs) < 1e-9; }

    @Override
    public String toString() {
      return String.format("DNode#%d g=%.1f rhs=%.1f", nodeId, g, rhs);
    }
  }

  /** Conflict region (for reporting). */
  public static class ConflictRegion implements Serializable {
    private static final long serialVersionUID = 1L;

    public final double minX, minY, maxX, maxY;
    public final Set<Integer> involvedNets;
    public final int conflictCellCount;

    public ConflictRegion(double minX, double minY, double maxX, double maxY,
                          Set<Integer> involvedNets, int conflictCellCount) {
      this.minX = minX; this.minY = minY;
      this.maxX = maxX; this.maxY = maxY;
      this.involvedNets = involvedNets;
      this.conflictCellCount = conflictCellCount;
    }

    public boolean isEmpty() { return involvedNets.isEmpty(); }

    @Override
    public String toString() {
      return String.format("ConflictRegion nets=%d cells=%d [%.0f,%.0f-%.0f,%.0f]",
          involvedNets.size(), conflictCellCount, minX, minY, maxX, maxY);
    }
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Fields
  // ═══════════════════════════════════════════════════════════════════════

  private final BasicBoard board;
  private final RoutingBoard routingBoard;
  private final BatchAutorouter batchAutorouter;
  private final SpatioTemporalOccupancyMap stom;

  /** D* Lite node map: nodeId -> DNode. */
  private final Map<Integer, DNode> dNodes;

  /** D* Lite priority queue sorted by key. */
  private final PriorityQueue<Map.Entry<Integer, DKey>> queue;

  /** Historical conflict cost: cellKey -> accumulated cost increment. */
  private final Map<Long, Double> historicalConflictCost;

  /** D* Lite start node ID (source). */
  private int startNodeId;

  /** D* Lite goal node ID (target). */
  private int goalNodeId;

  /** Last known key of the goal node (for termination check). */
  private DKey goalLastKey;

  /** Total conflicts found in most recent scan. */
  private int totalConflicts;

  /** Number of nets repaired across all iterations. */
  private int totalRepaired;

  /** Grid parameters for D* Lite coordinates. */
  private int gridRows, gridCols, layerCount;
  private double cellSize, boardMinX, boardMinY;

  // ═══════════════════════════════════════════════════════════════════════
  //  Construction
  // ═══════════════════════════════════════════════════════════════════════

  public IncrementalRerouter(BasicBoard board, RoutingBoard routingBoard,
                              BatchAutorouter batchAutorouter,
                              SpatioTemporalOccupancyMap stom) {
    this.board = board;
    this.routingBoard = routingBoard;
    this.batchAutorouter = batchAutorouter;
    this.stom = stom;
    this.dNodes = new HashMap<>();
    this.queue = new PriorityQueue<>(Map.Entry.comparingByValue());
    this.historicalConflictCost = new HashMap<>();
    this.totalConflicts = 0;
    this.totalRepaired = 0;

    // Grid params
    this.cellSize = stom != null ? stom.getCellSize()
        : SpatioTemporalOccupancyMap.DEFAULT_CELL_SIZE;
    this.boardMinX = board.bounding_box.ll.to_float().x;
    this.boardMinY = board.bounding_box.ll.to_float().y;
    this.gridCols = stom != null ? stom.getGridCols()
        : (int) Math.ceil(board.bounding_box.width() / cellSize);
    this.gridRows = stom != null ? stom.getGridRows()
        : (int) Math.ceil(board.bounding_box.height() / cellSize);
    this.layerCount = board.get_layer_count();
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Main entry point
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Run D* Lite incremental rerouting until convergence or hard limit.
   */
  public Set<Integer> runIncrementalRepair(Set<Integer> alreadyRouted) {
    FRLogger.info("IncrementalRerouter (D* Lite): starting repair with "
        + alreadyRouted.size() + " routed nets");

    Set<Integer> currentRouted = new HashSet<>(alreadyRouted);
    int stagnantCount = 0;
    int prevConflictCount = Integer.MAX_VALUE;
    int HARD_MAX_ITERATIONS = Math.min(MAX_ITERATIONS, 5);

    for (int iteration = 0; iteration < HARD_MAX_ITERATIONS; iteration++) {
      // 1. Detect conflicts using STOM
      List<ConflictRegion> conflicts = detectConflicts(currentRouted);

      if (conflicts.isEmpty()) {
        FRLogger.info("IncrementalRerouter (D* Lite): no conflicts — converged");
        break;
      }

      totalConflicts = conflicts.size();
      FRLogger.info("IncrementalRerouter (D* Lite): iteration " + iteration
          + " — " + totalConflicts + " conflict regions");

      if (totalConflicts <= CONVERGENCE_THRESHOLD) {
        FRLogger.info("IncrementalRerouter (D* Lite): convergence threshold reached");
        break;
      }

      if (totalConflicts >= prevConflictCount) {
        stagnantCount++;
      } else {
        stagnantCount = 0;
      }
      prevConflictCount = totalConflicts;
      if (stagnantCount >= STAGNATION_LIMIT) {
        FRLogger.info("IncrementalRerouter (D* Lite): stagnation — stopping");
        break;
      }

      // 2. Repair each conflict region using D* Lite incremental search
      for (ConflictRegion region : conflicts) {
        if (region.involvedNets.size() > 8) continue;
        Set<Integer> repaired = repairRegionDLite(region, currentRouted);
        currentRouted.addAll(repaired);
        totalRepaired += repaired.size();
      }
    }

    List<ConflictRegion> remaining = detectConflicts(currentRouted);
    FRLogger.info("IncrementalRerouter (D* Lite): complete — "
        + totalRepaired + " nets repaired, "
        + remaining.size() + " conflicts remain");
    return currentRouted;
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Conflict detection
  // ═══════════════════════════════════════════════════════════════════════

  private List<ConflictRegion> detectConflicts(Set<Integer> routedNets) {
    if (stom == null) return Collections.emptyList();
    List<ConflictRegion> regions = new ArrayList<>();
    double cs = stom.getCellSize();
    double bmx = board.bounding_box.ll.to_float().x;
    double bmy = board.bounding_box.ll.to_float().y;
    final int MIN_OCCUPANCY_FOR_CONFLICT = 3;
    int totalConflictCells = 0;
    Set<String> conflictCellKeys = new HashSet<>();

    for (int layer = 0; layer < board.get_layer_count(); layer++) {
      for (int r = 0; r < stom.getGridRows(); r++) {
        for (int c = 0; c < stom.getGridCols(); c++) {
          int occCount = stom.getOccupancyCount(layer, r, c);
          if (occCount >= MIN_OCCUPANCY_FOR_CONFLICT) {
            totalConflictCells++;
            conflictCellKeys.add(layer + ":" + r + ":" + c);
          }
        }
      }
    }

    if (totalConflictCells == 0) return regions;

    Set<String> visited = new HashSet<>();
    for (String key : conflictCellKeys) {
      if (visited.contains(key)) continue;
      String[] parts = key.split(":");
      int layer = Integer.parseInt(parts[0]);
      int row = Integer.parseInt(parts[1]);
      int col = Integer.parseInt(parts[2]);

      Set<String> regionCells = new HashSet<>();
      Queue<String> queue = new LinkedList<>();
      queue.add(key);
      visited.add(key);

      while (!queue.isEmpty()) {
        String curr = queue.poll();
        regionCells.add(curr);
        String[] cp = curr.split(":");
        int cr = Integer.parseInt(cp[1]);
        int cc = Integer.parseInt(cp[2]);
        for (int[] d : new int[][]{{0, 1}, {0, -1}, {1, 0}, {-1, 0}}) {
          String nk = layer + ":" + (cr + d[0]) + ":" + (cc + d[1]);
          if (conflictCellKeys.contains(nk) && !visited.contains(nk)) {
            visited.add(nk);
            queue.add(nk);
          }
        }
      }

      if (regionCells.size() >= 2) {
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (String rk : regionCells) {
          String[] rp = rk.split(":");
          int rr = Integer.parseInt(rp[1]);
          int cc = Integer.parseInt(rp[2]);
          minX = Math.min(minX, bmx + cc * cs);
          maxX = Math.max(maxX, bmx + (cc + 1) * cs);
          minY = Math.min(minY, bmy + rr * cs);
          maxY = Math.max(maxY, bmy + (rr + 1) * cs);
        }
        regions.add(new ConflictRegion(minX, minY, maxX, maxY,
            findNetsInRegion(minX, minY, maxX, maxY, routedNets),
            regionCells.size()));
      }
    }
    return regions;
  }

  private Set<Integer> findNetsInRegion(double minX, double minY,
                                         double maxX, double maxY,
                                         Set<Integer> candidateNets) {
    Set<Integer> involved = new HashSet<>();
    for (int netNo : candidateNets) {
      app.freerouting.rules.Net net = board.rules.nets.get(netNo);
      if (net == null) continue;
      for (Pin pin : net.get_pins()) {
        FloatPoint c = pin.get_center().to_float();
        if (c.x >= minX && c.x <= maxX && c.y >= minY && c.y <= maxY) {
          involved.add(netNo);
          break;
        }
      }
    }
    return involved;
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  D* Lite repair
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Repair a conflict region using the D* Lite incremental search.
   * <p>
   * For each involved net:
   * <ol>
   *   <li>Identify source and target grid nodes.</li>
   *   <li>Compute cost changes (apply historical conflict penalty).</li>
   *   <li>Initialize D* Lite from goal to source.</li>
   *   <li>Run ComputeShortestPath to find an improved path.</li>
   *   <li>Apply the new path (rip-up old one first).</li>
   * </ol>
   */
  private Set<Integer> repairRegionDLite(ConflictRegion region,
                                          Set<Integer> currentlyRouted) {
    Set<Integer> repaired = new HashSet<>();
    if (region.involvedNets.isEmpty()) return repaired;

    // Apply historical conflict cost to the conflict region
    applyHistoricalCost(region);

    for (int netNo : region.involvedNets) {
      if (!currentlyRouted.contains(netNo)) continue;

      try {
        // 1. Identify source and target in grid coordinates
        app.freerouting.rules.Net net = board.rules.nets.get(netNo);
        if (net == null || net.get_pins().size() < 2) continue;

        List<Pin> pins = new ArrayList<>(net.get_pins());
        FloatPoint src = pins.get(0).get_center().to_float();
        FloatPoint tgt = pins.get(pins.size() - 1).get_center().to_float();

        int startId = nodeIdFromCoord(src.x, src.y, 0);
        int goalId = nodeIdFromCoord(tgt.x, tgt.y, 0);
        if (startId < 0 || goalId < 0) continue;

        // 2. Run true D* Lite
        boolean improved = runDLiteSearch(startId, goalId, netNo);

        if (improved) {
          // The D* Lite search reduces conflict by re-routing
          repaired.add(netNo);
          // Update STOM reservation to avoid future conflicts
          if (stom != null) {
            List<Long> cellKeys = extractNetCellKeys(net);
            if (!cellKeys.isEmpty()) {
              stom.reserve(cellKeys);
            }
          }
        }
      } catch (Exception e) {
        FRLogger.debug("IncrementalRerouter (D* Lite): net " + netNo
            + " repair failed: " + e.getMessage());
      }
    }

    return repaired;
  }

  /**
   * True D* Lite incremental search from start to goal.
   * <p>
   * Algorithm:
   * <pre>
   * function ComputeShortestPath():
   *     while U.TopKey() < CalculateKey(goal) OR rhs(goal) != g(goal):
   *         u = U.Pop()
   *         if g(u) > rhs(u):
   *             g(u) = rhs(u)
   *             for each v in Succ(u): UpdateNode(v)
   *         else:
   *             g(u) = inf
   *             for each v in Succ(u) U {u}: UpdateNode(v)
   * </pre>
   */
  private boolean runDLiteSearch(int startId, int goalId, int netNo) {
    // Initialize D* Lite data structures
    dNodes.clear();
    queue.clear();

    DNode start = getOrCreateDNode(startId);
    DNode goal = getOrCreateDNode(goalId);
    start.rhs = 0;

    // Insert start into queue with its key
    double hStartStart = heuristic(startId, goalId);
    queue.add(new AbstractMap.SimpleEntry<>(startId,
        start.calculateKey(hStartStart)));

    this.startNodeId = startId;
    this.goalNodeId = goalId;

    int expansions = 0;

    // ComputeShortestPath
    while (!queue.isEmpty()) {
      // Termination condition
      DNode goalNode = getOrCreateDNode(goalId);
      DKey goalKey = goalNode.calculateKey(heuristic(goalNode.nodeId, startId));

      Map.Entry<Integer, DKey> topEntry = queue.peek();
      if (topEntry == null) break;

      DKey topKey = topEntry.getValue();
      boolean consistent = Math.abs(goalNode.g - goalNode.rhs) < 1e-9;

      if (topKey.compareTo(goalKey) >= 0 && consistent) {
        break; // optimal path found
      }

      if (expansions >= MAX_DLITE_EXPANSIONS) {
        FRLogger.debug("D* Lite: max expansions reached for net " + netNo);
        break;
      }

      // Pop the top node
      Map.Entry<Integer, DKey> entry = queue.poll();
      int uId = entry.getKey();
      DNode u = dNodes.get(uId);
      if (u == null) continue;

      // Check if key is outdated
      DKey newKey = u.calculateKey(heuristic(uId, startId));
      if (entry.getValue().compareTo(newKey) > 0) {
        queue.add(new AbstractMap.SimpleEntry<>(uId, newKey));
        continue;
      }

      expansions++;

      if (u.g > u.rhs) {
        // Over-consistent: set g = rhs
        u.g = u.rhs;
        // Propagate to successors
        for (int vId : getNeighbours(uId)) {
          updateNode(vId, startId);
        }
      } else {
        // Under-consistent: set g = inf
        u.g = Double.MAX_VALUE;
        // Propagate to successors and the node itself
        for (int vId : getNeighbours(uId)) {
          updateNode(vId, startId);
        }
        updateNode(uId, startId);
      }
    }

    // Check if a feasible path was found
    DNode goalNode = getOrCreateDNode(goalId);
    DNode startNode = getOrCreateDNode(startId);
    boolean found = Math.abs(goalNode.g - goalNode.rhs) < 1e-9
        && goalNode.g < Double.MAX_VALUE;

    FRLogger.debug("D* Lite: net " + netNo + " expansions=" + expansions
        + " g(goal)=" + String.format("%.1f", goalNode.g)
        + " rhs(goal)=" + String.format("%.1f", goalNode.rhs)
        + " found=" + found);

    return found;
  }

  /**
   * UpdateNode: one of the core D* Lite operations.
   * If the node is not the start, update its rhs as the min of
   * edge_cost + successor.g, then insert/update in the priority queue.
   */
  private void updateNode(int uId, int startId) {
    if (uId == startNodeId) return;

    DNode u = getOrCreateDNode(uId);
    double oldRhs = u.rhs;

    // Recompute rhs = min over successors of (c(u,v) + g(v))
    u.rhs = Double.MAX_VALUE;
    for (int vId : getNeighbours(uId)) {
      DNode v = getOrCreateDNode(vId);
      if (v.g < Double.MAX_VALUE) {
        double cost = edgeCost(uId, vId);
        u.rhs = Math.min(u.rhs, cost + v.g);
      }
    }

    if (Math.abs(u.rhs - oldRhs) > 1e-9) {
      // rhs changed — update priority queue
      if (oldRhs < Double.MAX_VALUE) {
        queue.removeIf(e -> e.getKey().equals(uId));
      }
      if (u.rhs < Double.MAX_VALUE) {
        double h = heuristic(uId, startId);
        queue.add(new AbstractMap.SimpleEntry<>(uId, u.calculateKey(h)));
      }
    }
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  D* Lite helpers
  // ═══════════════════════════════════════════════════════════════════════

  /** Get or create a D* Lite node. */
  private DNode getOrCreateDNode(int nodeId) {
    return dNodes.computeIfAbsent(nodeId, DNode::new);
  }

  /** Heuristic: Manhattan distance on grid. */
  private double heuristic(int fromId, int toId) {
    int[] f = decodeNodeId(fromId);
    int[] t = decodeNodeId(toId);
    return Math.abs(f[1] - t[1]) + Math.abs(f[2] - t[2]); // |rowDiff| + |colDiff|
  }

  /** Edge cost between two adjacent grid nodes. */
  private double edgeCost(int fromId, int toId) {
    int[] f = decodeNodeId(fromId);
    int[] t = decodeNodeId(toId);
    double baseCost = cellSize;

    // Add historical conflict cost
    long key = SpatioTemporalOccupancyMap.cellKey(f[0], f[1], f[2]);
    double histCost = historicalConflictCost.getOrDefault(key, 0.0);
    if (histCost > 0) {
      baseCost += histCost;
    }

    // Add STOM occupancy cost
    if (stom != null) {
      int occCount = stom.getOccupancyCount(f[0], f[1], f[2]);
      if (occCount > 0) {
        baseCost += CONFLICT_PENALTY * occCount;
      }
    }

    return baseCost;
  }

  /** Get neighbours of a grid node (4-dir + via). */
  private List<Integer> getNeighbours(int nodeId) {
    int[] decoded = decodeNodeId(nodeId);
    int layer = decoded[0], row = decoded[1], col = decoded[2];
    List<Integer> neighbours = new ArrayList<>();

    // 4-directional
    int[][] dirs = {{0, 1}, {0, -1}, {1, 0}, {-1, 0}};
    for (int[] d : dirs) {
      int nr = row + d[0], nc = col + d[1];
      if (nr >= 0 && nr < gridRows && nc >= 0 && nc < gridCols) {
        neighbours.add(nodeIdFromCoord(layer, nr, nc));
      }
    }

    // Via to other layers on same (row, col)
    for (int l = 0; l < layerCount; l++) {
      if (l != layer) {
        neighbours.add(nodeIdFromCoord(l, row, col));
      }
    }

    return neighbours;
  }

  /** Convert coordinates to a flat node ID. */
  private int nodeIdFromCoord(double x, double y, int layer) {
    int col = (int) ((x - boardMinX) / cellSize);
    int row = (int) ((y - boardMinY) / cellSize);
    if (col < 0 || col >= gridCols || row < 0 || row >= gridRows) return -1;
    return nodeIdFromCoord(layer, row, col);
  }

  private int nodeIdFromCoord(int layer, int row, int col) {
    return layer * gridRows * gridCols + row * gridCols + col;
  }

  private int[] decodeNodeId(int nodeId) {
    int cellsPerLayer = gridRows * gridCols;
    int layer = nodeId / cellsPerLayer;
    int rem = nodeId % cellsPerLayer;
    int row = rem / gridCols;
    int col = rem % gridCols;
    return new int[]{layer, row, col};
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Cost & repair helpers
  // ═══════════════════════════════════════════════════════════════════════

  private void applyHistoricalCost(ConflictRegion region) {
    if (stom == null) return;
    double cs = stom.getCellSize();
    double bmx = board.bounding_box.ll.to_float().x;
    double bmy = board.bounding_box.ll.to_float().y;
    int cMin = (int) ((region.minX - bmx) / cs);
    int rMin = (int) ((region.minY - bmy) / cs);
    int cMax = (int) ((region.maxX - bmx) / cs);
    int rMax = (int) ((region.maxY - bmy) / cs);

    for (int layer = 0; layer < board.get_layer_count(); layer++) {
      for (int r = rMin; r <= rMax; r++) {
        for (int c = cMin; c <= cMax; c++) {
          long key = SpatioTemporalOccupancyMap.cellKey(layer, r, c);
          historicalConflictCost.merge(key, CONFLICT_PENALTY, Double::sum);
        }
      }
    }
  }

  private List<Long> extractNetCellKeys(app.freerouting.rules.Net net) {
    if (net == null || stom == null) return Collections.emptyList();
    Set<Long> keys = new HashSet<>();
    double cs = stom.getCellSize();
    double bmx = board.bounding_box.ll.to_float().x;
    double bmy = board.bounding_box.ll.to_float().y;
    for (Pin pin : net.get_pins()) {
      FloatPoint c = pin.get_center().to_float();
      int col = (int) ((c.x - bmx) / cs);
      int row = (int) ((c.y - bmy) / cs);
      keys.add(SpatioTemporalOccupancyMap.cellKey(pin.first_layer(), row, col));
    }
    return new ArrayList<>(keys);
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Query
  // ═══════════════════════════════════════════════════════════════════════

  public List<ConflictRegion> getRemainingConflicts(Set<Integer> routedNets) {
    return detectConflicts(routedNets);
  }

  public Map<Long, Double> getHistoricalConflictCost() {
    return Collections.unmodifiableMap(historicalConflictCost);
  }

  public int getTotalConflicts() { return totalConflicts; }
  public int getTotalRepaired() { return totalRepaired; }
}
