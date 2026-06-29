package app.freerouting.autoroute;

import app.freerouting.board.*;
import app.freerouting.geometry.planar.FloatPoint;
import app.freerouting.logger.FRLogger;
import app.freerouting.rules.Net;

import java.io.Serializable;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Negotiation-based routing engine for same-layer conflict resolution.
 * Routes nets via BatchAutorouter and iteratively resolves inter-net
 * conflicts using historical congestion costs.
 */
public class NegotiationRouter implements Serializable {

  private static final int MAX_ITERATIONS = 50;
  private static final int STAGNATION_LIMIT = 10;

  private final BasicBoard board;
  private final CongestionMap congestionMap;
  private final CostModel costModel;
  private final BatchAutorouter batchAutorouter;

  private final Map<Integer, Set<Integer>> netConflicts = new HashMap<>();
  private int currentIteration = 0;
  private int stagnationCount = 0;
  private boolean converged = false;

  public NegotiationRouter(BasicBoard board, CongestionMap congestionMap,
                           CostModel costModel, BatchAutorouter batchAutorouter) {
    this.board = board;
    this.congestionMap = congestionMap;
    this.costModel = costModel;
    this.batchAutorouter = batchAutorouter;
  }

  /**
   * Routes all nets in a cluster using real A* routing via BatchAutorouter.
   * After routing, detects actual trace-based conflicts and iteratively
   * re-routes conflicting nets with increased historical costs.
   */
  public Set<Integer> routeCluster(NetCluster cluster, Set<Integer> alreadyRouted) {
    FRLogger.debug("NegotiationRouter: Routing cluster " + cluster.getClusterId()
        + " (" + cluster.getNetCount() + " nets)");
    Set<Integer> clusterNets = new HashSet<>(cluster.getNetNumbers());
    clusterNets.removeAll(alreadyRouted);
    if (clusterNets.isEmpty()) return new HashSet<>();

    currentIteration = 0;
    stagnationCount = 0;
    converged = false;
    netConflicts.clear();

    // Track which nets have been successfully routed in any iteration
    Set<Integer> routedThisCluster = new HashSet<>();
    Set<Integer> lastRouted = new HashSet<>();

    // Primary layer for routing (use cluster's assigned layer)
    int primaryLayer = cluster.getPrimaryLayer();

    while (currentIteration < MAX_ITERATIONS && !converged) {
      currentIteration++;

      // Route all unrouted nets in this cluster
      Set<Integer> toRoute = new HashSet<>(clusterNets);
      toRoute.removeAll(routedThisCluster);

      for (int netNo : toRoute) {
        int ripupPass = Math.min(1 + currentIteration / 5, 10); // Increase ripup aggressiveness over iterations
        BatchAutorouter.NetRouteResult result = batchAutorouter.routeNet(netNo, ripupPass);

        if (result.isRouted()) {
          routedThisCluster.add(netNo);
        }
      }

      // Detect conflicts based on actual trace geometry
      int conflictCount = detectConflicts(clusterNets, primaryLayer);

      if (conflictCount == 0) {
        converged = true;
        break;
      }

      // Check if we made progress this iteration
      if (routedThisCluster.size() > lastRouted.size()) {
        stagnationCount = 0;
      } else {
        stagnationCount++;
      }
      lastRouted = new HashSet<>(routedThisCluster);

      if (stagnationCount >= STAGNATION_LIMIT) {
        FRLogger.debug("NegotiationRouter: Cluster " + cluster.getClusterId()
            + " stagnation limit reached");
        break;
      }

      // Increase historical costs in conflict areas to encourage re-routing
      updateHistoricalCosts(clusterNets, primaryLayer);
    }

    FRLogger.debug("NegotiationRouter: Cluster " + cluster.getClusterId()
        + " completed after " + currentIteration + " iterations, "
        + routedThisCluster.size() + "/" + clusterNets.size() + " nets routed");
    return routedThisCluster;
  }

