package com.targettracker.math;

import org.ejml.data.Complex_F64;
import org.ejml.data.DMatrixRMaj;
import org.ejml.dense.row.CommonOps_DDRM;
import org.ejml.dense.row.factory.DecompositionFactory_DDRM;
import org.ejml.dense.row.mult.VectorVectorMult_DDRM;
import org.ejml.interfaces.decomposition.CholeskyDecomposition_F64;
import org.ejml.interfaces.decomposition.EigenDecomposition_F64;

/** Thin array-compatible wrapper around EJML for dense tracker math. */
public final class LinearAlgebra {
    private LinearAlgebra() {
    }

    public static double[][] identity(int size) {
        return toArray(CommonOps_DDRM.identity(size));
    }

    public static double[][] copy(double[][] matrix) {
        return toArray(toMatrix(matrix));
    }

    public static double[] add(double[] first, double[] second) {
        DMatrixRMaj result = column(first);
        CommonOps_DDRM.addEquals(result, column(second));
        return toVector(result);
    }

    public static double[] subtract(double[] first, double[] second) {
        DMatrixRMaj result = new DMatrixRMaj(first.length, 1);
        CommonOps_DDRM.subtract(column(first), column(second), result);
        return toVector(result);
    }

    public static double[][] add(double[][] first, double[][] second) {
        DMatrixRMaj result = new DMatrixRMaj(first.length, first[0].length);
        CommonOps_DDRM.add(toMatrix(first), toMatrix(second), result);
        return toArray(result);
    }

    public static double[][] subtract(double[][] first, double[][] second) {
        DMatrixRMaj result = new DMatrixRMaj(first.length, first[0].length);
        CommonOps_DDRM.subtract(toMatrix(first), toMatrix(second), result);
        return toArray(result);
    }

    public static double[][] multiply(double[][] first, double[][] second) {
        DMatrixRMaj firstMatrix = toMatrix(first);
        DMatrixRMaj secondMatrix = toMatrix(second);
        DMatrixRMaj result = new DMatrixRMaj(firstMatrix.numRows, secondMatrix.numCols);
        CommonOps_DDRM.mult(firstMatrix, secondMatrix, result);
        return toArray(result);
    }

    public static double[] multiply(double[][] matrix, double[] vector) {
        DMatrixRMaj result = new DMatrixRMaj(matrix.length, 1);
        CommonOps_DDRM.mult(toMatrix(matrix), column(vector), result);
        return toVector(result);
    }

    public static double[][] transpose(double[][] matrix) {
        DMatrixRMaj source = toMatrix(matrix);
        DMatrixRMaj result = new DMatrixRMaj(source.numCols, source.numRows);
        CommonOps_DDRM.transpose(source, result);
        return toArray(result);
    }

    public static double[][] outer(double[] first, double[] second) {
        DMatrixRMaj result = new DMatrixRMaj(first.length, second.length);
        CommonOps_DDRM.multTransB(column(first), column(second), result);
        return toArray(result);
    }

    public static double quadraticForm(double[] vector, double[][] matrix) {
        double[] product = multiply(matrix, vector);
        return dot(vector, product);
    }

    public static double bilinearForm(double[] left, double[][] matrix, double[] right) {
        return dot(left, multiply(matrix, right));
    }

    public static double dot(double[] first, double[] second) {
        return VectorVectorMult_DDRM.innerProd(column(first), column(second));
    }

    public static double determinant(double[][] matrix) {
        return CommonOps_DDRM.det(toMatrix(matrix));
    }

    public static double[][] symmetrized(double[][] matrix, double minimumDiagonal) {
        DMatrixRMaj source = toMatrix(matrix);
        DMatrixRMaj transposed = new DMatrixRMaj(source.numCols, source.numRows);
        CommonOps_DDRM.transpose(source, transposed);
        DMatrixRMaj result = new DMatrixRMaj(source.numRows, source.numCols);
        CommonOps_DDRM.add(0.5, source, 0.5, transposed, result);
        int diagonal = Math.min(result.numRows, result.numCols);
        for (int index = 0; index < diagonal; index++) {
            result.set(index, index, Math.max(result.get(index, index), minimumDiagonal));
        }
        return toArray(result);
    }

