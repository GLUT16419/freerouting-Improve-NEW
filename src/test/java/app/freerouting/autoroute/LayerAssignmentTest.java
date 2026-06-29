package app.freerouting.autoroute;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.board.ItemIdentificationNumberGenerator;
import app.freerouting.core.RoutingJob;
import app.freerouting.fixtures.RoutingFixtureTest;
import app.freerouting.management.HeadlessBoardManager;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LayerAssignmentTest extends RoutingFixtureTest {

  @Test
  void assignLayersToClustersOnDac2020Board() {
    RoutingJob job = GetRoutingJob("Issue508-DAC2020_bm01.dsn");
    HeadlessBoardManager boardManager = new HeadlessBoardManager(job);
    try {
      boardManager.loadFromSpecctraDsn(job.input.getData(), null, new ItemIdentificationNumberGenerator());
    } catch (Exception e) {
      throw new RuntimeException("Failed to load DSN board", e);
    }

    // Run Phase 0 first to get congestion map and layer functions
    Phase0CongestionEstimator estimator = new Phase0CongestionEstimator(
        boardManager.get_routing_board());
    CongestionMap congestionMap = estimator.analyze();

    LayerFunction[] layerFunctions = LayerFunctionAutoAssigner.assignFunctions(
        boardManager.get_routing_board().layer_structure);

    // Create some test nets as a cluster
    Set<Integer> testNets = Set.of(1, 2, 3);
    List<NetCluster> clusters = NetCluster.clusterNets(
        boardManager.get_routing_board(), testNets);

    LayerAssignment assignment = new LayerAssignment(
        boardManager.get_routing_board(), congestionMap);
    assignment.assignLayers(clusters, layerFunctions);

    assertNotNull(clusters);
    for (NetCluster cluster : clusters) {
      assertTrue(cluster.getPrimaryLayer() >= 0,
          "Cluster " + cluster.getClusterId() + " should have a primary layer assigned");
    }
  }

  @Test
  void everyClusterGetsAtLeastPrimaryLayer() {
    RoutingJob job = GetRoutingJob("Issue508-DAC2020_bm01.dsn");
    HeadlessBoardManager boardManager = new HeadlessBoardManager(job);
    try {
      boardManager.loadFromSpecctraDsn(job.input.getData(), null, new ItemIdentificationNumberGenerator());
    } catch (Exception e) {
      throw new RuntimeException("Failed to load DSN board", e);
    }

    Phase0CongestionEstimator estimator = new Phase0CongestionEstimator(
        boardManager.get_routing_board());
    CongestionMap congestionMap = estimator.analyze();

    LayerFunction[] layerFunctions = LayerFunctionAutoAssigner.assignFunctions(
        boardManager.get_routing_board().layer_structure);

    Set<Integer> allNets = Set.of(1, 2, 3, 4, 5, 6, 7);
    List<NetCluster> clusters = NetCluster.clusterNets(
        boardManager.get_routing_board(), allNets);

    LayerAssignment assignment = new LayerAssignment(
        boardManager.get_routing_board(), congestionMap);
    assignment.assignLayers(clusters, layerFunctions);

    for (NetCluster cluster : clusters) {
      assertTrue(cluster.getPrimaryLayer() >= 0,
          "Cluster " + cluster.getClusterId() + " must have primary layer");
      assertTrue(cluster.getAssignedLayers().size() >= 1);
    }
  }

  @Test
  void hasLayerCapacityReturnsTrueForAvailableLayers() {
    RoutingJob job = GetRoutingJob("Issue508-DAC2020_bm01.dsn");
    HeadlessBoardManager boardManager = new HeadlessBoardManager(job);
    try {
      boardManager.loadFromSpecctraDsn(job.input.getData(), null, new ItemIdentificationNumberGenerator());
    } catch (Exception e) {
      throw new RuntimeException("Failed to load DSN board", e);
    }

    Phase0CongestionEstimator estimator = new Phase0CongestionEstimator(
        boardManager.get_routing_board());
    CongestionMap congestionMap = estimator.analyze();

    LayerAssignment assignment = new LayerAssignment(
        boardManager.get_routing_board(), congestionMap);
    assertTrue(assignment.hasLayerCapacity(0));
  }
}
