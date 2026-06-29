package app.freerouting.autoroute;

import app.freerouting.board.*;
import app.freerouting.geometry.planar.FloatPoint;
import app.freerouting.logger.FRLogger;
import app.freerouting.rules.Net;

import java.io.Serializable;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Phase 3: SAT/ILP-based exact routing for remaining stubborn nets.
 * Generates multiple candidate paths using real A* routing via BatchAutorouter,
 * builds a conflict graph, and solves the maximum independent set problem
 * using a weighted greedy algorithm with relaxation.
 */
public class Phase3SatRouter implements Serializable {

  private static final int MAX_CANDIDATES_PER_NET = 20;
  private static final int MAX_RELAXATION_LEVELS = 5;
  private static final int CANDIDATE_ROUTING_PASSES = 3;

  private final BasicBoard board;
  private final CongestionMap congestionMap;
  private final CostModel costModel;
  private final BatchAutorouter batchAutorouter;

  private final Map<Integer, List<CandidatePath>> candidatePaths = new HashMap<>();
  private final Map<Integer, Set<Integer>> conflictGraph = new HashMap<>();

  private final Set<Integer> phase3Routed = new HashSet<>();
  private final Set<Integer> failedNets = new HashSet<>();
  private int relaxationLevel = 0;

  public Phase3SatRouter(BasicBoard board, CongestionMap congestionMap,
                         CostModel costModel, BatchAutorouter batchAutorouter) {
    this.board = board;
    this.congestionMap = congestionMap;
    this.costModel = costModel;
    this.batchAutorouter = batchAutorouter;
  }

  /**
   * Attempts to route remaining stubborn nets using a SAT-like approach.
   * Generates multiple candidate routing solutions per net, builds a
   * conflict graph, and selects a compatible subset.
   */
  public Set<Integer> routeRemaining(Set<Integer> unroutedNets) {
    FRLogger.debug("Phase 3: Starting SAT-based exact routing for "
        + unroutedNets.size() + " stubborn nets...");
    if (unroutedNets.isEmpty()) return Collections.emptySet();

    relaxationLevel = 0;
    Set<Integer> remaining = new HashSet<>(unroutedNets);

    while (relaxationLevel <= MAX_RELAXATION_LEVELS && !remaining.isEmpty()) {
      FRLogger.debug("Phase 3: Relaxation level " + relaxationLevel
          + ", " + remaining.size() + " nets remaining");

      // 1. Generate multiple candidate routing solutions for each net
      generateCandidatePaths(remaining);

      // 2. Build conflict graph between candidates
      buildConflictGraph(remaining);

      // 3. Solve weighted max independent set
      Set<Integer> solved = greedyMaxSatSolve(remaining);

      // 4. Rip up and re-route solved nets using actual A* routing
      for (int netNo : solved) {
        BatchAutorouter.NetRouteResult result = batchAutorouter.routeNet(netNo,
            5 + relaxationLevel * 2); // Higher ripup costs at higher relaxation
        if (result.isRouted()) {
          phase3Routed.add(netNo);
        } else {
          // Even if greedy picks it, actual routing may fail
          solved.remove(netNo);
        }
      }
      remaining.removeAll(solved);

      relaxationLevel++;
    }

    // Final attempt: route all remaining with maximum aggression
    for (int netNo : new HashSet<>(remaining)) {
      BatchAutorouter.NetRouteResult result = batchAutorouter.routeNet(netNo, 20);
      if (result.isRouted()) {
        phase3Routed.add(netNo);
        remaining.remove(netNo);
      } else {
        failedNets.add(netNo);
      }
    }

    logResults(remaining);
    return phase3Routed;
  }

