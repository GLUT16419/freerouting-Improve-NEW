package app.freerouting.autoroute;

import app.freerouting.board.BasicBoard;
import app.freerouting.board.Pin;
import app.freerouting.geometry.planar.FloatPoint;
import app.freerouting.logger.FRLogger;
import app.freerouting.rules.Net;

import java.io.Serializable;
import java.util.*;
import java.util.stream.Collectors;

/**
 * CRP (Customizable Route Planning) — Multi-Level Partitioner for PCB routing.
 * <p>
 * Divides the PCB into a 3-level hierarchy mirroring a city's urban planning
 * structure:
 * <ol>
 *   <li><b>Level 1 (全市一级区)</b>: The entire PCB = whole metropolitan area.</li>
 *   <li><b>Level 2 (功能区二级区)</b>: Functional blocks (FPGA zone, DDR zone,
 *       Power zone, Connector zone, etc.) = city districts.</li>
 *   <li><b>Level 3 (城区三级区)</b>: Spectral-clustering-based urban districts
 *       within each functional block = neighbourhoods.</li>
 * </ol>
 * <p>
 * Each level-3 district is a <b>completely independent routing sub-problem</b>
 * with its own precomputed boundary path table, enabling fully parallelised
 * route solving.
 */
public class MultiLevelPartitioner implements Serializable {

  private static final long serialVersionUID = 1L;

  /** Minimum number of nets in a level-2 functional block. */
  static final int MIN_FUNC_BLOCK_NETS = 5;

  /** Minimum number of nets in a level-3 district. */
  static final int MIN_DISTRICT_NETS = 3;

  /** Expansion margin for functional-block bounding box (fraction). */
  static final double BLOCK_MARGIN = 0.1;

  // ═══════════════════════════════════════════════════════════════════════
  //  Level 3: District (城区)
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * A level-3 urban district within a functional block.
   * <p>
   * Each district has an independent routing sub-problem with precomputed
   * boundary paths, enabling fully parallel route solving.
   */
  public static class District implements Serializable {
    private static final long serialVersionUID = 1L;

    public final int id;
    public final int functionalBlockId;
    public final double minX, minY, maxX, maxY;
    public final List<BoundaryPoint> boundaryPoints;

    /** CH node IDs that fall within this district. */
    public final Set<Integer> nodeIds = new HashSet<>();

    /** Net numbers that belong to this district. */
    public final Set<Integer> netNumbers = new HashSet<>();

    /** Estimated routing load (proportional to net count). */
    public double estimatedLoad;

    /** Layer pairs assigned to this district (set by Phase 2 traffic-mode assigner). */
    public final Set<Integer> layerPairIds = new HashSet<>();

    /** True if this district's routing is completed. */
    public boolean routingComplete;

    public District(int id, int functionalBlockId,
                    double minX, double minY, double maxX, double maxY) {
      this.id = id;
      this.functionalBlockId = functionalBlockId;
      this.minX = minX;
      this.minY = minY;
      this.maxX = maxX;
      this.maxY = maxY;
      this.boundaryPoints = new ArrayList<>();
      this.estimatedLoad = 0.0;
      this.routingComplete = false;
    }

    public double getWidth() { return maxX - minX; }

    public double getHeight() { return maxY - minY; }

    public double getCenterX() { return (minX + maxX) / 2.0; }

    public double getCenterY() { return (minY + maxY) / 2.0; }

    public boolean contains(double x, double y) {
      return x >= minX && x <= maxX && y >= minY && y <= maxY;
    }

    public void addBoundaryPoint(BoundaryPoint bp) {
      boundaryPoints.add(bp);
    }

    @Override
    public String toString() {
      return String.format("District#%d (FB%d) nets=%d nodes=%d bps=%d load=%.1f [%.0f,%.0f-%.0f,%.0f]",
          id, functionalBlockId, netNumbers.size(), nodeIds.size(),
          boundaryPoints.size(), estimatedLoad, minX, minY, maxX, maxY);
    }
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Level 2: Functional Block (功能区)
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * A level-2 functional block (FPGA zone, DDR zone, etc.).
   * Contains one or more level-3 districts.
   */
  public static class FunctionalBlock implements Serializable {
    private static final long serialVersionUID = 1L;

    public final int id;
    public final String name;
    public final double minX, minY, maxX, maxY;
    public final List<District> districts = new ArrayList<>();
    public final Set<Integer> netNumbers = new HashSet<>();
    public final Set<Integer> componentPinLocations = new HashSet<>();

