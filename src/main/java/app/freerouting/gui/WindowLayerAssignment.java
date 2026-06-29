package app.freerouting.gui;

import app.freerouting.autoroute.LayerFunction;
import app.freerouting.autoroute.LayerFunctionAutoAssigner;
import app.freerouting.autoroute.NetType;
import app.freerouting.autoroute.PowerGndAutoLabeler;
import app.freerouting.board.Layer;
import app.freerouting.board.LayerStructure;
import app.freerouting.board.BasicBoard;
import app.freerouting.interactive.GuiBoardManager;
import app.freerouting.rules.Net;
import app.freerouting.settings.RouterSettings;
import app.freerouting.util.TextManager;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Vector;

/**
 * Unified window for layer function assignment, net type labeling,
 * net selection, via type and routing time limit.
 * Auto-assignment runs on board load; manual overrides available.
 */
public class WindowLayerAssignment extends BoardSavableSubWindow {

  private static final long DEFAULT_TIMEOUT_SECONDS = 3600L; // 1 hour default
  private static final long MAX_TIMEOUT_SECONDS = 86400L; // 24 hours

  private final GuiBoardManager board_handling;
  private final BoardFrame boardFrame;
  private JTable layerTable;
  private JTable netTable;
  private DefaultTableModel layerTableModel;
  private DefaultTableModel netTableModel;

  // Timeout fields (shared across tabs)
  private JFormattedTextField timeout_hours_field;
  private JFormattedTextField timeout_minutes_field;
  private JFormattedTextField timeout_seconds_field;
  private JComboBox<String> viaTypeCombo;

  public WindowLayerAssignment(BoardFrame p_board_frame) {
    setLanguage(p_board_frame.get_locale());
    this.boardFrame = p_board_frame;
    this.board_handling = p_board_frame.board_panel.board_handling;
    this.setTitle(tm.getText("title"));
    this.setDefaultCloseOperation(DISPOSE_ON_CLOSE);
    this.setPreferredSize(new Dimension(600, 580));
    this.setResizable(true);

    // Lock algorithm to hybrid on open
    RouterSettings rs = board_handling.getCurrentRoutingJob().routerSettings;
    if (rs != null) {
      rs.setAlgorithm(RouterSettings.ALGORITHM_HYBRID);
    }

    JTabbedPane tabbedPane = new JTabbedPane();

    JPanel layerPanel = createLayerPanel();
    tabbedPane.addTab(tm.getText("layer_tab"), layerPanel);

    JPanel netPanel = createNetPanel();
    tabbedPane.addTab(tm.getText("net_tab"), netPanel);

    JPanel globalPanel = createGlobalPanel();
    tabbedPane.addTab("全局设置", globalPanel);

    getContentPane().add(tabbedPane);
    this.pack();
    this.setLocationRelativeTo(p_board_frame);
  }

  // ════════════════════════════════════════════════════════════
  //  Layer Tab (unchanged)
  // ════════════════════════════════════════════════════════════

