package com.payrollforge.attendance.adapter;

import com.payrollforge.attendance.application.AttendanceDay;
import com.payrollforge.attendance.application.AttendanceService;
import com.payrollforge.attendance.application.PunchIn;
import com.payrollforge.attendance.application.PunchResult;
import com.payrollforge.shared.security.Caller;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
class AttendanceController {

    private final AttendanceService attendance;

    AttendanceController(AttendanceService attendance) {
        this.attendance = attendance;
    }

    record Batch(@NotNull List<PunchIn> punches) {
    }

    record CloseBody(@NotNull LocalDate day) {
    }

    @PostMapping("/v1/attendance/punches:batch")
    List<PunchResult> batch(Caller caller, @Valid @RequestBody Batch b) {
        return attendance.ingest(caller, b.punches());
    }

    @PostMapping("/v1/attendance/days:close")
    AttendanceService.CloseResult close(Caller caller, @Valid @RequestBody CloseBody b) {
        return attendance.closeDay(caller, b.day());
    }

    @GetMapping("/v1/attendance/days")
    List<AttendanceDay> days(Caller caller, @RequestParam UUID employeeId,
                             @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                             @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return attendance.days(caller, employeeId, from, to);
    }
}
