package com.payrollforge.attendance.application;

import com.payrollforge.attendance.application.PunchResult.Outcome;
import com.payrollforge.attendance.domain.DayClassifier;
import com.payrollforge.attendance.domain.Geofence;
import com.payrollforge.attendance.domain.PunchSource;
import com.payrollforge.attendance.domain.WorkedTime;
import com.payrollforge.org.application.Calendar;
import com.payrollforge.outbox.application.Outbox;
import com.payrollforge.outbox.application.ProcessedEvents;
import com.payrollforge.shared.config.BusinessZone;
import com.payrollforge.shared.config.PayrollForgeProperties;
import com.payrollforge.shared.error.ApiException;
import com.payrollforge.shared.lock.PeriodLocks;
import com.payrollforge.shared.security.Caller;
import com.payrollforge.shared.security.Role;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Punch ingestion and day closing. A day is decided once it is closed: weekly off / holiday, approved leave,
 * or worked minutes against the shift. Anything that changes a closed day (a late punch, a leave approval)
 * re-closes it with a higher version, and payroll keeps only the highest version it has seen.
 */
@Service
public class AttendanceService {

    private final AttendanceStore store;
    private final Roster roster;
    private final LeaveCalendar leave;
    private final PeriodLocks locks;
    private final Outbox outbox;
    private final ProcessedEvents processed;
    private final PayrollForgeProperties.Attendance cfg;
    private final DayClassifier.Shift shift;
    private final Clock clock;

    public AttendanceService(AttendanceStore store, Roster roster, LeaveCalendar leave, PeriodLocks locks,
                             Outbox outbox, ProcessedEvents processed, PayrollForgeProperties props, Clock clock) {
        this.store = store;
        this.roster = roster;
        this.leave = leave;
        this.locks = locks;
        this.outbox = outbox;
        this.processed = processed;
        this.cfg = props.attendance();
        this.shift = new DayClassifier.Shift(cfg.fullDayMinutes(), cfg.halfDayMinutes());
        this.clock = clock;
    }

    @Transactional
    public List<PunchResult> ingest(Caller caller, List<PunchIn> batch) {
        caller.require(Role.DEVICE, Role.EMPLOYEE);
        if (batch.isEmpty() || batch.size() > cfg.batchMax()) {
            throw ApiException.invalid("a batch holds 1.." + cfg.batchMax() + " punches");
        }
        Instant now = clock.instant();
        var results = new ArrayList<PunchResult>(batch.size());
        var touchedClosed = new LinkedHashMap<String, Map.Entry<UUID, LocalDate>>();
        for (PunchIn p : batch) {
            String reason = reject(caller, p, now);
            if (reason != null) {
                results.add(new PunchResult(p.deviceEventId(), Outcome.REJECTED, reason));
                continue;
            }
            if (!store.insertPunch(p, now)) {
                results.add(new PunchResult(p.deviceEventId(), Outcome.DUPLICATE, null));
                continue;
            }
            results.add(new PunchResult(p.deviceEventId(), Outcome.ACCEPTED, null));
            LocalDate local = LocalDate.ofInstant(p.at(), roster.calendarFor(p.employeeId(),
                    LocalDate.ofInstant(p.at(), BusinessZone.ZONE)).orElseThrow()
                    .location().timezone());
            if (store.day(p.employeeId(), local).isPresent()) {
                touchedClosed.put(p.employeeId() + "|" + local, Map.entry(p.employeeId(), local));
            }
        }
        // a late punch on an already closed day re-decides that day
        touchedClosed.values().forEach(e -> recloseOne(e.getKey(), e.getValue()));
        return results;
    }

    private String reject(Caller caller, PunchIn p, Instant now) {
        if (p.deviceEventId() == null || p.deviceEventId().isBlank() || p.deviceEventId().length() > 100) {
            return "deviceEventId is required (max 100 chars)";
        }
        if (p.employeeId() == null || p.at() == null || p.source() == null) {
            return "employeeId, at and source are required";
        }
        if (p.at().isAfter(now.plus(cfg.futureSkew()))) {
            return "punch is in the future";
        }
        if (p.at().isBefore(now.minus(cfg.maxAge()))) {
            return "punch is older than " + cfg.maxAge().toDays() + " days";
        }
        if (p.source() == PunchSource.DEVICE && !caller.has(Role.DEVICE)) {
            return "only a device account can submit DEVICE punches";
        }
        if (p.source() == PunchSource.MOBILE && !p.employeeId().equals(caller.employeeId())) {
            return "a mobile punch must be the caller's own";
        }
        LocalDate approx = LocalDate.ofInstant(p.at(), BusinessZone.ZONE);
        var cal = roster.calendarFor(p.employeeId(), approx);
        if (cal.isEmpty()) {
            return "employee is not employed on that date";
        }
        var loc = cal.get().location();
        LocalDate local = LocalDate.ofInstant(p.at(), loc.timezone());
        if (locks.isLocked(YearMonth.from(local))) {
            return "payroll for " + YearMonth.from(local) + " is locked";
        }
        if (p.source() == PunchSource.MOBILE) {
            if (p.lat() == null || p.lng() == null) {
                return "a mobile punch needs lat and lng";
            }
            if (!Geofence.inside(p.lat(), p.lng(), loc.lat(), loc.lng(), loc.radiusMetres())) {
                return "outside the " + loc.code() + " geofence";
            }
        }
        return null;
    }

