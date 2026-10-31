package com.payrollforge.shared.security;

import com.payrollforge.shared.error.ApiException;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;

/**
 * A JWT caller, passed explicitly into application services so authorisation is visible in the code.
 *
 * @param employeeId the employee record linked to this subject, or null for pure service/staff accounts
 */
public record Caller(String subject, Set<Role> roles, UUID employeeId) {

    public Caller {
        roles = Set.copyOf(roles);
    }

    public static Caller system(String job) {
        return new Caller("system:" + job, Set.of(Role.HR, Role.PAYROLL_MAKER, Role.PAYROLL_CHECKER), null);
    }

    public boolean has(Role r) {
        return roles.contains(r);
    }

    public boolean hasAny(Role... rs) {
        return Arrays.stream(rs).anyMatch(roles::contains);
    }

    public void require(Role... rs) {
        if (!hasAny(rs)) {
            throw ApiException.forbidden("forbidden", "This needs one of the roles " + Arrays.toString(rs));
        }
    }

    /** Payroll staff, HR, or the employee themselves. Salary data is visible to nobody else. */
    public void requireSelfOr(UUID employee, Role... rs) {
        if (employee != null && employee.equals(employeeId)) {
            return;
        }
        if (!hasAny(rs)) {
            throw ApiException.forbidden("forbidden", "Only the employee or " + Arrays.toString(rs) + " can see this");
        }
    }

    public UUID requireEmployee() {
        if (employeeId == null) {
            throw ApiException.forbidden("not-an-employee", "This token is not linked to an employee record");
        }
        return employeeId;
    }
}
