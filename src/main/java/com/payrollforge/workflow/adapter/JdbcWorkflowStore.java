package com.payrollforge.workflow.adapter;

import com.payrollforge.workflow.application.StepRow;
import com.payrollforge.workflow.application.WorkflowStore;
import com.payrollforge.workflow.domain.Delegation;
import com.payrollforge.workflow.domain.Step;
import com.payrollforge.workflow.domain.StepRole;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
class JdbcWorkflowStore implements WorkflowStore {

    private final JdbcClient db;

    JdbcWorkflowStore(JdbcClient db) {
        this.db = db;
    }

    @Override
    public void insertSteps(String type, UUID subject, List<Step> steps) {
        for (int i = 0; i < steps.size(); i++) {
            db.sql("""
                            insert into approval_step (id, subject_type, subject_id, step_no, role, assignee, status, version)
                            values (?, ?, ?, ?, ?, ?, ?, 0)""")
                    .params(UUID.randomUUID(), type, subject, i + 1, steps.get(i).role().name(), steps.get(i).assignee(),
                            i == 0 ? "PENDING" : "WAITING")
                    .update();
        }
    }

    @Override
    public List<StepRow> lockSteps(String type, UUID subject) {
        return db.sql("select * from approval_step where subject_type = ? and subject_id = ? order by step_no for update")
                .params(type, subject).query(JdbcWorkflowStore::map).list();
    }

    @Override
    public List<StepRow> steps(String type, UUID subject) {
        return db.sql("select * from approval_step where subject_type = ? and subject_id = ? order by step_no")
                .params(type, subject).query(JdbcWorkflowStore::map).list();
    }

    @Override
    public void decide(UUID step, String status, String by, UUID onBehalfOf, Instant at) {
        db.sql("update approval_step set status = ?, decided_by = ?, on_behalf_of = ?, decided_at = ?, version = version + 1 where id = ? and status = 'PENDING'")
                .params(status, by, onBehalfOf, Timestamp.from(at), step).update();
    }

    @Override
    public void activate(UUID step) {
        db.sql("update approval_step set status = 'PENDING', version = version + 1 where id = ? and status = 'WAITING'").param(step).update();
    }

    @Override
    public void skipRemaining(String type, UUID subject) {
        db.sql("update approval_step set status = 'SKIPPED', version = version + 1 where subject_type = ? and subject_id = ? and status = 'WAITING'")
                .params(type, subject).update();
    }

    @Override
    public List<Delegation> delegationsFrom(UUID delegator) {
        return db.sql("select delegator, delegate, from_day, to_day from delegation where delegator = ?").param(delegator)
                .query(JdbcWorkflowStore::delegation).list();
    }

    @Override
    public List<Delegation> delegationsTo(UUID delegate, LocalDate day) {
        return db.sql("select delegator, delegate, from_day, to_day from delegation where delegate = ? and ? between from_day and to_day")
                .params(delegate, day).query(JdbcWorkflowStore::delegation).list();
    }

    @Override
    public void insertDelegation(UUID id, Delegation d, Instant at) {
        db.sql("insert into delegation (id, delegator, delegate, from_day, to_day, created_at) values (?, ?, ?, ?, ?, ?)")
                .params(id, d.delegator(), d.delegate(), d.from(), d.to(), Timestamp.from(at)).update();
    }

    @Override
    public List<StepRow> pending(List<UUID> assignees, boolean hr, int limit) {
        return db.sql("""
                        select * from approval_step where status = 'PENDING'
                          and (assignee = any(?) or (? and role = 'HR')) order by subject_type, subject_id limit ?""")
                .params(assignees.toArray(UUID[]::new), hr, limit).query(JdbcWorkflowStore::map).list();
    }

    private static Delegation delegation(ResultSet rs, int i) throws SQLException {
        return new Delegation(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, LocalDate.class),
                rs.getObject(4, LocalDate.class));
    }

    private static StepRow map(ResultSet rs, int i) throws SQLException {
        Timestamp at = rs.getTimestamp("decided_at");
        return new StepRow(rs.getObject("id", UUID.class), rs.getString("subject_type"), rs.getObject("subject_id", UUID.class),
                rs.getInt("step_no"), StepRole.valueOf(rs.getString("role")), rs.getObject("assignee", UUID.class),
                rs.getString("status"), rs.getString("decided_by"), rs.getObject("on_behalf_of", UUID.class),
                at == null ? null : at.toInstant(), rs.getLong("version"));
    }
}
