package com.payrollforge.employee.application;

import com.payrollforge.employee.domain.EmployeeVersion;

import java.time.LocalDate;
import java.util.UUID;

/** What the API shows: bank account and PAN masked, plus the version in force on {@code asOf}. */
public record EmployeeView(UUID id, String code, String name, String email, String status, LocalDate joinedOn,
                           LocalDate exitedOn, String bankAccount, String bankIfsc, String pan, LocalDate asOf,
                           EmployeeVersion version) {
}
