package app.freerouting.autoroute;

import app.freerouting.logger.FRLogger;
import java.io.Serializable;
import java.util.*;

/**
 * Precomputed district boundary path table for the CRP (Customizable Route
 * Planning) multi-level partitioner.
 * <p>
 * In the UTPR city metaphor, this is analogous to a <b>city transit handbook</b>
 * that lists the shortest road or subway connection between every pair of
 * boundary checkpoints of each district. With this table precomputed,
 * inter- and intra-district path planning becomes a fast table lookup instead
 * of an expensive graph search.
 * <p>
 * <b>Two levels of precomputation:</b>
 * <ol>
 *   <li><b>Intra-district</b>: shortest paths between every pair of boundary
 *       points <i>within</i> the same district.</li>
 *   <li><b>Functional-block</b>: shortest paths between boundary points of
 *       different districts within the same functional block.</li>
 * </ol>
 * These precomputed paths depend only on the geometry and obstacles within the
 * district, and are <b>independent of routing decisions</b> — enabling true
 * parallel routing of each district.
 */
public class DistrictBoundaryPathTable implements Serializable {

  private static final long serialVersionUID = 1L;

  // ═══════════════════════════════════════════════════════════════════════
  //  PathInfo
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Describes a precomputed shortest path between two boundary points.
   */
  public static class PathInfo implements Serializable {
    private static final long serialVersionUID = 1L;

    /** Total path weight (distance + cost). */
    public final double length;

    /** CH node IDs along the path (from source to target). */
    public final List<Integer> nodePath;

    /** Number of CH nodes in the path. */
    public final int hopCount;

    public PathInfo(double length, List<Integer> nodePath) {
      this.length = length;
      this.nodePath = nodePath;
      this.hopCount = nodePath.size();
    }

    public boolean isEmpty() { return nodePath.isEmpty(); }

    @Override
    public String toString() {
      return String.format("Path len=%.1f hops=%d [%d→...→%d]",
          length, hopCount,
          nodePath.isEmpty() ? -1 : nodePath.get(0),
          nodePath.isEmpty() ? -1 : nodePath.get(nodePath.size() - 1));
    }
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Fields
  // ═══════════════════════════════════════════════════════════════════════

  /** The CH graph used for shortest path computation. */
  private final ContractionHierarchies ch;

  /** The multi-level partitioner that defines districts and boundary points. */
  private final MultiLevelPartitioner partitioner;

  /**
   * Intra-district paths.
   * Key: (districtId << 32) | (fromBpId << 16) | toBpId  (long)
   * <p>
   * Only stores one direction (fromBpId < toBpId).
   */
  private final Map<Long, PathInfo> intraDistrictPaths;

  /**
   * Inter-district (functional-block level) paths.
   * Same key encoding.
   */
  private final Map<Long, PathInfo> interDistrictPaths;

  /** Number of precomputed intra-district paths. */
  private int intraPathCount;

  /** Number of precomputed inter-district paths. */
  private int interPathCount;

  /** Cache hit counters (transient — reset each session). */
  private transient int cacheHits;

  // ═══════════════════════════════════════════════════════════════════════
  //  Construction
  // ═══════════════════════════════════════════════════════════════════════

