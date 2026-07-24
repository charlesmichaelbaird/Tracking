package com.targettracker.ui;

import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.ListCellRenderer;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.SwingConstants;
import javax.swing.JTable;
import javax.swing.JToggleButton;
import javax.swing.JViewport;
import javax.swing.UIManager;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.border.LineBorder;
import javax.swing.border.MatteBorder;
import javax.swing.border.TitledBorder;
import javax.swing.plaf.basic.BasicArrowButton;
import javax.swing.plaf.basic.BasicButtonUI;
import javax.swing.plaf.basic.BasicComboBoxUI;
import javax.swing.plaf.basic.BasicGraphicsUtils;
import javax.swing.text.JTextComponent;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Insets;
import java.awt.Rectangle;

/** Shared application palette and recursive Swing theming helper. */
public final class AppTheme {
    static final String ROLE_KEY = "targettracker.themeRole";
    static final String ROLE_APP = "app";
    static final String ROLE_CARD = "card";
    static final String ROLE_HEADER = "header";
    static final String ROLE_STATUS = "status";

    private static final Palette LIGHT = new Palette(
            new Color(246, 248, 251),
            new Color(247, 249, 251),
            Color.WHITE,
            Color.WHITE,
            new Color(245, 247, 250),
            new Color(214, 220, 227),
            new Color(43, 51, 59),
            new Color(80, 92, 104),
            Color.WHITE,
            new Color(31, 39, 47),
            new Color(168, 176, 184),
            new Color(255, 224, 224),
            new Color(44, 112, 62),
            new Color(132, 74, 17),
            new Color(177, 43, 43),
            new Color(235, 238, 242),
            new Color(28, 36, 44),
            new Color(198, 221, 246),
            new Color(199, 231, 255),
            new Color(203, 236, 210),
            new Color(255, 218, 92),
            new Color(226, 231, 236),
            new Color(143, 153, 163));

    private static final Palette DARK = new Palette(
            new Color(18, 23, 30),
            new Color(24, 30, 38),
            new Color(31, 38, 47),
            new Color(25, 31, 39),
            new Color(22, 28, 35),
            new Color(76, 88, 101),
            new Color(232, 238, 245),
            new Color(163, 174, 186),
            new Color(18, 23, 30),
            new Color(232, 238, 245),
            new Color(232, 238, 245),
            new Color(90, 40, 47),
            new Color(106, 205, 132),
            new Color(236, 178, 91),
            new Color(244, 112, 112),
            new Color(43, 52, 63),
            new Color(232, 238, 245),
            new Color(55, 84, 119),
            new Color(48, 84, 112),
            new Color(47, 88, 61),
            new Color(122, 93, 28),
            new Color(56, 67, 80),
            new Color(122, 137, 152));

    private static boolean darkMode;

    private AppTheme() {
    }

    static void setDarkMode(boolean enabled) {
        darkMode = enabled;
        configureUiDefaults();
    }

    static boolean isDarkMode() {
        return darkMode;
    }

    static Palette current() {
        return darkMode ? DARK : LIGHT;
    }