    public FunctionalBlock(int id, String name,
                           double minX, double minY, double maxX, double maxY) {
      this.id = id;
      this.name = name;
      this.minX = minX;
      this.minY = minY;
      this.maxX = maxX;
      this.maxY = maxY;
    }

    public double getWidth() { return maxX - minX; }

    public double getHeight() { return maxY - minY; }

    public void addDistrict(District d) { districts.add(d); }

    public int getDistrictCount() { return districts.size(); }

    public int getTotalNetCount() {
      return districts.stream().mapToInt(d -> d.netNumbers.size()).sum();
    }

    @Override
    public String toString() {
      return String.format("FuncBlock#%d '%s' nets=%d dists=%d [%.0f,%.0f-%.0f,%.0f]",
          id, name, netNumbers.size(), districts.size(), minX, minY, maxX, maxY);
    }
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Boundary Point
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * A point on the boundary of a district, used for inter-district
   * connections and the boundary path table.
   */
  public static class BoundaryPoint implements Serializable {
    private static final long serialVersionUID = 1L;

    public final int id;
    public final double x, y;
    /** CH node ID nearest to this boundary point. */
    public final int chNodeId;
    /** Side: 0=left, 1=right, 2=bottom, 3=top. */
    public final int side;

    public BoundaryPoint(int id, double x, double y, int chNodeId, int side) {
      this.id = id;
      this.x = x;
      this.y = y;
      this.chNodeId = chNodeId;
      this.side = side;
    }

    @Override
    public String toString() {
      return String.format("BP#%d (%.0f,%.0f) chN=%d side=%d", id, x, y, chNodeId, side);
    }
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Fields
  // ═══════════════════════════════════════════════════════════════════════

  private final BasicBoard board;
  private final ContractionHierarchies ch;

  /** Globally all functional blocks. */
  private final List<FunctionalBlock> functionalBlocks;

  /** Flat list of ALL districts (for fast lookup). */
  private final List<District> allDistricts;

  /** Mapping: district ID → District. */
  private final Map<Integer, District> districtMap;

  /** Mapping: net number → district ID. */
  private final Map<Integer, Integer> netDistrictMap;

  // ═══════════════════════════════════════════════════════════════════════
  //  Construction
  // ═══════════════════════════════════════════════════════════════════════

