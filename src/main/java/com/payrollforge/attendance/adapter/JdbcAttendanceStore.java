package com.payrollforge.attendance.adapter;

import com.payrollforge.attendance.application.AttendanceDay;
import com.payrollforge.attendance.application.AttendanceStore;
import com.payrollforge.attendance.application.PunchIn;
import com.payrollforge.attendance.domain.DayStatus;
import com.payrollforge.attendance.domain.Punch;
import com.payrollforge.attendance.domain.PunchSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
class JdbcAttendanceStore implements AttendanceStore {

    private final JdbcClient db;

    JdbcAttendanceStore(JdbcClient db) {
        this.db = db;
    }

    @Override
    public boolean insertPunch(PunchIn p, Instant receivedAt) {
        return db.sql("""
                        insert into attendance_punch (id, employee_id, ts, source, device_event_id, lat, lng, received_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?) on conflict (device_event_id) do nothing""")
                .params(UUID.randomUUID(), p.employeeId(), Timestamp.from(p.at()), p.source().name(), p.deviceEventId(),
                        p.lat(), p.lng(), Timestamp.from(receivedAt))
                .update() == 1;
    }

    @Override
    public Map<UUID, List<Punch>> punches(Collection<UUID> employees, Instant from, Instant to) {
        var out = new HashMap<UUID, List<Punch>>();
        db.sql("select employee_id, ts, source from attendance_punch where employee_id = any(?) and ts >= ? and ts < ? order by ts")
                .params(employees.toArray(UUID[]::new), Timestamp.from(from), Timestamp.from(to))
                .query(rs -> {
                    out.computeIfAbsent(rs.getObject(1, UUID.class), k -> new ArrayList<>())
                            .add(new Punch(rs.getTimestamp(2).toInstant(), PunchSource.valueOf(rs.getString(3))));
                });
        return out;
    }

    @Override
    public Optional<AttendanceDay> day(UUID employee, LocalDate day) {
        return db.sql("select * from attendance_day where employee_id = ? and day = ?").params(employee, day)
                .query(JdbcAttendanceStore::map).optional();
    }

    @Override
    public AttendanceDay close(AttendanceDay d) {
        return db.sql("""
                        insert into attendance_day (employee_id, day, status, worked_minutes, payable, missing_punch, version, closed_at)
                        values (?, ?, ?, ?, ?, ?, 1, ?)
                        on conflict (employee_id, day) do update set status = excluded.status,
                            worked_minutes = excluded.worked_minutes, payable = excluded.payable,
                            missing_punch = excluded.missing_punch, version = attendance_day.version + 1,
                            closed_at = excluded.closed_at
                        returning *""")
                .params(d.employeeId(), d.day(), d.status().name(), d.workedMinutes(), d.payable(), d.missingPunch(),
                        Timestamp.from(d.closedAt()))
                .query(JdbcAttendanceStore::map).single();
    }

    @Override
    public List<AttendanceDay> days(UUID employee, LocalDate from, LocalDate to) {
        return db.sql("select * from attendance_day where employee_id = ? and day between ? and ? order by day")
                .params(employee, from, to).query(JdbcAttendanceStore::map).list();
    }

    private static AttendanceDay map(ResultSet rs, int i) throws SQLException {
        return new AttendanceDay(rs.getObject("employee_id", UUID.class), rs.getObject("day", LocalDate.class),
                DayStatus.valueOf(rs.getString("status")), rs.getLong("worked_minutes"), rs.getBigDecimal("payable"),
                rs.getBoolean("missing_punch"), rs.getInt("version"), rs.getTimestamp("closed_at").toInstant());
    }
}
