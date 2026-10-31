package com.payrollforge.org.application;

import com.payrollforge.shared.error.ApiException;
import com.payrollforge.shared.security.Caller;
import com.payrollforge.shared.security.Role;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Locations and holiday calendars. Calendars are read on every day-close and leave application, so they are
 * cached per location and dropped when a holiday is added. The cache is per instance: with several instances a
 * newly added holiday can take until restart to be seen elsewhere (documented limitation).
 */
@Service
public class OrgService {

    private final OrgStore store;
    private final Map<String, Calendar> cache = new ConcurrentHashMap<>();

    public OrgService(OrgStore store) {
        this.store = store;
    }

    @Transactional
    public Location createLocation(Caller caller, Location l) {
        caller.require(Role.HR);
        if (!l.code().matches("[A-Z][A-Z0-9_]{1,15}")) {
            throw ApiException.invalid("location code must be 2-16 of A-Z, 0-9, _");
        }
        if (Math.abs(l.lat()) > 90 || Math.abs(l.lng()) > 180) {
            throw ApiException.invalid("lat/lng out of range");
        }
        if (!store.insertLocation(l)) {
            throw ApiException.conflict("location-exists", "Location " + l.code() + " already exists");
        }
        return l;
    }

    @Transactional
    public void addHoliday(Caller caller, String location, LocalDate day, String name) {
        caller.require(Role.HR);
        store.location(location).orElseThrow(() -> ApiException.notFound("location " + location));
        store.upsertHoliday(location, day, name);
        cache.remove(location);
    }

    public Calendar calendar(String location) {
        return cache.computeIfAbsent(location, code -> new Calendar(
                store.location(code).orElseThrow(() -> ApiException.notFound("location " + code)),
                Map.copyOf(store.holidays(code))));
    }

    public List<Location> locations() {
        return store.locations();
    }

    public static ZoneId zone(String tz) {
        try {
            return ZoneId.of(tz);
        } catch (RuntimeException e) {
            throw ApiException.invalid("unknown timezone " + tz);
        }
    }
}
