package app.freerouting.autoroute;

import app.freerouting.board.BasicBoard;
import app.freerouting.logger.FRLogger;

import java.util.*;
import java.util.concurrent.*;

/**
 * V6: Multi-solver parallel manager for Phase 3 SAT/ILP exploration.
 *
 * Launches multiple SAT solver instances with different random seeds and
 * strategy parameters in parallel, collects the first SAT result, and
 * extracts UNSAT cores for incremental expansion.
 */
public class MultiSolverManager {

  private static final int SOLVER_TIMEOUT_SECONDS = 30;
  private static final int MAX_PARALLEL_SOLVERS = 4;

  private final Sat4jSolver baseSolver;
  private final ExecutorService executor;

  public MultiSolverManager(Sat4jSolver baseSolver) {
    this.baseSolver = baseSolver;
    this.executor = Executors.newFixedThreadPool(
        Math.min(MAX_PARALLEL_SOLVERS, Runtime.getRuntime().availableProcessors()));
  }

  /**
   * Solve the SAT problem with multiple solver instances in parallel.
   *
   * @param netNumbers the set of nets to solve
   * @param baseRelaxation base relaxation level
   * @return the best solution found (or empty if UNSAT)
   */
  public SolveResult parallelSolve(Set<Integer> netNumbers, int baseRelaxation) {
    List<Future<SolveResult>> futures = new ArrayList<>();

    // Launch solver variants with different strategies
    int numVariants = Math.min(MAX_PARALLEL_SOLVERS, netNumbers.size());
    for (int i = 0; i < numVariants; i++) {
      final int variant = i;
      futures.add(executor.submit(() -> {
        int relaxation = baseRelaxation + variant / 2;
        // Create a solver clone with this variant's configuration
        Sat4jSolver solver = new Sat4jSolver(baseSolver);
        Set<Integer> solved = solver.solve(netNumbers, relaxation);
        Set<Integer> unsat = solver.extractUnsatCore(netNumbers, solved);
        return new SolveResult(solved, unsat, variant, relaxation);
      }));
    }

    // Collect best result
    SolveResult best = null;
    int bestSolved = -1;
    for (Future<SolveResult> future : futures) {
      try {
        SolveResult result = future.get(SOLVER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        int count = result.solved.size();
        if (count > bestSolved) {
          bestSolved = count;
          best = result;
        }
      } catch (TimeoutException e) {
        future.cancel(true);
        FRLogger.warn("MultiSolver: Solver variant timed out");
      } catch (Exception e) {
        FRLogger.warn("MultiSolver: Solver error: " + e.getMessage());
      }
    }

    if (best == null) {
      best = new SolveResult(Collections.emptySet(), netNumbers, 0, baseRelaxation);
    }

    FRLogger.info("MultiSolver: " + best.solved.size() + " solved (best of "
        + numVariants + " variants), " + best.unsatCore.size() + " UNSAT");
    return best;
  }

  /**
   * Extract minimal UNSAT core by iteratively removing nets.
   */
  public Set<Integer> minimizeUnsatCore(Set<Integer> netNumbers, Set<Integer> solved) {
    Set<Integer> core = new HashSet<>(netNumbers);
    core.removeAll(solved);

    // Try removing each UNSAT net to see if the rest becomes solvable
    List<Integer> coreList = new ArrayList<>(core);
    Set<Integer> minimal = new HashSet<>(core);

    for (int netNo : coreList) {
      Set<Integer> reduced = new HashSet<>(minimal);
      reduced.remove(netNo);
      if (reduced.size() < 2) continue;

      Sat4jSolver trial = new Sat4jSolver(baseSolver);
      trial.detectDifferentialPairs(reduced);
      // Re-copy candidates from base solver for the trial
      for (int n : reduced) {
        trial.addCandidates(n, baseSolver.getCandidates(n));
      }
      // Trial solve
      Set<Integer> testSolved = trial.solve(reduced, 1);
      if (!testSolved.isEmpty()) {
        // This net was causing UNSAT-ness
        minimal.remove(netNo);
      }
    }

    FRLogger.info("MultiSolver: UNSAT core minimized from " + core.size()
        + " to " + minimal.size() + " nets");
    return minimal;
  }

  /** Shutdown executor service. */
  public void shutdown() {
    executor.shutdownNow();
  }

  /** Result of a parallel solve attempt. */
  public static class SolveResult {
    public final Set<Integer> solved;
    public final Set<Integer> unsatCore;
    public final int variant;
    public final int relaxation;

    public SolveResult(Set<Integer> solved, Set<Integer> unsatCore,
                       int variant, int relaxation) {
      this.solved = solved;
      this.unsatCore = unsatCore;
      this.variant = variant;
      this.relaxation = relaxation;
    }
  }

  /** Create a solver clone from the same board and candidate data. */
  private static Sat4jSolver cloneSolver(Sat4jSolver original) {
    Sat4jSolver clone = new Sat4jSolver(original.getBoard());
    for (int netNo : original.getNetNumbers()) {
      List<Sat4jSolver.CandidatePath> paths = original.getCandidates(netNo);
      if (paths != null) clone.addCandidates(netNo, paths);
    }
    return clone;
  }
}
