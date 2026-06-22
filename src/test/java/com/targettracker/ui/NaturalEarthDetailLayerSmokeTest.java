package com.targettracker.ui;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;

/** Verifies that all bundled offline geography parses and renders without networking. */
public final class NaturalEarthDetailLayerSmokeTest {
    private NaturalEarthDetailLayerSmokeTest() {
    }

    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "true");
        NaturalEarthDetailLayer layer = new NaturalEarthDetailLayer(() -> {
        }, false);
        layer.loadSynchronously();
        if (!layer.isLoaded() || layer.lineCount() < 1_000) {
            throw new AssertionError("Bundled Natural Earth linework should parse locally");
        }

        BufferedImage image = new BufferedImage(800, 500, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        layer.draw(
                graphics,
                new Rectangle(0, 0, 800, 500),
                -74.0,
                40.7,
                2.0,
                1.25,
                180.0);
        graphics.dispose();
        boolean renderedDetail = false;
        for (int y = 0; y < image.getHeight() && !renderedDetail; y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24) != 0) {
                    renderedDetail = true;
                    break;
                }
            }
        }
        if (!renderedDetail) {
            throw new AssertionError("Parsed Natural Earth detail should render into a local view");
        }
        System.out.println("NaturalEarthDetailLayerSmokeTest passed with "
                + layer.lineCount() + " line strings");
    }
}
