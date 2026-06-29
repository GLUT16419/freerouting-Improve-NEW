package app.freerouting.gui;

import app.freerouting.util.TextManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;

/**
 * The `BoardPanelStatus` class represents a status bar at the lower border of the board frame.
 * It contains components such as message lines, current layer indicator, and cursor position.
 */
class BoardPanelStatus extends JPanel {

  public final JLabel errorLabel;
  public final JLabel warningLabel;
  public final JLabel statusMessage;
  public final JLabel additionalMessage;
  public final JLabel currentLayer;
  public final JLabel currentBoardScore;
  public final JLabel mousePosition;
  public final JLabel unitLabel;
  /** Stage timing display (fanout / autoroute / optimizer elapsed). */
  public final JLabel stageTimeLabel;
  // An icon for errors and warnings
  private final JPanel errorsWarningsPanel;
  private final JLabel errorIcon;
  private final JLabel warningIcon;
  // List to hold the listeners for error or warning label clicks
  private final List<ErrorOrWarningLabelClickedListener> errorOrWarningLabelClickedListeners = new ArrayList<>();

  // Dark theme colors
  private static final Color DARK_BACKGROUND = new Color(30, 30, 30);
  private static final Color DARK_FOREGROUND = new Color(100, 150, 255);
  private static final Color DARK_ACCENT = new Color(100, 150, 255);

  /**
   * Creates a new instance of the `BoardPanelStatus` class.
   *
   * @param locale the locale to use for resource bundles
   */
  BoardPanelStatus(Locale locale) {
    TextManager tm = new TextManager(this.getClass(), locale);

    setLayout(new BorderLayout());
    setBackground(DARK_BACKGROUND);

    // Left panel with warnings, errors, and status messages
    errorsWarningsPanel = new JPanel(new BorderLayout());
    errorsWarningsPanel.setBackground(DARK_BACKGROUND);

    // Load the Material Icons for warnings and errors
    warningIcon = new JLabel();
    tm.setText(warningIcon, "{{icon:alert}}");
    errorIcon = new JLabel();
    tm.setText(errorIcon, "{{icon:close-octagon}}");

    // Initialize labels with icons
    warningLabel = new JLabel("0", SwingConstants.LEADING);
    warningLabel.setForeground(DARK_FOREGROUND);
    errorLabel = new JLabel("0", SwingConstants.LEADING);
    errorLabel.setForeground(DARK_FOREGROUND);

    // Left-aligned panel for icons and counts
    JPanel countsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    countsPanel.setBackground(DARK_BACKGROUND);
    errorIcon.setForeground(DARK_FOREGROUND);
    warningIcon.setForeground(DARK_FOREGROUND);
    countsPanel.add(errorIcon);
    countsPanel.add(errorLabel);
    countsPanel.add(warningIcon);
    countsPanel.add(warningLabel);
    errorsWarningsPanel.add(countsPanel, BorderLayout.WEST);

    // Add mouse listeners for error and warning labels
    addErrorOrWarningLabelClickedListener();

    // Add margin to the right of the labels
    int top = 0;
    int left = 0;
    int bottom = 0;
    int right = 10;
    warningLabel.setBorder(new EmptyBorder(top, left, bottom, right));
    errorLabel.setBorder(new EmptyBorder(top, left, bottom, right));

    // Initialize status message label with SmartLabel for auto-truncation
    statusMessage = new SmartLabel();
    statusMessage.setHorizontalAlignment(SwingConstants.CENTER);
    statusMessage.setForeground(DARK_FOREGROUND);
    tm.setText(statusMessage, "status_line");
    errorsWarningsPanel.add(statusMessage, BorderLayout.CENTER);

    // Initialize additional message label with SmartLabel for auto-truncation
    additionalMessage = new SmartLabel();
    tm.setText(additionalMessage, "additional_text_field");
    additionalMessage.setMaximumSize(new Dimension(300, 14));
    additionalMessage.setMinimumSize(new Dimension(140, 14));
    additionalMessage.setPreferredSize(new Dimension(180, 14));
    additionalMessage.setForeground(DARK_FOREGROUND);
    errorsWarningsPanel.add(additionalMessage, BorderLayout.EAST);
    add(errorsWarningsPanel, BorderLayout.CENTER);

    // Right panel with current layer and cursor position
    JPanel rightMessagePanel = new JPanel(new BorderLayout());
    rightMessagePanel.setMinimumSize(new Dimension(200, 20));
    rightMessagePanel.setOpaque(false);
    rightMessagePanel.setPreferredSize(new Dimension(550, 20));

    // Initialize current layer label
    currentLayer = new JLabel();
    tm.setText(currentLayer, "current_layer");
    currentLayer.setForeground(DARK_FOREGROUND);
    rightMessagePanel.add(currentLayer, BorderLayout.WEST);

    // Initialize current board score label
    currentBoardScore = new JLabel();
    tm.setText(currentBoardScore, "current_board_score");
    currentBoardScore.setForeground(DARK_ACCENT);
    rightMessagePanel.add(currentBoardScore, BorderLayout.CENTER);

    // Create stage time label
    stageTimeLabel = new JLabel();
    stageTimeLabel.setHorizontalAlignment(SwingConstants.RIGHT);
    stageTimeLabel.setForeground(new Color(100, 150, 255)); // blue for time
    stageTimeLabel.setMinimumSize(new Dimension(80, 14));
    stageTimeLabel.setPreferredSize(new Dimension(120, 14));
    stageTimeLabel.setText("");
    rightMessagePanel.add(stageTimeLabel, BorderLayout.EAST);

    // Create cursor panel
    JPanel cursorPanel = new JPanel(new BorderLayout());
    cursorPanel.setMinimumSize(new Dimension(220, 14));
    cursorPanel.setPreferredSize(new Dimension(220, 14));
    cursorPanel.setOpaque(false);

    // Initialize mouse position label
    mousePosition = new JLabel();
    mousePosition.setText("X 0.00   Y 0.00");
    mousePosition.setMaximumSize(new Dimension(170, 14));
    mousePosition.setPreferredSize(new Dimension(170, 14));
    mousePosition.setForeground(DARK_FOREGROUND);
    cursorPanel.add(mousePosition, BorderLayout.WEST);

    // Initialize cursor label
    unitLabel = new JLabel();
    unitLabel.setHorizontalAlignment(SwingConstants.CENTER);
    unitLabel.setText("unit");
    unitLabel.setMaximumSize(new Dimension(100, 14));
    unitLabel.setMinimumSize(new Dimension(50, 14));
    unitLabel.setPreferredSize(new Dimension(50, 14));
    unitLabel.setForeground(DARK_FOREGROUND);
    cursorPanel.add(unitLabel, BorderLayout.EAST);

    // Wrap right panels: stage time + layer + score on top, cursor on bottom
    JPanel rightTopPanel = new JPanel(new BorderLayout());
    rightTopPanel.setOpaque(false);
    rightTopPanel.add(currentLayer, BorderLayout.WEST);
    rightTopPanel.add(currentBoardScore, BorderLayout.CENTER);
    rightTopPanel.add(stageTimeLabel, BorderLayout.EAST);

    JPanel rightOuterPanel = new JPanel(new BorderLayout());
    rightOuterPanel.setOpaque(false);
    rightOuterPanel.add(rightTopPanel, BorderLayout.NORTH);
    rightOuterPanel.add(cursorPanel, BorderLayout.SOUTH);
    rightMessagePanel.add(rightOuterPanel, BorderLayout.EAST);

    add(rightMessagePanel, BorderLayout.EAST);
  }

