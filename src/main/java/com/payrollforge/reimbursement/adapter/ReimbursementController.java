package com.payrollforge.reimbursement.adapter;

import com.payrollforge.reimbursement.application.Claim;
import com.payrollforge.reimbursement.application.ReimbursementService;
import com.payrollforge.shared.security.Caller;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
class ReimbursementController {

    private final ReimbursementService claims;

    ReimbursementController(ReimbursementService claims) {
        this.claims = claims;
    }

    record SubmitBody(@NotBlank String category, @NotNull BigDecimal amount, @NotNull LocalDate spentOn, String note) {
    }

    @PostMapping("/v1/reimbursements")
    @ResponseStatus(HttpStatus.CREATED)
    Claim submit(Caller caller, @Valid @RequestBody SubmitBody b) {
        return claims.submit(caller, b.category(), b.amount(), b.spentOn(), b.note());
    }

    @GetMapping("/v1/reimbursements/{id}")
    Claim get(Caller caller, @PathVariable UUID id) {
        return claims.get(caller, id);
    }

    @PostMapping("/v1/reimbursements/{id}/approve")
    Claim approve(Caller caller, @PathVariable UUID id) {
        return claims.decide(caller, id, true);
    }

    @PostMapping("/v1/reimbursements/{id}/reject")
    Claim reject(Caller caller, @PathVariable UUID id) {
        return claims.decide(caller, id, false);
    }

    @GetMapping("/v1/employees/{id}/reimbursements")
    List<Claim> list(Caller caller, @PathVariable UUID id) {
        return claims.list(caller, id);
    }
}
