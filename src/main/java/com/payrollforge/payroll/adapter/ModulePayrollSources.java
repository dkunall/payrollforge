package com.payrollforge.payroll.adapter;

import com.payrollforge.employee.application.EmployeeService;
import com.payrollforge.employee.domain.Timeline;
import com.payrollforge.leave.application.LeaveService;
import com.payrollforge.payroll.application.PayrollSources;
import com.payrollforge.reimbursement.application.ReimbursementService;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
class ModulePayrollSources implements PayrollSources {

    private final EmployeeService employees;
    private final LeaveService leave;
    private final ReimbursementService claims;

    ModulePayrollSources(EmployeeService employees, LeaveService leave, ReimbursementService claims) {
        this.employees = employees;
        this.leave = leave;
        this.claims = claims;
    }

    @Override
    public List<Person> employedBetween(LocalDate from, LocalDate to) {
        return employees.employedBetween(from, to).stream()
                .map(e -> new Person(e.id(), e.code(), e.name(), e.joinedOn(), e.exitedOn())).toList();
    }

    @Override
    public Map<UUID, List<PaySegment>> segments(Collection<Person> people, LocalDate from, LocalDate to) {
        var versions = employees.versions(people.stream().map(Person::id).toList());
        var out = new HashMap<UUID, List<PaySegment>>();
        for (Person p : people) {
            LocalDate start = p.joinedOn().isAfter(from) ? p.joinedOn() : from;
            LocalDate end = p.exitedOn() != null && p.exitedOn().isBefore(to) ? p.exitedOn() : to;
            var list = new ArrayList<PaySegment>();
            if (!start.isAfter(end)) {
                for (var s : new Timeline(versions.getOrDefault(p.id(), List.of())).segments(start, end)) {
                    list.add(new PaySegment(s.from(), s.to(), s.version().ctcAnnual(), s.version().structureCode()));
                }
            }
            out.put(p.id(), list);
        }
        return out;
    }

    @Override
    public List<Item> reimbursements(UUID employee) {
        return claims.approvedUnpaid(employee).stream().map(c -> new Item(c.id(), c.amount())).toList();
    }

    @Override
    public List<Item> encashments(UUID employee) {
        return leave.pendingEncashments(employee).stream().map(e -> new Item(e.id(), e.days())).toList();
    }

    @Override
    public void settle(UUID runId, Collection<UUID> reimbursementIds, Collection<UUID> encashmentIds) {
        claims.markPaid(reimbursementIds, runId);
        leave.markEncashmentsPaid(encashmentIds, runId);
    }

    @Override
    public List<Payee> payees(Collection<UUID> ids) {
        return employees.bankDetails(ids).stream()
                .map(b -> new Payee(b.employeeId(), b.code(), b.name(), b.account(), b.ifsc())).toList();
    }
}
