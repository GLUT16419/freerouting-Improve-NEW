package app.freerouting.autoroute;

import app.freerouting.board.BasicBoard;
import app.freerouting.board.Pin;
import app.freerouting.geometry.planar.FloatPoint;
import app.freerouting.logger.FRLogger;
import app.freerouting.rules.Net;

import java.io.Serializable;
import java.util.*;

/**
 * Represents a cluster of nets that share spatial locality.
 */
public class NetCluster implements Serializable {

  private static final double OVERLAP_THRESHOLD = 0.3;
  private static final int CLUSTER_SIZE_LIMIT = 20;
  private static final double BOUNDING_BOX_MARGIN = 0.2;

  private final int clusterId;
  private final List<Integer> netNumbers = new ArrayList<>();
  private final List<String> netNames = new ArrayList<>();
  private final List<NetType> netTypes = new ArrayList<>();

  private double minX = Double.MAX_VALUE;
  private double minY = Double.MAX_VALUE;
  private double maxX = Double.MIN_VALUE;
  private double maxY = Double.MIN_VALUE;

  private int primaryLayer = -1;
  private int secondaryLayer = -1;
  private final Set<Integer> assignedLayers = new HashSet<>();
  private int totalCount = 0;

  public NetCluster(int clusterId) { this.clusterId = clusterId; }

  public void addNet(int netNumber, String netName, NetType netType,
                     double netMinX, double netMinY, double netMaxX, double netMaxY) {
    netNumbers.add(netNumber);
    netNames.add(netName);
    netTypes.add(netType);
    minX = Math.min(minX, netMinX);
    minY = Math.min(minY, netMinY);
    maxX = Math.max(maxX, netMaxX);
    maxY = Math.max(maxY, netMaxY);
    totalCount++;
  }

  public boolean hasSignificantOverlap(double netMinX, double netMinY,
                                       double netMaxX, double netMaxY) {
    if (netNumbers.isEmpty()) return true;
    double cw = maxX - minX, ch = maxY - minY;
    double cmMinX = minX - cw * BOUNDING_BOX_MARGIN;
    double cmMaxX = maxX + cw * BOUNDING_BOX_MARGIN;
    double cmMinY = minY - ch * BOUNDING_BOX_MARGIN;
    double cmMaxY = maxY + ch * BOUNDING_BOX_MARGIN;
    double overlapX = Math.max(0, Math.min(netMaxX, cmMaxX) - Math.max(netMinX, cmMinX));
    double overlapY = Math.max(0, Math.min(netMaxY, cmMaxY) - Math.max(netMinY, cmMinY));
    double overlapArea = overlapX * overlapY;
    double unionX = Math.max(netMaxX, cmMaxX) - Math.min(netMinX, cmMinX);
    double unionY = Math.max(netMaxY, cmMaxY) - Math.min(netMinY, cmMinY);
    double unionArea = unionX * unionY;
    return unionArea > 0 && (overlapArea / unionArea) >= OVERLAP_THRESHOLD;
  }

  public boolean isFull() { return netNumbers.size() >= CLUSTER_SIZE_LIMIT; }

  public int getClusterId() { return clusterId; }
  public List<Integer> getNetNumbers() { return Collections.unmodifiableList(netNumbers); }
  public int getNetCount() { return netNumbers.size(); }
  public double getMinX() { return minX; }
  public double getMinY() { return minY; }
  public double getMaxX() { return maxX; }
  public double getMaxY() { return maxY; }
  public double getWidth() { return maxX - minX; }
  public double getHeight() { return maxY - minY; }

  public int getPrimaryLayer() { return primaryLayer; }
  public void setPrimaryLayer(int layer) { this.primaryLayer = layer; assignedLayers.add(layer); }
  public int getSecondaryLayer() { return secondaryLayer; }
  public void setSecondaryLayer(int layer) { this.secondaryLayer = layer; if (layer >= 0) assignedLayers.add(layer); }
  public Set<Integer> getAssignedLayers() { return assignedLayers; }
  public int getTotalCount() { return totalCount; }

  public double getCenterX() { return (minX + maxX) / 2.0; }
  public double getCenterY() { return (minY + maxY) / 2.0; }

  @Override
  public String toString() {
    return "Cluster #" + clusterId + " [" + netNumbers.size() + " nets]"
        + " bounds=(" + minX + "," + minY + ")-(" + maxX + "," + maxY + ")"
        + " layer=" + primaryLayer;
  }

  public static List<NetCluster> clusterNets(BasicBoard board, Set<Integer> netsToCluster) {
    if (board == null || board.rules == null || board.rules.nets == null) return Collections.emptyList();

    List<NetBoundingBox> netBBs = new ArrayList<>();
    for (int netNo : netsToCluster) {
      Net net = board.rules.nets.get(netNo);
      if (net == null) continue;
      Collection<Pin> pins = net.get_pins();
      if (pins.size() < 2) continue;
      double nMinX = Double.MAX_VALUE, nMinY = Double.MAX_VALUE;
      double nMaxX = Double.MIN_VALUE, nMaxY = Double.MIN_VALUE;
      for (Pin pin : pins) {
        FloatPoint c = pin.get_center().to_float();
        nMinX = Math.min(nMinX, c.x);
        nMaxX = Math.max(nMaxX, c.x);
        nMinY = Math.min(nMinY, c.y);
        nMaxY = Math.max(nMaxY, c.y);
      }
      netBBs.add(new NetBoundingBox(netNo, net.name, NetType.fromName(net.name),
          nMinX, nMinY, nMaxX, nMaxY));
    }

    netBBs.sort((a, b) -> Double.compare(
        (b.maxX - b.minX) * (b.maxY - b.minY),
        (a.maxX - a.minX) * (a.maxY - a.minY)));

    List<NetCluster> clusters = new ArrayList<>();
    int clusterId = 0;
    for (NetBoundingBox netBB : netBBs) {
      boolean added = false;
      for (NetCluster cluster : clusters) {
        if (!cluster.isFull() && cluster.hasSignificantOverlap(
            netBB.minX, netBB.minY, netBB.maxX, netBB.maxY)) {
          cluster.addNet(netBB.netNo, netBB.netName, netBB.netType,
              netBB.minX, netBB.minY, netBB.maxX, netBB.maxY);
          added = true;
          break;
        }
      }
      if (!added) {
        NetCluster cluster = new NetCluster(clusterId++);
        cluster.addNet(netBB.netNo, netBB.netName, netBB.netType,
            netBB.minX, netBB.minY, netBB.maxX, netBB.maxY);
        clusters.add(cluster);
      }
    }
    return clusters;
  }

  private static class NetBoundingBox {
    final int netNo; final String netName; final NetType netType;
    final double minX, minY, maxX, maxY;
    NetBoundingBox(int netNo, String netName, NetType netType,
                   double minX, double minY, double maxX, double maxY) {
      this.netNo = netNo; this.netName = netName; this.netType = netType;
      this.minX = minX; this.minY = minY; this.maxX = maxX; this.maxY = maxY;
    }
  }
}