    public record CloseResult(LocalDate day, int closed, int missingPunch) {
    }

    /** Close {@code day} for everyone employed on it. Closing again re-decides each day with a higher version. */
    @Transactional
    public CloseResult closeDay(Caller caller, LocalDate day) {
        caller.require(Role.HR);
        locks.requireOpen(day, day);
        if (!day.isBefore(BusinessZone.today(clock).plusDays(1))) {
            throw ApiException.invalid("only past days or today can be closed");
        }
        Map<UUID, Calendar> employed = roster.employedOn(day);
        var closed = closeAll(employed, day);
        return new CloseResult(day, closed.size(), (int) closed.stream().filter(AttendanceDay::missingPunch).count());
    }

    private List<AttendanceDay> closeAll(Map<UUID, Calendar> employed, LocalDate day) {
        if (employed.isEmpty()) {
            return List.of();
        }
        // widest window over all zones, then each employee's own local day is cut from it
        var punches = store.punches(employed.keySet(), day.minusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC),
                day.plusDays(2).atStartOfDay().toInstant(ZoneOffset.UTC));
        var leaves = leave.approvedOn(employed.keySet(), day);
        var out = new ArrayList<AttendanceDay>(employed.size());
        for (var e : employed.entrySet()) {
            var cal = e.getValue();
            Instant from = day.atStartOfDay(cal.location().timezone()).toInstant();
            Instant to = day.plusDays(1).atStartOfDay(cal.location().timezone()).toInstant();
            var mine = punches.getOrDefault(e.getKey(), List.of()).stream()
                    .filter(p -> !p.at().isBefore(from) && p.at().isBefore(to)).toList();
            var worked = WorkedTime.of(mine);
            var outcome = DayClassifier.classify(cal.isWeeklyOff(day), cal.isHoliday(day), leaves.get(e.getKey()),
                    worked.minutes(), shift);
            var stored = store.close(new AttendanceDay(e.getKey(), day, outcome.status(), worked.minutes(),
                    outcome.payable(), worked.missingPunch(), 0, clock.instant()));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("employeeId", stored.employeeId().toString());
            data.put("day", day.toString());
            data.put("status", stored.status().name());
            data.put("payable", stored.payable().toPlainString());
            data.put("workedMinutes", stored.workedMinutes());
            data.put("version", stored.version());
            outbox.append(Outbox.ATTENDANCE, stored.employeeId().toString(), AttendanceEvents.DAY_CLOSED, data);
            out.add(stored);
        }
        return out;
    }

    private void recloseOne(UUID employee, LocalDate day) {
        if (locks.isLocked(YearMonth.from(day))) {
            return;
        }
        roster.calendarFor(employee, day).ifPresent(cal -> closeAll(Map.of(employee, cal), day));
    }

    /**
     * A leave was approved: re-decide any of its days that were already closed. Days not yet closed pick the
     * leave up when they are. Idempotent per event.
     */
    @Transactional
    public int onLeaveApproved(UUID eventId, UUID employee, LocalDate from, LocalDate to) {
        if (!processed.markProcessed("attendance-leave", eventId)) {
            return 0;
        }
        Set<LocalDate> closedDays = new HashSet<>();
        store.days(employee, from, to).forEach(d -> closedDays.add(d.day()));
        closedDays.stream().sorted().forEach(d -> recloseOne(employee, d));
        return closedDays.size();
    }

    public List<AttendanceDay> days(Caller caller, UUID employee, LocalDate from, LocalDate to) {
        caller.requireSelfOr(employee, Role.HR, Role.PAYROLL_MAKER, Role.PAYROLL_CHECKER);
        if (to.isBefore(from) || from.plusDays(62).isBefore(to)) {
            throw ApiException.invalid("from..to must be a range of at most 62 days");
        }
        return store.days(employee, from, to);
    }
}
