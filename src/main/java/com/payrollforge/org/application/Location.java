package com.payrollforge.org.application;

import java.time.DayOfWeek;
import java.time.ZoneId;
import java.util.Set;

/** An office: its timezone defines attendance days, its circle the mobile-punch geofence. */
public record Location(String code, String name, ZoneId timezone, double lat, double lng, int radiusMetres,
                       Set<DayOfWeek> weeklyOff) {
}
