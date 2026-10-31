package com.payrollforge.payroll.domain.run;

import com.payrollforge.payroll.domain.structure.ComponentDef;
import com.payrollforge.payroll.domain.structure.ComponentKind;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The structure version a payslip used, copied in full so the snapshot does not depend on the live tables. */
public record StructureSpec(String code, int version, List<ComponentDef> components) {

    public Map<String, Object> toMap() {
        var m = new LinkedHashMap<String, Object>();
        m.put("code", code);
        m.put("version", String.valueOf(version));
        m.put("components", components.stream().map(c -> Map.of("code", c.code(), "label", c.label(),
                "kind", c.kind().name(), "formula", c.formula(), "taxable", c.taxable())).toList());
        return m;
    }

    @SuppressWarnings("unchecked")
    public static StructureSpec fromMap(Map<String, Object> m) {
        var comps = ((List<Map<String, Object>>) m.get("components")).stream().map(c -> new ComponentDef(
                (String) c.get("code"), (String) c.get("label"), ComponentKind.valueOf((String) c.get("kind")),
                (String) c.get("formula"), Boolean.TRUE.equals(c.get("taxable")))).toList();
        return new StructureSpec((String) m.get("code"), Integer.parseInt(String.valueOf(m.get("version"))), comps);
    }

    public String key() {
        return code + "@" + version;
    }
}
