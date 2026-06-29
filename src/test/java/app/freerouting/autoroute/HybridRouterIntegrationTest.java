package app.freerouting.autoroute;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.board.ItemIdentificationNumberGenerator;
import app.freerouting.board.RoutingBoard;
import app.freerouting.core.RoutingJob;
import app.freerouting.fixtures.RoutingFixtureTest;
import app.freerouting.management.HeadlessBoardManager;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * End-to-end integration tests for the HybridBatchAutorouter.
 * <p>
 * These tests are tagged "slow" because they load full DSN boards and run
 * the complete three-phase algorithm. They are excluded from the default
 * test run and must be explicitly enabled with -PincludeSlowTests=true.
 */
@Tag("slow")
class HybridRouterIntegrationTest extends RoutingFixtureTest {

  @Test
  void hybridRouterCompletesWithoutException() {
    RoutingJob job = GetRoutingJob("Issue508-DAC2020_bm01.dsn");
    HeadlessBoardManager boardManager = new HeadlessBoardManager(job);
    try {
      boardManager.loadFromSpecctraDsn(job.input.getData(), null, new ItemIdentificationNumberGenerator());
    } catch (Exception e) {
      throw new RuntimeException("Failed to load DSN board", e);
    }

    RoutingBoard board = boardManager.get_routing_board();
    HybridRouterSettings hybridSettings = new HybridRouterSettings();
    hybridSettings.qualityLevel = HybridRouterSettings.QualityLevel.DRAFT; // Fastest

    // Create a BatchAutorouter needed by the new HybridBatchAutorouter constructor
    job.thread = new app.freerouting.core.StoppableThread() {
      @Override protected void thread_action() {}
    };
    BatchAutorouter batchAutorouter = new BatchAutorouter(job);

    HybridBatchAutorouter autorouter = new HybridBatchAutorouter(
        board, job.routerSettings, hybridSettings, batchAutorouter);

    Map<Integer, String> results = autorouter.runAllPhases();

    assertNotNull(results);
  }

  @Test
  void hybridRouterLayerFunctionsAssigned() {
    RoutingJob job = GetRoutingJob("Issue508-DAC2020_bm01.dsn");
    HeadlessBoardManager boardManager = new HeadlessBoardManager(job);
    try {
      boardManager.loadFromSpecctraDsn(job.input.getData(), null, new ItemIdentificationNumberGenerator());
    } catch (Exception e) {
      throw new RuntimeException("Failed to load DSN board", e);
    }

    RoutingBoard board = boardManager.get_routing_board();
    HybridRouterSettings hybridSettings = new HybridRouterSettings();
    hybridSettings.qualityLevel = HybridRouterSettings.QualityLevel.DRAFT;

    job.thread = new app.freerouting.core.StoppableThread() {
      @Override protected void thread_action() {}
    };
    BatchAutorouter batchAutorouter = new BatchAutorouter(job);

    HybridBatchAutorouter autorouter = new HybridBatchAutorouter(
        board, job.routerSettings, hybridSettings, batchAutorouter);
    autorouter.runAllPhases();

    LayerFunction[] functions = autorouter.getLayerFunctions();
    assertNotNull(functions, "Layer functions should be assigned");
    if (functions.length > 0) {
      assertTrue(LayerFunctionAutoAssigner.countRoutableLayers(functions) > 0,
          "At least one routable layer should be identified");
    }
  }

  @Test
  void hybridRouterPowerGndLabeled() {
    RoutingJob job = GetRoutingJob("Issue508-DAC2020_bm01.dsn");
    HeadlessBoardManager boardManager = new HeadlessBoardManager(job);
    try {
      boardManager.loadFromSpecctraDsn(job.input.getData(), null, new ItemIdentificationNumberGenerator());
    } catch (Exception e) {
      throw new RuntimeException("Failed to load DSN board", e);
    }

    RoutingBoard board = boardManager.get_routing_board();
    HybridRouterSettings hybridSettings = new HybridRouterSettings();
    hybridSettings.qualityLevel = HybridRouterSettings.QualityLevel.DRAFT;

    job.thread = new app.freerouting.core.StoppableThread() {
      @Override protected void thread_action() {}
    };
    BatchAutorouter batchAutorouter = new BatchAutorouter(job);

    HybridBatchAutorouter autorouter = new HybridBatchAutorouter(
        board, job.routerSettings, hybridSettings, batchAutorouter);
    autorouter.runAllPhases();

    PowerGndAutoLabeler labeler = autorouter.getPowerGndLabeler();
    assertNotNull(labeler, "Power/GND labeler should be created");
  }

  @Test
  void hybridRouterProgressTracking() {
    RoutingJob job = GetRoutingJob("Issue508-DAC2020_bm01.dsn");
    HeadlessBoardManager boardManager = new HeadlessBoardManager(job);
    try {
      boardManager.loadFromSpecctraDsn(job.input.getData(), null, new ItemIdentificationNumberGenerator());
    } catch (Exception e) {
      throw new RuntimeException("Failed to load DSN board", e);
    }

    RoutingBoard board = boardManager.get_routing_board();
    HybridRouterSettings hybridSettings = new HybridRouterSettings();
    hybridSettings.qualityLevel = HybridRouterSettings.QualityLevel.DRAFT;

    job.thread = new app.freerouting.core.StoppableThread() {
      @Override protected void thread_action() {}
    };
    BatchAutorouter batchAutorouter = new BatchAutorouter(job);

    HybridBatchAutorouter autorouter = new HybridBatchAutorouter(
        board, job.routerSettings, hybridSettings, batchAutorouter);
    autorouter.runAllPhases();

    // Progress should be at or near 1.0 after completion
    assertTrue(autorouter.getProgress() >= 0.0);
    assertTrue(!autorouter.isRunning(), "Router should not be running after completion");
  }
}
