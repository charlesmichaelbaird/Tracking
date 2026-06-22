package com.targettracker.tracking;

public enum ImmModel {
    CV("CV"),
    CA("CA");

    private final String label;

    ImmModel(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
