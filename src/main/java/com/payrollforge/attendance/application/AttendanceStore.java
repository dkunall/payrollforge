package com.payrollforge.attendance.application;

import com.payrollforge.attendance.domain.Punch;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface AttendanceStore {

    /** @return false when {@code deviceEventId} was already stored */
    boolean insertPunch(PunchIn p, Instant receivedAt);

    Map<UUID, List<Punch>> punches(Collection<UUID> employees, Instant from, Instant to);

    Optional<AttendanceDay> day(UUID employee, LocalDate day);

    /** Writes the day with version = previous + 1 (1 when new) and returns the stored row. */
    AttendanceDay close(AttendanceDay day);

    List<AttendanceDay> days(UUID employee, LocalDate from, LocalDate to);
}
