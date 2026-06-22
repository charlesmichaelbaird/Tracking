package com.targettracker.ui;

import com.targettracker.model.EcefPoint;
import com.targettracker.model.EcefVector;
import com.targettracker.model.GeodeticPoint;
import com.targettracker.model.ScenarioModel;
import com.targettracker.model.SensorParameters;
import com.targettracker.model.SensorSettings;
import com.targettracker.model.TargetMeasurement;
import com.targettracker.model.TargetTrajectory;
import com.targettracker.model.Wgs84;

import java.util.List;
import java.util.Random;

/** Deterministic checks for measurement timing, covariance, Pd, and display history. */
public final class MeasurementEngineSmokeTest {
    private static final double TOLERANCE = 1.0e-6;

    private MeasurementEngineSmokeTest() {
    }

    public static void main(String[] args) {
        ScenarioModel model = new ScenarioModel();
        TargetTrajectory target = model.addTarget();
        target.addPathPoint(Wgs84.toEcef(new GeodeticPoint(0.0, 0.0, 0.0)));
        target.addPathPoint(Wgs84.toEcef(new GeodeticPoint(0.0, 1.0, 0.0)));

        SensorSettings settings = new SensorSettings();
        settings.setParameters(new SensorParameters(15.0, 5.0, 0.0, 0.0, 1.0, 10));
        MeasurementEngine engine = new MeasurementEngine(model, settings, new Random(1234L));
        engine.beginScenario();
        engine.advanceTo(4.999);
        requireCount(0, engine.visibleMeasurements(), "before first offset look");

        engine.advanceTo(5.0);
        List<TargetMeasurement> measurements = engine.visibleMeasurements();
        requireCount(1, measurements, "at first offset look");
        TargetMeasurement first = measurements.get(0);
        requireClose(5.0, first.timeSeconds(), "first look time");
        requirePoint(target.positionAt(5.0), first.measuredPosition(), "zero-noise position");
        requireVector(target.ecefVelocityAt(5.0), first.measuredVelocity(), "zero-noise velocity");

        engine.advanceTo(19.999);
        requireCount(1, engine.visibleMeasurements(), "before second interval look");
        engine.advanceTo(20.0);
        requireCount(2, engine.visibleMeasurements(), "at second interval look");

        settings.setParameters(new SensorParameters(10.0, 2.0, 0.0, 0.0, 1.0, 10));
        engine.parametersChanged(20.0);
        engine.advanceTo(21.999);
        requireCount(2, engine.visibleMeasurements(), "before live-rescheduled look");
        engine.advanceTo(22.0);
        requireCount(3, engine.visibleMeasurements(), "at live-rescheduled look");
        requireCount(3, engine.measurementHistoryAt(22.0, 1.0), "full slider history");
        requireCount(1, engine.measurementHistoryAt(22.0, 0.33), "partial slider history");
        requireCount(0, engine.measurementHistoryAt(22.0, 0.0), "zero slider history");
        requireClose(22.0, engine.visibleMeasurements().get(2).timeSeconds(),
                "live-rescheduled look time");

        settings.setParameters(new SensorParameters(15.0, 5.0, 3.0, 4.0, 1.0, 1));
        requireCount(1, engine.visibleMeasurements(), "one displayed measurement per target");
        engine.beginScenario();
        engine.advanceTo(5.0);
        TargetMeasurement noisy = engine.visibleMeasurements().get(0);
        requireClose(9.0, noisy.positionVarianceMetersSquared(), "position covariance diagonal");
        requireClose(16.0, noisy.velocityVarianceMetersSquaredPerSecondSquared(),
                "velocity covariance diagonal");

        settings.setParameters(new SensorParameters(15.0, 5.0, 1.0, 1.0, 0.0, 10));
        engine.beginScenario();
        engine.advanceTo(100.0);
        requireCount(0, engine.visibleMeasurements(), "Pd zero suppresses every detection");

        settings.setParameters(new SensorParameters(15.0, 5.0, 1.0, 1.0, 1.0, 0));
        engine.beginScenario();
        engine.advanceTo(5.0);
        requireCount(0, engine.visibleMeasurements(), "zero requested history hides detections");

        System.out.println("MeasurementEngineSmokeTest passed");
    }

    private static void requireCount(int expected, List<TargetMeasurement> actual, String label) {
        if (actual.size() != expected) {
            throw new AssertionError("%s: expected %d but got %d".formatted(
                    label, expected, actual.size()));
        }
    }

    private static void requirePoint(EcefPoint expected, EcefPoint actual, String label) {
        requireClose(expected.x(), actual.x(), label + " X");
        requireClose(expected.y(), actual.y(), label + " Y");
        requireClose(expected.z(), actual.z(), label + " Z");
    }

    private static void requireVector(EcefVector expected, EcefVector actual, String label) {
        requireClose(expected.x(), actual.x(), label + " X");
        requireClose(expected.y(), actual.y(), label + " Y");
        requireClose(expected.z(), actual.z(), label + " Z");
    }

    private static void requireClose(double expected, double actual, String label) {
        if (Math.abs(expected - actual) > TOLERANCE) {
            throw new AssertionError("%s: expected %f but got %f".formatted(label, expected, actual));
        }
    }
}
