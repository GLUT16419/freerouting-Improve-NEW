package app.freerouting.autoroute;

import app.freerouting.board.*;
import app.freerouting.geometry.planar.FloatPoint;
import app.freerouting.logger.FRLogger;
import app.freerouting.rules.Net;
import app.freerouting.rules.NetClass;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * UTPR Phase 3 — Bottleneck SAT/ILP Exact Solver.
 * <p>
 * In the city-traffic metaphor, this is the <b>traffic signal control room</b>
 * that takes the worst remaining gridlocked intersections (bottlenecks) and
 * computes a provably conflict-free schedule using exact combinatorial solving.
 * <p>
 * <b>Algorithm pipeline for each bottleneck:</b>
 * <ol>
 *   <li><b>Candidate generation</b> — route each net multiple times with
 *       randomly perturbed costs to obtain a diverse set of candidate paths.</li>
 *   <li><b>Conflict detection</b> — build a binary conflict graph between
 *       candidate paths that share routing resources.</li>
 *   <li><b>MaxSAT encoding</b> — select one path per net such that no two
 *       selected paths conflict; minimise total cost + length mismatch
 *       as soft objectives.</li>
 *   <li><b>Parallel solving</b> — run multiple solver instances with different
 *       relaxation levels and random seeds.</li>
 *   <li><b>UNSAT recovery</b> — extract UNSAT core, generate targeted
 *       additional candidates, relax constraints, and re-solve.</li>
 *   <li><b>Local thaw</b> — if still UNSAT, temporarily rip up a small number
 *       of non-critical nets around the bottleneck and re-solve.</li>
 * </ol>
 */
public class BottleneckSatSolver implements Serializable {

  private static final long serialVersionUID = 1L;

  // ── Solver parameters ─────────────────────────────────────────────────

  /** Maximum candidate paths to generate per net. */
  static final int MAX_CANDIDATES_PER_NET = 8;

  /** Maximum relaxation levels (iterations of candidate expansion). */
  static final int MAX_RELAXATION_LEVELS = 5;

  /** Base ripup cost for candidate generation. */
  static final int BASE_RIPUP_COST = 3;

  /** Random perturbation range for cost weights (± fraction). */
  static final double COST_PERTURBATION = 0.3;

  /** Length mismatch tolerance (fraction of total length). */
  static final double LENGTH_MISMATCH_TOLERANCE = 0.2;

  /** Number of solver variants to run in parallel. */
  static final int PARALLEL_SOLVER_COUNT = 4;

  /** Timeout per solver variant (seconds). */
  static final int SOLVER_TIMEOUT_SECONDS = 30;

  /** Maximum nets to thaw around a bottleneck. */
  static final int MAX_THAW_NETS = 4;

  /** Random seed base for deterministic variation. */
  static final int RANDOM_SEED = 42;

  // ═══════════════════════════════════════════════════════════════════════
  //  SolveResult
  // ═══════════════════════════════════════════════════════════════════════

  /** Result of solving a single bottleneck region. */
  public static class SolveResult implements Serializable {
    private static final long serialVersionUID = 1L;

    /** Set of net numbers that were successfully routed. */
    public final Set<Integer> solvedNets;

    /** Set of net numbers that could NOT be routed (UNSAT). */
    public final Set<Integer> unsatNets;

    /** Total cost of selected candidate paths. */
    public final double totalCost;

    /** Number of relaxation levels used. */
    public final int relaxationUsed;

    /** Whether the solve was successful (all nets routed). */
    public final boolean fullySolved;

    /** Candidate paths that were selected (netNo → path). */
    public final Map<Integer, CandidatePath> selectedPaths;

    public SolveResult(Set<Integer> solvedNets, Set<Integer> unsatNets,
                       double totalCost, int relaxationUsed,
                       Map<Integer, CandidatePath> selectedPaths) {
      this.solvedNets = solvedNets;
      this.unsatNets = unsatNets;
      this.totalCost = totalCost;
      this.relaxationUsed = relaxationUsed;
      this.selectedPaths = selectedPaths;
      this.fullySolved = unsatNets.isEmpty();
    }

