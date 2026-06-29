package app.freerouting.gui;

import app.freerouting.management.analytics.FRAnalytics;
import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;

/**
 * Welcome panel displayed when no board is loaded.
 * Provides quick-start buttons for common tasks.
 */
public class WelcomePanel extends JPanel {

  private final BoardFrame boardFrame;

  // Dark theme colors
  private static final Color DARK_BG = new Color(25, 25, 30);
  private static final Color DARK_CARD_BG = new Color(35, 35, 42);
  private static final Color ACCENT_BLUE = new Color(60, 130, 230);
  private static final Color TEXT_PRIMARY = new Color(100, 150, 255);
  private static final Color TEXT_SECONDARY = new Color(80, 130, 230);
  private static final Color BORDER_COLOR = new Color(55, 55, 65);

  public WelcomePanel(BoardFrame p_board_frame) {
    this.boardFrame = p_board_frame;
    setLayout(new GridBagLayout());
    setBackground(DARK_BG);

    GridBagConstraints gbc = new GridBagConstraints();
    gbc.gridwidth = GridBagConstraints.REMAINDER;
    gbc.anchor = GridBagConstraints.CENTER;
    gbc.insets = new Insets(8, 20, 8, 20);

    // ── Title ──
    JLabel titleLabel = new JLabel("PCB 布线工具");
    titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 28f));
    titleLabel.setForeground(TEXT_PRIMARY);
    gbc.insets = new Insets(80, 20, 10, 20);
    add(titleLabel, gbc);

    // ── Subtitle ──
    JLabel subtitleLabel = new JLabel("快速开始");
    subtitleLabel.setFont(subtitleLabel.getFont().deriveFont(Font.PLAIN, 16f));
    subtitleLabel.setForeground(TEXT_SECONDARY);
    gbc.insets = new Insets(0, 20, 30, 20);
    add(subtitleLabel, gbc);

    // ── Quick action buttons ──
    JPanel buttonPanel = new JPanel();
    buttonPanel.setLayout(new BoxLayout(buttonPanel, BoxLayout.Y_AXIS));
    buttonPanel.setOpaque(false);

    buttonPanel.add(createQuickButton("📂  打开设计文件", "点击打开 .dsn 或 .ses 格式的 PCB 设计文件",
        e -> boardFrame.menubar.fileMenu.triggerOpenFileDialog(boardFrame)));

    buttonPanel.add(Box.createVerticalStrut(12));

    buttonPanel.add(createQuickButton("⚙  查看层分配", "检查并手动调整各层的功能分配（信号层/电源层/地层等）",
        e -> {
          if (boardFrame.layer_assignment_window == null) {
            boardFrame.layer_assignment_window = new WindowLayerAssignment(boardFrame);
          }
          boardFrame.layer_assignment_window.setVisible(true);
        }));

    buttonPanel.add(Box.createVerticalStrut(12));

    JButton startBtn = createQuickButton("▶  开始自动布线", "载入设计文件后，开始自动布线流程",
        e -> {
          if (boardFrame.board_panel != null
              && boardFrame.board_panel.board_handling != null
              && boardFrame.board_panel.board_handling.get_routing_board() != null) {
            boardFrame.getToolbarPanel().getAutorouteButton().doClick();
          } else {
            JOptionPane.showMessageDialog(boardFrame,
                "请先打开一个设计文件",
                "提示",
                JOptionPane.INFORMATION_MESSAGE);
          }
        });

    buttonPanel.add(startBtn);

    gbc.insets = new Insets(10, 20, 40, 20);
    add(buttonPanel, gbc);

    // ── Usage tips ──
    JPanel tipPanel = new JPanel();
    tipPanel.setLayout(new BoxLayout(tipPanel, BoxLayout.Y_AXIS));
    tipPanel.setOpaque(false);
    tipPanel.setBorder(BorderFactory.createTitledBorder(
        BorderFactory.createLineBorder(BORDER_COLOR, 1, true),
        "使用提示"));

    // Make titled border title text light
    if (tipPanel.getBorder() instanceof javax.swing.border.TitledBorder titledBorder) {
      titledBorder.setTitleColor(TEXT_PRIMARY);
    }

    JLabel tip1 = new JLabel("• 使用「文件」→「打开」加载 .dsn 或 .ses 文件");
    JLabel tip2 = new JLabel("• 使用「参数」→「层分配与网络标记」调整层功能");
    JLabel tip3 = new JLabel("• 点击「自动布线」按钮开始全自动布线");
    JLabel tip4 = new JLabel("• 按 S 键切换到选择模式，按 R 键切换到布线模式");

    tip1.setFont(tip1.getFont().deriveFont(13f));
    tip2.setFont(tip2.getFont().deriveFont(13f));
    tip3.setFont(tip3.getFont().deriveFont(13f));
    tip4.setFont(tip4.getFont().deriveFont(13f));
    tip1.setForeground(TEXT_SECONDARY);
    tip2.setForeground(TEXT_SECONDARY);
    tip3.setForeground(TEXT_SECONDARY);
    tip4.setForeground(TEXT_SECONDARY);

    tipPanel.add(tip1);
    tipPanel.add(tip2);
    tipPanel.add(tip3);
    tipPanel.add(tip4);

    gbc.insets = new Insets(10, 20, 80, 20);
    add(tipPanel, gbc);
  }

  private JButton createQuickButton(String text, String tooltip, java.awt.event.ActionListener action) {
    JButton btn = new JButton(text);
    btn.setToolTipText(tooltip);
    btn.setFont(btn.getFont().deriveFont(Font.PLAIN, 14f));
    btn.setPreferredSize(new Dimension(280, 45));
    btn.setMaximumSize(new Dimension(280, 45));
    btn.setAlignmentX(Component.CENTER_ALIGNMENT);
    btn.setFocusPainted(false);
    btn.setBackground(ACCENT_BLUE);
    btn.setForeground(new Color(100, 150, 255));
    btn.setBorder(BorderFactory.createEmptyBorder(8, 16, 8, 16));
    btn.addActionListener(action);
    btn.addActionListener(_ -> FRAnalytics.buttonClicked("welcome_" + text, text));
    return btn;
  }

  /**
   * Returns true if the board is loaded and ready.
   */
  public boolean isBoardLoaded() {
    return boardFrame.board_panel != null
        && boardFrame.board_panel.board_handling != null
        && boardFrame.board_panel.board_handling.get_routing_board() != null;
  }
}
