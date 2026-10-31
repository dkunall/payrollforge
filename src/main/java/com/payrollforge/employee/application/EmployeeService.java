package com.payrollforge.employee.application;

import com.payrollforge.employee.domain.EmployeeVersion;
import com.payrollforge.employee.domain.Timeline;
import com.payrollforge.shared.config.BusinessZone;
import com.payrollforge.shared.config.PayrollForgeProperties;
import com.payrollforge.shared.crypto.FieldCipher;
import com.payrollforge.shared.error.ApiException;
import com.payrollforge.shared.security.Caller;
import com.payrollforge.shared.security.EmployeeIdentity;
import com.payrollforge.shared.security.Role;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** Employee records, effective-dated versions, the org chart and on/offboarding checklists. */
@Service
public class EmployeeService implements EmployeeIdentity {

    private static final Pattern PAN = Pattern.compile("[A-Z]{5}[0-9]{4}[A-Z]");
    private static final Pattern IFSC = Pattern.compile("[A-Z]{4}0[A-Z0-9]{6}");
    private static final Pattern ACCOUNT = Pattern.compile("[0-9]{9,18}");
    private static final int MAX_CHAIN = 64;

    private final EmployeeStore store;
    private final LocationCheck locations;
    private final FieldCipher cipher;
    private final PayrollForgeProperties props;
    private final Clock clock;

    public EmployeeService(EmployeeStore store, LocationCheck locations, FieldCipher cipher,
                           PayrollForgeProperties props, Clock clock) {
        this.store = store;
        this.locations = locations;
        this.cipher = cipher;
        this.props = props;
        this.clock = clock;
    }

    public record NewEmployee(String code, String name, String email, String subject, LocalDate joinedOn,
                              String bankAccount, String bankIfsc, String pan, NewVersion first) {
    }

    @Transactional
    public EmployeeView create(Caller caller, NewEmployee n) {
        caller.require(Role.HR);
        validatePayment(n.bankAccount(), n.bankIfsc(), n.pan());
        if (!n.first().effectiveFrom().equals(n.joinedOn())) {
            throw ApiException.invalid("the first version must start on the joining date");
        }
        var e = new Employee(UUID.randomUUID(), n.code(), n.name(), n.email(), n.subject(), "ACTIVE", n.joinedOn(),
                null, cipher.encrypt("bank_account", n.bankAccount()), n.bankIfsc(), cipher.encrypt("pan", n.pan()));
        if (!store.insert(e, clock.instant())) {
            throw ApiException.conflict("employee-exists", "Employee code or subject already in use");
        }
        checkVersion(e, n.first());
        store.insertVersion(e.id(), n.first(), caller.subject(), clock.instant());
        store.addTasks(e.id(), "ONBOARDING", props.checklists().onboarding());
        return view(e, n.joinedOn());
    }

    @Transactional
    public EmployeeView addVersion(Caller caller, UUID id, NewVersion v) {
        caller.require(Role.HR);
        var e = store.lock(id).orElseThrow(() -> ApiException.notFound("employee " + id));
        checkVersion(e, v);
        if (!store.insertVersion(id, v, caller.subject(), clock.instant())) {
            throw ApiException.conflict("version-exists", "A version already starts on " + v.effectiveFrom()
                    + "; versions are append-only, pick another date");
        }
        return view(e, v.effectiveFrom());
    }

    @Transactional
    public EmployeeView exit(Caller caller, UUID id, LocalDate lastDay) {
        caller.require(Role.HR);
        var e = store.lock(id).orElseThrow(() -> ApiException.notFound("employee " + id));
        if (e.exitedOn() != null) {
            throw ApiException.conflict("already-exited", "Employee exited on " + e.exitedOn());
        }
        if (lastDay.isBefore(e.joinedOn())) {
            throw ApiException.invalid("exit date is before the joining date");
        }
        store.exit(id, lastDay);
        store.addTasks(id, "OFFBOARDING", props.checklists().offboarding());
        return view(store.find(id).orElseThrow(), lastDay);
    }

