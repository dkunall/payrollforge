package com.payrollforge.employee.adapter;

import com.payrollforge.employee.application.LocationCheck;
import com.payrollforge.org.application.OrgService;
import org.springframework.stereotype.Component;

@Component
class OrgLocationCheck implements LocationCheck {

    private final OrgService org;

    OrgLocationCheck(OrgService org) {
        this.org = org;
    }

    @Override
    public boolean exists(String code) {
        return org.locations().stream().anyMatch(l -> l.code().equals(code));
    }
}
