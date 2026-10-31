package com.payrollforge.payroll.application;

public interface PayslipRenderer {

    byte[] render(PayslipRow payslip, String entity);
}