    public EmployeeView get(Caller caller, UUID id, LocalDate asOf) {
        caller.requireSelfOr(id, Role.HR, Role.PAYROLL_MAKER, Role.PAYROLL_CHECKER);
        var e = store.find(id).orElseThrow(() -> ApiException.notFound("employee " + id));
        return view(e, asOf == null ? BusinessZone.today(clock) : asOf);
    }

    public List<EmployeeVersion> history(Caller caller, UUID id) {
        caller.requireSelfOr(id, Role.HR, Role.PAYROLL_MAKER, Role.PAYROLL_CHECKER);
        store.find(id).orElseThrow(() -> ApiException.notFound("employee " + id));
        return new Timeline(store.versions(id)).versions();
    }

    /** Directory: names, codes and current placement, no pay or bank data. Any authenticated caller. */
    public record DirectoryEntry(UUID id, String code, String name, String email, String status, String grade,
                                 String dept, String location, UUID managerId) {
    }

    public List<DirectoryEntry> directory(String status, int limit, int offset) {
        var rows = store.list(status, Math.min(Math.max(limit, 1), 500), Math.max(offset, 0));
        var versions = store.versions(rows.stream().map(Employee::id).toList());
        LocalDate today = BusinessZone.today(clock);
        return rows.stream().map(e -> {
            var v = new Timeline(versions.getOrDefault(e.id(), List.of())).asOf(today).orElse(null);
            return new DirectoryEntry(e.id(), e.code(), e.name(), e.email(), e.status(),
                    v == null ? null : v.grade(), v == null ? null : v.dept(), v == null ? null : v.location(),
                    v == null ? null : v.managerId());
        }).toList();
    }

    public record OrgNode(UUID id, String code, String name, String grade, String dept, List<OrgNode> reports) {
    }

    /** The reporting tree as it stood on {@code asOf}, built from each employee's version in force that day. */
    public List<OrgNode> orgChart(LocalDate asOf) {
        LocalDate d = asOf == null ? BusinessZone.today(clock) : asOf;
        var employed = store.employedBetween(d, d);
        var versions = store.versions(employed.stream().map(Employee::id).toList());
        var byManager = new java.util.HashMap<UUID, List<Employee>>();
        var current = new java.util.HashMap<UUID, EmployeeVersion>();
        var roots = new ArrayList<Employee>();
        for (var e : employed) {
            var v = new Timeline(versions.getOrDefault(e.id(), List.of())).asOf(d).orElse(null);
            if (v == null) {
                continue;
            }
            current.put(e.id(), v);
        }
        for (var e : employed) {
            var v = current.get(e.id());
            if (v == null) {
                continue;
            }
            if (v.managerId() == null || !current.containsKey(v.managerId())) {
                roots.add(e);
            } else {
                byManager.computeIfAbsent(v.managerId(), k -> new ArrayList<>()).add(e);
            }
        }
        return roots.stream().map(r -> node(r, current, byManager, 0)).toList();
    }

    private OrgNode node(Employee e, Map<UUID, EmployeeVersion> current, Map<UUID, List<Employee>> byManager, int depth) {
        var v = current.get(e.id());
        var kids = depth > MAX_CHAIN ? List.<OrgNode>of() : byManager.getOrDefault(e.id(), List.of()).stream()
                .sorted(java.util.Comparator.comparing(Employee::code))
                .map(k -> node(k, current, byManager, depth + 1)).toList();
        return new OrgNode(e.id(), e.code(), e.name(), v.grade(), v.dept(), kids);
    }

    public List<Task> tasks(Caller caller, UUID id) {
        caller.requireSelfOr(id, Role.HR);
        return store.tasks(id);
    }

    @Transactional
    public void completeTask(Caller caller, UUID id, UUID task) {
        caller.requireSelfOr(id, Role.HR);
        if (!store.completeTask(id, task, caller.subject(), clock.instant())) {
            throw ApiException.notFound("open task " + task);
        }
    }

