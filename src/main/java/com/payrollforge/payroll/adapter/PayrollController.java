package com.payrollforge.payroll.adapter;

import com.payrollforge.payroll.application.BankFile;
import com.payrollforge.payroll.application.BankFileService;
import com.payrollforge.payroll.application.PayrollRun;
import com.payrollforge.payroll.application.PayrollRunService;
import com.payrollforge.payroll.application.PayrollStore;
import com.payrollforge.payroll.application.PayslipService;
import com.payrollforge.payroll.domain.run.RunDiff;
import com.payrollforge.shared.security.Caller;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

@RestController
class PayrollController {

    private final PayrollRunService runs;
    private final PayslipService payslips;
    private final BankFileService bankFiles;

    PayrollController(PayrollRunService runs, PayslipService payslips, BankFileService bankFiles) {
        this.runs = runs;
        this.payslips = payslips;
        this.bankFiles = bankFiles;
    }

    record CreateBody(@NotNull YearMonth period) {
    }

    record PaidBody(@NotBlank String batchRef) {
    }

    record ReissueBody(@NotBlank String reason) {
    }

    @PostMapping("/v1/payroll-runs")
    ResponseEntity<PayrollRun> create(Caller caller, @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                      @Valid @RequestBody CreateBody b) {
        var c = runs.create(caller, b.period(), key);
        return ResponseEntity.status(c.created() ? HttpStatus.CREATED : HttpStatus.OK).body(c.run());
    }

    @GetMapping("/v1/payroll-runs")
    List<PayrollRun> list(Caller caller) {
        return runs.list(caller);
    }

    @GetMapping("/v1/payroll-runs/{id}")
    PayrollRunService.Progress get(Caller caller, @PathVariable UUID id) {
        return runs.progress(caller, id);
    }

    @PostMapping("/v1/payroll-runs/{id}/start")
    @ResponseStatus(HttpStatus.ACCEPTED)
    PayrollRun start(Caller caller, @PathVariable UUID id) {
        return runs.start(caller, id);
    }

    @PostMapping("/v1/payroll-runs/{id}/submit")
    PayrollRun submit(Caller caller, @PathVariable UUID id) {
        return runs.submit(caller, id);
    }

    @PostMapping("/v1/payroll-runs/{id}/approve")
    PayrollRun approve(Caller caller, @PathVariable UUID id) {
        return runs.approve(caller, id);
    }

    @PostMapping("/v1/payroll-runs/{id}/finalize")
    PayrollRun finalizeRun(Caller caller, @PathVariable UUID id) {
        return runs.finalizeRun(caller, id);
    }

    @PostMapping("/v1/payroll-runs/{id}/cancel")
    PayrollRun cancel(Caller caller, @PathVariable UUID id) {
        return runs.cancel(caller, id);
    }

    @PostMapping("/v1/payroll-runs/{id}/mark-paid")
    PayrollRun markPaid(Caller caller, @PathVariable UUID id, @Valid @RequestBody PaidBody b) {
        return runs.markPaid(caller, id, b.batchRef());
    }

    @PostMapping("/v1/payroll-runs/{id}:verify")
    PayslipService.Verification verify(Caller caller, @PathVariable UUID id) {
        return payslips.verify(caller, id);
    }

    @GetMapping("/v1/payroll-runs/{id}/payslips")
    List<PayslipService.Summary> payslips(Caller caller, @PathVariable UUID id) {
        return payslips.list(caller, id);
    }

    @GetMapping("/v1/payroll-runs/{id}/payslips/{employeeId}")
    PayslipService.Payslip payslip(Caller caller, @PathVariable UUID id, @PathVariable UUID employeeId) {
        return payslips.get(caller, id, employeeId);
    }

    @GetMapping("/v1/payroll-runs/{id}/payslips/{employeeId}/pdf")
    ResponseEntity<byte[]> pdf(Caller caller, @PathVariable UUID id, @PathVariable UUID employeeId) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=payslip.pdf")
                .body(payslips.pdf(caller, id, employeeId));
    }

    @GetMapping("/v1/me/payslips")
    List<PayslipService.Payslip> mine(Caller caller) {
        return payslips.mine(caller);
    }

    @GetMapping("/v1/payroll-runs/{id}/diff")
    RunDiff.Summary diff(Caller caller, @PathVariable UUID id, @RequestParam UUID against) {
        return payslips.diff(caller, id, against);
    }

    @GetMapping(value = "/v1/payroll-runs/{id}/register.csv", produces = "text/csv")
    ResponseEntity<byte[]> register(Caller caller, @PathVariable UUID id) {
        return csv(payslips.register(caller, id).getBytes(StandardCharsets.UTF_8), "register.csv");
    }

    @PostMapping("/v1/payroll-runs/{id}/bank-file")
    BankFile generateBankFile(Caller caller, @PathVariable UUID id) {
        return bankFiles.generate(caller, id);
    }

    @GetMapping("/v1/payroll-runs/{id}/bank-file")
    BankFile bankFile(Caller caller, @PathVariable UUID id) {
        return bankFiles.active(caller, id);
    }

    @GetMapping(value = "/v1/payroll-runs/{id}/bank-file/content", produces = "text/csv")
    ResponseEntity<byte[]> bankFileContent(Caller caller, @PathVariable UUID id) {
        return csv(bankFiles.content(caller, id), "bank-file.csv");
    }

    @PostMapping("/v1/payroll-runs/{id}/bank-file/reissues")
    @ResponseStatus(HttpStatus.CREATED)
    PayrollStore.Reissue reissue(Caller caller, @PathVariable UUID id, @Valid @RequestBody ReissueBody b) {
        return bankFiles.requestReissue(caller, id, b.reason());
    }

    @PostMapping("/v1/payroll-runs/{id}/bank-file/reissues/{reissueId}/approve")
    BankFile approveReissue(Caller caller, @PathVariable UUID id, @PathVariable UUID reissueId) {
        return bankFiles.approveReissue(caller, id, reissueId);
    }

    private static ResponseEntity<byte[]> csv(byte[] body, String name) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + name).body(body);
    }
}
