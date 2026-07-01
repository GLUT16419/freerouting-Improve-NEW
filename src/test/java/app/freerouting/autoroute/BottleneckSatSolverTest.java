package app.freerouting.autoroute;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class BottleneckSatSolverTest {

  @Test
  void candidatePathCreatedCorrectly() {
    List<Integer> cells = Arrays.asList(100, 200, 300);
    BottleneckSatSolver.CandidatePath cp =
        new BottleneckSatSolver.CandidatePath(1, 500.0, 100.0, 0, 42L, cells);
    assertEquals(1, cp.netNo);
    assertEquals(500.0, cp.totalLength, 1e-6);
    assertEquals(100.0, cp.totalCost, 1e-6);
    assertEquals(3, cp.occupiedCells.size());
  }

  @Test
  void toSat4jPathConvertsCorrectly() {
    List<Integer> cells = Arrays.asList(10, 20);
    BottleneckSatSolver.CandidatePath cp =
        new BottleneckSatSolver.CandidatePath(5, 300.0, 50.0, 2, 100L, cells);
    Sat4jSolver.CandidatePath satPath = cp.toSat4jPath();
    assertEquals(5, satPath.netNo);
    assertEquals(300.0, satPath.totalLength, 1e-6);
    assertEquals(50.0, satPath.totalCost, 1e-6);
  }

  @Test
  void solveResultFullySolved() {
    Set<Integer> solved = new HashSet<>(Arrays.asList(1, 2, 3));
    Map<Integer, BottleneckSatSolver.CandidatePath> selected = new HashMap<>();
    BottleneckSatSolver.SolveResult result =
        new BottleneckSatSolver.SolveResult(solved, new HashSet<>(), 150.0, 1, selected);
    assertTrue(result.fullySolved);
    assertEquals(3, result.solvedNets.size());
  }

  @Test
  void solveResultUnsatCore() {
    Set<Integer> solved = new HashSet<>(Arrays.asList(1, 2));
    Set<Integer> unsat = new HashSet<>(Arrays.asList(3, 4));
    BottleneckSatSolver.SolveResult result =
        new BottleneckSatSolver.SolveResult(solved, unsat, 100.0, 2, new HashMap<>());
    assertFalse(result.fullySolved);
    assertEquals(2, result.unsatNets.size());
    assertEquals(2, result.relaxationUsed);
  }

  @Test
  void failedResultHasCorrectState() {
    BottleneckSatSolver.SolveResult failed = BottleneckSatSolver.SolveResult.FAILED;
    assertTrue(failed.solvedNets.isEmpty());
    assertEquals(Double.MAX_VALUE, failed.totalCost, 1e-6);
  }

  @Test
  void solveResultConstructorWorks() {
    BottleneckSatSolver.SolveResult result =
        new BottleneckSatSolver.SolveResult(
            Collections.emptySet(), Collections.emptySet(),
            Double.MAX_VALUE, 0, Collections.emptyMap());
    assertNotNull(result);
    assertTrue(result.unsatNets.isEmpty());
  }
}
