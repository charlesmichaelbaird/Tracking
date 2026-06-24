package com.targettracker.model;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/** Stores user-generated scenario definitions in simple Java properties files. */
public final class SavedScenarioRepository {
    private static final String EXTENSION = ".scenario";
    private final Path directory;

    public SavedScenarioRepository(Path directory) {
        this.directory = directory;
    }

    public Path directory() {
        return directory;
    }

    public List<SavedScenarioDefinition> list() throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (var stream = Files.list(directory)) {
            List<Path> files = stream
                    .filter(path -> path.getFileName().toString().endsWith(EXTENSION))
                    .sorted()
                    .toList();
            List<SavedScenarioDefinition> scenarios = new ArrayList<>();
            for (Path file : files) {
                scenarios.add(read(file));
            }
            scenarios.sort(Comparator.comparing(SavedScenarioDefinition::name));
            return List.copyOf(scenarios);
        }
    }

    public SavedScenarioDefinition save(String requestedName, ScenarioModel model)
            throws IOException {
        String name = sanitizeDisplayName(requestedName);
        if (name.isBlank()) {
            throw new IllegalArgumentException("Enter a scenario name before saving");
        }
        Files.createDirectories(directory);
        Path path = uniquePath(name);
        Properties properties = new Properties();
        properties.setProperty("name", name);
        if (model.hasScenarioLength()) {
            properties.setProperty("scenario.length.seconds",
                    Double.toString(model.explicitScenarioLengthSeconds()));
        }
        properties.setProperty("target.count", Integer.toString(model.targets().size()));
        for (int targetIndex = 0; targetIndex < model.targets().size(); targetIndex++) {
            TargetTrajectory target = model.targets().get(targetIndex);
            String prefix = "target." + targetIndex + ".";
            properties.setProperty(prefix + "path.count", Integer.toString(target.path().size()));
            for (int pointIndex = 0; pointIndex < target.path().size(); pointIndex++) {
                GeodeticPoint point = Wgs84.toGeodetic(target.path().get(pointIndex));
                properties.setProperty(prefix + "path." + pointIndex, String.format(
                        Locale.ROOT,
                        "%.12f,%.12f",
                        point.latitudeDegrees(),
                        point.longitudeDegrees()));
            }
            properties.setProperty(prefix + "velocity",
                    samples(target.velocityProfile()));
            properties.setProperty(prefix + "altitude",
                    samples(target.altitudeProfile()));
        }
        properties.setProperty("blackout.count",
                Integer.toString(model.blackoutRegions().size()));
        for (int regionIndex = 0; regionIndex < model.blackoutRegions().size(); regionIndex++) {
            BlackoutRegion region = model.blackoutRegions().get(regionIndex);
            String prefix = "blackout." + regionIndex + ".";
            properties.setProperty(prefix + "name", region.name());
            properties.setProperty(prefix + "center", String.format(
                    Locale.ROOT,
                    "%.12f,%.12f",
                    region.center().latitudeDegrees(),
                    region.center().longitudeDegrees()));
            properties.setProperty(prefix + "width", Double.toString(region.widthMeters()));
            properties.setProperty(prefix + "height", Double.toString(region.heightMeters()));
        }
        try (Writer writer = Files.newBufferedWriter(path)) {
            properties.store(writer, "ECEF Target Tracker saved scenario");
        }
        return read(path);
    }

    public void loadInto(SavedScenarioDefinition scenario, ScenarioModel model) {
        List<TargetTrajectory> targets = model.replaceTargets(scenario.targets().size());
        model.setScenarioLengthSeconds(scenario.scenarioLengthSeconds());
        for (int index = 0; index < targets.size(); index++) {
            TargetTrajectory target = targets.get(index);
            SavedScenarioDefinition.TargetData data = scenario.targets().get(index);
            target.clearPath();
            for (GeodeticPoint point : data.path()) {
                target.addPathPoint(Wgs84.toEcef(point.withAltitude(0.0)));
            }
            applySamples(target.velocityProfile(), data.velocitySamples());
            applySamples(target.altitudeProfile(), data.altitudeSamples());
        }
        model.clearBlackoutRegions();
        scenario.blackoutRegions().forEach(model::addBlackoutRegion);
    }

    private SavedScenarioDefinition read(Path path) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path)) {
            properties.load(reader);
        }
        String name = properties.getProperty("name", baseName(path));
        Double scenarioLengthSeconds = parseOptionalDouble(
                properties, "scenario.length.seconds");
        int targetCount = parseInt(properties, "target.count", 0);
        List<SavedScenarioDefinition.TargetData> targets = new ArrayList<>();
        for (int targetIndex = 0; targetIndex < targetCount; targetIndex++) {
            String prefix = "target." + targetIndex + ".";
            int pathCount = parseInt(properties, prefix + "path.count", 0);
            List<GeodeticPoint> points = new ArrayList<>();
            for (int pointIndex = 0; pointIndex < pathCount; pointIndex++) {
                String[] parts = properties.getProperty(prefix + "path." + pointIndex, "")
                        .split(",", -1);
                if (parts.length == 2) {
                    points.add(new GeodeticPoint(
                            Double.parseDouble(parts[0]),
                            Double.parseDouble(parts[1]),
                            0.0));
                }
            }
            targets.add(new SavedScenarioDefinition.TargetData(
                    points,
                    parseSamples(properties.getProperty(prefix + "velocity", "")),
                    parseSamples(properties.getProperty(prefix + "altitude", ""))));
        }
        int blackoutCount = parseInt(properties, "blackout.count", 0);
        List<BlackoutRegion> regions = new ArrayList<>();
        for (int index = 0; index < blackoutCount; index++) {
            String prefix = "blackout." + index + ".";
            String[] center = properties.getProperty(prefix + "center", "").split(",", -1);
            if (center.length == 2) {
                regions.add(new BlackoutRegion(
                        properties.getProperty(prefix + "name", "BLK-%03d".formatted(index + 1)),
                        new GeodeticPoint(
                                Double.parseDouble(center[0]),
                                Double.parseDouble(center[1]),
                                0.0),
                        Double.parseDouble(properties.getProperty(prefix + "width", "1000")),
                        Double.parseDouble(properties.getProperty(prefix + "height", "1000"))));
            }
        }
        return new SavedScenarioDefinition(name, path, scenarioLengthSeconds, targets, regions);
    }

    private Path uniquePath(String name) {
        String stem = safeFileStem(name);
        Path candidate = directory.resolve(stem + EXTENSION);
        int suffix = 2;
        while (Files.exists(candidate)) {
            candidate = directory.resolve(stem + "_" + suffix + EXTENSION);
            suffix++;
        }
        return candidate;
    }

    private static String samples(ScalarProfile profile) {
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < profile.sampleCount(); index++) {
            if (index > 0) {
                text.append(',');
            }
            text.append(profile.sample(index));
        }
        return text.toString();
    }

    private static List<Double> parseSamples(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String[] parts = text.split(",");
        List<Double> samples = new ArrayList<>();
        for (String part : parts) {
            samples.add(Double.parseDouble(part.trim()));
        }
        return List.copyOf(samples);
    }

    private static void applySamples(ScalarProfile profile, List<Double> samples) {
        for (int index = 0; index < profile.sampleCount() && index < samples.size(); index++) {
            profile.setSample(index, samples.get(index));
        }
    }

    private static int parseInt(Properties properties, String key, int fallback) {
        String value = properties.getProperty(key);
        return value == null ? fallback : Integer.parseInt(value);
    }

    private static Double parseOptionalDouble(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return null;
        }
        double parsed = Double.parseDouble(value);
        return Double.isFinite(parsed) && parsed > 0.0 ? parsed : null;
    }

    private static String sanitizeDisplayName(String text) {
        return text == null ? "" : text.trim();
    }

    private static String safeFileStem(String text) {
        String safe = text.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "_")
                .replaceAll("^_+|_+$", "");
        return safe.isBlank() ? "scenario" : safe;
    }

    private static String baseName(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(EXTENSION)
                ? name.substring(0, name.length() - EXTENSION.length())
                : name;
    }
}
