package com.payrollforge.employee.application;

import java.util.UUID;

/** Decrypted payment details, only ever handed to the bank-file writer. */
public record BankDetails(UUID employeeId, String code, String name, String account, String ifsc) {
}
