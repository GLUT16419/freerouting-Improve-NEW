package app.freerouting.autoroute;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.board.ItemIdentificationNumberGenerator;
import app.freerouting.core.RoutingJob;
import app.freerouting.fixtures.RoutingFixtureTest;
import app.freerouting.management.HeadlessBoardManager;
import org.junit.jupiter.api.Test;

class Phase0CongestionEstimatorTest extends RoutingFixtureTest {

  @Test
  void analyzeDac2020Board() {
    RoutingJob job = GetRoutingJob("Issue508-DAC2020_bm01.dsn");
    HeadlessBoardManager boardManager = new HeadlessBoardManager(job);
    try {
      boardManager.loadFromSpecctraDsn(job.input.getData(), null, new ItemIdentificationNumberGenerator());
    } catch (Exception e) {
      throw new RuntimeException("Failed to load DSN board", e);
    }

    Phase0CongestionEstimator estimator = new Phase0CongestionEstimator(
        boardManager.get_routing_board());
    CongestionMap cm = estimator.analyze();

    assertNotNull(cm);
    assertTrue(cm.getGridCols() > 0, "Grid should have columns");
    assertTrue(cm.getGridRows() > 0, "Grid should have rows");
    assertTrue(cm.getLayerCount() > 0, "Should have at least one layer");

    // Verify accessors
    assertTrue(estimator.getGlobalAvgCongestion() >= 0.0, "Average congestion should be non-negative");
    assertTrue(estimator.getGlobalMaxCongestion() >= 0.0, "Max congestion should be non-negative");
  }

  @Test
  void analyzeEmptyBoard() {
    RoutingJob job = GetRoutingJob("empty_board.dsn");
    HeadlessBoardManager boardManager = new HeadlessBoardManager(job);
    try {
      boardManager.loadFromSpecctraDsn(job.input.getData(), null, new ItemIdentificationNumberGenerator());
    } catch (Exception e) {
      throw new RuntimeException("Failed to load DSN board", e);
    }

    Phase0CongestionEstimator estimator = new Phase0CongestionEstimator(
        boardManager.get_routing_board());
    CongestionMap cm = estimator.analyze();

    assertNotNull(cm);
    assertTrue(cm.getGridCols() > 0);
    assertTrue(cm.getGridRows() > 0);
  }

  @Test
  void netPriorityIsNonNegative() {
    RoutingJob job = GetRoutingJob("Issue508-DAC2020_bm01.dsn");
    HeadlessBoardManager boardManager = new HeadlessBoardManager(job);
    try {
      boardManager.loadFromSpecctraDsn(job.input.getData(), null, new ItemIdentificationNumberGenerator());
    } catch (Exception e) {
      throw new RuntimeException("Failed to load DSN board", e);
    }

    Phase0CongestionEstimator estimator = new Phase0CongestionEstimator(
        boardManager.get_routing_board());
    estimator.analyze();

    // Test with a few net numbers
    for (int netNo : new int[]{1, 2, 3, 10}) {
      double priority = estimator.getNetPriority(netNo);
      assertTrue(priority >= 0.0, "Net " + netNo + " priority should be non-negative");
    }
  }

  @Test
  void congestionZonesDetectedOnRoutedBoard() {
    RoutingJob job = GetRoutingJob("Issue508-DAC2020_bm01.dsn");
    HeadlessBoardManager boardManager = new HeadlessBoardManager(job);
    try {
      boardManager.loadFromSpecctraDsn(job.input.getData(), null, new ItemIdentificationNumberGenerator());
    } catch (Exception e) {
      throw new RuntimeException("Failed to load DSN board", e);
    }

    Phase0CongestionEstimator estimator = new Phase0CongestionEstimator(
        boardManager.get_routing_board());
    estimator.analyze();

    // Congestion zones list should be accessible
    assertNotNull(estimator.getCongestionZones());
  }
}
