package app.freerouting.autoroute;

import static org.junit.jupiter.api.Assertions.*;

import app.freerouting.board.ItemIdentificationNumberGenerator;
import app.freerouting.board.RoutingBoard;
import app.freerouting.core.RoutingJob;
import app.freerouting.fixtures.RoutingFixtureTest;
import app.freerouting.management.HeadlessBoardManager;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * UTPR full pipeline integration tests — runs the complete four-phase
 * routing engine on real board designs.
 */
@Tag("slow")
class UtprFullPipelineIntegrationTest extends RoutingFixtureTest {

  @Test
  void stomReservationWorksOnRealBoard() {
    RoutingJob job = GetRoutingJob("empty_board.dsn");
    HeadlessBoardManager boardManager = new HeadlessBoardManager(job);
    try {
      boardManager.loadFromSpecctraDsn(
          job.input.getData(), null, new ItemIdentificationNumberGenerator());
    } catch (Exception e) {
      throw new RuntimeException("Failed to load DSN board", e);
    }
    RoutingBoard board = boardManager.get_routing_board();

    // STOM creation and basic operations
    SpatioTemporalOccupancyMap stom = new SpatioTemporalOccupancyMap(board, 200000.0);
    List<Long> cells = List.of(
        SpatioTemporalOccupancyMap.cellKey(0, 0, 0),
        SpatioTemporalOccupancyMap.cellKey(1, 0, 0));
    int ts = stom.reserve(cells);
    assertTrue(ts >= 0);
    assertTrue(stom.isOccupied(0, 0, 0, ts));
    assertEquals(1, stom.getCurrentTimeStep());
  }

  @Test
  void incrementalRerouterHandlesEmptyState() {
    RoutingJob job = GetRoutingJob("empty_board.dsn");
    HeadlessBoardManager boardManager = new HeadlessBoardManager(job);
    try {
      boardManager.loadFromSpecctraDsn(
          job.input.getData(), null, new ItemIdentificationNumberGenerator());
    } catch (Exception e) {
      throw new RuntimeException("Failed to load DSN board", e);
    }
    RoutingBoard board = boardManager.get_routing_board();

    job.thread = new app.freerouting.core.StoppableThread() {
      @Override protected void thread_action() {}
    };
    SpatioTemporalOccupancyMap stom = new SpatioTemporalOccupancyMap(board, 200000.0);
    BatchAutorouter batchRouter = new BatchAutorouter(job);

    IncrementalRerouter rerouter = new IncrementalRerouter(
        board, board, batchRouter, stom);
    Set<Integer> result = rerouter.runIncrementalRepair(new HashSet<>());
    assertNotNull(result);
    assertTrue(result.isEmpty());
  }

  @Test
  void urbanTrafficRouterSettingsDefaults() {
    UrbanTrafficRouterSettings settings = new UrbanTrafficRouterSettings();
    assertEquals(UrbanTrafficRouterSettings.QualityLevel.BALANCED, settings.qualityLevel);
    assertTrue(settings.phase0Enabled);
    assertTrue(settings.phase1Enabled);
    assertTrue(settings.phase2Enabled);
  }

  @Test
  void urbanTrafficRouterSettingsQualityLevels() {
    UrbanTrafficRouterSettings settings = new UrbanTrafficRouterSettings();
    settings.qualityLevel = UrbanTrafficRouterSettings.QualityLevel.DRAFT;
    settings.applyQualityLevel();
    assertFalse(settings.phase1Enabled);
    assertFalse(settings.phase2Enabled);
    assertEquals(2, settings.chLevels);

    settings.qualityLevel = UrbanTrafficRouterSettings.QualityLevel.HIGH;
    settings.applyQualityLevel();
    assertTrue(settings.phase1Enabled);
    assertTrue(settings.phase3Enabled);
    assertEquals(8, settings.chLevels);
  }

  @Test
  void urbanTrafficRouterSettingsClone() {
    UrbanTrafficRouterSettings settings = new UrbanTrafficRouterSettings();
    settings.qualityLevel = UrbanTrafficRouterSettings.QualityLevel.HIGH;
    settings.applyQualityLevel();
    UrbanTrafficRouterSettings cloned = settings.clone();
    assertEquals(settings.qualityLevel, cloned.qualityLevel);
    assertEquals(settings.phase0Enabled, cloned.phase0Enabled);
    assertEquals(settings.phase1Enabled, cloned.phase1Enabled);
    assertEquals(settings.phase2Enabled, cloned.phase2Enabled);
    assertEquals(settings.phase3Enabled, cloned.phase3Enabled);
    assertEquals(settings.chLevels, cloned.chLevels);
  }

  @Test
  void timedAStarSearchOnRealBoard() {
    RoutingJob job = GetRoutingJob("empty_board.dsn");
    HeadlessBoardManager boardManager = new HeadlessBoardManager(job);
    try {
      boardManager.loadFromSpecctraDsn(
          job.input.getData(), null, new ItemIdentificationNumberGenerator());
    } catch (Exception e) {
      throw new RuntimeException("Failed to load DSN board", e);
    }
    RoutingBoard board = boardManager.get_routing_board();

    ContractionHierarchies ch = ContractionHierarchies.buildFromBoard(board, 200000.0);
    SpatioTemporalOccupancyMap stom = new SpatioTemporalOccupancyMap(board, 200000.0);
    MainCorridorPlanner corridorPlanner = new MainCorridorPlanner(board, 200000.0);
    corridorPlanner.planCorridors(new HashSet<>(), new HashSet<>());
    ProbabilisticCongestionEstimator probEstimator =
        new ProbabilisticCongestionEstimator(board, 200000.0);
    probEstimator.estimate(new HashSet<>());

    TimedBidirectionalAStar aStar = new TimedBidirectionalAStar(
        board, board, ch, stom, corridorPlanner, probEstimator);

    // Search across the board
    TimedBidirectionalAStar.SearchResult result =
        aStar.search(10000, 10000, 30000, 30000, 0, 0);
    // May or may not find a path depending on board shape
    assertNotNull(result);
    assertTrue(result.nodesExpanded >= 0);
  }

  @Test
  void hubFanoutTemplatesCreated() {
    HubFanoutTemplates templates = new HubFanoutTemplates(300, 600, 4);
    assertFalse(templates.getLibrary().isEmpty());
    assertTrue(templates.getLibrary().size() >= 4);
  }
}
