package com.payrollforge.payroll.domain.run;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Everything one employee's payslip is computed from. The payslip is a pure function of this plus the run's tax
 * rules, which is what makes a run reproducible: recompute from the stored snapshot and compare.
 *
 * <p>Numbers are decimal strings so the canonical form, and therefore the hash, cannot drift through floats.
 */
public record EmployeeSnapshot(UUID employeeId, String code, String name, String period, StructureSpec structure,
                               Map<String, String> inputs, String regime, String declaredDeductions,
                               List<ArrearsItem> arrears, List<String> reimbursementIds, List<String> encashmentIds) {

    public Map<String, Object> toMap() {
        var m = new LinkedHashMap<String, Object>();
        m.put("employeeId", employeeId.toString());
        m.put("code", code);
        m.put("name", name);
        m.put("period", period);
        m.put("structure", structure.toMap());
        m.put("inputs", inputs);
        m.put("regime", regime);
        m.put("declaredDeductions", declaredDeductions);
        m.put("arrears", arrears.stream().map(ArrearsItem::toMap).toList());
        m.put("reimbursementIds", reimbursementIds);
        m.put("encashmentIds", encashmentIds);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static EmployeeSnapshot fromMap(Map<String, Object> m) {
        return new EmployeeSnapshot(UUID.fromString((String) m.get("employeeId")), (String) m.get("code"),
                (String) m.get("name"), (String) m.get("period"),
                StructureSpec.fromMap((Map<String, Object>) m.get("structure")), (Map<String, String>) m.get("inputs"),
                (String) m.get("regime"), (String) m.get("declaredDeductions"),
                ((List<Map<String, Object>>) m.get("arrears")).stream().map(ArrearsItem::fromMap).toList(),
                (List<String>) m.get("reimbursementIds"), (List<String>) m.get("encashmentIds"));
    }

    public String hash() {
        return Canonical.sha256(toMap());
    }

    /** The run hash covers the tax rules and every employee's hash in employee-id order. */
    public static String runHash(Map<String, Object> taxRules, Map<UUID, String> employeeHashes) {
        var sorted = new java.util.TreeMap<String, String>();
        employeeHashes.forEach((k, v) -> sorted.put(k.toString(), v));
        return Canonical.sha256(Map.of("taxRules", taxRules, "employees", sorted));
    }
}