  /**
   * Adds mouse listeners for error and warning labels to handle click events.
   */
  private void addErrorOrWarningLabelClickedListener() {
    // Raise an event if the user clicks on the error or warning label
    errorsWarningsPanel.addMouseListener(new MouseAdapter() {
      @Override
      public void mouseClicked(MouseEvent e) {
        raiseErrorOrWarningLabelClickedEvent();
      }
    });

    // Change the mouse cursor to a hand when hovering over these labels
    errorsWarningsPanel.setCursor(new Cursor(Cursor.HAND_CURSOR));
  }

  /**
   * Raises the `ErrorOrWarningLabelClicked` event for all registered listeners.
   */
  private void raiseErrorOrWarningLabelClickedEvent() {
    for (ErrorOrWarningLabelClickedListener listener : errorOrWarningLabelClickedListeners) {
      listener.errorOrWarningLabelClicked();
    }
  }

  /**
   * Adds an `ErrorOrWarningLabelClickedListener` to the list of listeners.
   *
   * @param listener the listener to be added
   */
  public void addErrorOrWarningLabelClickedListener(ErrorOrWarningLabelClickedListener listener) {
    errorOrWarningLabelClickedListeners.add(listener);
  }

  /**
   * The `ErrorOrWarningLabelClickedListener` interface defines a method to handle the click event on the error or warning labels.
   */
  @FunctionalInterface
  public interface ErrorOrWarningLabelClickedListener {

    /**
     * Invoked when the error or warning label is clicked.
     */
    void errorOrWarningLabelClicked();
  }
}
