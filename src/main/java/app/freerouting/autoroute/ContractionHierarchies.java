package app.freerouting.autoroute;

import app.freerouting.board.BasicBoard;
import app.freerouting.board.Layer;
import app.freerouting.geometry.planar.FloatPoint;
import app.freerouting.geometry.planar.IntBox;
import app.freerouting.logger.FRLogger;

import java.io.Serializable;
import java.util.Objects;
import java.util.*;

/**
 * Contraction Hierarchies (CH) — a precomputation-based speedup technique for
 * point-to-point shortest-path queries on PCB routing graphs.
 * <p>
 * <b>Core idea:</b> Iteratively remove unimportant nodes from the graph and
 * add <i>shortcut</i> edges that preserve exact shortest-path distances.
 * Queries then run bidirectional Dijkstra on the resulting
 * <b>upward</b> / <b>downward</b> subgraphs, visiting far fewer nodes than a
 * plain Dijkstra would.
 * <p>
 * In the UTPR metaphor, CH corresponds to building <b>express lanes / elevated
 * highways</b> that allow long-distance traversal to skip local intersections.
 * <p>
 * <b>References:</b>
 * <ul>
 *   <li>Geisberger et al., "Contraction Hierarchies: Faster and Simpler
 *       Hierarchical Routing in Road Networks", WEA 2008.</li>
 * </ul>
 */
public class ContractionHierarchies implements Serializable {

  private static final long serialVersionUID = 1L;

  // ═══════════════════════════════════════════════════════════════════════
  //  Default parameters
  // ═══════════════════════════════════════════════════════════════════════

  /** Default grid cell size in internal board units (~2 mm). */
  public static final double DEFAULT_CELL_SIZE = 200_000.0;

  /** Via cost weight for changing between adjacent layers. */
  static final double VIA_COST = 3.0;

  /** Hop limit for witness searches during contraction. */
  static final int WITNESS_LIMIT_HOPS = 5;

  /**
   * Edge weight for crossing into an adjacent layer via a via.
   * This is added on top of the Euclidean distance to the via point.
   */
  static final double VIA_BASE_COST = 10.0;

  // ═══════════════════════════════════════════════════════════════════════
  //  Fields
  // ═══════════════════════════════════════════════════════════════════════

  /** All CH nodes; index = node ID. */
  private final List<CHNode> nodes;

  /**
   * Fast lookup: (layer * gridRows + row) * gridCols + col → node ID.
   * Populated during build.
   */
  private final Map<Long, Integer> gridIndexMap;

  /** Number of grid columns (X). */
  public final int gridCols;

  /** Number of grid rows (Y). */
  public final int gridRows;

  /** Number of layers. */
  public final int layerCount;

  /** Grid cell size in internal board units. */
  public final double cellSize;

  /** Board bounding box minima. */
  public final double boardMinX;
  public final double boardMinY;

  /** Number of nodes that have been contracted so far. */
  private int contractedCount;

  /** Total number of shortcut edges added. */
  private int shortcutCount;

  /** Total witness searches performed. */
  private transient int witnessSearchCount;

  // ═══════════════════════════════════════════════════════════════════════
  //  Construction
  // ═══════════════════════════════════════════════════════════════════════