    public static void configureUiDefaults() {
        Palette palette = current();
        UIManager.put("Panel.background", palette.appBackground());
        UIManager.put("Label.foreground", palette.text());
        UIManager.put("Button.background", palette.buttonBackground());
        UIManager.put("Button.foreground", palette.buttonText());
        UIManager.put("Button.disabledText", buttonTextColor(false));
        UIManager.put("Button.disabledForeground", buttonTextColor(false));
        UIManager.put("ToggleButton.background", palette.buttonBackground());
        UIManager.put("ToggleButton.foreground", palette.buttonText());
        UIManager.put("ToggleButton.disabledText", buttonTextColor(false));
        UIManager.put("ToggleButton.disabledForeground", buttonTextColor(false));
        UIManager.put("TextField.background", palette.inputBackground());
        UIManager.put("TextField.foreground", palette.inputText());
        UIManager.put("TextField.caretForeground", palette.inputText());
        UIManager.put("TextField.inactiveBackground", palette.inputBackground());
        UIManager.put("TextField.inactiveForeground", palette.inputText());
        UIManager.put("TextField.disabledBackground", palette.inputBackground());
        UIManager.put("TextField.disabledForeground", palette.mutedText());
        UIManager.put("TextField.selectionBackground", palette.selectionBackground());
        UIManager.put("TextField.selectionForeground", palette.text());
        UIManager.put("TextField.border", textComponentBorder(false));
        UIManager.put("TextArea.background", palette.inputBackground());
        UIManager.put("TextArea.foreground", palette.inputText());
        UIManager.put("TextArea.caretForeground", palette.inputText());
        UIManager.put("TextArea.inactiveBackground", palette.inputBackground());
        UIManager.put("TextArea.inactiveForeground", palette.inputText());
        UIManager.put("TextArea.selectionBackground", palette.selectionBackground());
        UIManager.put("TextArea.selectionForeground", palette.text());
        UIManager.put("TextArea.border", textComponentBorder(false));
        UIManager.put("TextPane.background", palette.inputBackground());
        UIManager.put("TextPane.foreground", palette.inputText());
        UIManager.put("TextPane.caretForeground", palette.inputText());
        UIManager.put("TextPane.inactiveBackground", palette.inputBackground());
        UIManager.put("TextPane.inactiveForeground", palette.inputText());
        UIManager.put("TextPane.selectionBackground", palette.selectionBackground());
        UIManager.put("TextPane.selectionForeground", palette.text());
        UIManager.put("TextPane.border", textComponentBorder(false));
        UIManager.put("ComboBox.background", palette.inputBackground());
        UIManager.put("ComboBox.foreground", palette.inputText());
        UIManager.put("ComboBox.selectionBackground", palette.selectionBackground());
        UIManager.put("ComboBox.selectionForeground", palette.text());
        UIManager.put("ComboBox.disabledBackground", palette.inputBackground());
        UIManager.put("ComboBox.disabledForeground", palette.mutedText());
        UIManager.put("List.background", palette.inputBackground());
        UIManager.put("List.foreground", palette.inputText());
        UIManager.put("ScrollPane.background", palette.appBackground());
        UIManager.put("Viewport.background", palette.appBackground());
        UIManager.put("Slider.background", palette.surface());
        UIManager.put("ToolTip.background", palette.card());
        UIManager.put("ToolTip.foreground", palette.text());
    }

    static void setRole(JComponent component, String role) {
        component.putClientProperty(ROLE_KEY, role);
        applyComponent(component);
    }

