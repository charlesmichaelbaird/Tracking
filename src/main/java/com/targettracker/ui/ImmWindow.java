package com.targettracker.ui;

import com.targettracker.tracking.ImmModel;
import com.targettracker.tracking.ImmParameters;
import com.targettracker.tracking.ImmSettings;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoublePredicate;

/** Non-modal editor for IMM models, process noise, association, and track breaks. */
final class ImmWindow extends JDialog {
    private final ImmSettings settings;
    private final Runnable onParametersChanged;
    private final JToggleButton cvButton = new JToggleButton("CV", true);
    private final JToggleButton caButton = new JToggleButton("CA", true);
    private final JTextField cvNoiseField;
    private final JTextField caNoiseField;
    private final JTextField associationField;
    private final JTextField timeoutField;
    private final JTextField uncertaintyField;
    private final JPanel transitionPanel = new JPanel(new BorderLayout(0, 5));
    private final JLabel validationLabel = new JLabel("Values apply immediately");
    private final List<JTextField> transitionFields = new ArrayList<>();
    private DocumentListener inputListener;
    private boolean rebuildingMatrix;

    ImmWindow(JFrame owner, ImmSettings settings, Runnable onParametersChanged) {
        super(owner, "IMM tracker specifications", false);
        this.settings = settings;
        this.onParametersChanged = onParametersChanged;
        setDefaultCloseOperation(HIDE_ON_CLOSE);
        setMinimumSize(new Dimension(470, 610));
        setSize(500, 680);

        JPanel content = new JPanel(new BorderLayout(0, 10));
        content.setBorder(BorderFactory.createEmptyBorder(15, 17, 13, 17));
        content.setBackground(new Color(246, 248, 251));
        setContentPane(content);

        JPanel header = new JPanel();
        header.setOpaque(false);
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("IMM tracker specifications");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18.0f));
        JLabel note = new JLabel("<html>ECEF state: position, velocity, acceleration. "
                + "At least one model must remain enabled.</html>");
        note.setForeground(new Color(80, 92, 104));
        header.add(title);
        header.add(Box.createVerticalStrut(4));
        header.add(note);
        content.add(header, BorderLayout.NORTH);

        JPanel form = new JPanel();
        form.setBackground(Color.WHITE);
        form.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(211, 218, 225)),
                BorderFactory.createEmptyBorder(10, 12, 10, 12)));
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        ImmParameters defaults = settings.parameters();
        cvNoiseField = addModelRow(
                form,
                cvButton,
                "DCWNA acceleration PSD q (m²/s³)",
                defaults.cvProcessNoise());
        caNoiseField = addModelRow(
                form,
                caButton,
                "Continuous white-jerk PSD q (m²/s⁵)",
                defaults.caProcessNoise());
        form.add(Box.createVerticalStrut(8));
        form.add(sectionTitle("Association"));
        associationField = addField(
                form,
                "Mahalanobis distance threshold",
                defaults.associationMahalanobisThreshold());
        form.add(sectionTitle("Track break"));
        timeoutField = addField(
                form, "No-detection timeout (seconds)", defaults.timeoutSeconds());
        uncertaintyField = addField(
                form,
                "Maximum 3D covariance radius (meters)",
                defaults.uncertaintyRadiusMeters());
        form.add(sectionTitle("Model transition probability matrix"));
        transitionPanel.setOpaque(false);
        transitionPanel.setAlignmentX(LEFT_ALIGNMENT);
        transitionPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 125));
        form.add(transitionPanel);
        content.add(form, BorderLayout.CENTER);

        DocumentListener listener = new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                commitParameters();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                commitParameters();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                commitParameters();
            }
        };
        inputListener = listener;
        List.of(cvNoiseField, caNoiseField, associationField, timeoutField, uncertaintyField)
                .forEach(field -> field.getDocument().addDocumentListener(listener));
        cvButton.addActionListener(event -> modelSelectionChanged(cvButton));
        caButton.addActionListener(event -> modelSelectionChanged(caButton));
        rebuildTransitionMatrix(defaults.transitionProbabilityMatrix(), listener);

        JPanel footer = new JPanel(new BorderLayout());
        footer.setOpaque(false);
        validationLabel.setForeground(new Color(44, 112, 62));
        footer.add(validationLabel, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        JButton applyButton = new JButton("Apply");
        applyButton.addActionListener(event -> commitParameters());
        JButton closeButton = new JButton("Close");
        closeButton.addActionListener(event -> setVisible(false));
        actions.add(applyButton);
        actions.add(closeButton);
        footer.add(actions, BorderLayout.EAST);
        content.add(footer, BorderLayout.SOUTH);
    }

    boolean commitParameters() {
        if (rebuildingMatrix) {
            return false;
        }
        Double cvNoise = parseDouble(cvNoiseField, value -> value >= 0.0);
        Double caNoise = parseDouble(caNoiseField, value -> value >= 0.0);
        Double association = parseDouble(associationField, value -> value > 0.0);
        Double timeout = parseDouble(timeoutField, value -> value > 0.0);
        Double uncertainty = parseDouble(uncertaintyField, value -> value > 0.0);
        List<ImmModel> models = selectedModels();
        double[][] matrix = parseTransitionMatrix(models.size());
        if (cvNoise == null || caNoise == null || association == null
                || timeout == null || uncertainty == null || matrix == null) {
            validationLabel.setText("Correct the highlighted value(s)");
            validationLabel.setForeground(new Color(177, 43, 43));
            return false;
        }
        settings.setParameters(new ImmParameters(
                models, cvNoise, caNoise, association, timeout, uncertainty, matrix));
        validationLabel.setText("Values applied immediately");
        validationLabel.setForeground(new Color(44, 112, 62));
        onParametersChanged.run();
        return true;
    }

    private void modelSelectionChanged(JToggleButton changedButton) {
        if (!cvButton.isSelected() && !caButton.isSelected()) {
            changedButton.setSelected(true);
            validationLabel.setText("At least one model must remain enabled");
            validationLabel.setForeground(new Color(177, 43, 43));
            return;
        }
        double[][] defaults = selectedModels().size() == 1
                ? new double[][]{{1.0}}
                : new double[][]{{0.95, 0.05}, {0.05, 0.95}};
        rebuildTransitionMatrix(defaults, inputListener);
        commitParameters();
    }

    private void rebuildTransitionMatrix(double[][] values, DocumentListener listener) {
        rebuildingMatrix = true;
        transitionFields.clear();
        transitionPanel.removeAll();
        List<ImmModel> models = selectedModels();
        JPanel labels = new JPanel(new GridLayout(1, models.size() + 1, 5, 0));
        labels.setOpaque(false);
        labels.add(new JLabel("from \\ to"));
        models.forEach(model -> labels.add(new JLabel(model.label(), JLabel.CENTER)));
        transitionPanel.add(labels, BorderLayout.NORTH);
        JPanel grid = new JPanel(new GridLayout(models.size(), models.size() + 1, 5, 5));
        grid.setOpaque(false);
        for (int row = 0; row < models.size(); row++) {
            grid.add(new JLabel(models.get(row).label()));
            for (int column = 0; column < models.size(); column++) {
                JTextField field = editableField(formatInputValue(values[row][column]));
                if (listener != null) {
                    field.getDocument().addDocumentListener(listener);
                }
                transitionFields.add(field);
                grid.add(field);
            }
        }
        transitionPanel.add(grid, BorderLayout.CENTER);
        transitionPanel.revalidate();
        transitionPanel.repaint();
        rebuildingMatrix = false;
    }

    private double[][] parseTransitionMatrix(int size) {
        if (transitionFields.size() != size * size) {
            return null;
        }
        double[][] matrix = new double[size][size];
        boolean valid = true;
        for (int row = 0; row < size; row++) {
            double rowSum = 0.0;
            for (int column = 0; column < size; column++) {
                JTextField field = transitionFields.get(row * size + column);
                Double value = parseDouble(field, probability -> probability >= 0.0 && probability <= 1.0);
                if (value == null) {
                    valid = false;
                } else {
                    matrix[row][column] = value;
                    rowSum += value;
                }
            }
            if (Math.abs(rowSum - 1.0) > 1.0e-6) {
                valid = false;
                for (int column = 0; column < size; column++) {
                    transitionFields.get(row * size + column)
                            .setBackground(new Color(255, 224, 224));
                }
            }
        }
        return valid ? matrix : null;
    }

    private List<ImmModel> selectedModels() {
        List<ImmModel> models = new ArrayList<>();
        if (cvButton.isSelected()) {
            models.add(ImmModel.CV);
        }
        if (caButton.isSelected()) {
            models.add(ImmModel.CA);
        }
        return models;
    }

    private static JTextField addModelRow(
            JPanel panel,
            JToggleButton button,
            String processNoiseLabel,
            double value) {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setOpaque(false);
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        button.setPreferredSize(new Dimension(58, 28));
        row.add(button, BorderLayout.WEST);
        row.add(new JLabel(processNoiseLabel), BorderLayout.CENTER);
        JTextField field = editableField(formatInputValue(value));
        field.setPreferredSize(new Dimension(82, 28));
        row.add(field, BorderLayout.EAST);
        panel.add(row);
        panel.add(Box.createVerticalStrut(7));
        return field;
    }

    private static JTextField addField(JPanel panel, String labelText, double value) {
        JLabel label = new JLabel(labelText);
        label.setAlignmentX(LEFT_ALIGNMENT);
        panel.add(label);
        panel.add(Box.createVerticalStrut(2));
        JTextField field = editableField(formatInputValue(value));
        field.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        field.setAlignmentX(LEFT_ALIGNMENT);
        panel.add(field);
        panel.add(Box.createVerticalStrut(7));
        return field;
    }

    private static JLabel sectionTitle(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 13.0f));
        label.setAlignmentX(LEFT_ALIGNMENT);
        return label;
    }

    private static JTextField editableField(String value) {
        JTextField field = new JTextField(value);
        field.setEditable(true);
        field.setEnabled(true);
        field.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent event) {
                SwingUtilities.invokeLater(field::selectAll);
            }
        });
        return field;
    }

    private static Double parseDouble(JTextField field, DoublePredicate predicate) {
        try {
            double value = Double.parseDouble(field.getText().trim());
            boolean valid = Double.isFinite(value) && predicate.test(value);
            field.setBackground(valid ? Color.WHITE : new Color(255, 224, 224));
            return valid ? value : null;
        } catch (NumberFormatException exception) {
            field.setBackground(new Color(255, 224, 224));
            return null;
        }
    }

    private static String formatInputValue(double value) {
        return value == Math.rint(value) ? "%.0f".formatted(value) : Double.toString(value);
    }
}
