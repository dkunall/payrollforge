package com.payrollforge.payroll.domain.run;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One earlier settled period to re-pay: the inputs it would have had with today's records, and what was already
 * paid for it per component (the original payslip plus arrears settled since).
 */
public record ArrearsItem(String period, StructureSpec structure, Map<String, String> inputs,
                          Map<String, String> baseline) {

    public Map<String, Object> toMap() {
        var m = new LinkedHashMap<String, Object>();
        m.put("period", period);
        m.put("structure", structure.toMap());
        m.put("inputs", inputs);
        m.put("baseline", baseline);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static ArrearsItem fromMap(Map<String, Object> m) {
        return new ArrearsItem((String) m.get("period"), StructureSpec.fromMap((Map<String, Object>) m.get("structure")),
                (Map<String, String>) m.get("inputs"), (Map<String, String>) m.get("baseline"));
    }
}
