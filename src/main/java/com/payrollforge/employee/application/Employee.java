package com.payrollforge.employee.application;

import java.time.LocalDate;
import java.util.UUID;

/** An employee row as stored; bank account and PAN are still ciphertext here. */
public record Employee(UUID id, String code, String name, String email, String subject, String status,
                       LocalDate joinedOn, LocalDate exitedOn, String bankAccountEnc, String bankIfsc, String panEnc) {

    public boolean employedOn(LocalDate d) {
        return !d.isBefore(joinedOn) && (exitedOn == null || !d.isAfter(exitedOn));
    }
}
