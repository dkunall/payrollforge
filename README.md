# PayrollForge

An HRMS and monthly payroll backend I built as a portfolio project: employees and their salary history,
attendance from biometric terminals and a mobile app, leave with manager approval, expense claims, and a payroll
run that a maker computes and a different checker approves before anything is paid. Payslip PDFs, a salary
register and a bank transfer file come out the other end.

Java 21, Spring Boot 3.5, PostgreSQL, Kafka, S3-compatible object storage (RustFS), OAuth2 resource server.
All data in this repository is synthetic.

> The tax slabs, PF, ESI and professional-tax formulas are **illustrative**. They are shaped like Indian payroll
> rules so the engine has something realistic to chew on, but they are not current law (no rebate, surcharge or
> marginal relief, no state-specific PT tables) and nothing here is tax or compliance advice.

## What it does

- **People.** Employees with effective-dated versions (CTC, structure, manager, location), so a backdated raise is
  just a new version and the next run pays the difference as arrears. PAN and bank account are encrypted per
  column with AES-256-GCM (column name bound in as associated data) and come back masked. Onboarding/exit tasks,
  an org chart and simple headcount/attrition reports.
- **Attendance.** Terminals send batches of punches; each punch has a device event id, so a resent batch is
  stored once. Mobile punches are checked against the location's geofence. HR closes a day, which decides
  PRESENT / HALF_DAY / ABSENT / LEAVE / HOLIDAY / WEEKLY_OFF and a payable fraction for every employee. Once a month has been
  finalized its days cannot be closed again.
- **Leave.** Balances per type with grants, accrual and encashment. Applying takes a row lock on the balance, so
  concurrent requests cannot overdraw it. Approval goes to the manager (or a delegate) and is decided once.
- **Claims.** Reimbursement claims with category limits, approved by the manager, paid through the next payroll
  and marked settled by the run that paid them.
- **Salary structures.** Components are small formulas (`round(CTC_MONTHLY * 0.40 * FACTOR, 0)`) evaluated over
  `BigDecimal` by a hand-written parser with a whitelist of functions; no script engine. Components are ordered
  by their dependencies and cycles are refused when the structure is saved. Two defaults ship: `STD` (basic, HRA,
  special, PF, ESI, PT, TDS) and `INTERN` (stipend only).
- **Payroll run.** `DRAFT → COMPUTING → REVIEW → SUBMITTED → APPROVED → FINALIZED → PAID`, with cancel.
  - Creating a run needs an `Idempotency-Key`; the same key returns the same run, and a period can have only one
    run that is not cancelled (a partial unique index, not just a check in code).
  - Start snapshots every employee's inputs (version, days, leave, claims, YTD) and the tax tables, then computes
    in chunks on virtual threads. A failed chunk is retried on its own; a finished chunk is never redone.
  - `:verify` recomputes every payslip from the stored snapshots and compares.
  - Approve and finalize re-hash the live inputs. If anything changed since start (a late leave approval, a
    re-closed day, a new claim) the run goes back to DRAFT instead of paying stale numbers.
  - The checker must be a different person from whoever created or submitted the run.
  - Finalize locks the period, queues payslip PDFs (stored in object storage) and settles the claims it paid.
  - The bank file is generated once per run (same batch reference and SHA-256 on every call). Reissuing one
    needs a second person's approval. Mark-paid needs the batch reference.
- **Events.** State changes go into an outbox table in the same transaction and a relay publishes them to Kafka.
  Payroll keeps its own copy of closed attendance days, fed from those events; versions make redelivery and
  reordering harmless.

Money is `BigDecimal` from the API (decimal strings with two places) down to `numeric(14,2)` columns. There are
no `double`s anywhere except geofence coordinates.

## Layout

Hexagonal, one package per module (`employee`, `attendance`, `leave`, `reimbursement`, `payroll`, `workflow`,
`org`, `reports`, `outbox`), each with `domain`, `application` and `adapter`. ArchUnit checks that domains stay
free of Spring/JDBC/web, that application code does not reach into adapters or infrastructure, that each
module's core is self-contained, that there are no cycles between modules, and that only the employee module
touches the field cipher.

## Running it

Needs Docker with Compose. Everything binds to 127.0.0.1.

```bash
docker build -t payrollforge:local .
./scripts/up.sh --no-build     # generates local secrets into ~/.config/payrollforge/compose.env (0600), starts the stack
./scripts/demo.sh              # one payroll month end to end, with checks
./scripts/bench.sh             # rough timings for N synthetic employees (default 300)
./scripts/down.sh              # stops it and removes the volumes
```

