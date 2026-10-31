package com.payrollforge.reports.adapter;

import com.payrollforge.reports.application.ReportService;
import com.payrollforge.shared.security.Caller;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
class ReportController {

    private final ReportService reports;

    ReportController(ReportService reports) {
        this.reports = reports;
    }

    @GetMapping("/v1/reports/headcount")
    ReportService.Headcount headcount(Caller caller, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return reports.headcount(caller, asOf);
    }

    @GetMapping("/v1/reports/attrition")
    ReportService.Attrition attrition(Caller caller, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reports.attrition(caller, from, to);
    }
}