    static void applyTo(Component component) {
        applyComponent(component);
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                applyTo(child);
            }
        }
    }

    static Border lineBorder() {
        return BorderFactory.createLineBorder(current().border());
    }

    static Border topBorder() {
        return BorderFactory.createMatteBorder(1, 0, 0, 0, current().border());
    }

    static Color statusColor(Status status) {
        Palette palette = current();
        return switch (status) {
            case SUCCESS -> palette.success();
            case WARNING -> palette.warning();
            case DANGER -> palette.danger();
            case MUTED -> palette.mutedText();
        };
    }

    private static void applyComponent(Component component) {
        Palette palette = current();
        if (component instanceof JPanel panel && panel.isOpaque()) {
            panel.setBackground(backgroundFor(panel));
        } else if (component instanceof JScrollPane scrollPane) {
            scrollPane.setBackground(palette.appBackground());
        } else if (component instanceof JViewport viewport) {
            viewport.setBackground(palette.appBackground());
        } else if (component instanceof JTextComponent textComponent) {
            styleTextComponent(textComponent, hasInvalidInputBackground(textComponent));
        } else if (component instanceof JComboBox<?> comboBox) {
            applyComboBox(comboBox);
        } else if (component instanceof JList<?> list) {
            list.setBackground(palette.inputBackground());
            list.setForeground(palette.inputText());
            list.setSelectionBackground(palette.selectionBackground());
            list.setSelectionForeground(palette.text());
        } else if (component instanceof JTable table) {
            table.setBackground(palette.inputBackground());
            table.setForeground(palette.inputText());
            table.setGridColor(palette.border());
            table.setSelectionBackground(palette.selectionBackground());
            table.setSelectionForeground(palette.text());
        } else if (component instanceof JSlider slider) {
            slider.setBackground(palette.surface());
            slider.setForeground(palette.text());
        } else if (component instanceof JScrollBar scrollBar) {
            scrollBar.setBackground(palette.surface());
        }

        if (component instanceof JLabel label) {
            label.setForeground(remapForeground(label.getForeground()));
        } else if (component instanceof AbstractButton button) {
            applyButton(button);
        }
        if (component instanceof JComponent jComponent) {
            updateBorder(jComponent);
        }
        component.repaint();
    }

    private static void applyButton(AbstractButton button) {
        Palette palette = current();
        if (button instanceof JCheckBox || button instanceof JRadioButton) {
            button.setOpaque(false);
            button.setForeground(palette.text());
            return;
        }
        if (button instanceof JButton || button instanceof JToggleButton) {
            button.setUI(new ThemedButtonUi());
        }
        Color background = button.isSelected()
                ? palette.selectionBackground()
                : palette.buttonBackground();
        Color border = button.isSelected()
                ? palette.selectionBackground()
                : palette.border();
        if (!button.isEnabled()) {
            background = palette.surface();
            border = palette.border();
        }
        button.setOpaque(true);
        button.setContentAreaFilled(true);
        button.setFocusPainted(false);
        button.setBorderPainted(true);
        button.setRolloverEnabled(true);
        button.setBackground(background);
        button.setForeground(buttonTextColor(button.isEnabled()));
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(border),
                BorderFactory.createEmptyBorder(4, 9, 4, 9)));
    }

    static Color buttonTextColor(boolean enabled) {
        Palette palette = current();
        if (darkMode) {
            return Color.WHITE;
        }
        return enabled ? palette.buttonText() : palette.mutedText();
    }

    private static Color buttonPaintBackground(AbstractButton button) {
        Palette palette = current();
        Color base = button.getBackground() == null
                ? button.isSelected() ? palette.selectionBackground() : palette.buttonBackground()
                : button.getBackground();
        if (!button.isEnabled()) {
            return palette.surface();
        }
        var model = button.getModel();
        if (model.isPressed() && model.isArmed()) {
            return interactiveButtonColor(base, 0.18);
        }
        if (model.isRollover()) {
            return interactiveButtonColor(base, 0.08);
        }
        return base;
    }

    private static Color interactiveButtonColor(Color base, double amount) {
        return darkMode
                ? blend(base, Color.WHITE, amount)
                : blend(base, Color.BLACK, amount);
    }

    private static Color blend(Color base, Color overlay, double amount) {
        double clamped = Math.max(0.0, Math.min(1.0, amount));
        int red = (int) Math.round(base.getRed() * (1.0 - clamped)
                + overlay.getRed() * clamped);
        int green = (int) Math.round(base.getGreen() * (1.0 - clamped)
                + overlay.getGreen() * clamped);
        int blue = (int) Math.round(base.getBlue() * (1.0 - clamped)
                + overlay.getBlue() * clamped);
        return new Color(red, green, blue);
    }

    private static final class ThemedButtonUi extends BasicButtonUI {
        @Override
        public void update(Graphics graphics, JComponent component) {
            if (component instanceof AbstractButton button && component.isOpaque()) {
                graphics.setColor(buttonPaintBackground(button));
                graphics.fillRect(0, 0, component.getWidth(), component.getHeight());
                paint(graphics, component);
                return;
            }
            super.update(graphics, component);
        }

        @Override
        protected void paintButtonPressed(Graphics graphics, AbstractButton button) {
            // The pressed fill is painted in update() so text/icon layout remains unchanged.
        }

        @Override
        protected void paintText(
                Graphics graphics,
                JComponent component,
                Rectangle textRect,
                String text) {
            if (component instanceof AbstractButton button) {
                FontMetrics metrics = graphics.getFontMetrics();
                graphics.setColor(buttonTextColor(button.isEnabled()));
                BasicGraphicsUtils.drawStringUnderlineCharAt(
                        graphics,
                        text,
                        button.getDisplayedMnemonicIndex(),
                        textRect.x,
                        textRect.y + metrics.getAscent());
                return;
            }
            super.paintText(graphics, component, textRect, text);
        }
    }

    private static void applyComboBox(JComboBox<?> comboBox) {
        Palette palette = current();
        comboBox.setUI(new ThemedComboBoxUi());
        installComboBoxRenderer(comboBox);
        comboBox.setOpaque(true);
        comboBox.setBackground(palette.inputBackground());
        comboBox.setForeground(comboBox.isEnabled() ? palette.inputText() : palette.mutedText());
        comboBox.setBorder(textComponentBorder(false));
        Component editor = comboBox.getEditor().getEditorComponent();
        if (editor instanceof JTextComponent textComponent) {
            styleTextComponent(textComponent, false);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void installComboBoxRenderer(JComboBox<?> comboBox) {
        JComboBox rawComboBox = comboBox;
        ListCellRenderer renderer = rawComboBox.getRenderer();
        if (!(renderer instanceof ThemedComboBoxRenderer)) {
            rawComboBox.setRenderer(new ThemedComboBoxRenderer(renderer));
        }
    }

    private static final class ThemedComboBoxUi extends BasicComboBoxUI {
        @Override
        protected JButton createArrowButton() {
            Palette palette = current();
            BasicArrowButton button = new BasicArrowButton(
                    SwingConstants.SOUTH,
                    palette.inputBackground(),
                    palette.inputBorder(),
                    palette.inputText(),
                    palette.inputBackground());
            button.setBorder(BorderFactory.createMatteBorder(
                    0, 1, 0, 0, palette.inputBorder()));
            button.setOpaque(true);
            button.setBackground(palette.inputBackground());
            return button;
        }
    }

    private static final class ThemedComboBoxRenderer implements ListCellRenderer<Object> {
        private final ListCellRenderer<Object> delegate;

        @SuppressWarnings("unchecked")
        private ThemedComboBoxRenderer(ListCellRenderer<?> delegate) {
            this.delegate = (ListCellRenderer<Object>) (delegate == null
                    ? new DefaultListCellRenderer()
                    : delegate);
        }

        @Override
        public Component getListCellRendererComponent(
                JList<?> list,
                Object value,
                int index,
                boolean isSelected,
                boolean cellHasFocus) {
            Component component = delegate.getListCellRendererComponent(
                    list, value, index, isSelected, cellHasFocus);
            Palette palette = current();
            component.setBackground(isSelected
                    ? palette.selectionBackground()
                    : palette.inputBackground());
            component.setForeground(list.isEnabled()
                    ? isSelected ? palette.text() : palette.inputText()
                    : palette.mutedText());
            if (component instanceof JComponent jComponent) {
                jComponent.setOpaque(true);
            }
            return component;
        }
    }

    static void styleTextComponent(JTextComponent textComponent, boolean invalid) {
        Palette palette = current();
        textComponent.setOpaque(true);
        textComponent.setBackground(invalid ? palette.invalidInput() : palette.inputBackground());
        textComponent.setForeground(palette.inputText());
        textComponent.setCaretColor(palette.inputText());
        textComponent.setDisabledTextColor(palette.mutedText());
        textComponent.setSelectionColor(palette.selectionBackground());
        textComponent.setSelectedTextColor(palette.text());
        textComponent.setBorder(textComponentBorder(invalid));
    }

    private static boolean hasInvalidInputBackground(JTextComponent textComponent) {
        return same(textComponent.getBackground(), LIGHT.invalidInput())
                || same(textComponent.getBackground(), DARK.invalidInput());
    }

    private static Border textComponentBorder(boolean invalid) {
        Palette palette = current();
        Color borderColor = invalid ? palette.danger() : palette.inputBorder();
        return BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(borderColor),
                BorderFactory.createEmptyBorder(3, 5, 3, 5));
    }

    private static Color backgroundFor(JComponent component) {
        Palette palette = current();
        Object role = component.getClientProperty(ROLE_KEY);
        if (ROLE_CARD.equals(role)) {
            return palette.card();
        }
        if (ROLE_HEADER.equals(role)) {
            return palette.header();
        }
        if (ROLE_STATUS.equals(role)) {
            return palette.statusBackground();
        }
        if (ROLE_APP.equals(role)) {
            return palette.appBackground();
        }
        Color existing = component.getBackground();
        if (same(existing, LIGHT.card()) || same(existing, DARK.card())
                || same(existing, Color.WHITE)) {
            return palette.card();
        }
        if (same(existing, LIGHT.header()) || same(existing, DARK.header())) {
            return palette.header();
        }
        if (same(existing, LIGHT.statusBackground()) || same(existing, DARK.statusBackground())) {
            return palette.statusBackground();
        }
        return palette.appBackground();
    }

    private static Color remapInputBackground(Color color) {
        Palette palette = current();
        if (same(color, LIGHT.invalidInput()) || same(color, DARK.invalidInput())) {
            return palette.invalidInput();
        }
        return palette.inputBackground();
    }

    private static Color remapForeground(Color color) {
        Palette palette = current();
        if (same(color, LIGHT.success()) || same(color, DARK.success())
                || same(color, new Color(44, 112, 62))) {
            return palette.success();
        }
        if (same(color, LIGHT.warning()) || same(color, DARK.warning())
                || same(color, new Color(132, 74, 17))) {
            return palette.warning();
        }
        if (same(color, LIGHT.danger()) || same(color, DARK.danger())
                || same(color, new Color(176, 40, 40))
                || same(color, new Color(196, 28, 28))
                || same(color, new Color(177, 43, 43))) {
            return palette.danger();
        }
        if (same(color, LIGHT.mutedText()) || same(color, DARK.mutedText())
                || same(color, new Color(91, 103, 115))
                || same(color, new Color(85, 97, 108))
                || same(color, new Color(102, 113, 124))
                || same(color, new Color(61, 73, 84))
                || same(color, new Color(55, 65, 75))) {
            return palette.mutedText();
        }
        return palette.text();
    }

    private static void updateBorder(JComponent component) {
        Border border = component.getBorder();
        if (border instanceof TitledBorder titledBorder) {
            titledBorder.setTitleColor(current().text());
        } else {
            Border themed = themedBorder(border);
            if (themed != border) {
                component.setBorder(themed);
            }
        }
    }

    private static Border themedBorder(Border border) {
        if (border instanceof CompoundBorder compoundBorder) {
            Border outside = themedBorder(compoundBorder.getOutsideBorder());
            Border inside = themedBorder(compoundBorder.getInsideBorder());
            if (outside != compoundBorder.getOutsideBorder()
                    || inside != compoundBorder.getInsideBorder()) {
                return BorderFactory.createCompoundBorder(outside, inside);
            }
        } else if (border instanceof LineBorder lineBorder) {
            Color line = lineBorder.getLineColor();
            if (isBorderColor(line)) {
                return BorderFactory.createLineBorder(
                        current().border(),
                        lineBorder.getThickness(),
                        lineBorder.getRoundedCorners());
            }
        } else if (border instanceof MatteBorder matteBorder) {
            Color matte = matteBorder.getMatteColor();
            if (isBorderColor(matte)) {
                Insets insets = matteBorder.getBorderInsets();
                return BorderFactory.createMatteBorder(
                        insets.top,
                        insets.left,
                        insets.bottom,
                        insets.right,
                        current().border());
            }
        }
        return border;
    }

    private static boolean isBorderColor(Color color) {
        return same(color, LIGHT.border()) || same(color, DARK.border())
                || same(color, new Color(211, 218, 225))
                || same(color, new Color(210, 216, 222))
                || same(color, new Color(214, 220, 227))
                || same(color, new Color(232, 235, 239));
    }

    private static boolean same(Color left, Color right) {
        return left != null && right != null
                && left.getRed() == right.getRed()
                && left.getGreen() == right.getGreen()
                && left.getBlue() == right.getBlue();
    }

    enum Status {
        SUCCESS,
        WARNING,
        DANGER,
        MUTED
    }

    record Palette(
            Color appBackground,
            Color surface,
            Color card,
            Color header,
            Color statusBackground,
            Color border,
            Color text,
            Color mutedText,
            Color inputBackground,
            Color inputText,
            Color inputBorder,
            Color invalidInput,
            Color success,
            Color warning,
            Color danger,
            Color buttonBackground,
            Color buttonText,
            Color selectionBackground,
            Color arrowSelectedBackground,
            Color modifySelectedBackground,
            Color moveSelectedBackground,
            Color chartGrid,
            Color chartFrame) {
    }
}
