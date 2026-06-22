package com.targettracker.tracking;

public final class ImmSettings {
    private ImmParameters parameters = ImmParameters.defaults();

    public ImmParameters parameters() {
        return parameters;
    }

    public void setParameters(ImmParameters parameters) {
        this.parameters = parameters;
    }
}
