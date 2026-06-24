package com.targettracker.math;

/** Deterministic checks for EJML-backed linear algebra convenience wrappers. */
public final class LinearAlgebraSmokeTest {
    private static final double TOLERANCE = 1.0e-9;

    private LinearAlgebraSmokeTest() {
    }

    public static void main(String[] args) {
        verifyMahalanobisDistanceUsesCovarianceScaling();
        verifyGaussianLikelihoodMatchesCanonicalForm();
        System.out.println("LinearAlgebraSmokeTest passed");
    }

    private static void verifyMahalanobisDistanceUsesCovarianceScaling() {
        double[] innovation = {2.0, 4.0};
        double[][] covariance = {
                {4.0, 0.0},
                {0.0, 16.0}
        };
        double distance = LinearAlgebra.mahalanobisDistance(innovation, covariance);
        requireClose(Math.sqrt(2.0), distance,
                "Mahalanobis distance should equal sqrt(v' C^-1 v)");
    }

    private static void verifyGaussianLikelihoodMatchesCanonicalForm() {
        double[] innovation = {2.0, 4.0};
        double[][] covariance = {
                {4.0, 0.0},
                {0.0, 16.0}
        };
        LinearAlgebra.GaussianLikelihood likelihood =
                LinearAlgebra.gaussianLikelihood(innovation, covariance);
        double expectedLogDeterminant = Math.log(64.0);
        double expectedNegativeLogLikelihood = 0.5 * (
                2.0 * Math.log(2.0 * Math.PI)
                        + expectedLogDeterminant
                        + 2.0);
        requireClose(expectedLogDeterminant, likelihood.logDeterminant(),
                "Gaussian log determinant");
        requireClose(expectedNegativeLogLikelihood, likelihood.negativeLogLikelihood(),
                "Canonical Gaussian negative log likelihood");
    }

    private static void requireClose(double expected, double actual, String label) {
        if (Math.abs(expected - actual) > TOLERANCE) {
            throw new AssertionError("%s: expected %.12f but got %.12f"
                    .formatted(label, expected, actual));
        }
    }
}
