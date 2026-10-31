# PayrollForge bench, 2026-10-31 08:14 IST

One run of `scripts/bench.sh` (N=300 new employees, batches of 100 punches) against the compose stack on one machine
(8 CPUs, 15 GiB RAM; app, Postgres, Kafka, RustFS and the bench client all share it).
Numbers are wall-clock from the client, one sample each unless a percentile is shown; treat them as a smoke-level
indication, not capacity figures.

| What | Result |
|---|---|
| Punches ingested | 3000 in 5 day(s), 30 batches |
| Punch batch latency p50 / p95 | 66 ms / 124 ms |
| Punch throughput (sequential batches) | 1304 punches/s |
| Day close, all employees, p50 | 85 ms |
| Wait for payroll's copy of closed days to catch up after the last close | 786 ms |
| Payroll compute for 304 payslips (start to REVIEW, polled every 0.5 s) | 801 ms |
| Verify (recompute every payslip from snapshots) | 54 ms |
| Finalize call | 125 ms |
| All 304 payslip PDFs stored after finalize (polled every 0.5 s) | 2252 ms |
| Bank file generation | 60 ms |