  public ContractionHierarchies(int gridCols, int gridRows, int layerCount,
                                double cellSize, double boardMinX, double boardMinY) {
    this.nodes = new ArrayList<>();
    this.gridIndexMap = new HashMap<>();
    this.gridCols = gridCols;
    this.gridRows = gridRows;
    this.layerCount = layerCount;
    this.cellSize = cellSize;
    this.boardMinX = boardMinX;
    this.boardMinY = boardMinY;
    this.contractedCount = 0;
    this.shortcutCount = 0;
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Factory: build from a Board
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Build a CH routing graph from a board.
   *
   * @param board    the PCB board
   * @param cellSize grid cell size in internal units; if ≤ 0, defaults to
   *                 {@link #DEFAULT_CELL_SIZE}
   * @return a built (pre-contraction) CH instance
   */
  public static ContractionHierarchies buildFromBoard(BasicBoard board, double cellSize) {
    if (cellSize <= 0) cellSize = DEFAULT_CELL_SIZE;

    IntBox bb = board.bounding_box;
    double minX = bb.ll.to_float().x;
    double minY = bb.ll.to_float().y;
    double maxX = bb.ur.to_float().x;
    double maxY = bb.ur.to_float().y;
    double width = maxX - minX;
    double height = maxY - minY;

    int gridCols = Math.max(1, (int) Math.ceil(width / cellSize));
    int gridRows = Math.max(1, (int) Math.ceil(height / cellSize));
    int layerCount = board.get_layer_count();

    FRLogger.info("CH: building grid " + gridCols + " x " + gridRows + " x "
        + layerCount + " layers, cellSize=" + cellSize);

    ContractionHierarchies ch = new ContractionHierarchies(
        gridCols, gridRows, layerCount, cellSize, minX, minY);

    // ── 1. Create nodes ────────────────────────────────────────────────
    for (int layer = 0; layer < layerCount; layer++) {
      boolean isSignal = layer < board.layer_structure.arr.length
          && board.layer_structure.arr[layer] != null
          && board.layer_structure.arr[layer].is_signal;

      for (int row = 0; row < gridRows; row++) {
        for (int col = 0; col < gridCols; col++) {
          int nodeId = ch.makeNodeId(layer, row, col);
          double fx = minX + (col + 0.5) * cellSize;
          double fy = minY + (row + 0.5) * cellSize;

          CHNode node = new CHNode(nodeId, layer, col, row, fx, fy);
          node.isFree = isSignal;
          node.isBlocked = !isSignal;

          ch.nodes.add(node);
          ch.gridIndexMap.put(ch.gridKey(layer, row, col), nodeId);
        }
      }
    }

    // ── 2. Add edges ───────────────────────────────────────────────────
    for (int layer = 0; layer < layerCount; layer++) {
      boolean isSignal = layer < board.layer_structure.arr.length
          && board.layer_structure.arr[layer] != null
          && board.layer_structure.arr[layer].is_signal;
      if (!isSignal) continue;

      for (int row = 0; row < gridRows; row++) {
        for (int col = 0; col < gridCols; col++) {
          int currId = ch.getNodeId(layer, row, col);
          CHNode curr = ch.nodes.get(currId);

          // 4-directional adjacency within the same layer
          addBidirectionalEdge(ch, curr, layer, row, col - 1, cellSize); // West
          addBidirectionalEdge(ch, curr, layer, row - 1, col, cellSize); // North
        }
      }
    }

    // ── 3. Inter-layer (via) edges ─────────────────────────────────────
    // Connect each node to the same (row, col) on adjacent signal layers.
    int[] signalLayers = ch.getSignalLayerIndices(board);
    for (int si = 0; si < signalLayers.length - 1; si++) {
      int l1 = signalLayers[si];
      int l2 = signalLayers[si + 1];
      for (int row = 0; row < gridRows; row++) {
        for (int col = 0; col < gridCols; col++) {
          int id1 = ch.getNodeId(l1, row, col);
          int id2 = ch.getNodeId(l2, row, col);
          // Weight = VIA_BASE_COST + Euclidean (same position so zero)
          double viaWeight = VIA_BASE_COST;
          ch.nodes.get(id1).addOriginalEdge(id2, viaWeight);
          ch.nodes.get(id2).addOriginalEdge(id1, viaWeight);
        }
      }
    }

    FRLogger.info("CH: created " + ch.nodes.size() + " nodes, "
        + ch.totalEdges() + " directed edges");
    return ch;
  }

  /** Helper: add a bidirectional edge if target within bounds. */
  private static void addBidirectionalEdge(ContractionHierarchies ch,
                                            CHNode curr, int layer,
                                            int row, int col, double cellSize) {
    if (row < 0 || row >= ch.gridRows || col < 0 || col >= ch.gridCols) return;
    int targetId = ch.getNodeId(layer, row, col);
    double dist = cellSize; // Manhattan step: same as cell size for 4-dir
    curr.addOriginalEdge(targetId, dist);
    CHNode target = ch.nodes.get(targetId);
    target.addOriginalEdge(curr.id, dist);
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Contraction (core algorithm)
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Run the CH contraction algorithm, assigning hierarchy levels to all nodes.
   * <p>
   * Nodes are contracted in order of increasing importance. Each node's
   * neighbours are checked for shortcut necessity via a limited witness
   * search.
   *
   * @param targetLevels number of distinct hierarchy levels desired (≥ 2)
   */
  public void contract(int targetLevels) {
    if (targetLevels < 2) targetLevels = 2;
    int totalNodes = nodes.size();
    // Each level gets roughly equal-sized batches (except the top level
    // which retains the most important nodes)
    int batchSize = Math.max(1, totalNodes / targetLevels);

    FRLogger.info("CH: starting contraction, " + totalNodes + " nodes, "
        + targetLevels + " levels, ~" + batchSize + " nodes/level");

    // 1. Compute initial importance for all nodes
    computeInitialImportance();

    // 2. Build priority queue (lowest importance first)
    PriorityQueue<CHNode> pq = new PriorityQueue<>(
        (a, b) -> {
          int cmp = Integer.compare(a.importance, b.importance);
          // Tie-break by ID for determinism
          return cmp != 0 ? cmp : Integer.compare(a.id, b.id);
        });
    // Only add free (routable) nodes
    for (CHNode node : nodes) {
      if (node.isFree && !node.isBlocked) {
        pq.add(node);
      }
    }

    // 3. Contract in batches
    int processed = 0;
    int currentLevel = 0;

    while (!pq.isEmpty()) {
      CHNode node = pq.poll();
      if (node.isContracted()) continue;

      // Determine level: assign higher level for more important nodes (later batches)
      // We want the LAST-batched (most important) nodes to get the HIGHEST level.
      // Since we process low-imp first: level grows as processedCount increases.
      int level = Math.min(targetLevels - 1, processed * targetLevels / totalNodes);
      node.level = level;

      // Contract this node
      contractNode(node);
      processed++;
      contractedCount++;

      // Periodic logging
      if (processed % Math.max(1, totalNodes / 20) == 0) {
        FRLogger.info("CH: contracted " + processed + "/" + totalNodes
            + " (" + (processed * 100 / totalNodes) + "%), shortcuts=" + shortcutCount);
      }
    }

    // 4. Assign level 0 to any remaining uncontracted (blocked) nodes
    for (CHNode node : nodes) {
      if (!node.isContracted() && node.level == 0) {
        // Blocked nodes stay at level 0
        node.level = 0;
      }
    }

    FRLogger.info("CH: contraction complete. " + contractedCount + " contracted, "
        + shortcutCount + " shortcuts, " + witnessSearchCount + " witnesses");
  }

  /** Compute initial importance for all nodes. */
  private void computeInitialImportance() {
    // Edge proximity score: nodes near board edge are less important
    double centerX = boardMinX + gridCols * cellSize / 2.0;
    double centerY = boardMinY + gridRows * cellSize / 2.0;
    double maxDist = Math.max(gridCols, gridRows) * cellSize / 2.0;

    for (CHNode node : nodes) {
      if (!node.isFree || node.isBlocked) {
        node.importance = Integer.MAX_VALUE; // never contract
        continue;
      }

      // ── Score 1: centrality (distance from board center) ──
      double distToCenter = Math.sqrt(
          Math.pow(node.floatX - centerX, 2) + Math.pow(node.floatY - centerY, 2));
      double centralityScore = 1.0 - (distToCenter / Math.max(1.0, maxDist));
      // Centrality: 0..1, higher = closer to center

      // ── Score 2: degree (more connected = more important) ──
      double degreeScore = Math.min(1.0, node.originalEdges.size() / 8.0);

      // ── Combined ──
      // Low importance = candidate for early contraction.
      // We want edge nodes + low-degree nodes contracted first.
      double importanceDbl = (1.0 - centralityScore) * 50
          + (1.0 - degreeScore) * 30;
      node.importance = (int) Math.round(importanceDbl);

      // Seed importance for neighbours + layer transitions gives a slight bump
      if (node.originalEdges.size() > 6) {
        node.importance += 10; // hub potential
      }
    }
  }

  /**
   * Contract a single node: remove it, add shortcuts between its neighbours
   * where necessary.
   */
  private void contractNode(CHNode node) {
    List<Integer> neighbours = new ArrayList<>(node.originalEdges.keySet());

    // Process each unordered pair of neighbours
    for (int i = 0; i < neighbours.size(); i++) {
      int uId = neighbours.get(i);
      CHNode u = nodes.get(uId);
      if (!u.isFree || u.isBlocked) continue;

      double weightUW = node.getWeightTo(uId);
      if (Double.isInfinite(weightUW)) continue;

      for (int j = i + 1; j < neighbours.size(); j++) {
        int vId = neighbours.get(j);
        CHNode v = nodes.get(vId);
        if (!v.isFree || v.isBlocked) continue;

        double weightVW = node.getWeightTo(vId);
        if (Double.isInfinite(weightVW)) continue;

        double viaWeight = weightUW + weightVW;

        // Witness search: is there already a short path from u to v that
        // does NOT go through the current node?
        boolean shortcutNeeded = witnessSearch(uId, vId, node.id, viaWeight);

        if (shortcutNeeded) {
          // Add shortcut in both directions
          // The shortcut weight = distance from u to v via node
          u.addShortcut(vId, node.id, viaWeight, new int[]{node.id});
          v.addShortcut(uId, node.id, viaWeight, new int[]{node.id});
          shortcutCount++;

          // Update neighbour importances (they gained connectivity)
          u.importance += 5;
          v.importance += 5;
        }
      }
    }

    // Mark node as contracted: clear original edges
    // (shortcuts remain so queries can still pass through)
    node.originalEdges.clear();

    // Remove all edges TO this node from neighbours
    for (int neighbourId : neighbours) {
      CHNode nb = nodes.get(neighbourId);
      nb.removeOriginalEdge(node.id);
    }
  }

  /**
   * Limited witness search: do a bounded Dijkstra from {@code from} to {@code to},
   * excluding {@code viaNode} from the graph. Returns true if NO path ≤
   * {@code viaWeight} exists (meaning a shortcut IS needed).
   */
  private boolean witnessSearch(int fromId, int toId, int viaId, double viaWeight) {
    witnessSearchCount++;

    // Dijkstra priority queue
    PriorityQueue<SearchState> pq = new PriorityQueue<>();
    Map<Integer, Double> bestDist = new HashMap<>();

    pq.add(new SearchState(fromId, 0.0));
    bestDist.put(fromId, 0.0);

    int hops = 0;

    while (!pq.isEmpty()) {
      SearchState state = pq.poll();
      int currId = state.nodeId;
      double currDist = state.dist;

      // Prune: if current distance already exceeds viaWeight, no hope
      if (currDist > viaWeight) continue;

      // Hop limit to keep contraction fast
      if (hops > WITNESS_LIMIT_HOPS * 10) break;

      // Found a path to target that is ≤ viaWeight → no shortcut needed
      if (currId == toId) {
        return false; // shortcut NOT needed (alternative path exists)
      }

      CHNode curr = nodes.get(currId);
      if (curr == null) continue;

      // Explore original edges + shortcuts, but DO NOT use the via node
      for (Map.Entry<Integer, Double> entry : curr.originalEdges.entrySet()) {
        int nbId = entry.getKey();
        if (nbId == viaId) continue; // skip the contracted node
        double newDist = currDist + entry.getValue();
        if (newDist < bestDist.getOrDefault(nbId, Double.MAX_VALUE)) {
          bestDist.put(nbId, newDist);
          pq.add(new SearchState(nbId, newDist));
          hops++;
        }
      }
      for (Map.Entry<Integer, CHNode.ShortcutInfo> entry : curr.shortcuts.entrySet()) {
        int nbId = entry.getKey();
        if (nbId == viaId) continue;
        double newDist = currDist + entry.getValue().weight;
        if (newDist < bestDist.getOrDefault(nbId, Double.MAX_VALUE)) {
          bestDist.put(nbId, newDist);
          pq.add(new SearchState(nbId, newDist));
          hops++;
        }
      }
    }

    // No path ≤ viaWeight found → shortcut IS needed
    return true;
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Query: Bidirectional Dijkstra
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Layer mask constants for layer-aware CH queries.
   */
  public static final int LAYER_MASK_ALL = 0;
  public static final int LAYER_MASK_SUBWAY = 1;   // Grade 2 — inner layers (e.g. 2-3)
  public static final int LAYER_MASK_ELEVATED = 2; // Grade 1 — near-surface (e.g. 1,4)
  public static final int LAYER_MASK_SURFACE = 4;  // Grade 0 — outer layers (e.g. 0,5)

  /**
   * Result of a CH shortest-path query.
   */
  public static class CHPath implements Serializable {
    private static final long serialVersionUID = 1L;

    /** Total path weight (distance + costs). */
    public final double totalWeight;

    /** Node IDs along the path (source ... target). */
    public final List<Integer> nodeIds;

    /** True if a path was found (non-empty). */
    public final boolean found;

    public CHPath(boolean found, double totalWeight, List<Integer> nodeIds) {
      this.found = found;
      this.totalWeight = totalWeight;
      this.nodeIds = nodeIds;
    }

    public static final CHPath NOT_FOUND = new CHPath(false, Double.MAX_VALUE, Collections.emptyList());
  }

  /**
   * Bidirectional Dijkstra query on the CH graph.
   * <p>
   * Forward search uses only <b>upward</b> edges (level(target) >= level(current)).
   * Backward search uses only <b>downward</b> edges (level(target) <= level(current)).
   *
   * @param sourceId source node ID
   * @param targetId target node ID
   * @return shortest path result
   */
  public CHPath query(int sourceId, int targetId) {
    return query(sourceId, targetId, LAYER_MASK_ALL);
  }

  /**
   * Layer-aware bidirectional Dijkstra query on the CH graph.
   * <p>
   * When {@code layerMask != LAYER_MASK_ALL}, only nodes belonging to layers
   * matching the mask are expanded, reducing the effective search graph.
   *
   * @param sourceId  source node ID
   * @param targetId  target node ID
   * @param layerMask bitmask of allowed layers (LAYER_MASK_SUBWAY, etc.)
   * @return shortest path result
   */
  public CHPath query(int sourceId, int targetId, int layerMask) {
    if (sourceId < 0 || sourceId >= nodes.size()
        || targetId < 0 || targetId >= nodes.size()) {
      return CHPath.NOT_FOUND;
    }
    if (sourceId == targetId) {
      return new CHPath(true, 0.0, Collections.singletonList(sourceId));
    }

    // ── Forward search (source → target, upward edges) ──
    PriorityQueue<SearchState> forwardPQ = new PriorityQueue<>();
    Map<Integer, Double> forwardDist = new HashMap<>();
    Map<Integer, Integer> forwardPrev = new HashMap<>();
    if (!isLayerExcluded(sourceId, layerMask)) {
      forwardPQ.add(new SearchState(sourceId, 0.0));
      forwardDist.put(sourceId, 0.0);
    }

    // ── Backward search (target → source, downward edges) ──
    PriorityQueue<SearchState> backwardPQ = new PriorityQueue<>();
    Map<Integer, Double> backwardDist = new HashMap<>();
    Map<Integer, Integer> backwardPrev = new HashMap<>();
    if (!isLayerExcluded(targetId, layerMask)) {
      backwardPQ.add(new SearchState(targetId, 0.0));
      backwardDist.put(targetId, 0.0);
    }

    double bestPathWeight = Double.MAX_VALUE;
    int meetingNode = -1;

    while (!forwardPQ.isEmpty() || !backwardPQ.isEmpty()) {
      // Process forward
      if (!forwardPQ.isEmpty()) {
        SearchState fwd = forwardPQ.poll();
        if (fwd.dist > forwardDist.getOrDefault(fwd.nodeId, Double.MAX_VALUE)) continue;
        if (fwd.dist >= bestPathWeight) continue; // bound

        CHNode fwdNode = nodes.get(fwd.nodeId);
        // Explore upward: only neighbours with level >= current
        for (Map.Entry<Integer, Double> e : fwdNode.originalEdges.entrySet()) {
          relaxForward(e.getKey(), e.getValue(), fwd, forwardDist, forwardPrev, forwardPQ, fwdNode.level, layerMask);
        }
        for (Map.Entry<Integer, CHNode.ShortcutInfo> e : fwdNode.shortcuts.entrySet()) {
          relaxForward(e.getKey(), e.getValue().weight, fwd, forwardDist, forwardPrev, forwardPQ, fwdNode.level, layerMask);
        }

        // Check meeting
        if (backwardDist.containsKey(fwd.nodeId)) {
          double total = fwd.dist + backwardDist.get(fwd.nodeId);
          if (total < bestPathWeight) {
            bestPathWeight = total;
            meetingNode = fwd.nodeId;
          }
        }
      }

      // Process backward
      if (!backwardPQ.isEmpty()) {
        SearchState bwd = backwardPQ.poll();
        if (bwd.dist > backwardDist.getOrDefault(bwd.nodeId, Double.MAX_VALUE)) continue;
        if (bwd.dist >= bestPathWeight) continue;

        CHNode bwdNode = nodes.get(bwd.nodeId);
        // Explore downward: only neighbours with level <= current
        for (Map.Entry<Integer, Double> e : bwdNode.originalEdges.entrySet()) {
          relaxBackward(e.getKey(), e.getValue(), bwd, backwardDist, backwardPrev, backwardPQ, bwdNode.level, layerMask);
        }
        for (Map.Entry<Integer, CHNode.ShortcutInfo> e : bwdNode.shortcuts.entrySet()) {
          relaxBackward(e.getKey(), e.getValue().weight, bwd, backwardDist, backwardPrev, backwardPQ, bwdNode.level, layerMask);
        }

        // Check meeting
        if (forwardDist.containsKey(bwd.nodeId)) {
          double total = bwd.dist + forwardDist.get(bwd.nodeId);
          if (total < bestPathWeight) {
            bestPathWeight = total;
            meetingNode = bwd.nodeId;
          }
        }
      }
    }

    if (meetingNode < 0) return CHPath.NOT_FOUND;

    // ── Reconstruct path ──
    List<Integer> path = reconstructPath(meetingNode, forwardPrev, backwardPrev, sourceId, targetId);
    return new CHPath(true, bestPathWeight, path);
  }

  private void relaxForward(int nbId, double edgeWeight, SearchState current,
                            Map<Integer, Double> dist, Map<Integer, Integer> prev,
                            PriorityQueue<SearchState> pq, int currentLevel) {
    relaxForward(nbId, edgeWeight, current, dist, prev, pq, currentLevel, LAYER_MASK_ALL);
  }

  private void relaxForward(int nbId, double edgeWeight, SearchState current,
                            Map<Integer, Double> dist, Map<Integer, Integer> prev,
                            PriorityQueue<SearchState> pq, int currentLevel, int layerMask) {
    if (nbId < 0 || nbId >= nodes.size()) return;
    if (isLayerExcluded(nbId, layerMask)) return;
    CHNode nb = nodes.get(nbId);
    if (nb.level < currentLevel) return; // upward only
    if (nb.isBlocked) return;
    double newDist = current.dist + edgeWeight;
    if (newDist < dist.getOrDefault(nbId, Double.MAX_VALUE)) {
      dist.put(nbId, newDist);
      prev.put(nbId, current.nodeId);
      pq.add(new SearchState(nbId, newDist));
    }
  }

  private void relaxBackward(int nbId, double edgeWeight, SearchState current,
                             Map<Integer, Double> dist, Map<Integer, Integer> prev,
                             PriorityQueue<SearchState> pq, int currentLevel) {
    relaxBackward(nbId, edgeWeight, current, dist, prev, pq, currentLevel, LAYER_MASK_ALL);
  }

  private void relaxBackward(int nbId, double edgeWeight, SearchState current,
                             Map<Integer, Double> dist, Map<Integer, Integer> prev,
                             PriorityQueue<SearchState> pq, int currentLevel, int layerMask) {
    if (nbId < 0 || nbId >= nodes.size()) return;
    if (isLayerExcluded(nbId, layerMask)) return;
    CHNode nb = nodes.get(nbId);
    if (nb.level > currentLevel) return; // downward only
    if (nb.isBlocked) return;
    double newDist = current.dist + edgeWeight;
    if (newDist < dist.getOrDefault(nbId, Double.MAX_VALUE)) {
      dist.put(nbId, newDist);
      prev.put(nbId, current.nodeId);
      pq.add(new SearchState(nbId, newDist));
    }
  }

  /** Check if a node's layer is excluded by the given mask. */
  private boolean isLayerExcluded(int nodeId, int layerMask) {
    if (layerMask == LAYER_MASK_ALL) return false;
    if (nodeId < 0 || nodeId >= nodes.size()) return true;
    CHNode n = nodes.get(nodeId);
    // Determine which layer grade this node belongs to
    // Surface = outer layers (Grade 0), Elevated = near-surface (Grade 1), Subway = inner (Grade 2)
    int grade;
    if (n.layer == 0 || n.layer == layerCount - 1) {
      grade = 0; // Surface
    } else if (n.layer == 1 || n.layer == layerCount - 2) {
      grade = 1; // Elevated
    } else {
      grade = 2; // Subway (inner layers)
    }
    int nodeMask = (grade == 2) ? LAYER_MASK_SUBWAY
        : (grade == 1) ? LAYER_MASK_ELEVATED : LAYER_MASK_SURFACE;
    return (layerMask & nodeMask) == 0;
  }

  /** Reconstruct the full path from source to target via the meeting node. */
  private List<Integer> reconstructPath(int meetingNode,
                                        Map<Integer, Integer> forwardPrev,
                                        Map<Integer, Integer> backwardPrev,
                                        int sourceId, int targetId) {
    LinkedList<Integer> path = new LinkedList<>();

    // Forward segment: meeting → source (reverse)
    Integer curr = meetingNode;
    while (curr != null && curr != sourceId) {
      path.addFirst(curr);
      curr = forwardPrev.get(curr);
    }
    path.addFirst(sourceId);

    // Backward segment: meeting → target (forward)
    curr = backwardPrev.get(meetingNode);
    while (curr != null && curr != targetId) {
      path.addLast(curr);
      curr = backwardPrev.get(curr);
    }
    if (curr == null || Objects.equals(curr, targetId)) {
      path.addLast(targetId);
    }

    return new ArrayList<>(path);
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Node / grid helpers
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Encode a layer, row, col into a single flat node ID.
   */
  public int makeNodeId(int layer, int row, int col) {
    return layer * gridRows * gridCols + row * gridCols + col;
  }

  /**
   * Decode a flat node ID back to an integer array {layer, row, col}.
   */
  public int[] decodeNodeId(int nodeId) {
    int cellsPerLayer = gridRows * gridCols;
    int layer = nodeId / cellsPerLayer;
    int remainder = nodeId % cellsPerLayer;
    int row = remainder / gridCols;
    int col = remainder % gridCols;
    return new int[]{layer, row, col};
  }

  /** Map (layer, row, col) → node ID via the grid index map. */
  public int getNodeId(int layer, int row, int col) {
    if (row < 0 || row >= gridRows || col < 0 || col >= gridCols) return -1;
    Long key = gridKey(layer, row, col);
    Integer id = gridIndexMap.get(key);
    return id != null ? id : -1;
  }

  /** Map FloatPoint to the nearest node ID (closest grid centre). */
  public int findNearestNode(FloatPoint fp, int layer) {
    if (layer < 0 || layer >= layerCount) return -1;
    double colF = (fp.x - boardMinX) / cellSize - 0.5;
    double rowF = (fp.y - boardMinY) / cellSize - 0.5;
    int col = (int) Math.round(colF);
    int row = (int) Math.round(rowF);
    col = Math.max(0, Math.min(gridCols - 1, col));
    row = Math.max(0, Math.min(gridRows - 1, row));
    return getNodeId(layer, row, col);
  }

  /** Find the nearest node across all layers (returns node on the first signal layer). */
  public int findNearestNode(FloatPoint fp) {
    return findNearestNode(fp, 0);
  }

  /** Get the FloatPoint coordinate from a node ID. */
  public FloatPoint getNodeCoordinate(int nodeId) {
    if (nodeId < 0 || nodeId >= nodes.size()) return null;
    CHNode n = nodes.get(nodeId);
    return new FloatPoint(n.floatX, n.floatY);
  }

  private long gridKey(int layer, int row, int col) {
    return ((long) layer << 32) | ((long) row << 16) | (col & 0xFFFF);
  }

  /** Count total directed edges (original + shortcut). */
  public int totalEdges() {
    int count = 0;
    for (CHNode n : nodes) {
      count += n.originalEdges.size() + n.shortcuts.size();
    }
    return count;
  }

  /** Total number of nodes in the graph. */
  public int getNodeCount() {
    return nodes.size();
  }

  public List<CHNode> getNodes() {
    return Collections.unmodifiableList(nodes);
  }

  public CHNode getNode(int id) {
    return (id >= 0 && id < nodes.size()) ? nodes.get(id) : null;
  }

  public int getContractedCount() {
    return contractedCount;
  }

  public int getShortcutCount() {
    return shortcutCount;
  }

  /** Get the indices of signal layers from the board. */
  private int[] getSignalLayerIndices(BasicBoard board) {
    List<Integer> sigLayers = new ArrayList<>();
    for (int i = 0; i < layerCount; i++) {
      if (i < board.layer_structure.arr.length
          && board.layer_structure.arr[i] != null
          && board.layer_structure.arr[i].is_signal) {
        sigLayers.add(i);
      }
    }
    return sigLayers.stream().mapToInt(Integer::intValue).toArray();
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Shortcut unpacking (捷径展开) — reconstruct original-graph path from
  //  a CH query result that may contain shortcut edges.
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Unpack a CH query result: expand shortcuts into the full sequence of
   * original (non-shortcut) node IDs.
   * <p>
   * Each shortcut encodes the bypassed node IDs via
   * {@link CHNode.ShortcutInfo#bypassedNodes}. This method recursively
   * expands them into the constituent original edges.
   *
   * @param chPath result from {@link #query(int, int)}
   * @return de-shortcutted node list, or empty list if not found
   */
  public List<Integer> unpackPath(CHPath chPath) {
    if (!chPath.found || chPath.nodeIds.isEmpty()) {
      return Collections.emptyList();
    }
    LinkedList<Integer> result = new LinkedList<>();
    for (int i = 0; i < chPath.nodeIds.size(); i++) {
      int a = chPath.nodeIds.get(i);
      if (i + 1 < chPath.nodeIds.size()) {
        int b = chPath.nodeIds.get(i + 1);
        List<Integer> segment = unpackEdge(a, b, new HashSet<>());
        // Add all nodes in segment except the last (a), to avoid duplicates
        for (int j = 0; j < segment.size() - 1; j++) {
          result.addLast(segment.get(j));
        }
      }
    }
    // Add the last node of the full path
    result.addLast(chPath.nodeIds.get(chPath.nodeIds.size() - 1));
    return new ArrayList<>(result);
  }

  /**
   * Recursively expand a single CH edge (a→b) into original-graph nodes.
   * If the edge is a shortcut, look up the bypassed node and follow the
   * via-node's incoming/outgoing edges.
   */
  private List<Integer> unpackEdge(int fromId, int toId, Set<String> visited) {
    String key = fromId + "->" + toId;
    if (visited.contains(key)) return Collections.singletonList(fromId);
    visited.add(key);

    CHNode from = getNode(fromId);
    CHNode to = getNode(toId);
    if (from == null || to == null) return Arrays.asList(fromId, toId);

    // Check if this edge is a shortcut
    CHNode.ShortcutInfo si = from.shortcuts.get(toId);
    if (si == null) {
      // Original edge — direct adjacency
      return Arrays.asList(fromId, toId);
    }

    // Shortcut: expand via the first bypassed node
    int viaId = si.bypassedNodes.length > 0 ? si.bypassedNodes[0] : si.viaNode;
    if (viaId < 0 || viaId >= nodes.size()) {
      return Arrays.asList(fromId, toId);
    }

    // Recursively unpack from→via and via→to
    List<Integer> left = unpackEdge(fromId, viaId, visited);
    List<Integer> right = unpackEdge(viaId, toId, visited);

    // Merge: left + right[1..] (skip duplicate via node)
    List<Integer> merged = new ArrayList<>(left);
    for (int i = 1; i < right.size(); i++) {
      merged.add(right.get(i));
    }
    return merged;
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Inner types
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Compute shortest distances from a source node to ALL other nodes.
   * Uses forward Dijkstra on the full CH graph (both upward and downward edges).
   * Used by ALT heuristic landmark precomputation.
   *
   * @param sourceId source node ID
   * @return array of distances indexed by node ID (Double.MAX_VALUE = unreachable)
   */
  public double[] computeAllDistances(int sourceId) {
    int n = nodes.size();
    double[] dist = new double[n];
    Arrays.fill(dist, Double.MAX_VALUE);
    if (sourceId < 0 || sourceId >= n) return dist;

    PriorityQueue<SearchState> pq = new PriorityQueue<>();
    dist[sourceId] = 0.0;
    pq.add(new SearchState(sourceId, 0.0));

    while (!pq.isEmpty()) {
      SearchState curr = pq.poll();
      if (curr.dist != dist[curr.nodeId]) continue;

      CHNode node = nodes.get(curr.nodeId);
      if (node == null) continue;

      // Explore original edges (all neighbours, no level restriction)
      for (Map.Entry<Integer, Double> e : node.originalEdges.entrySet()) {
        int nbId = e.getKey();
        if (nbId < 0 || nbId >= n) continue;
        double nd = curr.dist + e.getValue();
        if (nd < dist[nbId]) {
          dist[nbId] = nd;
          pq.add(new SearchState(nbId, nd));
        }
      }

      // Explore shortcuts
      for (Map.Entry<Integer, CHNode.ShortcutInfo> e : node.shortcuts.entrySet()) {
        int nbId = e.getKey();
        if (nbId < 0 || nbId >= n) continue;
        double nd = curr.dist + e.getValue().weight;
        if (nd < dist[nbId]) {
          dist[nbId] = nd;
          pq.add(new SearchState(nbId, nd));
        }
      }
    }
    return dist;
  }

  /**
   * Get the functional block ID for a given node.
   * Returns -1 if unassigned or node out of range.
   */
  public int getNodeFunctionalBlock(int nodeId) {
    if (nodeId < 0 || nodeId >= nodes.size()) return -1;
    return nodes.get(nodeId).functionalBlockId;
  }

  /**
   * Get the district ID for a given node.
   * Returns -1 if unassigned or node out of range.
   */
  public int getNodeDistrict(int nodeId) {
    if (nodeId < 0 || nodeId >= nodes.size()) return -1;
    return nodes.get(nodeId).districtId;
  }

  /** State for CH search (priority queue element). */
  static class SearchState implements Comparable<SearchState> {
    int nodeId;
    double dist;

    SearchState(int nodeId, double dist) {
      this.nodeId = nodeId;
      this.dist = dist;
    }

    @Override
    public int compareTo(SearchState o) {
      int cmp = Double.compare(this.dist, o.dist);
      return cmp != 0 ? cmp : Integer.compare(this.nodeId, o.nodeId);
    }
  }
}
