package com.payrollforge.attendance.adapter;

import com.payrollforge.attendance.application.LeaveCalendar;
import com.payrollforge.attendance.domain.DayClassifier.LeaveOnDay;
import com.payrollforge.leave.application.LeaveService;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Component
class LeaveModuleCalendar implements LeaveCalendar {

    private final LeaveService leave;

    LeaveModuleCalendar(LeaveService leave) {
        this.leave = leave;
    }

    @Override
    public Map<UUID, LeaveOnDay> approvedOn(Collection<UUID> employees, LocalDate day) {
        var out = new HashMap<UUID, LeaveOnDay>();
        leave.approvedOn(employees, day).forEach((id, l) -> out.put(id, new LeaveOnDay(l.paid(), l.halfDay())));
        return out;
    }
}
