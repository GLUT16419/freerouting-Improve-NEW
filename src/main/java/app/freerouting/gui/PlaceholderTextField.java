package app.freerouting.gui;

import java.awt.Color;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import javax.swing.JTextField;

public class PlaceholderTextField extends JTextField {

  private final String placeholder;

  public PlaceholderTextField(String placeholder) {
    this.placeholder = placeholder;
    setForeground(new Color(80, 130, 230));
    setText(placeholder);

    addFocusListener(new FocusAdapter() {
      @Override
      public void focusGained(FocusEvent e) {
        if (getText().equals(placeholder) || getText().isEmpty()) {
          setForeground(new Color(100, 150, 255));
          selectAll();
        }
      }

      @Override
      public void focusLost(FocusEvent e) {
        if (getText().isEmpty() || getText().equals(placeholder)) {
          setForeground(new Color(80, 130, 230));
          setText(placeholder);
        }
      }
    });
  }

  @Override
  public String getText() {
    String text = super.getText();
    return text.equals(placeholder) ? "" : text;
  }

  @Override
  public void setText(String text) {
    super.setText(text.isEmpty() ? placeholder : text);
    setForeground(text.isEmpty() ? new Color(80, 130, 230) : new Color(100, 150, 255));
  }
}