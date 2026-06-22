package com.targettracker.model;

/** Mutable holder shared by the Swing inputs and measurement scheduler. */
public final class SensorSettings {
    private SensorParameters parameters = SensorParameters.defaults();

    public SensorParameters parameters() {
        return parameters;
    }

    public void setParameters(SensorParameters parameters) {
        this.parameters = parameters;
    }
}
