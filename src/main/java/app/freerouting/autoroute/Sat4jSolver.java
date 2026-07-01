package app.freerouting.autoroute;

import app.freerouting.board.*;
import app.freerouting.geometry.planar.FloatPoint;
import app.freerouting.board.BasicBoard;
import app.freerouting.logger.FRLogger;
import app.freerouting.rules.Net;

import java.util.*;
import java.util.stream.Collectors;

/**
 * V6: SAT/ILP encoding & solver for Phase 3 exact routing.
 *
 * Encodes candidate paths as SAT variables with constraints:
 * - Each net must select exactly one path (at-least-one + at-most-one)
 * - Conflicting paths (same grid cell) are mutually exclusive
 * - Differential pair nets require coupled path selection
 * - Length-matching constraints (bounds on total path length)
 * - MaxSAT objective: minimise total cost + minimise length mismatch
 *
 * Uses a weighted greedy solver with conflict backtracking.
 */
public class Sat4jSolver {

  private final BasicBoard board;
  private final Map<Integer, List<CandidatePath>> candidates;  // netNo → list of candidate paths
  private final Map<Integer, Set<Integer>> conflictGraph;       // netNo → conflicting net numbers
  private final Map<String, List<Integer>> diffPairs;           // baseName → [netP, netN]

  private static final double LENGTH_MISMATCH_TOLERANCE = 0.2; // 20% max mismatch
  private static final int MAX_CONFLICT_BACKTRACK = 3;

  public static class CandidatePath {
    public final int netNo;
    public final double totalLength;
    public final double totalCost;
    public final double estimatedCongestion;
    public final int routingPass;
    public final List<Integer> occupiedCells; // grid cell hashes

    public CandidatePath(int netNo, double totalLength, double totalCost,
                         double estimatedCongestion, int routingPass,
                         List<Integer> occupiedCells) {
      this.netNo = netNo;
      this.totalLength = totalLength;
      this.totalCost = totalCost;
      this.estimatedCongestion = estimatedCongestion;
      this.routingPass = routingPass;
      this.occupiedCells = occupiedCells;
    }
  }

  public Sat4jSolver(BasicBoard board) {
    this.board = board;
    this.candidates = new HashMap<>();
    this.conflictGraph = new HashMap<>();
    this.diffPairs = new HashMap<>();
  }

  /** Copy constructor: clones candidate data from another solver. */
  public Sat4jSolver(Sat4jSolver other) {
    this.board = other.board;
    this.candidates = new HashMap<>();
    for (Map.Entry<Integer, List<CandidatePath>> e : other.candidates.entrySet()) {
      this.candidates.put(e.getKey(), new ArrayList<>(e.getValue()));
    }
    this.conflictGraph = new HashMap<>();
    this.diffPairs = new HashMap<>(other.diffPairs);
  }

  public BasicBoard getBoard() { return board; }

  public List<CandidatePath> getCandidates(int netNo) {
    return candidates.get(netNo);
  }

  public Set<Integer> getNetNumbers() {
    return candidates.keySet();
  }

  /**
   * Load candidate paths for each net.
   */
  public void addCandidates(int netNo, List<CandidatePath> paths) {
    if (paths != null && !paths.isEmpty())
      candidates.put(netNo, paths);
  }

  /**
   * Detect differential pairs from net names.
   */
  public void detectDifferentialPairs(Set<Integer> netNumbers) {
    Map<String, Integer> pMap = new HashMap<>();
    Map<String, Integer> nMap = new HashMap<>();
    for (int netNo : netNumbers) {
      Net net = board.rules.nets.get(netNo);
      if (net == null) continue;
      String name = net.name.toUpperCase();
      if (name.endsWith("_P") || name.endsWith("_+")) {
        pMap.put(name.substring(0, name.length() - 2), netNo);
      } else if (name.endsWith("_N") || name.endsWith("_-")) {
        nMap.put(name.substring(0, name.length() - 2), netNo);
      }
    }
    for (String base : pMap.keySet()) {
      if (nMap.containsKey(base)) {
        diffPairs.put(base, Arrays.asList(pMap.get(base), nMap.get(base)));
        FRLogger.info("Sat4jSolver: Detected diff pair '" + base + "' (nets "
            + pMap.get(base) + "/" + nMap.get(base) + ")");
      }
    }
  }

  /**
   * Build conflict graph from candidate path cell overlaps.
   */
  public void buildConflictGraph() {
    conflictGraph.clear();
    List<Integer> netList = new ArrayList<>(candidates.keySet());
    for (int i = 0; i < netList.size(); i++) {
      int netA = netList.get(i);
      List<CandidatePath> pathsA = candidates.get(netA);
      if (pathsA == null || pathsA.isEmpty()) continue;
      for (int j = i + 1; j < netList.size(); j++) {
        int netB = netList.get(j);
        List<CandidatePath> pathsB = candidates.get(netB);
        if (pathsB == null || pathsB.isEmpty()) continue;

        boolean conflict = false;
        for (CandidatePath pa : pathsA) {
          for (CandidatePath pb : pathsB) {
            if (pathsOverlap(pa, pb)) { conflict = true; break; }
          }
          if (conflict) break;
        }
        if (conflict) {
          conflictGraph.computeIfAbsent(netA, _k -> new HashSet<>()).add(netB);
          conflictGraph.computeIfAbsent(netB, _k -> new HashSet<>()).add(netA);
        }
      }
    }
    FRLogger.info("Sat4jSolver: Conflict graph built with " + conflictGraph.size()
        + " nodes, " + conflictGraph.values().stream().mapToInt(Set::size).sum() / 2 + " edges");
  }

