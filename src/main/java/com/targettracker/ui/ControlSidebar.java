package com.targettracker.ui;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import javax.swing.JToggleButton;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Single-window card console that replaces the former auxiliary dialogs. */
final class ControlSidebar extends JPanel {
    static final String IMM = "IMM";
    static final String SENSOR = "Sensor Parameters";
    static final String TARGETS = "Targets";
    static final String SCENARIO = "Scenario";

    private final CardLayout cardLayout = new CardLayout();
    private final JPanel cards = new JPanel(cardLayout);
    private final Map<String, JToggleButton> navigationButtons = new LinkedHashMap<>();
    private final Consumer<String> onCardChanged;

    ControlSidebar(
            JComponent immPanel,
            JComponent sensorPanel,
            JComponent targetsPanel,
            JComponent scenarioPanel) {
        this(immPanel, sensorPanel, targetsPanel, scenarioPanel, ignored -> {
        });
    }

    ControlSidebar(
            JComponent immPanel,
            JComponent sensorPanel,
            JComponent targetsPanel,
            JComponent scenarioPanel,
            Consumer<String> onCardChanged) {
        super(new BorderLayout());
        this.onCardChanged = onCardChanged;
        setPreferredSize(new Dimension(430, 0));
        setMinimumSize(new Dimension(360, 0));
        AppTheme.setRole(this, AppTheme.ROLE_APP);
        setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, AppTheme.current().border()));

        JPanel navigation = new JPanel(new GridLayout(0, 1, 6, 6));
        AppTheme.setRole(navigation, AppTheme.ROLE_CARD);
        navigation.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        ButtonGroup group = new ButtonGroup();
        addNavigationButton(navigation, group, IMM);
        addNavigationButton(navigation, group, SENSOR);
        addNavigationButton(navigation, group, TARGETS);
        addNavigationButton(navigation, group, SCENARIO);
        add(navigation, BorderLayout.NORTH);

        cards.add(scroll(immPanel), IMM);
        cards.add(scroll(sensorPanel), SENSOR);
        cards.add(scroll(targetsPanel), TARGETS);
        cards.add(scroll(scenarioPanel), SCENARIO);
        add(cards, BorderLayout.CENTER);
        showCard(TARGETS);
    }

    void showCard(String name) {
        cardLayout.show(cards, name);
        JToggleButton button = navigationButtons.get(name);
        if (button != null) {
            button.setSelected(true);
        }
        onCardChanged.accept(name);
    }

    private void addNavigationButton(JPanel navigation, ButtonGroup group, String name) {
        JToggleButton button = new JToggleButton(name);
        button.setToolTipText("Show " + name);
        button.addActionListener(event -> showCard(name));
        navigationButtons.put(name, button);
        group.add(button);
        navigation.add(button);
    }

    private static JScrollPane scroll(JComponent component) {
        JScrollPane scrollPane = new JScrollPane(new WidthTrackingPanel(component),
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.getVerticalScrollBar().setUnitIncrement(18);
        return scrollPane;
    }

    private static final class WidthTrackingPanel extends JPanel implements Scrollable {
        private WidthTrackingPanel(JComponent component) {
            super(new BorderLayout());
            setOpaque(false);
            add(component, BorderLayout.CENTER);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(
                Rectangle visibleRect,
                int orientation,
                int direction) {
            return 18;
        }

        @Override
        public int getScrollableBlockIncrement(
                Rectangle visibleRect,
                int orientation,
                int direction) {
            return orientation == SwingConstants.VERTICAL
                    ? Math.max(18, visibleRect.height - 18)
                    : Math.max(18, visibleRect.width - 18);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }
}