| Service | Address |
|---|---|
| API | http://127.0.0.1:8880 |
| Mock OAuth2 server (demo tokens) | http://127.0.0.1:8881 |
| RustFS (payslip PDFs) | http://127.0.0.1:8882 |

The demo hires four people, sends terminal punches (including a resent batch and a mobile punch from outside the
geofence), runs leave and a claim through approval, then computes, verifies, approves, finalizes and pays the
previous month. One run of it on my machine printed:

```
ASHA   payable 30.00/30 days  gross 148200.00  deductions 7384.00  net 140816.00
KABIR  payable 29.00/30 days  gross  24166.67  deductions    0.00  net  24166.67   (intern, absent on the 30th)
MEERA  payable 15.00/30 days  gross  28560.00  deductions 1640.00  net  26920.00   (joined on the 15th, absent on the 30th)
RAVI   payable 30.00/30 days  gross  79699.99  deductions 2538.00  net  77161.99   (includes a 1499.99 claim)
register total 269064.66; bank file total 269064.66 over 4 rows
```

## Tests

`mvn verify` runs 100 unit and architecture tests (12 of them ArchUnit) and 11 integration tests against real
PostgreSQL and Kafka via Testcontainers. Among the integration tests:

- create a run with one idempotency key from many threads at once: one 201, the same run for everyone else, and
  a second key for the same period refused;
- leave balance never goes negative when many requests for the same days race;
- an approval is decided exactly once, and only by its assignee;
- a terminal resending the same batch from several threads stores each punch once;
- a full month computed to the paisa, with four-eyes approval and settlement;
- a chunk that fails is retried without recomputing the others; changed inputs send the run back to DRAFT;
- a backdated raise paid as arrears to the rupee;
- the bank file is generated once and a reissue needs a second person;
- PAN and bank account are not readable in the database and are masked in responses.

CI (GitHub Actions) runs the tests, builds the image, scans it with Trivy (HIGH/CRITICAL, fixed only) and runs
the demo and a 150-employee bench against the compose stack.

## Numbers

One bench run with 300 new employees on an 8-CPU, 15 GiB machine, everything (app, Postgres, Kafka, RustFS and
the client) on the same box. Single samples, so read them as "it is not slow", not as capacity figures. Full
output in [docs/bench/compose-n300.md](docs/bench/compose-n300.md).

| What | Result |
|---|---|
| 3000 punches in 30 batches of 100 | p50 66 ms, p95 124 ms per batch (about 1300 punches/s sequentially) |
| Close a day for everyone, p50 | 85 ms |
| Payroll's copy of closed days catching up after the last close | 786 ms |
| Compute 304 payslips (start to REVIEW, polled every 0.5 s) | 801 ms |
| Verify all 304 from snapshots | 54 ms |
| All 304 PDFs stored after finalize | 2252 ms |
| Bank file | 60 ms |

## Limitations

Things I left out or simplified, so nobody mistakes this for a production payroll system:

- Tax and statutory rules are illustrative (see the note at the top). Only the new-regime-style slab table is
  configured; declarations are capped, not verified against proofs.
- Retro pay covers salary (CTC/structure) changes only, not retroactive attendance or leave corrections in a
  closed month.
- No night shifts or shifts crossing midnight, no attendance regularization requests, no leave cancellation
  after approval and no carry-forward at year end.
- One legal entity, one currency (INR), no multi-country rules.
- Payroll's attendance copy is eventually consistent. If a run is started before it catches up, approval notices
  the change and sends the run back to DRAFT rather than paying wrong numbers, but someone has to start it again.
  The demo and bench scripts wait for it by peeking at the database, which is a script shortcut, not an API.
- Some races are accepted rather than prevented: re-closing a day concurrently with another close (last version
  wins), and a leave approval landing between start and approve (caught by the input re-hash, not blocked).
- Resuming unfinished chunks after a crash assumes a single app instance; the holiday cache is per instance too.
- No email or notifications; tasks and approvals are only visible through the API.
- The field-encryption key is a single key from the environment, with no rotation.
- The mock OAuth2 server is for the demo only. The employee directory (names, codes, departments, no pay data)
  is visible to every signed-in employee by design.
- Reimbursements flowing into payroll are covered by the demo script, not by an integration test.
- Two Spring MVC CVEs are ignored in `.trivyignore` because the fix is only in Spring Framework 7 and the
  affected features (XSLT views, Server-Sent Events) are not used here.

## Licence

MIT.
