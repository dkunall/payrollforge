package com.payrollforge.employee.application;

import java.time.Instant;
import java.util.UUID;

public record Task(UUID id, UUID employeeId, String kind, String title, String doneBy, Instant doneAt) {
}
