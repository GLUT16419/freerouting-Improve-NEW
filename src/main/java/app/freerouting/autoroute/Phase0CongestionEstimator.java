package app.freerouting.autoroute;

import app.freerouting.board.BasicBoard;
import app.freerouting.board.Pin;
import app.freerouting.geometry.planar.FloatPoint;
import app.freerouting.logger.FRLogger;
import app.freerouting.rules.Net;

import java.util.*;

/**
 * Phase 0: Global Congestion Estimation.
 */
public class Phase0CongestionEstimator {

  private final BasicBoard board;
  private final CongestionMap congestionMap;
  private final Map<Integer, Double> netCongestionEstimate = new HashMap<>();
  private final Map<Integer, Double> netManhattanDistance = new HashMap<>();
  private final List<CongestionZone> congestionZones = new ArrayList<>();

  private double globalAvgCongestion;
  private double globalMaxCongestion;
  private int totalUnroutedConnections;
  private int estimatedRoutableNets;
  private boolean isRoutableEstimate;

  public Phase0CongestionEstimator(BasicBoard board) {
    this.board = board;
    this.congestionMap = new CongestionMap(board);
  }

  public CongestionMap analyze() {
    FRLogger.debug("Phase 0: Starting global congestion estimation...");
    congestionMap.logSummary();
    analyzeAllNets();
    detectCongestionZones();
    estimateRoutability();
    logResults();
    return congestionMap;
  }

  private void analyzeAllNets() {
    if (board.rules == null || board.rules.nets == null) {
      FRLogger.warn("Phase0: Board has no net data");
      return;
    }
    int netCount = board.rules.nets.max_net_no();
    for (int netNo = 1; netNo <= netCount; netNo++) {
      Net net = board.rules.nets.get(netNo);
      if (net == null) continue;
      Collection<Pin> pins = net.get_pins();
      if (pins.size() < 2) {
        netCongestionEstimate.put(net.net_number, 0.0);
        netManhattanDistance.put(net.net_number, 0.0);
        continue;
      }
      totalUnroutedConnections += (pins.size() - 1);
      double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
      double maxX = Double.MIN_VALUE, maxY = Double.MIN_VALUE;
      for (Pin pin : pins) {
        FloatPoint center = pin.get_center().to_float();
        minX = Math.min(minX, center.x);
        maxX = Math.max(maxX, center.x);
        minY = Math.min(minY, center.y);
        maxY = Math.max(maxY, center.y);
      }
      double manhattanDist = (maxX - minX) + (maxY - minY);
      netManhattanDistance.put(net.net_number, manhattanDist);
      double congestion = congestionMap.getAverageCongestion(minX, minY, maxX, maxY);
      netCongestionEstimate.put(net.net_number, congestion);
    }
  }

  private void detectCongestionZones() {
    for (int l = 0; l < congestionMap.getLayerCount(); l++) {
      for (int r = 0; r < congestionMap.getGridRows(); r++) {
        for (int c = 0; c < congestionMap.getGridCols(); c++) {
          double congestion = congestionMap.getCellCongestion(l, r, c);
          if (congestion > 0.5) {
            double cellSize = congestionMap.getCellSize();
            congestionZones.add(new CongestionZone(
                c * cellSize + cellSize / 2, r * cellSize + cellSize / 2,
                cellSize, l, congestion,
                congestion > 0.8 ? CongestionLevel.HIGH : CongestionLevel.MEDIUM));
          }
        }
      }
    }
  }

  private void estimateRoutability() {
    globalAvgCongestion = 0.0;
    globalMaxCongestion = 0.0;
    int count = 0;
    for (int l = 0; l < congestionMap.getLayerCount(); l++) {
      for (int r = 0; r < congestionMap.getGridRows(); r++) {
        for (int c = 0; c < congestionMap.getGridCols(); c++) {
          double cong = congestionMap.getCellCongestion(l, r, c);
          globalAvgCongestion += cong;
          globalMaxCongestion = Math.max(globalMaxCongestion, cong);
          count++;
        }
      }
    }
    globalAvgCongestion = count > 0 ? globalAvgCongestion / count : 0.0;
    isRoutableEstimate = globalAvgCongestion < 0.8 && globalMaxCongestion < 3.0;
    estimatedRoutableNets = isRoutableEstimate
        ? (int) (totalUnroutedConnections * 0.85)
        : (int) (totalUnroutedConnections * 0.5);
  }

  private void logResults() {
    FRLogger.debug(String.format(
        "Phase 0 Results: avgCongestion=%.2f, maxCongestion=%.2f, routableEstimate=%b",
        globalAvgCongestion, globalMaxCongestion, isRoutableEstimate));
  }

  public CongestionMap getCongestionMap() { return congestionMap; }
  public double getNetCongestionEstimate(int netNumber) { return netCongestionEstimate.getOrDefault(netNumber, 0.5); }
  public double getNetManhattanDistance(int netNumber) { return netManhattanDistance.getOrDefault(netNumber, Double.MAX_VALUE); }
  public double getNetPriority(int netNumber) {
    double dist = getNetManhattanDistance(netNumber);
    double congestion = getNetCongestionEstimate(netNumber);
    return dist * (1.0 + congestion);
  }
  public double getGlobalAvgCongestion() { return globalAvgCongestion; }
  public double getGlobalMaxCongestion() { return globalMaxCongestion; }
  public boolean getRoutabilityEstimate() { return isRoutableEstimate; }
  public List<CongestionZone> getCongestionZones() { return congestionZones; }

  public static class CongestionZone {
    public final double x, y, size;
    public final int layer;
    public final double congestion;
    public final CongestionLevel level;
    public CongestionZone(double x, double y, double size, int layer, double congestion, CongestionLevel level) {
      this.x = x; this.y = y; this.size = size; this.layer = layer; this.congestion = congestion; this.level = level;
    }
  }

  public enum CongestionLevel { LOW, MEDIUM, HIGH }
}
