package com.targettracker.ui;

import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;

/** Headless check for the single-window card navigation. */
public final class ControlSidebarSmokeTest {
    private ControlSidebarSmokeTest() {
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        SwingUtilities.invokeAndWait(ControlSidebarSmokeTest::runChecks);
        System.out.println("ControlSidebarSmokeTest passed");
    }

    private static void runChecks() {
        ControlSidebar sidebar = new ControlSidebar(
                named("imm"), named("sensor"), named("motion"),
                named("targets"), named("scenario"));
        List<JToggleButton> buttons = new ArrayList<>();
        collectButtons(sidebar, buttons);
        if (buttons.size() != 5) {
            throw new AssertionError("Expected five embedded-section navigation buttons");
        }
        List<String> expected = List.of(
                ControlSidebar.IMM,
                ControlSidebar.SENSOR,
                ControlSidebar.MOTION,
                ControlSidebar.TARGETS,
                ControlSidebar.SCENARIO);
        for (String name : expected) {
            JToggleButton button = buttons.stream()
                    .filter(candidate -> name.equals(candidate.getText()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Missing button: " + name));
            button.doClick();
            if (!button.isSelected()) {
                throw new AssertionError("Card button should remain selected: " + name);
            }
        }
    }

    private static JPanel named(String name) {
        JPanel panel = new JPanel();
        panel.setName(name);
        return panel;
    }

    private static void collectButtons(Container container, List<JToggleButton> buttons) {
        for (Component child : container.getComponents()) {
            if (child instanceof JToggleButton button) {
                buttons.add(button);
            }
            if (child instanceof Container nested) {
                collectButtons(nested, buttons);
            }
        }
    }
}
