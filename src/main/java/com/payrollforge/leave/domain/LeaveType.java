package com.payrollforge.leave.domain;

/** Configured per company. {@code limited} false means there is no balance to check (loss of pay). */
public record LeaveType(String code, boolean paid, boolean limited, boolean encashable, AccrualPolicy accrual) {
}