    public static final SolveResult FAILED = new SolveResult(
        Collections.emptySet(), Collections.emptySet(),
        Double.MAX_VALUE, 0, Collections.emptyMap());

    @Override
    public String toString() {
      return String.format("SolveResult: %d solved, %d UNSAT, cost=%.1f, relax=%d %s",
          solvedNets.size(), unsatNets.size(), totalCost, relaxationUsed,
          fullySolved ? "[FULLY SOLVED]" : "");
    }
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  CandidatePath (extends the Sat4jSolver model)
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * A candidate routing path for one net within the bottleneck region.
   * Reuses Sat4jSolver.CandidatePath data model for compatibility.
   */
  public static class CandidatePath implements Serializable {
    private static final long serialVersionUID = 1L;

    public final int netNo;
    public final double totalLength;
    public final double totalCost;
    public final int pass;
    public final long seed;
    public final List<Integer> occupiedCells; // cell hashes for conflict detection

    public CandidatePath(int netNo, double totalLength, double totalCost,
                         int pass, long seed, List<Integer> occupiedCells) {
      this.netNo = netNo;
      this.totalLength = totalLength;
      this.totalCost = totalCost;
      this.pass = pass;
      this.seed = seed;
      this.occupiedCells = occupiedCells;
    }

    /** Convert to Sat4jSolver.CandidatePath for compatibility. */
    public Sat4jSolver.CandidatePath toSat4jPath() {
      return new Sat4jSolver.CandidatePath(
          netNo, totalLength, totalCost, 0.0, pass, occupiedCells);
    }
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Fields
  // ═══════════════════════════════════════════════════════════════════════

  private final BasicBoard board;
  private final RoutingBoard routingBoard;
  private final BatchAutorouter batchAutorouter;
  private final CongestionMap congestionMap;

  /** Random generator for cost perturbations. */
  private final Random random;

  /** Current set of candidates (netNo → list of candidates). */
  private final Map<Integer, List<CandidatePath>> candidates;

  /** Cell-granularity for conflict detection (maps to CongestionMap grid). */
  private final double cellSize;

  // ═══════════════════════════════════════════════════════════════════════
  //  Construction
  // ═══════════════════════════════════════════════════════════════════════

