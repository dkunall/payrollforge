package com.payrollforge.payroll.adapter;

import com.payrollforge.payroll.application.StructureService;
import com.payrollforge.payroll.application.StructureStore;
import com.payrollforge.payroll.application.TaxDeclarationService;
import com.payrollforge.payroll.application.TaxDeclarationStore;
import com.payrollforge.payroll.domain.structure.ComponentDef;
import com.payrollforge.payroll.domain.structure.ComponentKind;
import com.payrollforge.shared.security.Caller;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
class StructureController {

    private final StructureService structures;
    private final TaxDeclarationService declarations;

    StructureController(StructureService structures, TaxDeclarationService declarations) {
        this.structures = structures;
        this.declarations = declarations;
    }

    record Component(@NotBlank String code, @NotBlank String label, @NotNull ComponentKind kind, @NotBlank String formula,
                     boolean taxable) {
    }

    record StructureBody(@NotBlank String code, @NotNull LocalDate effectiveFrom, @NotEmpty List<@Valid Component> components) {
    }

    record DeclarationBody(@NotBlank String regime, Map<String, BigDecimal> items) {
    }

    @PostMapping("/v1/salary-structures")
    @ResponseStatus(HttpStatus.CREATED)
    StructureStore.Version create(Caller caller, @Valid @RequestBody StructureBody b) {
        var comps = b.components().stream()
                .map(c -> new ComponentDef(c.code(), c.label(), c.kind(), c.formula(), c.taxable())).toList();
        return structures.create(caller, b.code(), b.effectiveFrom(), comps);
    }

    @GetMapping("/v1/salary-structures/{code}")
    List<StructureStore.Version> versions(Caller caller, @PathVariable String code) {
        return structures.versions(caller, code);
    }

    @PutMapping("/v1/employees/{id}/tax-declarations/{fy}")
    TaxDeclarationStore.Declaration declare(Caller caller, @PathVariable UUID id, @PathVariable String fy,
                                            @Valid @RequestBody DeclarationBody b) {
        return declarations.put(caller, id, fy, b.regime(), b.items() == null ? Map.of() : b.items());
    }

    @GetMapping("/v1/employees/{id}/tax-declarations/{fy}")
    TaxDeclarationStore.Declaration declaration(Caller caller, @PathVariable UUID id, @PathVariable String fy) {
        return declarations.get(caller, id, fy);
    }
}
