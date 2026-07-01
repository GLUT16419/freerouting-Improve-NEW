package app.freerouting.autoroute;

import java.io.Serializable;
import java.util.*;

/**
 * Represents a node in the Contraction Hierarchies (CH) routing graph.
 * <p>
 * Each CHNode corresponds to a grid cell on a specific layer in the PCB routing
 * graph. Nodes are ranked by <b>importance</b> (higher = more critical for
 * long-distance routing) and assigned a <b>hierarchy level</b> during the
 * contraction process. Level-0 nodes are the least important and get contracted
 * first; higher-level nodes serve as "express" waypoints for fast long-range
 * path queries.
 * <p>
 * The data model supports:
 * <ul>
 *   <li>Original adjacency edges (neighbour node ID → weight)</li>
 *   <li>Contraction shortcuts (bypass edge via a contracted node)</li>
 *   <li>Per-node geometric properties for importance scoring</li>
 * </ul>
 */
public class CHNode implements Serializable {

  private static final long serialVersionUID = 1L;

  /** Unique node ID (sequential, 0-based). */
  public final int id;

  /** Board layer index this node resides on. */
  public final int layer;

  /** Grid column index in the routing grid. */
  public final int gridX;

  /** Grid row index in the routing grid. */
  public final int gridY;

  /** World-coordinate X (internal board units). */
  public final double floatX;

  /** World-coordinate Y (internal board units). */
  public final double floatY;

  // ── Importance & hierarchy ──────────────────────────────────────────────

  /**
   * Computed importance score. Higher = more important.
   * Derived from geometric features (on-corridor, BGA vicinity, edge proximity).
   */
  public int importance;

  /**
   * Hierarchy level. 0 = lowest (contracted first).
   * Assigned monotonically during contraction.
   */
  public int level;

  // ── Graph structure ─────────────────────────────────────────────────────

  /**
   * Outgoing original edges to other CH nodes.
   * Key = target node ID, Value = travel weight (length + cost modifiers).
   */
  public final Map<Integer, Double> originalEdges;

  /**
   * Shortcuts added during contraction.
   * Key = target node ID, Value = shortcut metadata.
   */
  public final Map<Integer, ShortcutInfo> shortcuts;

  /**
   * Cached total outgoing degree (original edges + shortcuts);
   * used for importance re-calculation during contraction.
   */
  public transient int totalDegree;

  // ── Obstacle / blockage info ────────────────────────────────────────────

  /** True if this node is blocked by an obstacle (component, keepout, etc.). */
  public boolean isBlocked;

  /** True if this node is free space (routable). */
  public boolean isFree;

  // ── District assignment (filled by the partitioner) ──────────────────────

  /** ID of the top-level region this node belongs to (default 0). */
  public int regionId;

  /** ID of the functional block this node belongs to (-1 = unassigned). */
  public int functionalBlockId;

  /** ID of the urban district this node belongs to (-1 = unassigned). */
  public int districtId;

  // ── Boundary marker ─────────────────────────────────────────────────────

  /** True if this node lies on a functional-block or district boundary. */
  public boolean isBoundary;

  // =========================================================================
  //  Construction
  // =========================================================================

  public CHNode(int id, int layer, int gridX, int gridY,
                double floatX, double floatY) {
    this.id = id;
    this.layer = layer;
    this.gridX = gridX;
    this.gridY = gridY;
    this.floatX = floatX;
    this.floatY = floatY;

    this.importance = 0;
    this.level = 0;
    this.originalEdges = new HashMap<>(8);
    this.shortcuts = new HashMap<>(4);
    this.totalDegree = 0;

    this.isBlocked = false;
    this.isFree = true;
    this.regionId = 0;
    this.functionalBlockId = -1;
    this.districtId = -1;
    this.isBoundary = false;
  }

  // =========================================================================
  //  Edge management
  // =========================================================================

  /** Add an original (non-shortcut) edge to a neighbour node. */
  public void addOriginalEdge(int targetNodeId, double weight) {
    originalEdges.put(targetNodeId, weight);
    totalDegree = originalEdges.size() + shortcuts.size();
  }

  /** Remove an original edge (e.g. when the target gets contracted). */
  public void removeOriginalEdge(int targetNodeId) {
    originalEdges.remove(targetNodeId);
    totalDegree = originalEdges.size() + shortcuts.size();
  }

  /** Add a shortcut edge that bypasses {@code viaNode}. */
  public void addShortcut(int targetNodeId, int viaNode,
                          double weight, int[] bypassedNodes) {
    shortcuts.put(targetNodeId, new ShortcutInfo(viaNode, weight, bypassedNodes));
    totalDegree = originalEdges.size() + shortcuts.size();
  }

  /** Remove a shortcut. */
  public void removeShortcut(int targetNodeId) {
    shortcuts.remove(targetNodeId);
    totalDegree = originalEdges.size() + shortcuts.size();
  }

  /** Get the total travel weight to a neighbour (original or shortcut). */
  public double getWeightTo(int neighbourNodeId) {
    Double w = originalEdges.get(neighbourNodeId);
    if (w != null) return w;
    ShortcutInfo si = shortcuts.get(neighbourNodeId);
    return si != null ? si.weight : Double.POSITIVE_INFINITY;
  }

  /** Return all reachable neighbour IDs (original + shortcut targets). */
  public Set<Integer> getNeighbourIds() {
    Set<Integer> neighbours = new HashSet<>(originalEdges.size() + shortcuts.size());
    neighbours.addAll(originalEdges.keySet());
    neighbours.addAll(shortcuts.keySet());
    return neighbours;
  }

  /** True if this node has been contracted (level > 0 & cleared original edges). */
  public boolean isContracted() {
    return originalEdges.isEmpty() && !shortcuts.isEmpty();
  }

  // =========================================================================
  //  Utility
  // =========================================================================

  public double distanceTo(CHNode other) {
    double dx = this.floatX - other.floatX;
    double dy = this.floatY - other.floatY;
    return Math.sqrt(dx * dx + dy * dy);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof CHNode)) return false;
    CHNode node = (CHNode) o;
    return id == node.id;
  }

  @Override
  public int hashCode() {
    return Integer.hashCode(id);
  }

  @Override
  public String toString() {
    return String.format("CHNode#%d (L%d, %d,%d) imp=%d lv=%d deg=%d%s",
        id, layer, gridX, gridY, importance, level, totalDegree,
        isContracted() ? " [contracted]" : "");
  }

  // =========================================================================
  //  Inner class: ShortcutInfo
  // =========================================================================

  /**
   * Metadata for a CH shortcut edge that bypasses contracted node(s).
   */
  public static class ShortcutInfo implements Serializable {
    private static final long serialVersionUID = 1L;

    /** The node that was contracted and is now bypassed. */
    public final int viaNode;

    /** Total travel weight of the bypassed path. */
    public final double weight;

    /** Array of node IDs that this shortcut bypasses (for path reconstruction). */
    public final int[] bypassedNodes;

    public ShortcutInfo(int viaNode, double weight, int[] bypassedNodes) {
      this.viaNode = viaNode;
      this.weight = weight;
      this.bypassedNodes = bypassedNodes;
    }

    @Override
    public String toString() {
      return "Shortcut via N" + viaNode + " w=" + String.format("%.1f", weight)
          + " bypass=" + bypassedNodes.length + "nodes";
    }
  }
}
