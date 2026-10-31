package com.payrollforge.shared.security;

import java.util.Optional;
import java.util.UUID;

/** Port: which employee record (if any) a token subject belongs to. Implemented by the employee module. */
public interface EmployeeIdentity {

    Optional<UUID> employeeFor(String subject);
}
