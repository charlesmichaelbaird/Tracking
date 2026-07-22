package com.targettracker.ui;

import javax.swing.JLabel;
import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.border.LineBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/** Headless check for the light/dark Swing theme applier. */
public final class AppThemeSmokeTest {
    private AppThemeSmokeTest() {
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        SwingUtilities.invokeAndWait(AppThemeSmokeTest::runChecks);
        System.out.println("AppThemeSmokeTest passed");
    }

    private static void runChecks() {
        AppTheme.setDarkMode(false);
        JPanel root = new JPanel(new BorderLayout());
        AppTheme.setRole(root, AppTheme.ROLE_APP);
        JPanel card = new JPanel();
        AppTheme.setRole(card, AppTheme.ROLE_CARD);
        JLabel label = new JLabel("Theme label");
        JTextField field = new JTextField("bad");
        field.setBackground(AppTheme.current().invalidInput());
        JTextField normalField = new JTextField("visible");
        JTextField disabledField = new JTextField("disabled");
        disabledField.setEnabled(false);
        JComboBox<String> comboBox = new JComboBox<>(new String[]{"Option"});
        JToggleButton navButton = new JToggleButton("IMM");
        JToggleButton selectedButton = new JToggleButton("Selected", true);
        card.add(label);
        card.add(field);
        card.add(normalField);
        card.add(disabledField);
        card.add(comboBox);
        card.add(navButton);
        card.add(selectedButton);
        root.add(card, BorderLayout.CENTER);

        AppTheme.setDarkMode(true);
        AppTheme.applyTo(root);
        assertColor("root dark background", AppTheme.current().appBackground(), root.getBackground());
        assertColor("card dark background", AppTheme.current().card(), card.getBackground());
        assertColor("label dark foreground", AppTheme.current().text(), label.getForeground());
        assertColor("invalid field dark background",
                AppTheme.current().invalidInput(), field.getBackground());
        assertColor("normal field dark background",
                AppTheme.current().inputBackground(), normalField.getBackground());
        assertColor("normal field dark foreground",
                AppTheme.current().inputText(), normalField.getForeground());
        assertColor("normal field dark border",
                AppTheme.current().inputBorder(), inputBorderColor(normalField));
        assertColor("disabled field dark background",
                AppTheme.current().inputBackground(), disabledField.getBackground());
        assertColor("disabled field dark text",
                AppTheme.current().mutedText(), disabledField.getDisabledTextColor());
        assertColor("combo dark background",
                AppTheme.current().inputBackground(), comboBox.getBackground());
        assertColor("combo dark foreground",
                AppTheme.current().inputText(), comboBox.getForeground());
        assertColor("combo dark border",
                AppTheme.current().inputBorder(), inputBorderColor(comboBox));
        assertPaintedComponentBackground(
                "combo painted background",
                comboBox,
                AppTheme.current().inputBackground());
        assertComboRendererColors(comboBox);
        assertColor("nav button dark background",
                AppTheme.current().buttonBackground(), navButton.getBackground());
        assertColor("nav button dark foreground",
                AppTheme.current().buttonText(), navButton.getForeground());
        assertPaintedBackground(
                "nav button painted background",
                navButton,
                AppTheme.current().buttonBackground());
        assertColor("selected button background",
                AppTheme.current().selectionBackground(), selectedButton.getBackground());
        assertPaintedBackground(
                "selected button painted background",
                selectedButton,
                AppTheme.current().selectionBackground());

        AppTheme.setDarkMode(false);
        AppTheme.applyTo(root);
        assertColor("root light background", AppTheme.current().appBackground(), root.getBackground());
        assertColor("card light background", AppTheme.current().card(), card.getBackground());
        assertColor("invalid field light background",
                AppTheme.current().invalidInput(), field.getBackground());
        assertColor("normal field light foreground",
                AppTheme.current().inputText(), normalField.getForeground());
    }

    private static void assertColor(String label, Color expected, Color actual) {
        if (!same(expected, actual)) {
            throw new AssertionError("%s: expected %s but got %s"
                    .formatted(label, expected, actual));
        }
    }

    private static void assertComboRendererColors(JComboBox<String> comboBox) {
        String value = (String) comboBox.getSelectedItem();
        Component closed = comboBox.getRenderer().getListCellRendererComponent(
                new javax.swing.JList<>(),
                value,
                -1,
                false,
                false);
        assertColor("combo renderer closed background",
                AppTheme.current().inputBackground(), closed.getBackground());
        assertColor("combo renderer closed foreground",
                AppTheme.current().inputText(), closed.getForeground());
        Component selected = comboBox.getRenderer().getListCellRendererComponent(
                new javax.swing.JList<>(),
                value,
                0,
                true,
                false);
        assertColor("combo renderer selected background",
                AppTheme.current().selectionBackground(), selected.getBackground());
        assertColor("combo renderer selected foreground",
                AppTheme.current().text(), selected.getForeground());
    }

    private static boolean same(Color expected, Color actual) {
        return expected.getRed() == actual.getRed()
                && expected.getGreen() == actual.getGreen()
                && expected.getBlue() == actual.getBlue();
    }

    private static Color inputBorderColor(javax.swing.JComponent component) {
        Border border = component.getBorder();
        if (border instanceof CompoundBorder compoundBorder
                && compoundBorder.getOutsideBorder() instanceof LineBorder lineBorder) {
            return lineBorder.getLineColor();
        }
        throw new AssertionError("Input should have a line border inside a compound border");
    }

    private static void assertPaintedBackground(
            String label,
            JToggleButton button,
            Color expected) {
        button.setSize(96, 30);
        BufferedImage image = new BufferedImage(96, 30, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        button.paint(graphics);
        graphics.dispose();
        Color sample = new Color(image.getRGB(12, 12));
        if (colorDistance(sample, expected) > 45.0) {
            throw new AssertionError("%s: expected painted dark background near %s but got %s"
                    .formatted(label, expected, sample));
        }
    }

    private static void assertPaintedComponentBackground(
            String label,
            javax.swing.JComponent component,
            Color expected) {
        component.setSize(160, 30);
        component.doLayout();
        BufferedImage image = new BufferedImage(160, 30, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        component.paint(graphics);
        graphics.dispose();
        Color sample = new Color(image.getRGB(90, 15));
        if (colorDistance(sample, expected) > 45.0) {
            throw new AssertionError("%s: expected painted dark background near %s but got %s"
                    .formatted(label, expected, sample));
        }
    }

    private static double colorDistance(Color first, Color second) {
        int red = first.getRed() - second.getRed();
        int green = first.getGreen() - second.getGreen();
        int blue = first.getBlue() - second.getBlue();
        return Math.sqrt(red * red + green * green + blue * blue);
    }
}