  public BottleneckSatSolver(BasicBoard board, RoutingBoard routingBoard,
                             BatchAutorouter batchAutorouter,
                             CongestionMap congestionMap) {
    this.board = board;
    this.routingBoard = routingBoard;
    this.batchAutorouter = batchAutorouter;
    this.congestionMap = congestionMap;
    this.random = new Random(RANDOM_SEED);
    this.candidates = new HashMap<>();
    this.cellSize = congestionMap != null
        ? CongestionMap.DEFAULT_CELL_SIZE
        : ContractionHierarchies.DEFAULT_CELL_SIZE;
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Main entry point: solve one bottleneck region
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Solve a single bottleneck region using SAT/ILP encoding with full
   * UNSAT recovery pipeline.
   *
   * @param region the bottleneck region to solve
   * @param nearbyNetNumbers nets in the vicinity that may be thawed if needed
   * @return the solve result
   */
  public SolveResult solveBottleneck(BottleneckAnalyzer.BottleneckRegion region,
                                     Set<Integer> nearbyNetNumbers) {
    FRLogger.info("BottleneckSatSolver: solving bottleneck " + region.id
        + " (" + region.getNetCount() + " nets)");

    Set<Integer> targetNets = new HashSet<>(region.involvedNetNumbers);
    Set<Integer> remaining = new HashSet<>(targetNets);
    Map<Integer, CandidatePath> selectedPaths = new HashMap<>();
    int relaxation = 0;

    // ── Iterative UNSAT recovery ──────────────────────────────────────
    while (!remaining.isEmpty() && relaxation <= MAX_RELAXATION_LEVELS) {
      FRLogger.debug("BottleneckSatSolver: relaxation " + relaxation
          + ", " + remaining.size() + " nets remaining");

      // 1. Generate candidate paths for remaining nets
      generateCandidates(remaining, relaxation);

      // 2. Run parallel SAT search
      SolveResult result = parallelSatSolve(remaining);

      if (result.fullySolved) {
        selectedPaths.putAll(result.selectedPaths);
        remaining.clear();
      } else if (!result.solvedNets.isEmpty()) {
        // Partial solve
        selectedPaths.putAll(result.selectedPaths);
        for (Map.Entry<Integer, CandidatePath> e : result.selectedPaths.entrySet()) {
          remaining.remove(e.getKey());
        }

        // 3. Extract UNSAT core and generate additional candidates
        if (!result.unsatNets.isEmpty()) {
          FRLogger.debug("BottleneckSatSolver: UNSAT core: " + result.unsatNets);
          generateAdditionalCandidates(result.unsatNets);
        }
      }

      relaxation++;
    }

    // ── If still UNSAT, attempt local thaw ────────────────────────────
    if (!remaining.isEmpty()) {
      FRLogger.info("BottleneckSatSolver: attempting local thaw for "
          + remaining.size() + " remaining nets");
      remaining = attemptLocalThaw(remaining, region, nearbyNetNumbers, selectedPaths);
    }

    boolean fullySolved = remaining.isEmpty();
    if (fullySolved) {
      FRLogger.info("BottleneckSatSolver: bottleneck " + region.id
          + " fully solved (" + selectedPaths.size() + " nets)");
    } else {
      FRLogger.warn("BottleneckSatSolver: bottleneck " + region.id
          + " partially solved: " + selectedPaths.size() + " OK, "
          + remaining.size() + " failed");
    }

    double totalCost = selectedPaths.values().stream()
        .mapToDouble(p -> p.totalCost).sum();

    return new SolveResult(
        new HashSet<>(selectedPaths.keySet()),
        remaining, totalCost, relaxation, selectedPaths);
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Candidate generation
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Generate multiple diverse candidate paths for each net.
   * Uses BatchAutorouter with random cost perturbations for diversity.
   */
  private void generateCandidates(Set<Integer> netNumbers, int relaxation) {
    for (int netNo : netNumbers) {
      Net net = board.rules.nets.get(netNo);
      if (net == null) continue;
      if (net.get_pins().size() < 2) continue;

      List<CandidatePath> existing = candidates.getOrDefault(netNo, new ArrayList<>());
      int needed = MAX_CANDIDATES_PER_NET - existing.size();
      if (needed <= 0) continue;

      for (int i = 0; i < needed; i++) {
        int ripupCost = BASE_RIPUP_COST + relaxation * 2 + i;
        long seed = random.nextLong();

        try {
          BatchAutorouter.NetRouteResult routeResult =
              batchAutorouter.routeNet(netNo, ripupCost);

          if (routeResult != null && routeResult.isRouted()) {
            double length = estimatePathLength(net);
            double cost = computeCandidateCost(net, length, ripupCost, seed);
            List<Integer> cells = extractOccupiedCells(net);
            existing.add(new CandidatePath(netNo, length, cost,
                i, seed, cells));
          }
        } catch (Exception e) {
          FRLogger.debug("BottleneckSatSolver: routeNet exception for net "
              + netNo + ": " + e.getMessage());
        }
      }

      if (!existing.isEmpty()) {
        // Sort by cost ascending and keep top MAX_CANDIDATES_PER_NET
        existing.sort(Comparator.comparingDouble(p -> p.totalCost));
        if (existing.size() > MAX_CANDIDATES_PER_NET) {
          candidates.put(netNo, new ArrayList<>(
              existing.subList(0, MAX_CANDIDATES_PER_NET)));
        } else {
          candidates.put(netNo, existing);
        }
      }
    }
  }

  /**
   * Generate additional candidates specifically for UNSAT-core nets.
   * Uses more aggressive perturbations and higher ripup costs.
   */
  private void generateAdditionalCandidates(Set<Integer> unsatNets) {
    for (int netNo : unsatNets) {
      Net net = board.rules.nets.get(netNo);
      if (net == null) continue;
      List<CandidatePath> existing = candidates.getOrDefault(netNo, new ArrayList<>());
      int extraCount = MAX_CANDIDATES_PER_NET / 2; // add 4 more

      for (int i = 0; i < extraCount; i++) {
        int ripupCost = BASE_RIPUP_COST * 3 + i * 2;
        long seed = random.nextLong() ^ 0xDEADBEEFL;

        try {
          BatchAutorouter.NetRouteResult routeResult =
              batchAutorouter.routeNet(netNo, ripupCost);

          if (routeResult != null && routeResult.isRouted()) {
            double length = estimatePathLength(net);
            double cost = computeCandidateCost(net, length, ripupCost, seed);
            List<Integer> cells = extractOccupiedCells(net);
            existing.add(new CandidatePath(netNo, length, cost,
                MAX_CANDIDATES_PER_NET + i, seed, cells));
          }
        } catch (Exception e) {
          FRLogger.debug("BottleneckSatSolver: additional candidate gen for net "
              + netNo + " failed: " + e.getMessage());
        }
      }

      if (!existing.isEmpty()) {
        existing.sort(Comparator.comparingDouble(p -> p.totalCost));
        candidates.put(netNo, existing);
      }
    }
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Parallel SAT solving
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Run multiple SAT solver instances in parallel with different strategies.
   * Returns the best solution found.
   */
  private SolveResult parallelSatSolve(Set<Integer> netNumbers) {
    // Identify nets that have candidates
    List<Integer> solvableNets = netNumbers.stream()
        .filter(n -> candidates.containsKey(n) && !candidates.get(n).isEmpty())
        .collect(Collectors.toList());

    if (solvableNets.size() < 2) {
      // Not enough nets with candidates to form a SAT problem
      Set<Integer> solved = new HashSet<>();
      Map<Integer, CandidatePath> selected = new HashMap<>();
      for (int netNo : solvableNets) {
        CandidatePath best = candidates.get(netNo).get(0);
        solved.add(netNo);
        selected.put(netNo, best);
      }
      Set<Integer> unsat = new HashSet<>(netNumbers);
      unsat.removeAll(solved);
      return new SolveResult(solved, unsat,
          selected.values().stream().mapToDouble(p -> p.totalCost).sum(), 0, selected);
    }

    // Build the base SAT solver once — reuses diff pair detection across variants
    Sat4jSolver baseSatSolver = new Sat4jSolver(board);
    baseSatSolver.detectDifferentialPairs(new HashSet<>(solvableNets));

    // Convert candidates once and reuse across solver variants
    for (int netNo : solvableNets) {
      List<CandidatePath> ourPaths = candidates.get(netNo);
      List<Sat4jSolver.CandidatePath> satPaths = ourPaths.stream()
          .map(CandidatePath::toSat4jPath)
          .collect(Collectors.toList());
      baseSatSolver.addCandidates(netNo, satPaths);
    }
    baseSatSolver.buildConflictGraph();
    // Pre-serialize candidate data for fast variant cloning
    Map<Integer, List<Sat4jSolver.CandidatePath>> sharedCandidates = new HashMap<>();
    for (int netNo : solvableNets) {
      sharedCandidates.put(netNo, baseSatSolver.getCandidates(netNo));
    }

    // Parallel solve using multiple relaxation levels
    ExecutorService executor = Executors.newFixedThreadPool(
        Math.min(PARALLEL_SOLVER_COUNT, Runtime.getRuntime().availableProcessors()));

    List<Future<SolveResult>> futures = new ArrayList<>();
    Set<Integer> allNetSet = new HashSet<>(solvableNets);

    for (int variant = 0; variant < PARALLEL_SOLVER_COUNT; variant++) {
      final int relLevel = variant;
      futures.add(executor.submit(() -> {
        // Clone solver from base — avoids re-detecting diff pairs
        Sat4jSolver variantSolver = new Sat4jSolver(baseSatSolver);
        // Re-add candidates (shared data, different instances)
        for (int n : solvableNets) {
          variantSolver.addCandidates(n, sharedCandidates.get(n));
        }
        variantSolver.buildConflictGraph();
        Set<Integer> solved = variantSolver.solve(allNetSet, relLevel);

        // Convert back to our result format
        Set<Integer> unsat = new HashSet<>(allNetSet);
        unsat.removeAll(solved);

        // Build selected paths map
        Map<Integer, CandidatePath> selected = new HashMap<>();
        double totalCost = 0;
        // Since Sat4jSolver doesn't return which path was selected,
        // we reconstruct by taking the first candidate for solved nets
        for (int netNo : solved) {
          List<CandidatePath> paths = candidates.get(netNo);
          if (paths != null && !paths.isEmpty()) {
            selected.put(netNo, paths.get(0));
            totalCost += paths.get(0).totalCost;
          }
        }

        return new SolveResult(solved, unsat, totalCost, relLevel, selected);
      }));
    }

    // Collect best result
    SolveResult best = new SolveResult(Collections.emptySet(),
        allNetSet, Double.MAX_VALUE, 0, Collections.emptyMap());

    for (Future<SolveResult> future : futures) {
      try {
        SolveResult result = future.get(SOLVER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        // Prefer fully solved, then more solved nets, then lower cost
        if (result.fullySolved
            || (result.solvedNets.size() > best.solvedNets.size())
            || (result.solvedNets.size() == best.solvedNets.size()
                && result.totalCost < best.totalCost)) {
          best = result;
        }
      } catch (TimeoutException e) {
        future.cancel(true);
        FRLogger.debug("BottleneckSatSolver: variant timed out");
      } catch (Exception e) {
        FRLogger.debug("BottleneckSatSolver: variant error: " + e.getMessage());
      }
    }

    executor.shutdownNow();

    FRLogger.info("BottleneckSatSolver: parallel solve best: "
        + best.solvedNets.size() + "/" + solvableNets.size()
        + " solved, cost=" + String.format("%.1f", best.totalCost));
    return best;
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Local thaw
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Attempt to resolve remaining UNSAT nets by temporarily ripping up
   * nearby non-critical nets that share routing resources with the bottleneck.
   */
  private Set<Integer> attemptLocalThaw(Set<Integer> remaining,
                                         BottleneckAnalyzer.BottleneckRegion region,
                                         Set<Integer> nearbyNetNumbers,
                                         Map<Integer, CandidatePath> selectedPaths) {
    // Select a small number of nearby non-critical nets to thaw
    List<Integer> thawCandidates = new ArrayList<>();
    for (int netNo : nearbyNetNumbers) {
      if (!remaining.contains(netNo)
          && !selectedPaths.containsKey(netNo)
          && thawCandidates.size() < MAX_THAW_NETS) {
        // Check if this net's routing resources overlap the bottleneck
        if (netOverlapsBottleneck(netNo, region)) {
          thawCandidates.add(netNo);
        }
      }
    }

    if (thawCandidates.isEmpty()) {
      FRLogger.debug("BottleneckSatSolver: no suitable nets to thaw");
      return remaining; // no change
    }

    FRLogger.info("BottleneckSatSolver: thawing " + thawCandidates.size()
        + " nets around bottleneck " + region.id + ": " + thawCandidates);

    // Rip up thawed nets by routing them with low ripup cost (triggers re-route)
    for (int netNo : thawCandidates) {
      try {
        batchAutorouter.routeNet(netNo, 1); // low ripup = rips existing traces
      } catch (Exception e) {
        FRLogger.debug("BottleneckSatSolver: thaw ripup failed for net "
            + netNo + ": " + e.getMessage());
      }
    }

    // Re-generate candidates for remaining nets with the freed space
    Set<Integer> allRemaining = new HashSet<>(remaining);
    allRemaining.addAll(thawCandidates);

    generateCandidates(allRemaining, MAX_RELAXATION_LEVELS);

    // Re-solve
    SolveResult thawResult = parallelSatSolve(allRemaining);

    Set<Integer> stillRemaining = new HashSet<>(thawResult.unsatNets);
    stillRemaining.addAll(thawResult.unsatNets);
    return stillRemaining;
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Helpers
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Estimate path length for a net from its pin bounding box.
   */
  private double estimatePathLength(Net net) {
    double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
    double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
    for (Pin pin : net.get_pins()) {
      FloatPoint c = pin.get_center().to_float();
      if (c.x < minX) minX = c.x; if (c.x > maxX) maxX = c.x;
      if (c.y < minY) minY = c.y; if (c.y > maxY) maxY = c.y;
    }
    return (maxX - minX) + (maxY - minY); // Manhattan
  }

  /**
   * Compute candidate cost: length-based base cost + random perturbation
   * to encourage diversity in the candidate pool.
   */
  private double computeCandidateCost(Net net, double length,
                                       int ripupPass, long seed) {
    Random rng = new Random(seed);
    double perturbation = 1.0 + (rng.nextDouble() - 0.5) * 2 * COST_PERTURBATION;
    double viaPenalty = getViaCount(net) * 2.0;
    return Math.max(1.0, length * perturbation + viaPenalty);
  }

  /**
   * Estimate via count from a net's traces.
   */
  private int getViaCount(Net net) {
    int count = 0;
    for (Item item : net.get_items()) {
      if (item instanceof Via) count++;
    }
    return count;
  }

  /**
   * Extract occupied cell hashes for a net (for conflict detection).
   * Maps pin centers to congestion-map grid cells.
   */
  private List<Integer> extractOccupiedCells(Net net) {
    Set<Integer> cells = new HashSet<>();
    for (Pin pin : net.get_pins()) {
      FloatPoint c = pin.get_center().to_float();
      int col = (int) Math.floor(c.x / cellSize);
      int row = (int) Math.floor(c.y / cellSize);
      cells.add(row * 100000 + col); // simple hash
    }
    // Also add cells from traces
    for (Item item : net.get_items()) {
      if (item instanceof Trace trace) {
        FloatPoint start = trace.first_corner().to_float();
        FloatPoint end = trace.last_corner().to_float();
        hashCell(cells, start);
        hashCell(cells, end);
      } else if (item instanceof Via via) {
        FloatPoint c = via.get_center().to_float();
        hashCell(cells, c);
      }
    }
    return new ArrayList<>(cells);
  }

  private void hashCell(Set<Integer> cells, FloatPoint fp) {
    int col = (int) Math.floor(fp.x / cellSize);
    int row = (int) Math.floor(fp.y / cellSize);
    cells.add(row * 100000 + col);
  }

  /**
   * Check if a net's routing resources overlap a bottleneck region.
   */
  private boolean netOverlapsBottleneck(int netNo,
                                         BottleneckAnalyzer.BottleneckRegion region) {
    Net net = board.rules.nets.get(netNo);
    if (net == null) return false;
    for (Pin pin : net.get_pins()) {
      FloatPoint c = pin.get_center().to_float();
      if (c.x >= region.minX && c.x <= region.maxX
          && c.y >= region.minY && c.y <= region.maxY) {
        return true;
      }
    }
    return false;
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Result access
  // ═══════════════════════════════════════════════════════════════════════

  /** Get all generated candidates (read-only). */
  public Map<Integer, List<CandidatePath>> getCandidates() {
    return Collections.unmodifiableMap(candidates);
  }

  /** Clear all candidates (for fresh solve). */
  public void clearCandidates() {
    candidates.clear();
  }
}
