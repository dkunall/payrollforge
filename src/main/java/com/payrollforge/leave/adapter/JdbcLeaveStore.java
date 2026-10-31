package com.payrollforge.leave.adapter;

import com.payrollforge.leave.application.Encashment;
import com.payrollforge.leave.application.LeaveRequest;
import com.payrollforge.leave.application.LeaveStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
class JdbcLeaveStore implements LeaveStore {

    private final JdbcClient db;

    JdbcLeaveStore(JdbcClient db) {
        this.db = db;
    }

    @Override
    public void ensureBalance(UUID employee, String type) {
        db.sql("insert into leave_balance (employee_id, leave_type, balance, version) values (?, ?, 0, 0) on conflict do nothing")
                .params(employee, type).update();
    }

    @Override
    public boolean debit(UUID employee, String type, BigDecimal days) {
        return db.sql("""
                        update leave_balance set balance = balance - ?, version = version + 1
                        where employee_id = ? and leave_type = ? and balance >= ?""")
                .params(days, employee, type, days).update() == 1;
    }

    @Override
    public void credit(UUID employee, String type, BigDecimal days) {
        db.sql("update leave_balance set balance = balance + ?, version = version + 1 where employee_id = ? and leave_type = ?")
                .params(days, employee, type).update();
    }

    @Override
    public Optional<BigDecimal> lockBalance(UUID employee, String type) {
        return db.sql("select balance from leave_balance where employee_id = ? and leave_type = ? for update")
                .params(employee, type).query(BigDecimal.class).optional();
    }

    @Override
    public Map<String, BigDecimal> balances(UUID employee) {
        var out = new LinkedHashMap<String, BigDecimal>();
        db.sql("select leave_type, balance from leave_balance where employee_id = ? order by leave_type").param(employee)
                .query(rs -> {
                    out.put(rs.getString(1), rs.getBigDecimal(2));
                });
        return out;
    }

    @Override
    public boolean ledger(UUID employee, String type, String kind, String ref, BigDecimal days, Instant at) {
        return db.sql("""
                        insert into leave_ledger (employee_id, leave_type, kind, ref, days, at) values (?, ?, ?, ?, ?, ?)
                        on conflict (employee_id, leave_type, kind, ref) do nothing""")
                .params(employee, type, kind, ref, days, Timestamp.from(at)).update() == 1;
    }

    @Override
    public boolean overlaps(UUID employee, LocalDate from, LocalDate to) {
        return db.sql("""
                        select count(*) from leave_request where employee_id = ? and status in ('PENDING', 'APPROVED')
                          and from_day <= ? and to_day >= ?""")
                .params(employee, to, from).query(Long.class).single() > 0;
    }

    @Override
    public void insert(LeaveRequest r) {
        db.sql("""
                        insert into leave_request (id, employee_id, leave_type, from_day, to_day, half_day, days, reason, status, created_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(r.id(), r.employeeId(), r.type(), r.from(), r.to(), r.halfDay(), r.days(), r.reason(), r.status(),
                        Timestamp.from(r.createdAt()))
                .update();
    }

    @Override
    public Optional<LeaveRequest> lock(UUID id) {
        return db.sql("select * from leave_request where id = ? for update").param(id).query(JdbcLeaveStore::map).optional();
    }

    @Override
    public Optional<LeaveRequest> find(UUID id) {
        return db.sql("select * from leave_request where id = ?").param(id).query(JdbcLeaveStore::map).optional();
    }

    @Override
    public void setStatus(UUID id, String status, Instant at) {
        db.sql("update leave_request set status = ?, decided_at = ? where id = ?").params(status, Timestamp.from(at), id).update();
    }

    @Override
    public List<LeaveRequest> list(UUID employee, int limit) {
        return db.sql("select * from leave_request where employee_id = ? order by from_day desc limit ?")
                .params(employee, limit).query(JdbcLeaveStore::map).list();
    }

    @Override
    public List<LeaveRequest> approvedOn(Collection<UUID> employees, LocalDate day) {
        return db.sql("select * from leave_request where employee_id = any(?) and status = 'APPROVED' and ? between from_day and to_day")
                .params(employees.toArray(UUID[]::new), day).query(JdbcLeaveStore::map).list();
    }

    @Override
    public void insertEncashment(Encashment e, Instant at) {
        db.sql("insert into leave_encashment (id, employee_id, leave_type, days, status, created_at) values (?, ?, ?, ?, ?, ?)")
                .params(e.id(), e.employeeId(), e.type(), e.days(), e.status(), Timestamp.from(at)).update();
    }

    @Override
    public List<Encashment> pendingEncashments(UUID employee) {
        return db.sql("select * from leave_encashment where employee_id = ? and status = 'PENDING' order by created_at, id")
                .param(employee)
                .query((rs, i) -> new Encashment(rs.getObject("id", UUID.class), employee, rs.getString("leave_type"),
                        rs.getBigDecimal("days"), rs.getString("status"), rs.getObject("run_id", UUID.class)))
                .list();
    }

    @Override
    public void markEncashmentsPaid(Collection<UUID> ids, UUID runId) {
        if (!ids.isEmpty()) {
            db.sql("update leave_encashment set status = 'PAID', run_id = ? where id = any(?) and status = 'PENDING'")
                    .params(runId, ids.toArray(UUID[]::new)).update();
        }
    }

    private static LeaveRequest map(ResultSet rs, int i) throws SQLException {
        Timestamp decided = rs.getTimestamp("decided_at");
        return new LeaveRequest(rs.getObject("id", UUID.class), rs.getObject("employee_id", UUID.class),
                rs.getString("leave_type"), rs.getObject("from_day", LocalDate.class), rs.getObject("to_day", LocalDate.class),
                rs.getBoolean("half_day"), rs.getBigDecimal("days"), rs.getString("reason"), rs.getString("status"),
                rs.getTimestamp("created_at").toInstant(), decided == null ? null : decided.toInstant());
    }
}
