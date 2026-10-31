package com.payrollforge.payroll.application;

import com.payrollforge.payroll.domain.tax.RegimeName;
import com.payrollforge.shared.error.ApiException;
import com.payrollforge.shared.security.Caller;
import com.payrollforge.shared.security.Role;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/** Regime choice and declared deductions per financial year; the regime's cap applies at computation time. */
@Service
public class TaxDeclarationService {

    private static final Pattern FY = Pattern.compile("(\\d{4})-(\\d{2})");
    private static final Pattern ITEM = Pattern.compile("[A-Za-z0-9_]{1,40}");

    private final TaxDeclarationStore store;
    private final Clock clock;

    public TaxDeclarationService(TaxDeclarationStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    @Transactional
    public TaxDeclarationStore.Declaration put(Caller caller, UUID employee, String fy, String regime,
                                               Map<String, BigDecimal> items) {
        caller.requireSelfOr(employee, Role.HR);
        var m = FY.matcher(fy);
        if (!m.matches() || (Integer.parseInt(m.group(1)) + 1) % 100 != Integer.parseInt(m.group(2))) {
            throw ApiException.invalid("fy must look like 2026-27");
        }
        try {
            RegimeName.valueOf(regime);
        } catch (IllegalArgumentException e) {
            throw ApiException.invalid("regime must be OLD or NEW");
        }
        var clean = new TreeMap<String, BigDecimal>();
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        for (var e : (items == null ? Map.<String, BigDecimal>of() : items).entrySet()) {
            if (!ITEM.matcher(e.getKey()).matches() || e.getValue() == null || e.getValue().signum() < 0
                    || e.getValue().scale() > 2) {
                throw ApiException.invalid("item " + e.getKey() + " must be a non-negative amount with at most 2 decimals");
            }
            clean.put(e.getKey(), e.getValue().setScale(2));
            total = total.add(e.getValue());
        }
        var d = new TaxDeclarationStore.Declaration(employee, fy, regime, clean, total.setScale(2), clock.instant());
        store.upsert(d);
        return d;
    }

    public TaxDeclarationStore.Declaration get(Caller caller, UUID employee, String fy) {
        caller.requireSelfOr(employee, Role.HR, Role.PAYROLL_MAKER, Role.PAYROLL_CHECKER);
        return store.find(employee, fy).orElse(new TaxDeclarationStore.Declaration(employee, fy, "NEW", Map.of(),
                BigDecimal.ZERO.setScale(2), null));
    }
}
