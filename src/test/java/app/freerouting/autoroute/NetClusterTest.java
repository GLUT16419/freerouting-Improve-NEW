package app.freerouting.autoroute;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class NetClusterTest {

  @Test
  void emptyClusterHasZeroNets() {
    NetCluster cluster = new NetCluster(0);
    assertEquals(0, cluster.getNetCount());
    assertTrue(cluster.getNetNumbers().isEmpty());
  }

  @Test
  void addNetIncreasesCount() {
    NetCluster cluster = new NetCluster(1);
    cluster.addNet(10, "DATA0", NetType.SIGNAL, 0, 0, 1000, 1000);
    assertEquals(1, cluster.getNetCount());
    assertEquals(10, cluster.getNetNumbers().get(0).intValue());
  }

  @Test
  void clusterBoundingBoxExpands() {
    NetCluster cluster = new NetCluster(1);
    cluster.addNet(10, "NET1", NetType.SIGNAL, 0, 0, 1000, 1000);
    cluster.addNet(20, "NET2", NetType.SIGNAL, 2000, 2000, 3000, 3000);
    assertEquals(0.0, cluster.getMinX(), 1e-6);
    assertEquals(0.0, cluster.getMinY(), 1e-6);
    assertEquals(3000.0, cluster.getMaxX(), 1e-6);
    assertEquals(3000.0, cluster.getMaxY(), 1e-6);
  }

  @Test
  void emptyClusterAcceptsAny() {
    NetCluster cluster = new NetCluster(0);
    assertTrue(cluster.hasSignificantOverlap(0, 0, 1000, 1000));
  }

  @Test
  void distantNetsDontOverlap() {
    NetCluster cluster = new NetCluster(0);
    cluster.addNet(1, "NET1", NetType.SIGNAL, 0, 0, 1000, 1000);
    assertFalse(cluster.hasSignificantOverlap(50000, 50000, 60000, 60000));
  }

  @Test
  void clusterCapacityLimit() {
    NetCluster cluster = new NetCluster(0);
    for (int i = 0; i < 20; i++) {
      cluster.addNet(i, "NET" + i, NetType.SIGNAL, 0, 0, 100, 100);
    }
    assertTrue(cluster.isFull());
  }

  @Test
  void layerAssignment() {
    NetCluster cluster = new NetCluster(0);
    cluster.addNet(1, "NET1", NetType.SIGNAL, 0, 0, 100, 100);
    assertEquals(-1, cluster.getPrimaryLayer());
    cluster.setPrimaryLayer(1);
    assertEquals(1, cluster.getPrimaryLayer());
    assertTrue(cluster.getAssignedLayers().contains(1));
  }

  @Test
  void staticClusterEmptyInput() {
    List<NetCluster> clusters = NetCluster.clusterNets(null, Collections.emptySet());
    assertNotNull(clusters);
    assertTrue(clusters.isEmpty());
  }

  @Test
  void centerPointIsCorrect() {
    NetCluster cluster = new NetCluster(0);
    cluster.addNet(1, "NET1", NetType.SIGNAL, 0, 0, 100, 200);
    assertEquals(50.0, cluster.getCenterX(), 1e-6);
    assertEquals(100.0, cluster.getCenterY(), 1e-6);
  }
}
