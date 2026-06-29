package app.freerouting.board;

import app.freerouting.autoroute.LayerFunction;

import java.io.Serializable;

/**
 * Describes the structure of a board layer.
 */
public class Layer implements Serializable {

  /**
   * The name of the layer.
   */
  public final String name;
  /**
   * True, if this is a signal layer, which can be used for routing. Otherwise, it may be for example a power ground layer.
   */
  public final boolean is_signal;
  /**
   * The assigned functional role of this layer.
   * Set during Layer Function Assignment (LFA) pre-processing.
   */
  public LayerFunction function;

  /**
   * Creates a new instance of Layer
   */
  public Layer(String p_name, boolean p_is_signal) {
    name = p_name;
    is_signal = p_is_signal;
    // Auto-assign function from name heuristic
    this.function = LayerFunction.fromName(p_name);
    if (this.function == LayerFunction.OTHER || this.function == null) {
      this.function = p_is_signal ? LayerFunction.SIGNAL : LayerFunction.MECHANICAL;
    }
  }

  /**
   * Creates a new instance of Layer with explicit function assignment.
   */
  public Layer(String p_name, boolean p_is_signal, LayerFunction p_function) {
    name = p_name;
    is_signal = p_is_signal;
    this.function = p_function;
  }

  @Override
  public String toString() {
    return name;
  }
}
