package com.payrollforge.employee.adapter;

import com.payrollforge.employee.application.Employee;
import com.payrollforge.employee.application.EmployeeStore;
import com.payrollforge.employee.application.NewVersion;
import com.payrollforge.employee.application.Task;
import com.payrollforge.employee.domain.EmployeeVersion;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
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
class JdbcEmployeeStore implements EmployeeStore {

    private final JdbcClient db;

    JdbcEmployeeStore(JdbcClient db) {
        this.db = db;
    }

    @Override
    public boolean insert(Employee e, Instant at) {
        return db.sql("""
                        insert into employee (id, code, name, email, subject, status, joined_on, exited_on,
                                              bank_account, bank_ifsc, pan, created_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) on conflict do nothing""")
                .params(e.id(), e.code(), e.name(), e.email(), e.subject(), e.status(), e.joinedOn(), e.exitedOn(),
                        e.bankAccountEnc(), e.bankIfsc(), e.panEnc(), Timestamp.from(at))
                .update() == 1;
    }

    @Override
    public Optional<Employee> find(UUID id) {
        return db.sql("select * from employee where id = ?").param(id).query(JdbcEmployeeStore::employee).optional();
    }

    @Override
    public Optional<Employee> lock(UUID id) {
        return db.sql("select * from employee where id = ? for update").param(id).query(JdbcEmployeeStore::employee).optional();
    }

    @Override
    public Optional<UUID> idForSubject(String subject) {
        return db.sql("select id from employee where subject = ?").param(subject).query(UUID.class).optional();
    }

    @Override
    public List<Employee> list(String status, int limit, int offset) {
        return db.sql("select * from employee where (cast(? as text) is null or status = ?) order by code limit ? offset ?")
                .params(status, status, limit, offset).query(JdbcEmployeeStore::employee).list();
    }

    @Override
    public List<Employee> employedBetween(LocalDate from, LocalDate to) {
        return db.sql("select * from employee where joined_on <= ? and (exited_on is null or exited_on >= ?) order by code")
                .params(to, from).query(JdbcEmployeeStore::employee).list();
    }

    @Override
    public boolean insertVersion(UUID employee, NewVersion v, String by, Instant at) {
        return db.sql("""
                        insert into employee_version (employee_id, effective_from, grade, dept, location_code, manager_id,
                                                      ctc_minor, structure_code, reason, created_by, created_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) on conflict do nothing""")
                .params(employee, v.effectiveFrom(), v.grade(), v.dept(), v.location(), v.managerId(),
                        v.ctcAnnual().movePointRight(2).longValueExact(), v.structureCode(), v.reason(), by,
                        Timestamp.from(at))
                .update() == 1;
    }

    @Override
    public List<EmployeeVersion> versions(UUID employee) {
        return db.sql("select * from employee_version where employee_id = ? order by effective_from").param(employee)
                .query(JdbcEmployeeStore::version).list();
    }

    @Override
    public Map<UUID, List<EmployeeVersion>> versions(Collection<UUID> employees) {
        var out = new HashMap<UUID, List<EmployeeVersion>>();
        if (employees.isEmpty()) {
            return out;
        }
        db.sql("select * from employee_version where employee_id = any(?) order by employee_id, effective_from")
                .param(employees.toArray(UUID[]::new)).query(JdbcEmployeeStore::version).list()
                .forEach(v -> out.computeIfAbsent(v.employeeId(), k -> new ArrayList<>()).add(v));
        return out;
    }

    @Override
    public void exit(UUID employee, LocalDate on) {
        db.sql("update employee set status = 'EXITED', exited_on = ? where id = ?").params(on, employee).update();
    }

    @Override
    public void addTasks(UUID employee, String kind, List<String> titles) {
        for (String t : titles == null ? List.<String>of() : titles) {
            db.sql("insert into employee_task (id, employee_id, kind, title) values (?, ?, ?, ?) on conflict do nothing")
                    .params(UUID.randomUUID(), employee, kind, t).update();
        }
    }

    @Override
    public List<Task> tasks(UUID employee) {
        return db.sql("select * from employee_task where employee_id = ? order by kind, title").param(employee)
                .query((rs, i) -> new Task(rs.getObject("id", UUID.class), employee, rs.getString("kind"),
                        rs.getString("title"), rs.getString("done_by"),
                        rs.getTimestamp("done_at") == null ? null : rs.getTimestamp("done_at").toInstant()))
                .list();
    }

    @Override
    public boolean completeTask(UUID employee, UUID task, String by, Instant at) {
        return db.sql("update employee_task set done_by = ?, done_at = ? where id = ? and employee_id = ? and done_at is null")
                .params(by, Timestamp.from(at), task, employee).update() == 1;
    }

    private static Employee employee(ResultSet rs, int i) throws SQLException {
        return new Employee(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                rs.getString("email"), rs.getString("subject"), rs.getString("status"),
                rs.getObject("joined_on", LocalDate.class), rs.getObject("exited_on", LocalDate.class),
                rs.getString("bank_account"), rs.getString("bank_ifsc"), rs.getString("pan"));
    }

    private static EmployeeVersion version(ResultSet rs, int i) throws SQLException {
        return new EmployeeVersion(rs.getObject("employee_id", UUID.class), rs.getObject("effective_from", LocalDate.class),
                null, rs.getString("grade"), rs.getString("dept"), rs.getString("location_code"),
                rs.getObject("manager_id", UUID.class), BigDecimal.valueOf(rs.getLong("ctc_minor"), 2),
                rs.getString("structure_code"));
    }
}
