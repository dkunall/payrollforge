package com.payrollforge.reports.application;

import com.payrollforge.shared.error.ApiException;
import com.payrollforge.shared.security.Caller;
import com.payrollforge.shared.security.Role;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Map;
import java.util.TreeMap;

@Service
public class ReportService {

    public record Headcount(LocalDate asOf, int total, Map<String, Integer> byDept) {
    }

    /**
     * Attrition = leavers in the range / average of opening and closing headcount, as a percentage. With a zero
     * average the rate is reported as null rather than invented.
     */
    public record Attrition(LocalDate from, LocalDate to, int opening, int closing, int joiners, int leavers,
                            BigDecimal ratePercent, Map<String, Integer> leaversByDept) {
    }

    private final Workforce workforce;

    public ReportService(Workforce workforce) {
        this.workforce = workforce;
    }

    public Headcount headcount(Caller caller, LocalDate asOf) {
        caller.require(Role.HR);
        var byDept = new TreeMap<String, Integer>();
        var people = workforce.employedBetween(asOf, asOf, asOf);
        people.forEach(m -> byDept.merge(m.dept(), 1, Integer::sum));
        return new Headcount(asOf, people.size(), byDept);
    }

    public Attrition attrition(Caller caller, LocalDate from, LocalDate to) {
        caller.require(Role.HR);
        if (to.isBefore(from) || from.plusYears(2).isBefore(to)) {
            throw ApiException.invalid("range must be ordered and at most two years");
        }
        int opening = workforce.employedBetween(from, from, from).size();
        int closing = workforce.employedBetween(to, to, to).size();
        int joiners = 0;
        int leavers = 0;
        var byDept = new TreeMap<String, Integer>();
        for (var m : workforce.employedBetween(from, to, to)) {
            if (!m.joinedOn().isBefore(from) && !m.joinedOn().isAfter(to)) {
                joiners++;
            }
            if (m.exitedOn() != null && !m.exitedOn().isBefore(from) && !m.exitedOn().isAfter(to)) {
                leavers++;
                byDept.merge(m.dept(), 1, Integer::sum);
            }
        }
        BigDecimal avg = BigDecimal.valueOf(opening + closing).divide(BigDecimal.TWO, 4, RoundingMode.HALF_UP);
        BigDecimal rate = avg.signum() == 0 ? null
                : BigDecimal.valueOf(leavers).multiply(BigDecimal.valueOf(100)).divide(avg, 2, RoundingMode.HALF_UP);
        return new Attrition(from, to, opening, closing, joiners, leavers, rate, byDept);
    }
}
