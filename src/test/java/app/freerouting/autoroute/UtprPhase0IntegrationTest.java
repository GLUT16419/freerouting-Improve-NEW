package app.freerouting.autoroute;

import static org.junit.jupiter.api.Assertions.*;

import app.freerouting.board.ItemIdentificationNumberGenerator;
import app.freerouting.board.RoutingBoard;
import app.freerouting.core.RoutingJob;
import app.freerouting.fixtures.RoutingFixtureTest;
import app.freerouting.management.HeadlessBoardManager;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * UTPR Phase 0 integration tests — verifies that the urban planning phase
 * components work together end-to-end on a real board.
 */
@Tag("slow")
class UtprPhase0IntegrationTest extends RoutingFixtureTest {

  @Test
  void phase0BuildsCHGraph() {
    RoutingJob job = GetRoutingJob("tutorial_board.dsn");
    HeadlessBoardManager boardManager = new HeadlessBoardManager(job);
    try {
      boardManager.loadFromSpecctraDsn(
          job.input.getData(), null, new ItemIdentificationNumberGenerator());
    } catch (Exception e) {
      throw new RuntimeException("Failed to load DSN board", e);
    }

    RoutingBoard board = boardManager.get_routing_board();

    // Build CH graph
    ContractionHierarchies ch = ContractionHierarchies.buildFromBoard(board, 200000.0);
    assertNotNull(ch);
    assertTrue(ch.getNodeCount() > 0);
    assertTrue(ch.totalEdges() > 0);

    // Contract
    ch.contract(3);
    assertTrue(ch.getContractedCount() > 0);
  }

  @Test
  void phase0EstimatesCongestion() {
    RoutingJob job = GetRoutingJob("tutorial_board.dsn");
    HeadlessBoardManager boardManager = new HeadlessBoardManager(job);
    try {
      boardManager.loadFromSpecctraDsn(
          job.input.getData(), null, new ItemIdentificationNumberGenerator());
    } catch (Exception e) {
      throw new RuntimeException("Failed to load DSN board", e);
    }

    RoutingBoard board = boardManager.get_routing_board();

    // Probabilistic congestion estimation
    ProbabilisticCongestionEstimator estimator =
        new ProbabilisticCongestionEstimator(board, 200000.0);
    estimator.estimate(Set.of(1, 2, 3));
    assertNotNull(estimator.getCongestionMatrix());
    assertTrue(estimator.getGridCols() > 0);
  }

  @Test
  void phase0PlansCorridors() {
    RoutingJob job = GetRoutingJob("tutorial_board.dsn");
    HeadlessBoardManager boardManager = new HeadlessBoardManager(job);
    try {
      boardManager.loadFromSpecctraDsn(
          job.input.getData(), null, new ItemIdentificationNumberGenerator());
    } catch (Exception e) {
      throw new RuntimeException("Failed to load DSN board", e);
    }

    RoutingBoard board = boardManager.get_routing_board();

    MainCorridorPlanner planner = new MainCorridorPlanner(board, 200000.0);
    planner.planCorridors(Set.of(1), Set.of(1));
    assertFalse(planner.getCorridorCells().isEmpty());
  }

  @Test
  void phase0PartitionsBoard() {
    RoutingJob job = GetRoutingJob("tutorial_board.dsn");
    HeadlessBoardManager boardManager = new HeadlessBoardManager(job);
    try {
      boardManager.loadFromSpecctraDsn(
          job.input.getData(), null, new ItemIdentificationNumberGenerator());
    } catch (Exception e) {
      throw new RuntimeException("Failed to load DSN board", e);
    }

    RoutingBoard board = boardManager.get_routing_board();
    ContractionHierarchies ch = ContractionHierarchies.buildFromBoard(board, 200000.0);

    MultiLevelPartitioner partitioner = new MultiLevelPartitioner(board, ch);
    partitioner.buildHierarchy(Set.of(1));
    assertTrue(partitioner.getDistrictCount() > 0);
  }
}
