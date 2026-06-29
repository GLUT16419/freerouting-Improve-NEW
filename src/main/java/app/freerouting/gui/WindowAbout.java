package app.freerouting.gui;

import app.freerouting.logger.FRLogger;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.net.URI;
import java.util.Locale;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * 关于对话框 — 显示 Freerouting-Improve 版本信息及归属声明。
 * 基于 freerouting/freerouting 原项目改进。
 */
public class WindowAbout extends BoardSavableSubWindow {

  private static final String ORIGINAL_REPO = "https://github.com/freerouting/freerouting";
  private static final String FORK_REPO = "https://github.com/GLUT16419/freerouting-Improve";

  public WindowAbout(Locale p_locale, String freerouting_version) {
    setLanguage(p_locale);
    this.setTitle(tm.getText("title"));

    final JPanel window_panel = new JPanel(new GridBagLayout());
    this.add(window_panel);

    GridBagConstraints gbc = new GridBagConstraints();
    gbc.insets = new Insets(5, 10, 5, 10);
    gbc.gridwidth = GridBagConstraints.REMAINDER;

    // 标题
    window_panel.add(new JLabel("Freerouting-Improve"), gbc);
    window_panel.add(new JLabel(tm.getText("version") + " " + freerouting_version), gbc);

    // 分隔
    window_panel.add(new JLabel("──────────────────"), gbc);

    // 归属声明
    window_panel.add(new JLabel("基于 freerouting/freerouting 原项目改进"), gbc);
    window_panel.add(new JLabel("原项目地址："), gbc);
    window_panel.add(createHyperlinkLabel(ORIGINAL_REPO), gbc);

    // 分支地址
    window_panel.add(new JLabel("本仓库地址："), gbc);
    window_panel.add(createHyperlinkLabel(FORK_REPO), gbc);

    window_panel.add(new JLabel(""), gbc);
    window_panel.add(new JLabel(tm.getText("warranty")), gbc);

    this.setResizable(false);
    this.setMinimumSize(new Dimension(500, 300));
    this.pack();
  }

  private JLabel createHyperlinkLabel(String url) {
    final String urlText = url.trim();
    JLabel label = new JLabel("<html><a href=''>" + urlText + "</a></html>");
    label.setToolTipText("Open " + urlText + " in your browser");

    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
      label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
      label.addMouseListener(new MouseAdapter() {
        @Override
        public void mouseClicked(MouseEvent e) {
          try {
            Desktop.getDesktop().browse(new URI(urlText));
          } catch (Exception ex) {
            FRLogger.error("Could not open link: " + urlText, ex);
          }
        }
      });
    }
    return label;
  }
}
