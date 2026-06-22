package com.targettracker.ui;

import com.targettracker.model.EcefPoint;

import java.awt.Color;
import java.util.List;

/** Render-ready ground truth restored from a recorded scenario. */
record GroundTruthView(
        String id,
        EcefPoint currentPosition,
        List<EcefPoint> plannedPath,
        List<EcefPoint> history,
        Color color) {
}
