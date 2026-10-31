package com.payrollforge.org.application;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface OrgStore {

    boolean insertLocation(Location l);

    Optional<Location> location(String code);

    List<Location> locations();

    void upsertHoliday(String location, LocalDate day, String name);

    Map<LocalDate, String> holidays(String location);
}