  public MultiLevelPartitioner(BasicBoard board, ContractionHierarchies ch) {
    this.board = board;
    this.ch = ch;
    this.functionalBlocks = new ArrayList<>();
    this.allDistricts = new ArrayList<>();
    this.districtMap = new HashMap<>();
    this.netDistrictMap = new HashMap<>();
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Build full 3-level hierarchy
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Run the full 3-level partitioning pipeline.
   *
   * @param allNetNumbers all routable net numbers in the board
   * @return this partitioner (for chaining)
   */
  public MultiLevelPartitioner buildHierarchy(Set<Integer> allNetNumbers) {
    FRLogger.info("MLPartitioner: building 3-level hierarchy for "
        + allNetNumbers.size() + " nets");

    try {
      // Level 2: Functional block division
      buildLevel2FunctionalBlocks(allNetNumbers);
      FRLogger.info("MLPartitioner: Level 2 done, " + functionalBlocks.size() + " blocks");
    } catch (Exception e) {
      FRLogger.error("MLPartitioner: Level 2 failed: " + e.getMessage(), e);
      throw e;
    }

    try {
      // Level 3: District partition within each functional block
      buildLevel3Districts();
      FRLogger.info("MLPartitioner: Level 3 done, " + allDistricts.size() + " districts");
    } catch (Exception e) {
      FRLogger.error("MLPartitioner: Level 3 failed: " + e.getMessage(), e);
      throw e;
    }

    try {
      // Assign CH nodes to districts
      assignCHNodesToDistricts();
      FRLogger.info("MLPartitioner: CH node assignment done");
    } catch (Exception e) {
      FRLogger.error("MLPartitioner: CH assignment failed: " + e.getMessage(), e);
      throw e;
    }

    try {
      // Generate boundary points for each district
      generateBoundaryPoints();
      int totalBp = allDistricts.stream().mapToInt(d -> d.boundaryPoints.size()).sum();
      FRLogger.info("MLPartitioner: Boundary points generated: " + totalBp);
    } catch (Exception e) {
      FRLogger.error("MLPartitioner: Boundary point gen failed: " + e.getMessage(), e);
      throw e;
    }

    try {
      // Connect net numbers to districts
      assignNetsToDistricts(allNetNumbers);
      FRLogger.info("MLPartitioner: Net assignment done");
    } catch (Exception e) {
      FRLogger.error("MLPartitioner: Net assignment failed: " + e.getMessage(), e);
      throw e;
    }

    logPartitionSummary();
    return this;
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Level 2: Functional block division
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Divide the board into functional blocks based on pin spatial density.
   * <p>
   * Uses a simple grid-density approach:
   * <ol>
   *   <li>Divide the board into a coarse grid (e.g. 4×4 or 6×6)</li>
   *   <li>Compute pin count per cell</li>
   *   <li>Merge high-density adjacent cells into functional blocks</li>
   *   <li>Assign remaining low-density cells to nearest block</li>
   * </ol>
   */
  private void buildLevel2FunctionalBlocks(Set<Integer> allNetNumbers) {
    int fbGridCols = Math.max(2, Math.min(6, ch.gridCols / 4));
    int fbGridRows = Math.max(2, Math.min(6, ch.gridRows / 4));

    double cellW = (ch.gridCols * ch.cellSize) / fbGridCols;
    double cellH = (ch.gridRows * ch.cellSize) / fbGridRows;

    // Collect pin density per cell
    int[][] pinDensity = new int[fbGridRows][fbGridCols];
    for (int netNo : allNetNumbers) {
      Net net = board.rules.nets.get(netNo);
      if (net == null) continue;
      for (Pin pin : net.get_pins()) {
        FloatPoint c = pin.get_center().to_float();
        int col = (int) Math.min(fbGridCols - 1,
            Math.max(0, (c.x - ch.boardMinX) / cellW));
        int row = (int) Math.min(fbGridRows - 1,
            Math.max(0, (c.y - ch.boardMinY) / cellH));
        pinDensity[row][col]++;
      }
    }

    // Merge high-density cells into blocks (simple greedy flood-fill)
    boolean[][] visited = new boolean[fbGridRows][fbGridCols];
    int fbId = 0;

    for (int r = 0; r < fbGridRows; r++) {
      for (int c = 0; c < fbGridCols; c++) {
        if (visited[r][c]) continue;
        visited[r][c] = true;

        // Determine block bounding box from contiguous high-density region
        int minR = r, maxR = r, minC = c, maxC = c;
        boolean hasHighDensity = pinDensity[r][c] > 0;
        if (hasHighDensity) {
          // Expand region
          boolean expanded;
          do {
            expanded = false;
            for (int rr = minR; rr <= maxR; rr++) {
              for (int cc = minC; cc <= maxC; cc++) {
                if (rr >= 0 && rr < fbGridRows && cc >= 0 && cc < fbGridCols && !visited[rr][cc]) {
                  // Merge if cell has pins or is adjacent to pins (buffer zone)
                  if (pinDensity[rr][cc] > 0) {
                    visited[rr][cc] = true;
                    minR = Math.min(minR, rr);
                    maxR = Math.max(maxR, rr);
                    minC = Math.min(minC, cc);
                    maxC = Math.max(maxC, cc);
                    expanded = true;
                  }
                }
              }
            }
          } while (expanded);
        }

        double fbMinX = ch.boardMinX + minC * cellW;
        double fbMinY = ch.boardMinY + minR * cellH;
        double fbMaxX = ch.boardMinX + (maxC + 1) * cellW;
        double fbMaxY = ch.boardMinY + (maxR + 1) * cellH;

        // Add margin
        double mx = (fbMaxX - fbMinX) * BLOCK_MARGIN;
        double my = (fbMaxY - fbMinY) * BLOCK_MARGIN;
        String name = hasHighDensity ? "Block" + fbId : "Outskirts" + fbId;

        FunctionalBlock fb = new FunctionalBlock(fbId, name,
            fbMinX - mx, fbMinY - my, fbMaxX + mx, fbMaxY + my);
        functionalBlocks.add(fb);
        fbId++;
      }
    }

    // Assign nets to functional blocks
    for (int netNo : allNetNumbers) {
      Net net = board.rules.nets.get(netNo);
      if (net == null) continue;
      FloatPoint center = computeNetCenter(net);
      for (FunctionalBlock fb : functionalBlocks) {
        if (center.x >= fb.minX && center.x <= fb.maxX
            && center.y >= fb.minY && center.y <= fb.maxY) {
          fb.netNumbers.add(netNo);
          break;
        }
      }
    }

    // Remove empty blocks, merge very small blocks into neighbours
    functionalBlocks.removeIf(fb -> fb.netNumbers.size() < MIN_FUNC_BLOCK_NETS
        && functionalBlocks.size() > 1);

    FRLogger.info("MLPartitioner: created " + functionalBlocks.size()
        + " functional blocks");
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Level 3: District partition (spectral clustering within blocks)
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Partition each functional block into level-3 districts using the existing
   * SpectralClusterer for net-wise grouping, then spatial merging.
   */
  private void buildLevel3Districts() {
    SpectralClusterer spectralClusterer = new SpectralClusterer(board);
    int globalDistrictId = 0;

    for (FunctionalBlock fb : functionalBlocks) {
      if (fb.netNumbers.isEmpty()) {
        // Create a single default district anyway
        District d = new District(globalDistrictId++, fb.id,
            fb.minX, fb.minY, fb.maxX, fb.maxY);
        fb.addDistrict(d);
        allDistricts.add(d);
        continue;
      }

      // Use spectral clustering to group nets in this functional block
      List<NetCluster> clusters = spectralClusterer.cluster(fb.netNumbers);

      if (clusters.isEmpty() || clusters.size() == 1) {
        // Single district for this block
        District d = new District(globalDistrictId++, fb.id,
            fb.minX, fb.minY, fb.maxX, fb.maxY);
        d.netNumbers.addAll(fb.netNumbers);
        fb.addDistrict(d);
        allDistricts.add(d);
      } else {
        for (NetCluster cluster : clusters) {
          double dMinX = cluster.getMinX();
          double dMinY = cluster.getMinY();
          double dMaxX = cluster.getMaxX();
          double dMaxY = cluster.getMaxY();

          // Ensure the district bounding box stays within the functional block
          dMinX = Math.max(fb.minX, dMinX);
          dMinY = Math.max(fb.minY, dMinY);
          dMaxX = Math.min(fb.maxX, dMaxX);
          dMaxY = Math.min(fb.maxY, dMaxY);

          // Add margin
          double mx = (dMaxX - dMinX) * 0.15;
          double my = (dMaxY - dMinY) * 0.15;
          dMinX = Math.max(fb.minX, dMinX - mx);
          dMinY = Math.max(fb.minY, dMinY - my);
          dMaxX = Math.min(fb.maxX, dMaxX + mx);
          dMaxY = Math.min(fb.maxY, dMaxY + my);

          District d = new District(globalDistrictId++, fb.id,
              dMinX, dMinY, dMaxX, dMaxY);
          d.netNumbers.addAll(cluster.getNetNumbers());
          d.estimatedLoad = cluster.getNetCount();

          fb.addDistrict(d);
          allDistricts.add(d);
        }
      }
    }

    FRLogger.info("MLPartitioner: created " + allDistricts.size()
        + " level-3 districts across " + functionalBlocks.size()
        + " functional blocks");
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  CH node → district assignment
  // ═══════════════════════════════════════════════════════════════════════

  /** Assign each CH node to the district that contains it. */
  private void assignCHNodesToDistricts() {
    for (CHNode node : ch.getNodes()) {
      if (!node.isFree || node.isBlocked) continue;
      // Find the district containing this node's position
      for (District d : allDistricts) {
        if (d.contains(node.floatX, node.floatY)) {
          d.nodeIds.add(node.id);
          node.districtId = d.id;
          // Find which functional block
          for (FunctionalBlock fb : functionalBlocks) {
            if (fb.id == d.functionalBlockId) {
              node.functionalBlockId = fb.id;
              break;
            }
          }
          break;
        }
      }
    }

    // Mark boundary nodes: nodes that are on the edge of their district
    for (District d : allDistricts) {
      for (int nid : d.nodeIds) {
        CHNode n = ch.getNode(nid);
        if (n == null) continue;
        // Check if node is near the bounding box edge
        double marginX = d.getWidth() * 0.05;
        double marginY = d.getHeight() * 0.05;
        boolean onEdge = n.floatX - d.minX <= marginX
            || d.maxX - n.floatX <= marginX
            || n.floatY - d.minY <= marginY
            || d.maxY - n.floatY <= marginY;
        n.isBoundary = onEdge;
      }
    }
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Boundary point generation
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Generate boundary points for each district.
   * Boundary points are placed at district edges aligned to the CH grid.
   */
  private void generateBoundaryPoints() {
    int bpId = 0;
    for (District d : allDistricts) {
      // Place boundary points along each side at grid cell intervals
      double gw = ch.cellSize;
      double gh = ch.cellSize;

      // Top side
      for (double x = d.minX + gw / 2; x < d.maxX; x += gw * 2) {
        int chId = ch.findNearestNode(new FloatPoint((float) x, (float) d.maxY));
        if (chId >= 0) {
          d.addBoundaryPoint(new BoundaryPoint(bpId++, x, d.maxY, chId, 3));
        }
      }
      // Bottom side
      for (double x = d.minX + gw / 2; x < d.maxX; x += gw * 2) {
        int chId = ch.findNearestNode(new FloatPoint((float) x, (float) d.minY));
        if (chId >= 0) {
          d.addBoundaryPoint(new BoundaryPoint(bpId++, x, d.minY, chId, 2));
        }
      }
      // Left side
      for (double y = d.minY + gh / 2; y < d.maxY; y += gh * 2) {
        int chId = ch.findNearestNode(new FloatPoint((float) d.minX, (float) y));
        if (chId >= 0) {
          d.addBoundaryPoint(new BoundaryPoint(bpId++, d.minX, y, chId, 0));
        }
      }
      // Right side
      for (double y = d.minY + gh / 2; y < d.maxY; y += gh * 2) {
        int chId = ch.findNearestNode(new FloatPoint((float) d.maxX, (float) y));
        if (chId >= 0) {
          d.addBoundaryPoint(new BoundaryPoint(bpId++, d.maxX, y, chId, 1));
        }
      }
    }
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Net → district assignment
  // ═══════════════════════════════════════════════════════════════════════

  private void assignNetsToDistricts(Set<Integer> allNetNumbers) {
    for (int netNo : allNetNumbers) {
      // Find which district contains this net's center
      Net net = board.rules.nets.get(netNo);
      if (net == null) continue;
      FloatPoint center = computeNetCenter(net);
      for (District d : allDistricts) {
        if (d.contains(center.x, center.y)) {
          d.netNumbers.add(netNo);
          netDistrictMap.put(netNo, d.id);
          break;
        }
      }
    }
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Lookup
  // ═══════════════════════════════════════════════════════════════════════

  /** Find the district containing the given float point. */
  public District findDistrict(double x, double y) {
    for (District d : allDistricts) {
      if (d.contains(x, y)) return d;
    }
    return null;
  }

  /** Find the district for a given net number. */
  public District getDistrictForNet(int netNo) {
    Integer did = netDistrictMap.get(netNo);
    return did != null ? districtMap.get(did) : null;
  }

  /** Get the functional block for a given district. */
  public FunctionalBlock getFunctionalBlock(District d) {
    for (FunctionalBlock fb : functionalBlocks) {
      if (fb.id == d.functionalBlockId) return fb;
    }
    return null;
  }

  /** Get all districts (read-only). */
  public List<District> getAllDistricts() {
    return Collections.unmodifiableList(allDistricts);
  }

  /** Get all functional blocks (read-only). */
  public List<FunctionalBlock> getAllFunctionalBlocks() {
    return Collections.unmodifiableList(functionalBlocks);
  }

  /** Get the number of districts. */
  public int getDistrictCount() {
    return allDistricts.size();
  }

  /** Get the number of functional blocks. */
  public int getFunctionalBlockCount() {
    return functionalBlocks.size();
  }

  /** Get district by ID. */
  public District getDistrict(int id) {
    return districtMap.get(id);
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Helpers
  // ═══════════════════════════════════════════════════════════════════════

  private FloatPoint computeNetCenter(Net net) {
    double cx = 0, cy = 0;
    int count = 0;
    for (Pin pin : net.get_pins()) {
      FloatPoint c = pin.get_center().to_float();
      cx += c.x;
      cy += c.y;
      count++;
    }
    return count > 0 ? new FloatPoint((float) (cx / count), (float) (cy / count))
        : new FloatPoint(0, 0);
  }

  private void logPartitionSummary() {
    FRLogger.info("=== MLPartitioner Summary ===");
    FRLogger.info("  Functional blocks: " + functionalBlocks.size());
    FRLogger.info("  Districts: " + allDistricts.size());
    int totalBp = 0;
    for (District d : allDistricts) {
      totalBp += d.boundaryPoints.size();
    }
    FRLogger.info("  Boundary points: " + totalBp);
    if (!allDistricts.isEmpty()) {
      double avgNets = allDistricts.stream()
          .mapToInt(d -> d.netNumbers.size())
          .average().orElse(0);
      double avgNodes = allDistricts.stream()
          .mapToInt(d -> d.nodeIds.size())
          .average().orElse(0);
      FRLogger.info(String.format("  Avg nets/district: %.1f, Avg nodes/district: %.1f",
          avgNets, avgNodes));
    }
  }
}