  public DistrictBoundaryPathTable(ContractionHierarchies ch,
                                   MultiLevelPartitioner partitioner) {
    this.ch = ch;
    this.partitioner = partitioner;
    this.intraDistrictPaths = new HashMap<>();
    this.interDistrictPaths = new HashMap<>();
    this.intraPathCount = 0;
    this.interPathCount = 0;
    this.cacheHits = 0;
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Precomputation
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Precompute all intra-district and inter-district boundary paths.
   * <p>
   * For each district, compute shortest paths between every pair of its
   * boundary points using the CH bidirectional Dijkstra query.
   * Then, for each functional block, compute cross-district paths.
   */
  public void precomputeAll() {
    FRLogger.info("BoundaryPathTable: starting precomputation...");

    long t0 = System.currentTimeMillis();
    precomputeIntraDistrictPaths();
    long t1 = System.currentTimeMillis();
    precomputeInterDistrictPaths();
    long t2 = System.currentTimeMillis();

    FRLogger.info("BoundaryPathTable: precomputed " + intraPathCount
        + " intra-district paths (" + (t1 - t0) + " ms), "
        + interPathCount + " inter-district paths (" + (t2 - t1) + " ms)");
  }

  /**
   * For each district: compute shortest paths between every pair of its
   * boundary points.
   */
  private void precomputeIntraDistrictPaths() {
    for (MultiLevelPartitioner.District district : partitioner.getAllDistricts()) {
      List<MultiLevelPartitioner.BoundaryPoint> bps = district.boundaryPoints;
      if (bps.size() < 2) continue;

      int computed = 0;
      for (int i = 0; i < bps.size(); i++) {
        for (int j = i + 1; j < bps.size(); j++) {
          MultiLevelPartitioner.BoundaryPoint from = bps.get(i);
          MultiLevelPartitioner.BoundaryPoint to = bps.get(j);

          ContractionHierarchies.CHPath path = ch.query(from.chNodeId, to.chNodeId);
          if (path.found) {
            long key = encodeKey(district.id, from.id, to.id);
            intraDistrictPaths.put(key, new PathInfo(path.totalWeight, path.nodeIds));
            intraPathCount++;
            computed++;
          }
        }
      }

      if (computed > 0) {
        FRLogger.info("BoundaryPathTable: district " + district.id
            + " (" + bps.size() + " BPs): " + computed + " intra paths");
      }
    }
  }

  /**
   * For each functional block: compute shortest paths between boundary points
   * of different districts within the same block.
   * <p>
   * These correspond to the <b>区连接图</b> (district connection graph)
   * described in the CRP design.
   */
  private void precomputeInterDistrictPaths() {
    for (MultiLevelPartitioner.FunctionalBlock fb : partitioner.getAllFunctionalBlocks()) {
      List<MultiLevelPartitioner.District> districts = fb.districts;
      if (districts.size() < 2) continue;

      int computed = 0;
      for (int i = 0; i < districts.size(); i++) {
        MultiLevelPartitioner.District d1 = districts.get(i);
        List<MultiLevelPartitioner.BoundaryPoint> bps1 = d1.boundaryPoints;
        if (bps1.isEmpty()) continue;

        for (int j = i + 1; j < districts.size(); j++) {
          MultiLevelPartitioner.District d2 = districts.get(j);
          List<MultiLevelPartitioner.BoundaryPoint> bps2 = d2.boundaryPoints;
          if (bps2.isEmpty()) continue;

          // Connect every pair of boundary points across the two districts
          // (limit to nearest pairs for efficiency — typically only
          //  boundary points at the shared edge matter)
          // Full O(n^2) would be expensive; sample a subset.

          // Compute district centers
          double c1x = d1.getCenterX(), c1y = d1.getCenterY();
          double c2x = d2.getCenterX(), c2y = d2.getCenterY();

          // Find the boundary points on the facing edges
          List<MultiLevelPartitioner.BoundaryPoint> facing1 = findFacingBps(bps1, c1x, c1y, c2x, c2y, d1);
          List<MultiLevelPartitioner.BoundaryPoint> facing2 = findFacingBps(bps2, c2x, c2y, c1x, c1y, d2);

          int maxPairs = Math.min(facing1.size() * facing2.size(), 16);
          int pairCount = 0;

          for (MultiLevelPartitioner.BoundaryPoint bp1 : facing1) {
            for (MultiLevelPartitioner.BoundaryPoint bp2 : facing2) {
              if (pairCount >= maxPairs) break;

              ContractionHierarchies.CHPath path = ch.query(bp1.chNodeId, bp2.chNodeId);
              if (path.found) {
                // Use a virtual district ID for functional-block-level paths
                // Encode as: (-fbId-1) << 32 | ...
                long key = encodeKey(-fb.id - 1, bp1.id, bp2.id);
                interDistrictPaths.put(key, new PathInfo(path.totalWeight, path.nodeIds));
                interPathCount++;
                computed++;
                pairCount++;
              }
            }
          }
        }
      }

      if (computed > 0) {
        FRLogger.info("BoundaryPathTable: funblock " + fb.id + " (" + fb.name
            + "): " + computed + " inter-district paths");
      }
    }
  }

  /**
   * Find boundary points that face towards the other area.
   * For district A towards district B: finds boundary points on the side
   * of A that is closest to B.
   */
  private List<MultiLevelPartitioner.BoundaryPoint> findFacingBps(
      List<MultiLevelPartitioner.BoundaryPoint> bps,
      double ax, double ay, double bx, double by,
      MultiLevelPartitioner.District district) {

    List<MultiLevelPartitioner.BoundaryPoint> facing = new ArrayList<>();

    // Determine the direction from A to B
    double dx = bx - ax;
    double dy = by - ay;

    // Select boundary points on the side closest to B
    for (MultiLevelPartitioner.BoundaryPoint bp : bps) {
      boolean onFacingSide;
      if (Math.abs(dx) > Math.abs(dy)) {
        // Horizontal separation: prefer left/right boundary points
        onFacingSide = (dx > 0 && bp.side == 1) || (dx <= 0 && bp.side == 0);
      } else {
        // Vertical separation: prefer top/bottom boundary points
        onFacingSide = (dy > 0 && bp.side == 3) || (dy <= 0 && bp.side == 2);
      }
      if (onFacingSide) {
        facing.add(bp);
      }
    }

    // If no facing boundary points found, use all of them
    if (facing.isEmpty()) {
      return bps;
    }
    return facing;
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Lookup
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Get the precomputed intra-district path between two boundary points.
   *
   * @param districtId ID of the district
   * @param fromBpId   source boundary point ID
   * @param toBpId     target boundary point ID
   * @return the precomputed PathInfo, or {@code null} if not found
   */
  public PathInfo getIntraDistrictPath(int districtId, int fromBpId, int toBpId) {
    if (fromBpId < toBpId) {
      PathInfo pi = intraDistrictPaths.get(encodeKey(districtId, fromBpId, toBpId));
      if (pi != null) cacheHits++;
      return pi;
    } else {
      // Swap direction: paths are stored one-way (fromBpId < toBpId)
      PathInfo pi = intraDistrictPaths.get(encodeKey(districtId, toBpId, fromBpId));
      if (pi != null) {
        cacheHits++;
        return reversePath(pi);
      }
      return null;
    }
  }

  /**
   * Get the precomputed inter-district path (for the functional-block-level
   * connection graph).
   *
   * @param fbId     functional block ID (used for virtual district encoding)
   * @param fromBpId source boundary point ID
   * @param toBpId   target boundary point ID
   * @return the precomputed PathInfo, or {@code null}
   */
  public PathInfo getInterDistrictPath(int fbId, int fromBpId, int toBpId) {
    long key;
    if (fromBpId < toBpId) {
      key = encodeKey(-fbId - 1, fromBpId, toBpId);
    } else {
      key = encodeKey(-fbId - 1, toBpId, fromBpId);
    }
    PathInfo pi = interDistrictPaths.get(key);
    if (pi != null) {
      cacheHits++;
      if (fromBpId > toBpId) {
        return reversePath(pi);
      }
    }
    return pi;
  }

  /**
   * Check if an intra-district path exists between two boundary points
   * without retrieving the full path.
   */
  public boolean hasIntraPath(int districtId, int fromBpId, int toBpId) {
    long key = encodeKey(districtId, Math.min(fromBpId, toBpId), Math.max(fromBpId, toBpId));
    return intraDistrictPaths.containsKey(key);
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Stats
  // ═══════════════════════════════════════════════════════════════════════

  public int getIntraPathCount() { return intraPathCount; }

  public int getInterPathCount() { return interPathCount; }

  public int getCacheHits() { return cacheHits; }

  public void resetCacheHits() { cacheHits = 0; }

  // ═══════════════════════════════════════════════════════════════════════
  //  Key encoding
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Encode a (zoneId, bpId1, bpId2) tuple into a single long key.
   * <p>
   * ZoneId can be a positive district ID or a negative virtual FB ID.
   * Only used for bpId1 &lt; bpId2 (ordered endpoints).
   */
  private static long encodeKey(int zoneId, int bpId1, int bpId2) {
    // zoneId in upper 24 bits, bpId1 in middle 20 bits, bpId2 in lower 20 bits
    return ((long) (zoneId & 0xFFFFFF) << 40)
        | ((long) (bpId1 & 0xFFFFF) << 20)
        | (long) (bpId2 & 0xFFFFF);
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Path reversal
  // ═══════════════════════════════════════════════════════════════════════

  private PathInfo reversePath(PathInfo pi) {
    List<Integer> reversed = new ArrayList<>(pi.nodePath);
    Collections.reverse(reversed);
    return new PathInfo(pi.length, reversed);
  }
}
