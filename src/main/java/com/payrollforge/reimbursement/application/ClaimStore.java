package com.payrollforge.reimbursement.application;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ClaimStore {

    void insert(Claim c, Instant at);

    Optional<Claim> lock(UUID id);

    Optional<Claim> find(UUID id);

    void setStatus(UUID id, String status);

    List<Claim> list(UUID employee);

    List<Claim> approvedUnpaid(UUID employee);

    void markPaid(Collection<UUID> ids, UUID runId);
}
