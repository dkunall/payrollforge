package com.payrollforge.employee.application;

/** Port: does this location exist? Implemented over the org module. */
public interface LocationCheck {

    boolean exists(String code);
}
