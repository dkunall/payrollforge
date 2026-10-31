package com.payrollforge.attendance.domain;

/** Haversine distance on a spherical Earth; good to a few metres at office-radius scale. */
public final class Geofence {

    private static final double EARTH_RADIUS_M = 6_371_008.8;

    private Geofence() {
    }

    public static double distanceMetres(double lat1, double lng1, double lat2, double lng2) {
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dp = Math.toRadians(lat2 - lat1);
        double dl = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    public static boolean inside(double lat, double lng, double centreLat, double centreLng, double radiusMetres) {
        return distanceMetres(lat, lng, centreLat, centreLng) <= radiusMetres;
    }
}