  /**
   * Generate multiple candidate routing solutions for each remaining net.
   * Uses BatchAutorouter with different ripup pass values to get diverse paths.
   */
  private void generateCandidatePaths(Set<Integer> netNumbers) {
    candidatePaths.clear();
    for (int netNo : netNumbers) {
      Net net = board.rules.nets.get(netNo);
      if (net == null) continue;
      Collection<Pin> pins = net.get_pins();
      if (pins.size() < 2) continue;

      List<CandidatePath> paths = new ArrayList<>();

      // Try routing with multiple ripup passes for diversity
      for (int pass = 1; pass <= CANDIDATE_ROUTING_PASSES; pass++) {
        BatchAutorouter.NetRouteResult result = batchAutorouter.routeNet(netNo,
            2 + pass + relaxationLevel * 3);

        if (result.isRouted()) {
          // Compute an estimated cost based on the result
          double estCost = estimatePathCost(net, pass);
          List<FloatPoint> waypoints = extractWaypoints(net);
          paths.add(new CandidatePath(netNo, waypoints, estCost, pass));
        }
      }

      // Also generate a fallback Manhattan path for the greedy solver
      if (paths.isEmpty()) {
        CandidatePath fallback = generateSimplePath(pins);
        if (fallback != null) {
          paths.add(fallback);
        }
      }

      if (!paths.isEmpty()) {
        candidatePaths.put(netNo, paths);
      }
    }
  }

  /**
   * Estimate path cost based on net geometry and routing pass.
   */
  private double estimatePathCost(Net net, int pass) {
    // Simple heuristic: Manhattan distance weighted by pass number
    Collection<Pin> pins = net.get_pins();
    if (pins.size() < 2) return Double.MAX_VALUE;
    double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
    double maxX = Double.MIN_VALUE, maxY = Double.MIN_VALUE;
    for (Pin pin : pins) {
      FloatPoint c = pin.get_center().to_float();
      minX = Math.min(minX, c.x);
      maxX = Math.max(maxX, c.x);
      minY = Math.min(minY, c.y);
      maxY = Math.max(maxY, c.y);
    }
    double manhattan = (maxX - minX) + (maxY - minY);
    // Lower pass = cheaper (prefer simpler solutions)
    return manhattan * (1.0 + (pass - 1) * 0.5);
  }

  /**
   * Extract waypoints from actual traces of a routed net.
   */
  private List<FloatPoint> extractWaypoints(Net net) {
    List<FloatPoint> waypoints = new ArrayList<>();
    for (Item item : net.get_items()) {
      if (item instanceof PolylineTrace trace) {
        waypoints.add(trace.first_corner().to_float());
        // Add intermediate corners
        for (int i = 1; i < trace.corner_count(); i++) {
          waypoints.add(trace.polyline().corner_approx(i));
        }
      }
    }
    return waypoints;
  }

  /**
   * Generate a simple Manhattan path as fallback.
   */
  private CandidatePath generateSimplePath(Collection<Pin> pins) {
    List<FloatPoint> waypoints = new ArrayList<>();
    double totalCost = 0.0;
    List<Pin> pinList = new ArrayList<>(pins);
    pinList.sort((a, b) -> {
      int cmp = Double.compare(a.get_center().to_float().x, b.get_center().to_float().x);
      if (cmp == 0) cmp = Double.compare(a.get_center().to_float().y, b.get_center().to_float().y);
      return cmp;
    });
    FloatPoint prev = pinList.get(0).get_center().to_float();
    waypoints.add(prev);
    for (int i = 1; i < pinList.size(); i++) {
      FloatPoint curr = pinList.get(i).get_center().to_float();
      waypoints.add(curr);
      double dx = Math.abs(curr.x - prev.x);
      double dy = Math.abs(curr.y - prev.y);
      totalCost += dx + dy;
      prev = curr;
    }
    return new CandidatePath(0, waypoints, totalCost, 0);
  }

  /**
   * Build a conflict graph between nets based on candidate path overlaps.
   */
  private void buildConflictGraph(Set<Integer> netNumbers) {
    conflictGraph.clear();
    List<Integer> netList = new ArrayList<>(netNumbers);
    netList.removeIf(n -> !candidatePaths.containsKey(n) || candidatePaths.get(n).isEmpty());
    for (int i = 0; i < netList.size(); i++) {
      for (int j = i + 1; j < netList.size(); j++) {
        if (pathsConflict(netList.get(i), netList.get(j))) {
          conflictGraph.computeIfAbsent(netList.get(i), k -> new HashSet<>()).add(netList.get(j));
          conflictGraph.computeIfAbsent(netList.get(j), k -> new HashSet<>()).add(netList.get(i));
        }
      }
    }
  }

