package com.payrollforge.attendance.application;

/** Per-punch outcome of a batch; a batch never fails as a whole because of one bad punch. */
public record PunchResult(String deviceEventId, Outcome outcome, String reason) {

    public enum Outcome { ACCEPTED, DUPLICATE, REJECTED }
}
