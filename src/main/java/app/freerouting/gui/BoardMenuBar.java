package app.freerouting.gui;

import app.freerouting.settings.FeatureFlagsSettings;
import javax.swing.JMenu;
import javax.swing.JMenuBar;

/**
 * Creates the menu bar of a board frame together with its menu items.
 */
public class BoardMenuBar extends JMenuBar {

  private final BoardFrame boardFrame;
  public BoardMenuFile fileMenu;
  public BoardMenuDisplay appereanceMenu;
  public BoardMenuParameter settingsMenu;
  public BoardMenuRules rulesMenu;
  public BoardMenuInfo infoMenu;

  /**
   * Creates a new BoardMenuBar together with its menus
   */
  public BoardMenuBar(BoardFrame boardFrame, FeatureFlagsSettings featureFlags) {
    this.boardFrame = boardFrame;
    fileMenu = new BoardMenuFile(boardFrame);
    add(fileMenu);
    appereanceMenu = BoardMenuDisplay.get_instance(boardFrame);
    add(appereanceMenu);
    settingsMenu = BoardMenuParameter.get_instance(boardFrame);
    add(settingsMenu);
    rulesMenu = BoardMenuRules.get_instance(boardFrame);
    add(rulesMenu);
    infoMenu = BoardMenuInfo.get_instance(boardFrame);
    add(infoMenu);
    if (featureFlags.otherMenu) {
      JMenu other_menu = BoardMenuOther.get_instance(boardFrame);
      add(other_menu);
    }
    JMenu help_menu = new BoardMenuHelp(boardFrame);
    add(help_menu);
  }
}