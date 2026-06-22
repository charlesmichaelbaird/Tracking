package com.targettracker.tracking;

final class LinearAlgebra {
    private LinearAlgebra() {
    }

    static double[][] identity(int size) {
        double[][] result = new double[size][size];
        for (int i = 0; i < size; i++) {
            result[i][i] = 1.0;
        }
        return result;
    }

    static double[][] copy(double[][] matrix) {
        double[][] result = new double[matrix.length][];
        for (int row = 0; row < matrix.length; row++) {
            result[row] = matrix[row].clone();
        }
        return result;
    }

    static double[] add(double[] first, double[] second) {
        double[] result = new double[first.length];
        for (int i = 0; i < first.length; i++) {
            result[i] = first[i] + second[i];
        }
        return result;
    }

    static double[] subtract(double[] first, double[] second) {
        double[] result = new double[first.length];
        for (int i = 0; i < first.length; i++) {
            result[i] = first[i] - second[i];
        }
        return result;
    }

    static double[][] add(double[][] first, double[][] second) {
        double[][] result = new double[first.length][first[0].length];
        for (int row = 0; row < first.length; row++) {
            for (int column = 0; column < first[row].length; column++) {
                result[row][column] = first[row][column] + second[row][column];
            }
        }
        return result;
    }

    static double[][] subtract(double[][] first, double[][] second) {
        double[][] result = new double[first.length][first[0].length];
        for (int row = 0; row < first.length; row++) {
            for (int column = 0; column < first[row].length; column++) {
                result[row][column] = first[row][column] - second[row][column];
            }
        }
        return result;
    }

    static double[][] multiply(double[][] first, double[][] second) {
        double[][] result = new double[first.length][second[0].length];
        for (int row = 0; row < first.length; row++) {
            for (int shared = 0; shared < second.length; shared++) {
                double value = first[row][shared];
                for (int column = 0; column < second[0].length; column++) {
                    result[row][column] += value * second[shared][column];
                }
            }
        }
        return result;
    }

    static double[] multiply(double[][] matrix, double[] vector) {
        double[] result = new double[matrix.length];
        for (int row = 0; row < matrix.length; row++) {
            for (int column = 0; column < vector.length; column++) {
                result[row] += matrix[row][column] * vector[column];
            }
        }
        return result;
    }

    static double[][] transpose(double[][] matrix) {
        double[][] result = new double[matrix[0].length][matrix.length];
        for (int row = 0; row < matrix.length; row++) {
            for (int column = 0; column < matrix[row].length; column++) {
                result[column][row] = matrix[row][column];
            }
        }
        return result;
    }

    static double[][] outer(double[] first, double[] second) {
        double[][] result = new double[first.length][second.length];
        for (int row = 0; row < first.length; row++) {
            for (int column = 0; column < second.length; column++) {
                result[row][column] = first[row] * second[column];
            }
        }
        return result;
    }

    static double quadraticForm(double[] vector, double[][] matrix) {
        double[] product = multiply(matrix, vector);
        double result = 0.0;
        for (int i = 0; i < vector.length; i++) {
            result += vector[i] * product[i];
        }
        return result;
    }

    static SpdInverse inverseSpd(double[][] matrix) {
        double jitter = 0.0;
        for (int attempt = 0; attempt < 8; attempt++) {
            double[][] adjusted = copy(matrix);
            for (int i = 0; i < adjusted.length; i++) {
                adjusted[i][i] += jitter;
            }
            double[][] lower = cholesky(adjusted);
            if (lower != null) {
                int size = matrix.length;
                double[][] inverse = new double[size][size];
                for (int column = 0; column < size; column++) {
                    double[] unit = new double[size];
                    unit[column] = 1.0;
                    double[] solution = solveCholesky(lower, unit);
                    for (int row = 0; row < size; row++) {
                        inverse[row][column] = solution[row];
                    }
                }
                double logDeterminant = 0.0;
                for (int i = 0; i < size; i++) {
                    logDeterminant += 2.0 * Math.log(lower[i][i]);
                }
                return new SpdInverse(inverse, logDeterminant);
            }
            jitter = jitter == 0.0 ? 1.0e-9 : jitter * 100.0;
        }
        throw new IllegalArgumentException("Matrix is not positive definite");
    }

    static double largestEigenvalueSymmetric3(double[][] matrix) {
        double[] vector = {1.0, 1.0, 1.0};
        for (int iteration = 0; iteration < 24; iteration++) {
            double[] product = multiply(matrix, vector);
            double norm = Math.sqrt(quadraticForm(product, identity(3)));
            if (norm < 1.0e-15) {
                return 0.0;
            }
            for (int i = 0; i < 3; i++) {
                vector[i] = product[i] / norm;
            }
        }
        return Math.max(0.0, quadraticForm(vector, matrix));
    }

    private static double[][] cholesky(double[][] matrix) {
        int size = matrix.length;
        double[][] lower = new double[size][size];
        for (int row = 0; row < size; row++) {
            for (int column = 0; column <= row; column++) {
                double sum = matrix[row][column];
                for (int k = 0; k < column; k++) {
                    sum -= lower[row][k] * lower[column][k];
                }
                if (row == column) {
                    if (sum <= 0.0 || !Double.isFinite(sum)) {
                        return null;
                    }
                    lower[row][column] = Math.sqrt(sum);
                } else {
                    lower[row][column] = sum / lower[column][column];
                }
            }
        }
        return lower;
    }

    private static double[] solveCholesky(double[][] lower, double[] rightHandSide) {
        int size = lower.length;
        double[] intermediate = new double[size];
        for (int row = 0; row < size; row++) {
            double sum = rightHandSide[row];
            for (int column = 0; column < row; column++) {
                sum -= lower[row][column] * intermediate[column];
            }
            intermediate[row] = sum / lower[row][row];
        }
        double[] result = new double[size];
        for (int row = size - 1; row >= 0; row--) {
            double sum = intermediate[row];
            for (int column = row + 1; column < size; column++) {
                sum -= lower[column][row] * result[column];
            }
            result[row] = sum / lower[row][row];
        }
        return result;
    }

    record SpdInverse(double[][] inverse, double logDeterminant) {
    }
}
