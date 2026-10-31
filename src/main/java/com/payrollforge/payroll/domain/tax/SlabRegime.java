package com.payrollforge.payroll.domain.tax;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Progressive slabs: each slab taxes only the slice of income that falls inside it. The standard deduction and
 * the capped declared deductions come off first; a health-and-education style cess is applied on the total.
 * The numbers come from configuration and are illustrative, not tax advice.
 */
public final class SlabRegime implements TaxRegime {

    private final RegimeName name;
    private final BigDecimal standardDeduction;
    private final BigDecimal declarationCap;
    private final BigDecimal cessRate;
    private final List<Slab> slabs;

    public SlabRegime(RegimeName name, BigDecimal standardDeduction, BigDecimal declarationCap, BigDecimal cessRate,
                      List<Slab> slabs) {
        if (slabs.isEmpty() || slabs.getFirst().from().signum() != 0) {
            throw new IllegalArgumentException(name + ": slabs must start at 0");
        }
        for (int i = 1; i < slabs.size(); i++) {
            if (slabs.get(i - 1).upTo() == null || slabs.get(i - 1).upTo().compareTo(slabs.get(i).from()) != 0) {
                throw new IllegalArgumentException(name + ": slabs must be contiguous");
            }
        }
        if (slabs.getLast().upTo() != null) {
            throw new IllegalArgumentException(name + ": last slab must be open-ended");
        }
        this.name = name;
        this.standardDeduction = standardDeduction;
        this.declarationCap = declarationCap;
        this.cessRate = cessRate;
        this.slabs = List.copyOf(slabs);
    }

    @Override
    public RegimeName name() {
        return name;
    }

    @Override
    public BigDecimal annualTax(BigDecimal annualEarnings, BigDecimal declaredDeductions) {
        BigDecimal declared = declaredDeductions == null ? BigDecimal.ZERO : declaredDeductions.max(BigDecimal.ZERO);
        BigDecimal taxable = annualEarnings.subtract(standardDeduction).subtract(declared.min(declarationCap))
                .max(BigDecimal.ZERO);
        BigDecimal tax = BigDecimal.ZERO;
        for (Slab s : slabs) {
            if (taxable.compareTo(s.from()) <= 0) {
                break;
            }
            BigDecimal top = s.upTo() == null ? taxable : taxable.min(s.upTo());
            tax = tax.add(top.subtract(s.from()).multiply(s.rate()));
        }
        tax = tax.add(tax.multiply(cessRate));
        return tax.setScale(0, RoundingMode.HALF_UP);
    }

    public BigDecimal standardDeduction() {
        return standardDeduction;
    }

    public BigDecimal declarationCap() {
        return declarationCap;
    }

    public BigDecimal cessRate() {
        return cessRate;
    }

    public List<Slab> slabs() {
        return slabs;
    }
}
