package com.payrollforge.payroll.domain.structure;

import java.time.LocalDate;
import java.util.List;

/**
 * An immutable version of a salary structure. A change is a new version with a later {@code effectiveFrom};
 * runs record which version they used, so an old payslip can always be recomputed.
 */
public record SalaryStructure(String code, int version, LocalDate effectiveFrom, List<ComponentDef> components,
                              ComponentGraph graph) {

    public static SalaryStructure compile(String code, int version, LocalDate effectiveFrom,
                                          List<ComponentDef> components) {
        return new SalaryStructure(code, version, effectiveFrom, List.copyOf(components), ComponentGraph.build(components));
    }

    /** Components whose formula calls annual_tax(); arrears leave these to the current month's projection. */
    public boolean isTaxComponent(String componentCode) {
        return graph.order().stream().filter(n -> n.name().equals(componentCode) && !n.aggregate())
                .anyMatch(n -> n.formula().usesAnnualTax());
    }
}