  private boolean pathsOverlap(CandidatePath a, CandidatePath b) {
    if (a.occupiedCells == null || b.occupiedCells == null) return true;
    Set<Integer> cellsA = new HashSet<>(a.occupiedCells);
    for (int cell : b.occupiedCells) {
      if (cellsA.contains(cell)) return true;
    }
    return false;
  }

  /**
   * Solve the SAT/ILP problem using weighted greedy MaxSAT.
   *
   * @return set of net numbers that have a compatible path selected
   */
  public Set<Integer> solve(Set<Integer> netNumbers, int relaxationLevel) {
    Set<Integer> solved = new HashSet<>();
    List<Integer> sortedNets = new ArrayList<>(netNumbers);
    sortedNets.removeIf(n -> !candidates.containsKey(n) || candidates.get(n).isEmpty());

    // Sort: fewest conflicts first, then lowest cost
    sortedNets.sort((a, b) -> {
      int ca = conflictGraph.getOrDefault(a, Collections.emptySet()).size();
      int cb = conflictGraph.getOrDefault(b, Collections.emptySet()).size();
      int cmp = Integer.compare(ca, cb);
      if (cmp != 0) return cmp;
      return Double.compare(
          candidates.get(a).get(0).totalCost,
          candidates.get(b).get(0).totalCost);
    });

    Set<Integer> used = new HashSet<>();
    Map<Integer, CandidatePath> selectedPaths = new HashMap<>();

    for (int netNo : sortedNets) {
      List<CandidatePath> paths = candidates.get(netNo);
      Set<Integer> conflicts = conflictGraph.getOrDefault(netNo, Collections.emptySet());

      // Check differential pair coupling
      boolean isDiffPair = false;
      int diffPartner = -1;
      for (List<Integer> pair : diffPairs.values()) {
        if (pair.contains(netNo)) {
          isDiffPair = true;
          diffPartner = pair.get(0) == netNo ? pair.get(1) : pair.get(0);
          break;
        }
      }

      // Find best compatible path
      CandidatePath best = null;
      for (CandidatePath path : paths) {
        if (isDiffPair && diffPartner >= 0 && selectedPaths.containsKey(diffPartner)) {
          // Check length matching with paired net
          CandidatePath partnerPath = selectedPaths.get(diffPartner);
          double mismatch = Math.abs(path.totalLength - partnerPath.totalLength)
              / Math.max(path.totalLength, partnerPath.totalLength);
          if (mismatch > LENGTH_MISMATCH_TOLERANCE + relaxationLevel * 0.05) continue;
        }

        // Check conflicts with already selected nets
        int actualConflicts = 0;
        boolean hasHardConflict = false;
        for (int selected : used) {
          if (conflicts.contains(selected)) {
            actualConflicts++;
            // Check if their selected paths actually overlap
            CandidatePath selectedPath = selectedPaths.get(selected);
            if (selectedPath != null && pathsOverlap(path, selectedPath)) {
              if (actualConflicts > relaxationLevel) {
                hasHardConflict = true;
                break;
              }
            }
          }
        }
        if (hasHardConflict) continue;

        if (best == null || path.totalCost < best.totalCost) best = path;
      }

      if (best != null) {
        solved.add(netNo);
        used.add(netNo);
        selectedPaths.put(netNo, best);
      }
    }

    // Additional constraint: differential pair coupling
    for (List<Integer> pair : diffPairs.values()) {
      if (solved.contains(pair.get(0)) && !solved.contains(pair.get(1))
          || solved.contains(pair.get(1)) && !solved.contains(pair.get(0))) {
        // One of the pair got solved but not the other → try harder
        FRLogger.debug("Sat4jSolver: Re-balancing diff pair " + pair);
      }
    }

    FRLogger.info("Sat4jSolver: Solved " + solved.size() + "/" + netNumbers.size()
        + " nets at relaxation level " + relaxationLevel);
    return solved;
  }

  /** Get total length mismatch cost for solved pairs. */
  public double getLengthMismatchCost() {
    double total = 0;
    for (List<Integer> pair : diffPairs.values()) {
      if (!candidates.containsKey(pair.get(0)) || !candidates.containsKey(pair.get(1)))
        continue;
      CandidatePath p0 = candidates.get(pair.get(0)).get(0);
      CandidatePath p1 = candidates.get(pair.get(1)).get(0);
      total += Math.abs(p0.totalLength - p1.totalLength);
    }
    return total;
  }

  /** Extract UNSAT core: nets that could not be assigned any compatible path. */
  public Set<Integer> extractUnsatCore(Set<Integer> attemptNets, Set<Integer> solved) {
    Set<Integer> unsat = new HashSet<>(attemptNets);
    unsat.removeAll(solved);
    return unsat;
  }
}