  private JPanel createLayerPanel() {
    JPanel panel = new JPanel(new BorderLayout(5, 5));
    panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

    JLabel desc = new JLabel(tm.getText("layer_desc"));
    desc.setFont(desc.getFont().deriveFont(Font.PLAIN));
    panel.add(desc, BorderLayout.NORTH);

    String[] layerColumns = {
        tm.getText("col_index"),
        tm.getText("col_layer_name"),
        tm.getText("col_signal"),
        tm.getText("col_auto_function"),
        tm.getText("col_manual_function")
    };
    layerTableModel = new DefaultTableModel(layerColumns, 0) {
      @Override
      public boolean isCellEditable(int row, int column) {
        return column == 4;
      }
    };

    layerTable = new JTable(layerTableModel);
    layerTable.getColumnModel().getColumn(0).setMaxWidth(50);
    layerTable.getColumnModel().getColumn(2).setMaxWidth(60);

    JComboBox<LayerFunction> functionCombo = new JComboBox<>(LayerFunction.values());
    functionCombo.setRenderer((list, value, index, isSelected, cellHasFocus) -> {
      String display = (value != null) ? value.getChineseName() : "";
      return new JLabel(display);
    });
    layerTable.getColumnModel().getColumn(4).setCellEditor(new DefaultCellEditor(functionCombo));
    layerTable.getColumnModel().getColumn(3).setCellRenderer(new FunctionCellRenderer());
    layerTable.getColumnModel().getColumn(4).setCellRenderer(new FunctionCellRenderer());

    JScrollPane scrollPane = new JScrollPane(layerTable);
    scrollPane.setPreferredSize(new Dimension(500, 250));
    panel.add(scrollPane, BorderLayout.CENTER);

    JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));
    JButton autoAssignBtn = new JButton(tm.getText("auto_assign_layers"));
    autoAssignBtn.addActionListener(this::onAutoAssignLayers);
    buttonPanel.add(autoAssignBtn);

    JButton applyLayerBtn = new JButton(tm.getText("apply_manual"));
    applyLayerBtn.addActionListener(this::onApplyLayerChanges);
    buttonPanel.add(applyLayerBtn);

    panel.add(buttonPanel, BorderLayout.SOUTH);

    refreshLayerTable();
    return panel;
  }

  private void refreshLayerTable() {
    if (layerTableModel == null) return;
    layerTableModel.setRowCount(0);
    BasicBoard board = board_handling.get_routing_board();
    if (board == null || board.layer_structure == null) return;

    LayerStructure ls = board.layer_structure;
    LayerFunctionAutoAssigner.assignFunctions(ls);

    for (int i = 0; i < ls.arr.length; i++) {
      Layer layer = ls.arr[i];
      if (layer == null) continue;
      String signalStr = layer.is_signal ? tm.getText("yes") : tm.getText("no");
      String autoFunc = (layer.function != null) ? layer.function.getChineseName() : "";
      Vector<Object> row = new Vector<>();
      row.add(i);
      row.add(layer.name);
      row.add(signalStr);
      row.add(autoFunc);
      row.add(layer.function);
      layerTableModel.addRow(row);
    }
  }

  private void onAutoAssignLayers(ActionEvent e) {
    BasicBoard board = board_handling.get_routing_board();
    if (board == null || board.layer_structure == null) return;
    LayerFunctionAutoAssigner.assignFunctions(board.layer_structure);
    if (boardFrame != null && boardFrame.board_panel != null) {
      boardFrame.board_panel.repaint();
    }
    refreshLayerTable();
    JOptionPane.showMessageDialog(this,
        tm.getText("auto_assign_complete"),
        tm.getText("info"),
        JOptionPane.INFORMATION_MESSAGE);
  }

  private void onApplyLayerChanges(ActionEvent e) {
    BasicBoard board = board_handling.get_routing_board();
    if (board == null || board.layer_structure == null) return;

    LayerStructure ls = board.layer_structure;
    int changedCount = 0;

    for (int row = 0; row < layerTableModel.getRowCount(); row++) {
      int layerIndex = (Integer) layerTableModel.getValueAt(row, 0);
      if (layerIndex < 0 || layerIndex >= ls.arr.length) continue;
      Layer layer = ls.arr[layerIndex];
      if (layer == null) continue;

      Object manualVal = layerTableModel.getValueAt(row, 4);
      if (manualVal instanceof LayerFunction newFunc && newFunc != layer.function) {
        layer.function = newFunc;
        changedCount++;
      }
    }

    if (boardFrame != null && boardFrame.board_panel != null) {
      boardFrame.board_panel.repaint();
    }
    refreshLayerTable();
    JOptionPane.showMessageDialog(this,
        tm.getText("layer_changes_applied", String.valueOf(changedCount)),
        tm.getText("info"),
        JOptionPane.INFORMATION_MESSAGE);
  }

  // ════════════════════════════════════════════════════════════
  //  Net Type Tab (with route checkbox)
  // ════════════════════════════════════════════════════════════

  private JPanel createNetPanel() {
    JPanel panel = new JPanel(new BorderLayout(5, 5));
    panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

    JLabel desc = new JLabel(tm.getText("net_desc"));
    desc.setFont(desc.getFont().deriveFont(Font.PLAIN));
    panel.add(desc, BorderLayout.NORTH);

    // Columns: net_no, net_name, auto_type, manual_type (editable), route (checkbox)
    String[] netColumns = {
        tm.getText("col_net_no"),
        tm.getText("col_net_name"),
        tm.getText("col_auto_type"),
        tm.getText("col_manual_type"),
        tm.getText("col_route")
    };
    netTableModel = new DefaultTableModel(netColumns, 0) {
      @Override
      public Class<?> getColumnClass(int columnIndex) {
        if (columnIndex == 4) return Boolean.class;
        return super.getColumnClass(columnIndex);
      }
      @Override
      public boolean isCellEditable(int row, int column) {
        return column == 3 || column == 4; // manual type + route checkbox
      }
    };

    netTable = new JTable(netTableModel);
    netTable.getColumnModel().getColumn(0).setMaxWidth(60);
    netTable.getColumnModel().getColumn(4).setMaxWidth(50);

    JComboBox<NetType> typeCombo = new JComboBox<>(new NetType[]{NetType.SIGNAL, NetType.POWER, NetType.GROUND});
    typeCombo.setRenderer((list, value, index, isSelected, cellHasFocus) -> {
      String display = (value != null) ? value.getChineseName() : "";
      return new JLabel(display);
    });
    netTable.getColumnModel().getColumn(3).setCellEditor(new DefaultCellEditor(typeCombo));
    netTable.getColumnModel().getColumn(2).setCellRenderer(new NetTypeCellRenderer());
    netTable.getColumnModel().getColumn(3).setCellRenderer(new NetTypeCellRenderer());

    JScrollPane scrollPane = new JScrollPane(netTable);
    scrollPane.setPreferredSize(new Dimension(500, 250));
    panel.add(scrollPane, BorderLayout.CENTER);

    // ── Buttons ──
    JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));

    JButton selectAllBtn = new JButton(tm.getText("select_all"));
    selectAllBtn.addActionListener(e -> setAllRouteCheckboxes(true));
    buttonPanel.add(selectAllBtn);

    JButton deselectAllBtn = new JButton(tm.getText("deselect_all"));
    deselectAllBtn.addActionListener(e -> setAllRouteCheckboxes(false));
    buttonPanel.add(deselectAllBtn);

    buttonPanel.add(new JLabel("  "));

    JButton autoLabelBtn = new JButton(tm.getText("auto_label_nets"));
    autoLabelBtn.addActionListener(this::onAutoLabelNets);
    buttonPanel.add(autoLabelBtn);

    JButton applyNetBtn = new JButton(tm.getText("apply_manual"));
    applyNetBtn.addActionListener(this::onApplyNetChanges);
    buttonPanel.add(applyNetBtn);

    JButton refreshBtn = new JButton(tm.getText("refresh"));
    refreshBtn.addActionListener(e -> refreshNetTable());
    buttonPanel.add(refreshBtn);

    panel.add(buttonPanel, BorderLayout.SOUTH);

    refreshNetTable();
    return panel;
  }

  private void setAllRouteCheckboxes(boolean selected) {
    for (int row = 0; row < netTableModel.getRowCount(); row++) {
      netTableModel.setValueAt(selected, row, 4);
    }
  }

  private void refreshNetTable() {
    if (netTableModel == null) return;
    netTableModel.setRowCount(0);
    BasicBoard board = board_handling.get_routing_board();
    if (board == null || board.rules == null || board.rules.nets == null) return;

    PowerGndAutoLabeler labeler = new PowerGndAutoLabeler(board);
    Map<Integer, NetType> autoTypes = labeler.autoLabelAllNets();

    int netCount = board.rules.nets.max_net_no();
    for (int netNo = 1; netNo <= netCount; netNo++) {
      Net net = board.rules.nets.get(netNo);
      if (net == null) continue;
      NetType auto = autoTypes.getOrDefault(netNo, NetType.SIGNAL);
      Vector<Object> row = new Vector<>();
      row.add(netNo);
      row.add(net.name);
      row.add(auto);
      row.add(auto); // default manual = auto
      row.add(true); // default route = selected
      netTableModel.addRow(row);
    }
  }

  private void onAutoLabelNets(ActionEvent e) {
    BasicBoard board = board_handling.get_routing_board();
    if (board == null || board.rules == null || board.rules.nets == null) return;

    PowerGndAutoLabeler labeler = new PowerGndAutoLabeler(board);
    labeler.autoLabelAllNets();
    refreshNetTable();
    JOptionPane.showMessageDialog(this,
        tm.getText("auto_label_complete"),
        tm.getText("info"),
        JOptionPane.INFORMATION_MESSAGE);
  }

  private void onApplyNetChanges(ActionEvent e) {
    BasicBoard board = board_handling.get_routing_board();
    if (board == null || board.rules == null || board.rules.nets == null) return;

    int changedCount = 0;
    Set<Integer> selectedNets = new HashSet<>();

    for (int row = 0; row < netTableModel.getRowCount(); row++) {
      int netNo = (Integer) netTableModel.getValueAt(row, 0);
      Net net = board.rules.nets.get(netNo);
      if (net == null) continue;

      // Read route checkbox
      Boolean route = (Boolean) netTableModel.getValueAt(row, 4);
      if (Boolean.TRUE.equals(route)) {
        selectedNets.add(netNo);
      }

      // Count changes for feedback
      Object manualVal = netTableModel.getValueAt(row, 3);
      if (manualVal instanceof NetType) {
        changedCount++;
      }
    }

    // Save selected nets to RouterSettings
    RouterSettings rs = board_handling.getCurrentRoutingJob().routerSettings;
    if (rs != null) {
      rs.netsToRoute = selectedNets.isEmpty() ? null : selectedNets;
    }

    JOptionPane.showMessageDialog(this,
        tm.getText("net_changes_applied",
            String.valueOf(changedCount),
            String.valueOf(selectedNets.size())),
        tm.getText("info"),
        JOptionPane.INFORMATION_MESSAGE);
  }

  // ════════════════════════════════════════════════════════════
  //  Global Settings Tab (via type + timeout)
  // ════════════════════════════════════════════════════════════

  private JPanel createGlobalPanel() {
    JPanel panel = new JPanel(new GridBagLayout());
    panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
    GridBagConstraints c = new GridBagConstraints();
    c.anchor = GridBagConstraints.WEST;
    c.insets = new Insets(8, 8, 8, 8);

    RouterSettings rs = board_handling.getCurrentRoutingJob().routerSettings;

    // ── Algorithm indicator (locked to hybrid) ──
    c.gridx = 0; c.gridy = 0; c.gridwidth = 3;
    JLabel algoLabel = new JLabel("布线算法：混合三阶段算法（推荐）");
    algoLabel.setFont(algoLabel.getFont().deriveFont(Font.BOLD));
    panel.add(algoLabel, c);

    // ── Via type ──
    c.gridx = 0; c.gridy = 1; c.gridwidth = 1;
    JLabel viaLabel = new JLabel(tm.getText("via_type"));
    viaLabel.setFont(viaLabel.getFont().deriveFont(Font.BOLD));
    panel.add(viaLabel, c);

    c.gridx = 1; c.gridy = 1; c.gridwidth = 2;
    viaTypeCombo = new JComboBox<>(new String[]{
        tm.getText("via_th"),
        tm.getText("via_bv")
    });
    // Set default based on current settings
    String currentViaType = (rs != null && "bv".equals(rs.viaType)) ? tm.getText("via_bv") : tm.getText("via_th");
    viaTypeCombo.setSelectedItem(currentViaType);
    viaTypeCombo.addActionListener(e -> {
      String selected = (String) viaTypeCombo.getSelectedItem();
      if (rs != null) {
        rs.viaType = tm.getText("via_th").equals(selected) ? "th" : "bv";
      }
    });
    panel.add(viaTypeCombo, c);

    // ── Timeout ──
    c.gridx = 0; c.gridy = 2; c.gridwidth = 1;
    JLabel timeoutLabel = new JLabel(tm.getText("job_timeout"));
    timeoutLabel.setFont(timeoutLabel.getFont().deriveFont(Font.BOLD));
    panel.add(timeoutLabel, c);

    c.gridx = 1; c.gridy = 2; c.gridwidth = 1;
    NumberFormat nf = new DecimalFormat("00");
    timeout_hours_field = new JFormattedTextField(nf);
    timeout_hours_field.setColumns(3);
    timeout_hours_field.setHorizontalAlignment(JFormattedTextField.RIGHT);
    timeout_hours_field.setToolTipText(tm.getText("job_timeout_tooltip"));

    timeout_minutes_field = new JFormattedTextField(nf);
    timeout_minutes_field.setColumns(3);
    timeout_minutes_field.setHorizontalAlignment(JFormattedTextField.RIGHT);
    timeout_minutes_field.setToolTipText(tm.getText("job_timeout_tooltip"));

    timeout_seconds_field = new JFormattedTextField(nf);
    timeout_seconds_field.setColumns(3);
    timeout_seconds_field.setHorizontalAlignment(JFormattedTextField.RIGHT);
    timeout_seconds_field.setToolTipText(tm.getText("job_timeout_tooltip"));

    JPanel timeoutPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
    timeoutPanel.add(timeout_hours_field);
    timeoutPanel.add(new JLabel(":"));
    timeoutPanel.add(timeout_minutes_field);
    timeoutPanel.add(new JLabel(":"));
    timeoutPanel.add(timeout_seconds_field);

    // Load current value
    loadTimeoutFromSettings();
    panel.add(timeoutPanel, c);

    // ── Apply Global Settings button ──
    c.gridx = 0; c.gridy = 3; c.gridwidth = 3;
    c.anchor = GridBagConstraints.CENTER;
    JButton applyGlobalBtn = new JButton("应用全局设置");
    applyGlobalBtn.addActionListener(this::onApplyGlobalSettings);
    panel.add(applyGlobalBtn, c);

    return panel;
  }

  private void loadTimeoutFromSettings() {
    RouterSettings rs = board_handling.getCurrentRoutingJob().routerSettings;
    if (rs == null || rs.jobTimeoutString == null) {
      setTimeoutFields(3600); // default 1 hour
      return;
    }
    Long parsed = TextManager.parseTimespanString(rs.jobTimeoutString);
    long totalSec = (parsed == null) ? 3600 : Math.min(parsed, MAX_TIMEOUT_SECONDS);
    setTimeoutFields(totalSec);
  }

  private void setTimeoutFields(long totalSeconds) {
    long h = totalSeconds / 3600;
    long m = (totalSeconds % 3600) / 60;
    long s = totalSeconds % 60;
    timeout_hours_field.setValue(h);
    timeout_minutes_field.setValue(m);
    timeout_seconds_field.setValue(s);
  }

  private void onApplyGlobalSettings(ActionEvent e) {
    RouterSettings rs = board_handling.getCurrentRoutingJob().routerSettings;
    if (rs == null) return;

    // Save timeout
    try {
      long h = Long.parseLong(timeout_hours_field.getText().trim());
      long m = Long.parseLong(timeout_minutes_field.getText().trim());
      long s = Long.parseLong(timeout_seconds_field.getText().trim());
      long total = Math.min(h * 3600 + m * 60 + s, MAX_TIMEOUT_SECONDS);
      String timeoutStr = String.format(Locale.ROOT, "%02d:%02d:%02d", total / 3600, (total % 3600) / 60, total % 60);
      rs.setJobTimeoutString(timeoutStr);
    } catch (NumberFormatException ex) {
      // ignore
    }

    // Save via type
    String selectedVia = (String) viaTypeCombo.getSelectedItem();
    rs.viaType = tm.getText("via_th").equals(selectedVia) ? "th" : "bv";

    // Ensure algorithm stays hybrid
    rs.setAlgorithm(RouterSettings.ALGORITHM_HYBRID);

    JOptionPane.showMessageDialog(this,
        "全局设置已应用",
        tm.getText("info"),
        JOptionPane.INFORMATION_MESSAGE);
  }

  // ════════════════════════════════════════════════════════════
  //  Cell Renderers
  // ════════════════════════════════════════════════════════════

  private static class FunctionCellRenderer extends DefaultTableCellRenderer {
    @Override
    public Component getTableCellRendererComponent(JTable table, Object value,
                                                   boolean isSelected, boolean hasFocus,
                                                   int row, int column) {
      if (value instanceof LayerFunction func) {
        value = func.getChineseName();
      }
      return super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
    }
  }

  private static class NetTypeCellRenderer extends DefaultTableCellRenderer {
    @Override
    public Component getTableCellRendererComponent(JTable table, Object value,
                                                   boolean isSelected, boolean hasFocus,
                                                   int row, int column) {
      if (value instanceof NetType type) {
        value = type.getChineseName();
        JLabel label = (JLabel) super.getTableCellRendererComponent(
            table, value, isSelected, hasFocus, row, column);
        if (type == NetType.POWER) label.setForeground(new Color(100, 150, 255));
        else if (type == NetType.GROUND) label.setForeground(new Color(100, 150, 255));
        else label.setForeground(new Color(100, 150, 255));
        return label;
      }
      return super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
    }
  }

  @Override
  public void refresh() {
    refreshLayerTable();
    refreshNetTable();
    loadTimeoutFromSettings();
  }
}