  /**
   * Check if any candidate paths of two nets conflict.
   */
  private boolean pathsConflict(int netA, int netB) {
    List<CandidatePath> pathsA = candidatePaths.get(netA);
    List<CandidatePath> pathsB = candidatePaths.get(netB);
    if (pathsA == null || pathsB == null) return true;
    for (CandidatePath pathA : pathsA) {
      for (CandidatePath pathB : pathsB) {
        if (candidatePathsOverlap(pathA, pathB)) return true;
      }
    }
    return false;
  }

  /**
   * Check if two candidate paths overlap spatially.
   */
  private boolean candidatePathsOverlap(CandidatePath a, CandidatePath b) {
    for (FloatPoint pa : a.waypoints) {
      for (FloatPoint pb : b.waypoints) {
        double dx = Math.abs(pa.x - pb.x);
        double dy = Math.abs(pa.y - pb.y);
        if (dx < 10000 && dy < 10000) return true;
      }
    }
    return false;
  }

  /**
   * Greedy maximum independent set solver with relaxation.
   * Sorts nets by conflict degree (least conflicted first),
   * then selects compatible nets using weighted criteria.
   */
  private Set<Integer> greedyMaxSatSolve(Set<Integer> netNumbers) {
    Set<Integer> solved = new HashSet<>();
    List<Integer> sortedNets = new ArrayList<>(netNumbers);
    // Sort by: (1) fewer conflicts first, (2) lower cost first
    sortedNets.sort((a, b) -> {
      int ca = conflictGraph.getOrDefault(a, Collections.emptySet()).size();
      int cb = conflictGraph.getOrDefault(b, Collections.emptySet()).size();
      int cmp = Integer.compare(ca, cb);
      if (cmp != 0) return cmp;
      // Secondary: prefer nets with more/better candidate paths
      double costA = getBestCandidateCost(a);
      double costB = getBestCandidateCost(b);
      return Double.compare(costA, costB);
    });

    Set<Integer> used = new HashSet<>();
    for (int netNo : sortedNets) {
      // Check if this net conflicts with any already-selected net
      Set<Integer> conflicts = conflictGraph.getOrDefault(netNo, Collections.emptySet());
      boolean hasConflict = false;
      int maxAllowedConflicts = relaxationLevel; // Allow more conflicts at higher relaxation
      int actualConflicts = 0;
      for (int selected : used) {
        if (conflicts.contains(selected)) {
          actualConflicts++;
          if (actualConflicts > maxAllowedConflicts) {
            hasConflict = true;
            break;
          }
        }
      }
      if (!hasConflict) {
        solved.add(netNo);
        used.add(netNo);
      }
    }

    return solved;
  }

  /**
   * Get the lowest cost among all candidate paths for a net.
   */
  private double getBestCandidateCost(int netNo) {
    List<CandidatePath> paths = candidatePaths.get(netNo);
    if (paths == null || paths.isEmpty()) return Double.MAX_VALUE;
    return paths.stream().mapToDouble(p -> p.totalCost).min().orElse(Double.MAX_VALUE);
  }

  private void logResults(Set<Integer> remaining) {
    FRLogger.debug(String.format(
        "Phase 3 complete: %d routed, %d failed",
        phase3Routed.size(), remaining.size()));
  }

  public Set<Integer> getPhase3Routed() { return phase3Routed; }
  public Set<Integer> getFailedNets() { return failedNets; }
  public int getRelaxationLevel() { return relaxationLevel; }

  private static class CandidatePath {
    final int netNo;
    final List<FloatPoint> waypoints;
    final double totalCost;
    final int routingPass;

    CandidatePath(int netNo, List<FloatPoint> waypoints, double totalCost, int routingPass) {
      this.netNo = netNo;
      this.waypoints = waypoints;
      this.totalCost = totalCost;
      this.routingPass = routingPass;
    }
  }
}