    public static SpdInverse inverseSpd(double[][] matrix) {
        double jitter = 0.0;
        for (int attempt = 0; attempt < 8; attempt++) {
            DMatrixRMaj adjusted = toMatrix(matrix);
            for (int index = 0; index < adjusted.numRows; index++) {
                adjusted.add(index, index, jitter);
            }
            CholeskyDecomposition_F64<DMatrixRMaj> cholesky =
                    DecompositionFactory_DDRM.chol(adjusted.numRows, true);
            if (cholesky.decompose(adjusted.copy())) {
                DMatrixRMaj inverse = new DMatrixRMaj(adjusted.numRows, adjusted.numCols);
                if (CommonOps_DDRM.invert(adjusted, inverse)) {
                    DMatrixRMaj lower = cholesky.getT(null);
                    double logDeterminant = 0.0;
                    for (int index = 0; index < lower.numRows; index++) {
                        logDeterminant += 2.0 * Math.log(lower.get(index, index));
                    }
                    return new SpdInverse(
                            symmetrized(toArray(inverse), 0.0),
                            logDeterminant);
                }
            }
            jitter = jitter == 0.0 ? 1.0e-9 : jitter * 100.0;
        }
        throw new IllegalArgumentException("Matrix is not positive definite");
    }

    public static double largestEigenvalueSymmetric(double[][] matrix) {
        EigenDecomposition_F64<DMatrixRMaj> decomposition =
                DecompositionFactory_DDRM.eig(matrix.length, false);
        if (!decomposition.decompose(toMatrix(matrix))) {
            throw new IllegalArgumentException("Eigenvalue decomposition failed");
        }
        double largest = 0.0;
        for (int index = 0; index < decomposition.getNumberOfEigenvalues(); index++) {
            Complex_F64 value = decomposition.getEigenvalue(index);
            if (Math.abs(value.getImaginary()) <= 1.0e-8) {
                largest = Math.max(largest, value.getReal());
            }
        }
        return Math.max(0.0, largest);
    }

    public static double largestEigenvalueSymmetric3(double[][] matrix) {
        return largestEigenvalueSymmetric(matrix);
    }

    private static DMatrixRMaj toMatrix(double[][] matrix) {
        if (matrix == null || matrix.length == 0 || matrix[0].length == 0) {
            throw new IllegalArgumentException("Matrix must be non-empty");
        }
        DMatrixRMaj result = new DMatrixRMaj(matrix.length, matrix[0].length);
        for (int row = 0; row < matrix.length; row++) {
            if (matrix[row].length != matrix[0].length) {
                throw new IllegalArgumentException("Matrix rows must have equal length");
            }
            for (int column = 0; column < matrix[row].length; column++) {
                result.set(row, column, matrix[row][column]);
            }
        }
        return result;
    }

    private static DMatrixRMaj column(double[] vector) {
        DMatrixRMaj result = new DMatrixRMaj(vector.length, 1);
        for (int row = 0; row < vector.length; row++) {
            result.set(row, 0, vector[row]);
        }
        return result;
    }

    private static double[][] toArray(DMatrixRMaj matrix) {
        double[][] result = new double[matrix.numRows][matrix.numCols];
        for (int row = 0; row < matrix.numRows; row++) {
            for (int column = 0; column < matrix.numCols; column++) {
                result[row][column] = matrix.get(row, column);
            }
        }
        return result;
    }

    private static double[] toVector(DMatrixRMaj matrix) {
        double[] result = new double[matrix.getNumElements()];
        for (int index = 0; index < result.length; index++) {
            result[index] = matrix.get(index, 0);
        }
        return result;
    }

    public record SpdInverse(double[][] inverse, double logDeterminant) {
    }
}
