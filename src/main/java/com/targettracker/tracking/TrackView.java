package com.targettracker.tracking;

import com.targettracker.model.EcefPoint;

import java.awt.Color;
import java.util.List;

public record TrackView(
        String id,
        EcefPoint meanPosition,
        double[][] positionCovariance,
        List<EcefPoint> tail,
        Color color,
        boolean dead,
        double uncertaintyRadiusMeters,
        String deadReason) {
    public TrackView {
        if (deadReason == null) {
            deadReason = "";
        }
    }
}
