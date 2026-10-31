package com.payrollforge.leave.application;

import com.payrollforge.leave.domain.AccrualPolicy;
import com.payrollforge.leave.domain.LeaveType;
import com.payrollforge.shared.error.ApiException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** The configured leave types. Kept in code for this single-entity build; a policy table would replace it. */
public final class LeaveTypes {

    public static final LeaveType CASUAL = new LeaveType("CASUAL", true, true, false,
            new AccrualPolicy.Monthly(new BigDecimal("1.00"), new BigDecimal("12.00")));
    public static final LeaveType EARNED = new LeaveType("EARNED", true, true, true,
            new AccrualPolicy.Monthly(new BigDecimal("1.25"), new BigDecimal("30.00")));
    public static final LeaveType LOP = new LeaveType("LOP", false, false, false, new AccrualPolicy.None());

    public static final List<LeaveType> ALL = List.of(CASUAL, EARNED, LOP);
    private static final Map<String, LeaveType> BY_CODE = ALL.stream()
            .collect(Collectors.toMap(LeaveType::code, Function.identity()));

    private LeaveTypes() {
    }

    public static LeaveType of(String code) {
        var t = BY_CODE.get(code);
        if (t == null) {
            throw ApiException.invalid("unknown leave type " + code + "; one of " + BY_CODE.keySet());
        }
        return t;
    }
}
