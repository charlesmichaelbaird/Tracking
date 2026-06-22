package com.targettracker.tracking;

import java.util.List;

public record ImmParameters(
        List<ImmModel> enabledModels,
        double cvProcessNoise,
        double caProcessNoise,
        double associationMahalanobisThreshold,
        double timeoutSeconds,
        double uncertaintyRadiusMeters,
        double[][] transitionProbabilityMatrix) {

    public ImmParameters {
        enabledModels = List.copyOf(enabledModels);
        if (enabledModels.isEmpty()) {
            throw new IllegalArgumentException("At least one IMM model must be enabled");
        }
        if (!Double.isFinite(cvProcessNoise) || cvProcessNoise < 0.0
                || !Double.isFinite(caProcessNoise) || caProcessNoise < 0.0) {
            throw new IllegalArgumentException("Process noise values cannot be negative");
        }
        if (!Double.isFinite(associationMahalanobisThreshold)
                || associationMahalanobisThreshold <= 0.0) {
            throw new IllegalArgumentException("Association threshold must be positive");
        }
        if (!Double.isFinite(timeoutSeconds) || timeoutSeconds <= 0.0
                || !Double.isFinite(uncertaintyRadiusMeters) || uncertaintyRadiusMeters <= 0.0) {
            throw new IllegalArgumentException("Track-break thresholds must be positive");
        }
        int size = enabledModels.size();
        if (transitionProbabilityMatrix.length != size) {
            throw new IllegalArgumentException("Transition matrix must be square");
        }
        double[][] matrixCopy = new double[size][size];
        for (int row = 0; row < size; row++) {
            if (transitionProbabilityMatrix[row].length != size) {
                throw new IllegalArgumentException("Transition matrix must be square");
            }
            double sum = 0.0;
            for (int column = 0; column < size; column++) {
                double value = transitionProbabilityMatrix[row][column];
                if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
                    throw new IllegalArgumentException("Transition probabilities must be in [0, 1]");
                }
                matrixCopy[row][column] = value;
                sum += value;
            }
            if (Math.abs(sum - 1.0) > 1.0e-6) {
                throw new IllegalArgumentException("Each transition-matrix row must sum to one");
            }
        }
        transitionProbabilityMatrix = matrixCopy;
    }

    public double processNoiseFor(ImmModel model) {
        return model == ImmModel.CV ? cvProcessNoise : caProcessNoise;
    }

    public static ImmParameters defaults() {
        return new ImmParameters(
                List.of(ImmModel.CV, ImmModel.CA),
                2.0,
                0.25,
                5.0,
                60.0,
                10_000.0,
                new double[][]{{0.95, 0.05}, {0.05, 0.95}});
    }
}