    @Override
    public Optional<UUID> employeeFor(String subject) {
        return store.idForSubject(subject);
    }

    // ---- for other modules (through their own ports) ----------------------------------------------------------

    public Optional<Employee> find(UUID id) {
        return store.find(id);
    }

    public Optional<Employee> lock(UUID id) {
        return store.lock(id);
    }

    public Optional<EmployeeVersion> versionOn(UUID id, LocalDate d) {
        return new Timeline(store.versions(id)).asOf(d);
    }

    public List<Employee> employedBetween(LocalDate from, LocalDate to) {
        return store.employedBetween(from, to);
    }

    public Map<UUID, List<EmployeeVersion>> versions(Collection<UUID> ids) {
        return store.versions(ids);
    }

    public List<BankDetails> bankDetails(Collection<UUID> ids) {
        var out = new ArrayList<BankDetails>();
        for (UUID id : ids) {
            var e = store.find(id).orElseThrow();
            out.add(new BankDetails(id, e.code(), e.name(), cipher.decrypt("bank_account", e.bankAccountEnc()), e.bankIfsc()));
        }
        return out;
    }

    // ---- rules -------------------------------------------------------------------------------------------------

    private void checkVersion(Employee e, NewVersion v) {
        if (v.effectiveFrom().isBefore(e.joinedOn())) {
            throw ApiException.invalid("a version cannot start before the joining date " + e.joinedOn());
        }
        if (e.exitedOn() != null && v.effectiveFrom().isAfter(e.exitedOn())) {
            throw ApiException.invalid("a version cannot start after the exit date " + e.exitedOn());
        }
        if (v.ctcAnnual() == null || v.ctcAnnual().signum() <= 0 || v.ctcAnnual().scale() > 2) {
            throw ApiException.invalid("ctcAnnual must be positive with at most 2 decimals");
        }
        if (!locations.exists(v.location())) {
            throw ApiException.invalid("unknown location " + v.location());
        }
        if (v.managerId() != null) {
            if (v.managerId().equals(e.id())) {
                throw ApiException.invalid("an employee cannot report to themselves");
            }
            var m = store.find(v.managerId()).orElseThrow(() -> ApiException.invalid("unknown manager " + v.managerId()));
            if (!m.employedOn(v.effectiveFrom())) {
                throw ApiException.invalid("the manager is not employed on " + v.effectiveFrom());
            }
            // walk up from the new manager as of that date; reaching this employee would make a cycle
            var seen = new HashSet<UUID>();
            UUID cur = v.managerId();
            while (cur != null && seen.add(cur) && seen.size() <= MAX_CHAIN) {
                if (cur.equals(e.id())) {
                    throw ApiException.invalid("this manager change would create a reporting cycle");
                }
                cur = versionOn(cur, v.effectiveFrom()).map(EmployeeVersion::managerId).orElse(null);
            }
        }
    }

    private static void validatePayment(String account, String ifsc, String pan) {
        if (account == null || !ACCOUNT.matcher(account).matches()) {
            throw ApiException.invalid("bankAccount must be 9-18 digits");
        }
        if (ifsc == null || !IFSC.matcher(ifsc).matches()) {
            throw ApiException.invalid("bankIfsc must look like ABCD0123456");
        }
        if (pan == null || !PAN.matcher(pan).matches()) {
            throw ApiException.invalid("pan must look like ABCDE1234F");
        }
    }

    private EmployeeView view(Employee e, LocalDate asOf) {
        var v = new Timeline(store.versions(e.id())).asOf(asOf).orElse(null);
        return new EmployeeView(e.id(), e.code(), e.name(), e.email(), e.status(), e.joinedOn(), e.exitedOn(),
                FieldCipher.mask(cipher.decrypt("bank_account", e.bankAccountEnc())), e.bankIfsc(),
                FieldCipher.mask(cipher.decrypt("pan", e.panEnc())), asOf, v);
    }
}
