package com.payrollforge.reimbursement.adapter;

import com.payrollforge.reimbursement.application.Claim;
import com.payrollforge.reimbursement.application.ClaimStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JdbcClaimStore implements ClaimStore {

    private final JdbcClient db;

    JdbcClaimStore(JdbcClient db) {
        this.db = db;
    }

    @Override
    public void insert(Claim c, Instant at) {
        db.sql("insert into reimbursement (id, employee_id, category, amount, spent_on, note, status, created_at) values (?, ?, ?, ?, ?, ?, ?, ?)")
                .params(c.id(), c.employeeId(), c.category(), c.amount(), c.spentOn(), c.note(), c.status(), Timestamp.from(at))
                .update();
    }

    @Override
    public Optional<Claim> lock(UUID id) {
        return db.sql("select * from reimbursement where id = ? for update").param(id).query(JdbcClaimStore::map).optional();
    }

    @Override
    public Optional<Claim> find(UUID id) {
        return db.sql("select * from reimbursement where id = ?").param(id).query(JdbcClaimStore::map).optional();
    }

    @Override
    public void setStatus(UUID id, String status) {
        db.sql("update reimbursement set status = ? where id = ?").params(status, id).update();
    }

    @Override
    public List<Claim> list(UUID employee) {
        return db.sql("select * from reimbursement where employee_id = ? order by created_at desc").param(employee)
                .query(JdbcClaimStore::map).list();
    }

    @Override
    public List<Claim> approvedUnpaid(UUID employee) {
        return db.sql("select * from reimbursement where employee_id = ? and status = 'APPROVED' and run_id is null order by created_at, id")
                .param(employee).query(JdbcClaimStore::map).list();
    }

    @Override
    public void markPaid(Collection<UUID> ids, UUID runId) {
        if (!ids.isEmpty()) {
            db.sql("update reimbursement set status = 'PAID', run_id = ? where id = any(?) and status = 'APPROVED'")
                    .params(runId, ids.toArray(UUID[]::new)).update();
        }
    }

    private static Claim map(ResultSet rs, int i) throws SQLException {
        return new Claim(rs.getObject("id", UUID.class), rs.getObject("employee_id", UUID.class), rs.getString("category"),
                rs.getBigDecimal("amount"), rs.getObject("spent_on", LocalDate.class), rs.getString("note"),
                rs.getString("status"), rs.getObject("run_id", UUID.class));
    }
}
