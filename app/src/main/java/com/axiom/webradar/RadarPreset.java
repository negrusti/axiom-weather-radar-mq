package com.axiom.webradar;

final class RadarPreset {
    final String id;
    final String label;
    final double northLat;
    final double westLng;
    final double southLat;
    final double eastLng;
    final String radarLayer;
    final String contourLayer;
    final double minX;
    final double minY;
    final double maxX;
    final double maxY;
    final double aspectRatio;
    final int lookbackMinutes;
    final int periodMinutes;

    RadarPreset(
            String id,
            String label,
            double northLat,
            double westLng,
            double southLat,
            double eastLng,
            String radarLayer,
            String contourLayer
    ) {
        this.id = id;
        this.label = label;
        this.northLat = northLat;
        this.westLng = westLng;
        this.southLat = southLat;
        this.eastLng = eastLng;
        this.radarLayer = radarLayer;
        this.contourLayer = contourLayer;
        this.minX = lonToMercatorX(westLng);
        this.maxX = lonToMercatorX(eastLng);
        this.minY = latToMercatorY(southLat);
        this.maxY = latToMercatorY(northLat);
        this.aspectRatio = (this.maxX - this.minX) / (this.maxY - this.minY);
        this.lookbackMinutes = 180;
        this.periodMinutes = 15;
    }

    String bboxParam() {
        return Double.toString(minX) + ","
                + Double.toString(minY) + ","
                + Double.toString(maxX) + ","
                + Double.toString(maxY);
    }

    double centerLat() {
        return (northLat + southLat) * 0.5d;
    }

    double centerLng() {
        return (westLng + eastLng) * 0.5d;
    }

    static double lonToMercatorX(double lng) {
        return lng * 20037508.34d / 180.0d;
    }

    static double latToMercatorY(double lat) {
        double clamped = Math.max(-85.05112878d, Math.min(85.05112878d, lat));
        double radians = Math.toRadians(clamped);
        return Math.log(Math.tan(Math.PI * 0.25d + radians * 0.5d)) * 6378137.0d;
    }
}