  /**
   * Detect actual trace-based conflicts between nets sharing the same (or nearby) layers.
   * Uses bounding-box intersection for efficiency.
   */
  private int detectConflicts(Set<Integer> clusterNets, int primaryLayer) {
    int conflictCount = 0;
    netConflicts.clear();

    // Build trace bounding boxes for each net
    Map<Integer, List<Trace>> netTraces = new HashMap<>();
    for (int netNo : clusterNets) {
      Net net = board.rules.nets.get(netNo);
      if (net == null) continue;
      List<Trace> traces = new ArrayList<>();
      for (Item item : net.get_items()) {
        if (item instanceof Trace trace) {
          int tl = trace.get_layer();
          // Only consider traces on or near the primary layer
          if (primaryLayer < 0 || Math.abs(tl - primaryLayer) <= 1) {
            traces.add(trace);
          }
        }
      }
      if (!traces.isEmpty()) {
        netTraces.put(netNo, traces);
      }
    }

    // Check all pairs for trace overlap
    List<Integer> netList = new ArrayList<>(netTraces.keySet());
    for (int i = 0; i < netList.size(); i++) {
      for (int j = i + 1; j < netList.size(); j++) {
        if (tracesOverlap(netTraces.get(netList.get(i)), netTraces.get(netList.get(j)))) {
          conflictCount++;
          netConflicts.computeIfAbsent(netList.get(i), k -> new HashSet<>()).add(netList.get(j));
          netConflicts.computeIfAbsent(netList.get(j), k -> new HashSet<>()).add(netList.get(i));
        }
      }
    }
    return conflictCount;
  }

  /**
   * Check if any traces from two nets overlap (bounding box intersection).
   * Uses a simple axis-aligned bounding box check for performance.
   */
  private boolean tracesOverlap(List<Trace> tracesA, List<Trace> tracesB) {
    // Check each pair of traces
    for (Trace ta : tracesA) {
      FloatPoint ta1 = ta.first_corner().to_float();
      FloatPoint ta2 = ta.last_corner().to_float();
      double taMinX = Math.min(ta1.x, ta2.x);
      double taMaxX = Math.max(ta1.x, ta2.x);
      double taMinY = Math.min(ta1.y, ta2.y);
      double taMaxY = Math.max(ta1.y, ta2.y);

      for (Trace tb : tracesB) {
        if (ta.get_layer() != tb.get_layer()) continue; // Different layers
        FloatPoint tb1 = tb.first_corner().to_float();
        FloatPoint tb2 = tb.last_corner().to_float();
        double tbMinX = Math.min(tb1.x, tb2.x);
        double tbMaxX = Math.max(tb1.x, tb2.x);
        double tbMinY = Math.min(tb1.y, tb2.y);
        double tbMaxY = Math.max(tb1.y, tb2.y);

        // Simple AABB overlap check with clearance margin
        double margin = 10000; // 10um clearance margin
        if (taMaxX + margin > tbMinX && tbMaxX + margin > taMinX
            && taMaxY + margin > tbMinY && tbMaxY + margin > taMinY) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Increase historical congestion costs around conflicting traces
   * to encourage the autorouter to find alternative paths.
   */
  private void updateHistoricalCosts(Set<Integer> clusterNets, int primaryLayer) {
    for (Map.Entry<Integer, Set<Integer>> entry : netConflicts.entrySet()) {
      int netNo = entry.getKey();
      Net net = board.rules.nets.get(netNo);
      if (net == null) continue;
      for (Item item : net.get_items()) {
        if (item instanceof Trace trace && trace.get_layer() >= 0) {
          FloatPoint a = trace.first_corner().to_float();
          FloatPoint b = trace.last_corner().to_float();
          FloatPoint mid = new FloatPoint((a.x + b.x) / 2.0, (a.y + b.y) / 2.0);
          int layer = primaryLayer >= 0 ? primaryLayer : trace.get_layer();
          congestionMap.incrementHistoricalCost(mid, layer);
        }
      }
    }
  }

  /**
   * Resolve conflicts between different clusters that share layers.
   * Penalizes overlapping regions on shared layers.
   */
  public void resolveInterClusterConflicts(List<NetCluster> allClusters,
                                           LayerAssignment clusterAssignments) {
    for (int i = 0; i < allClusters.size(); i++) {
      for (int j = i + 1; j < allClusters.size(); j++) {
        NetCluster a = allClusters.get(i);
        NetCluster b = allClusters.get(j);
        Set<Integer> sharedLayers = new HashSet<>(a.getAssignedLayers());
        sharedLayers.retainAll(b.getAssignedLayers());
        if (sharedLayers.isEmpty()) continue;
        double overlapX = Math.max(0, Math.min(a.getMaxX(), b.getMaxX())
            - Math.max(a.getMinX(), b.getMinX()));
        double overlapY = Math.max(0, Math.min(a.getMaxY(), b.getMaxY())
            - Math.max(a.getMinY(), b.getMinY()));
        if (overlapX > 0 && overlapY > 0) {
          for (int layer : sharedLayers) {
            congestionMap.incrementHistoricalCost(
                new FloatPoint((a.getMaxX() + b.getMinX()) / 2,
                    (a.getMaxY() + b.getMinY()) / 2), layer);
          }
        }
      }
    }
  }

  public boolean isConverged() { return converged; }
  public int getCurrentIteration() { return currentIteration; }
}
