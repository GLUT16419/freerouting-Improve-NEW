package app.freerouting.autoroute;

/**
 * Represents a spatial region of the board for adaptive parallel routing.
 * Each region has a core bounding box (no overlap) and an expanded bounding box
 * (with 5% overlap buffer) used for stub truncation checks during parallel routing.
 */
public class Region {
  public final int id;
  public final double x0, y0;
  public final double x1, y1;
  public final double cx0, cy0;
  public final double cx1, cy1;
  public double estimatedLoad;
  public boolean isActive;

  /**
   * Creates a region with the given core bounding box.
   * The expanded bounding box is automatically computed with a 5% overlap buffer.
   */
  public Region(int id, double x0, double y0, double x1, double y1) {
    this.id = id;
    this.x0 = x0;
    this.y0 = y0;
    this.x1 = x1;
    this.y1 = y1;
    // 5% overlap buffer for expanded bbox
    double overlapX = (x1 - x0) * 0.05;
    double overlapY = (y1 - y0) * 0.05;
    this.cx0 = x0 - overlapX;
    this.cy0 = y0 - overlapY;
    this.cx1 = x1 + overlapX;
    this.cy1 = y1 + overlapY;
    this.estimatedLoad = 0.0;
    this.isActive = true;
  }

  /** Returns true if the point is within the core bounding box. */
  public boolean containsPoint(double x, double y) {
    return x >= x0 && x <= x1 && y >= y0 && y <= y1;
  }

  /** Returns true if the point is within the expanded bounding box. */
  public boolean containsInExpanded(double x, double y) {
    return x >= cx0 && x <= cx1 && y >= cy0 && y <= cy1;
  }

  /**
   * Computes the overlap ratio between the given bounding box and this region's
   * expanded bounding box, relative to this region's core area.
   */
  public double overlapRatio(double minX, double minY, double maxX, double maxY) {
    double ox = Math.max(0, Math.min(maxX, cx1) - Math.max(minX, cx0));
    double oy = Math.max(0, Math.min(maxY, cy1) - Math.max(minY, cy0));
    double area = (x1 - x0) * (y1 - y0);
    if (area <= 0) return 0;
    return (ox * oy) / area;
  }

  public double getWidth() { return x1 - x0; }
  public double getHeight() { return y1 - y0; }
  public double getCenterX() { return (x0 + x1) / 2.0; }
  public double getCenterY() { return (y0 + y1) / 2.0; }

  @Override
  public String toString() {
    return String.format("Region#%d [%s] load=%.1f active=%b",
        id, formatBbox(), estimatedLoad, isActive);
  }

  private String formatBbox() {
    return String.format("%.0f,%.0f-%.0f,%.0f", x0, y0, x1, y1);
  }
}
