package com.payrollforge.shared.security;

/**
 * HR manages people, calendars, leave policy and approves HR steps. PAYROLL_MAKER prepares runs; PAYROLL_CHECKER
 * approves and finalizes them and must be a different person. DEVICE is an attendance terminal's service account.
 * EMPLOYEE is anyone whose token subject is linked to an employee record. Staff roles come from configuration,
 * never from token claims.
 */
public enum Role { HR, PAYROLL_MAKER, PAYROLL_CHECKER, DEVICE, EMPLOYEE }
