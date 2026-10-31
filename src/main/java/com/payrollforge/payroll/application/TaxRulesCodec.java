package com.payrollforge.payroll.application;

import com.payrollforge.payroll.domain.tax.RegimeName;
import com.payrollforge.payroll.domain.tax.Slab;
import com.payrollforge.payroll.domain.tax.SlabRegime;
import com.payrollforge.payroll.domain.tax.TaxRules;
import com.payrollforge.shared.config.PayrollForgeProperties.TaxRulesConfig;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Tax rules travel with the run as plain maps of decimal strings: the run stores the rules it was computed with,
 * so a config change later cannot alter what verify recomputes.
 */
public final class TaxRulesCodec {

    private TaxRulesCodec() {
    }

    public static Map<String, Object> toMap(TaxRulesConfig cfg) {
        var regimes = new TreeMap<String, Object>();
        cfg.regimes().forEach((name, r) -> {
            RegimeName.valueOf(name);
            var slabs = new ArrayList<Map<String, Object>>();
            for (var s : r.slabs()) {
                var m = new LinkedHashMap<String, Object>();
                m.put("from", s.from().toPlainString());
                m.put("upTo", s.upTo() == null ? null : s.upTo().toPlainString());
                m.put("rate", s.rate().toPlainString());
                slabs.add(m);
            }
            var m = new LinkedHashMap<String, Object>();
            m.put("standardDeduction", r.standardDeduction().toPlainString());
            m.put("declarationCap", r.declarationCap().toPlainString());
            m.put("cessRate", r.cessRate().toPlainString());
            m.put("slabs", slabs);
            regimes.put(name, m);
        });
        var out = new LinkedHashMap<String, Object>();
        out.put("id", cfg.id());
        out.put("regimes", regimes);
        return out;
    }

    @SuppressWarnings("unchecked")
    public static TaxRules fromMap(Map<String, Object> m) {
        var regimes = new EnumMap<RegimeName, SlabRegime>(RegimeName.class);
        ((Map<String, Map<String, Object>>) m.get("regimes")).forEach((name, r) -> {
            var slabs = new ArrayList<Slab>();
            for (var s : (List<Map<String, Object>>) r.get("slabs")) {
                slabs.add(new Slab(dec(s.get("from")), s.get("upTo") == null ? null : dec(s.get("upTo")), dec(s.get("rate"))));
            }
            var n = RegimeName.valueOf(name);
            regimes.put(n, new SlabRegime(n, dec(r.get("standardDeduction")), dec(r.get("declarationCap")),
                    dec(r.get("cessRate")), slabs));
        });
        return new TaxRules((String) m.get("id"), regimes);
    }

    private static BigDecimal dec(Object o) {
        return new BigDecimal(o.toString());
    }
}
