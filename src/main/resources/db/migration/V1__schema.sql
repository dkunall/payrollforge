-- PayrollForge schema. Money is numeric(14,2) (rupees and paise, exact); day fractions are numeric(5,2).
-- Effective-dated rows and salary structure versions are insert-only: the application never updates them.

create table outbox (
    id           bigserial primary key,
    event_id     uuid        not null unique,
    topic        text        not null,
    msg_key      text        not null,
    type         text        not null,
    payload      jsonb       not null,
    created_at   timestamptz not null,
    published_at timestamptz
);
create index outbox_unpublished on outbox (id) where published_at is null;

create table processed_event (
    consumer text        not null,
    event_id uuid        not null,
    at       timestamptz not null,
    primary key (consumer, event_id)
);

-- org ----------------------------------------------------------------------------------------------------------
create table location (
    code         text primary key,
    name         text             not null,
    timezone     text             not null,
    lat          double precision not null,
    lng          double precision not null,
    radius_m     integer          not null check (radius_m between 10 and 5000),
    weekly_off   text[]           not null
);

create table holiday (
    location_code text not null references location (code),
    day           date not null,
    name          text not null,
    primary key (location_code, day)
);

-- employee -----------------------------------------------------------------------------------------------------
create table employee (
    id             uuid primary key,
    code           text        not null unique,
    name           text        not null,
    email          text        not null,
    subject        text unique,
    status         text        not null check (status in ('ACTIVE', 'EXITED')),
    joined_on      date        not null,
    exited_on      date,
    bank_account   text,
    bank_ifsc      text,
    pan            text,
    created_at     timestamptz not null,
    check (exited_on is null or exited_on >= joined_on)
);

create table employee_version (
    employee_id    uuid          not null references employee (id),
    effective_from date          not null,
    grade          text          not null,
    dept           text          not null,
    location_code  text          not null references location (code),
    manager_id     uuid references employee (id),
    ctc_minor      bigint        not null check (ctc_minor > 0),
    structure_code text          not null,
    reason         text          not null,
    created_by     text          not null,
    created_at     timestamptz   not null,
    primary key (employee_id, effective_from)
);
create index employee_version_manager on employee_version (manager_id);

create table employee_task (
    id           uuid primary key,
    employee_id  uuid        not null references employee (id),
    kind         text        not null check (kind in ('ONBOARDING', 'OFFBOARDING')),
    title        text        not null,
    done_by      text,
    done_at      timestamptz,
    unique (employee_id, kind, title)
);

-- attendance ---------------------------------------------------------------------------------------------------
create table attendance_punch (
    id              uuid primary key,
    employee_id     uuid             not null references employee (id),
    ts              timestamptz      not null,
    source          text             not null check (source in ('DEVICE', 'MOBILE')),
    device_event_id text             not null unique,
    lat             double precision,
    lng             double precision,
    received_at     timestamptz      not null
);
create index attendance_punch_employee_ts on attendance_punch (employee_id, ts);

create table attendance_day (
    employee_id    uuid         not null references employee (id),
    day            date         not null,
    status         text         not null,
    worked_minutes integer      not null,
    payable        numeric(3,2) not null check (payable between 0 and 1),
    missing_punch  boolean      not null,
    version        integer      not null,
    closed_at      timestamptz  not null,
    primary key (employee_id, day)
);

-- leave --------------------------------------------------------------------------------------------------------
create table leave_balance (
    employee_id uuid         not null references employee (id),
    leave_type  text         not null,
    balance     numeric(6,2) not null check (balance >= 0),
    version     bigint       not null,
    primary key (employee_id, leave_type)
);

create table leave_request (
    id          uuid primary key,
    employee_id uuid         not null references employee (id),
    leave_type  text         not null,
    from_day    date         not null,
    to_day      date         not null,
    half_day    boolean      not null,
    days        numeric(5,2) not null check (days > 0),
    reason      text,
    status      text         not null check (status in ('PENDING', 'APPROVED', 'REJECTED')),
    created_at  timestamptz  not null,
    decided_at  timestamptz,
    check (to_day >= from_day)
);
create index leave_request_employee on leave_request (employee_id, from_day);

-- every balance change, so a balance can be explained; accruals are idempotent per (employee, type, month)
create table leave_ledger (
    id          bigserial primary key,
    employee_id uuid         not null,
    leave_type  text         not null,
    kind        text         not null check (kind in ('ACCRUAL', 'DEBIT', 'REFUND', 'ENCASH', 'GRANT')),
    ref         text         not null,
    days        numeric(6,2) not null,
    at          timestamptz  not null,
    unique (employee_id, leave_type, kind, ref)
);

create table leave_encashment (
    id          uuid primary key,
    employee_id uuid         not null references employee (id),
    leave_type  text         not null,
    days        numeric(5,2) not null check (days > 0),
    status      text         not null check (status in ('PENDING', 'PAID')),
    run_id      uuid,
    created_at  timestamptz  not null
);

-- workflow -----------------------------------------------------------------------------------------------------
create table approval_step (
    id           uuid primary key,
    subject_type text        not null,
    subject_id   uuid        not null,
    step_no      integer     not null,
    role         text        not null check (role in ('MANAGER', 'HR')),
    assignee     uuid,
    status       text        not null check (status in ('WAITING', 'PENDING', 'APPROVED', 'REJECTED', 'SKIPPED')),
    decided_by   text,
    on_behalf_of uuid,
    decided_at   timestamptz,
    version      bigint      not null,
    unique (subject_type, subject_id, step_no)
);
create index approval_step_assignee on approval_step (assignee) where status = 'PENDING';

create table delegation (
    id         uuid primary key,
    delegator  uuid        not null references employee (id),
    delegate   uuid        not null references employee (id),
    from_day   date        not null,
    to_day     date        not null,
    created_at timestamptz not null,
    check (delegator <> delegate and to_day >= from_day)
);

-- reimbursement ------------------------------------------------------------------------------------------------
create table reimbursement (
    id          uuid primary key,
    employee_id uuid          not null references employee (id),
    category    text          not null,
    amount      numeric(14,2) not null check (amount > 0),
    spent_on    date          not null,
    note        text,
    status      text          not null check (status in ('SUBMITTED', 'APPROVED', 'REJECTED', 'PAID')),
    run_id      uuid,
    created_at  timestamptz   not null
);

-- tax declarations ---------------------------------------------------------------------------------------------
create table tax_declaration (
    employee_id uuid          not null references employee (id),
    fy          text          not null,
    regime      text          not null check (regime in ('OLD', 'NEW')),
    items       jsonb         not null,
    total       numeric(14,2) not null check (total >= 0),
    updated_at  timestamptz   not null,
    primary key (employee_id, fy)
);

-- payroll ------------------------------------------------------------------------------------------------------
create table salary_structure (
    code           text        not null,
    version        integer     not null,
    effective_from date        not null,
    created_by     text        not null,
    created_at     timestamptz not null,
    primary key (code, version)
);

create table salary_component (
    structure_code    text    not null,
    structure_version integer not null,
    ordinal           integer not null,
    code              text    not null,
    label             text    not null,
    kind              text    not null check (kind in ('EARNING', 'DEDUCTION', 'INFO')),
    formula           text    not null,
    depends_on        text[]  not null,
    taxable           boolean not null,
    primary key (structure_code, structure_version, code),
    foreign key (structure_code, structure_version) references salary_structure (code, version)
);

-- day-level payroll inputs, fed by AttendanceDayClosed events; the highest version of a day wins
create table payroll_day (
    employee_id uuid         not null,
    day         date         not null,
    status      text         not null,
    payable     numeric(3,2) not null,
    version     integer      not null,
    primary key (employee_id, day)
);

create table payroll_run (
    id             uuid primary key,
    entity         text        not null,
    period         text        not null,
    status         text        not null,
    idem_key       text        not null unique,
    input_snapshot_hash text,
    tax_rules      jsonb,
    employee_count integer,
    warnings       jsonb       not null default '[]',
    created_by     text        not null,
    submitted_by   text,
    approved_by    text,
    finalized_by   text,
    created_at     timestamptz not null,
    started_at     timestamptz,
    computed_at    timestamptz,
    finalized_at   timestamptz,
    paid_at        timestamptz,
    version        bigint      not null
);
-- one active run per (entity, period); a cancelled run frees the period
create unique index payroll_run_one_per_period on payroll_run (entity, period) where status <> 'CANCELLED';

create table payroll_chunk (
    run_id       uuid    not null references payroll_run (id),
    chunk_no     integer not null,
    employee_ids uuid[]  not null,
    status       text    not null check (status in ('PENDING', 'RUNNING', 'DONE', 'FAILED')),
    attempts     integer not null,
    error        text,
    primary key (run_id, chunk_no)
);

create table payroll_input (
    run_id      uuid  not null references payroll_run (id),
    employee_id uuid  not null,
    snapshot    jsonb not null,
    hash        text  not null,
    primary key (run_id, employee_id)
);

create table payslip (
    run_id      uuid          not null references payroll_run (id),
    employee_id uuid          not null,
    lines       jsonb         not null,
    inputs      jsonb         not null,
    gross       numeric(14,2) not null,
    deductions  numeric(14,2) not null,
    net         numeric(14,2) not null,
    taxable     numeric(14,2) not null,
    tds         numeric(14,2) not null,
    pdf_key     text,
    primary key (run_id, employee_id)
);

create table arrear_line (
    run_id      uuid          not null references payroll_run (id),
    employee_id uuid          not null,
    for_period  text          not null,
    component   text          not null,
    kind        text          not null,
    amount      numeric(14,2) not null,
    primary key (run_id, employee_id, for_period, component)
);

create table payroll_lock (
    period    text primary key,
    run_id    uuid        not null,
    locked_at timestamptz not null
);

create table pdf_job (
    run_id      uuid        not null,
    employee_id uuid        not null,
    status      text        not null check (status in ('PENDING', 'DONE', 'FAILED')),
    attempts    integer     not null,
    error       text,
    updated_at  timestamptz not null,
    primary key (run_id, employee_id)
);
create index pdf_job_pending on pdf_job (updated_at) where status = 'PENDING';

create table bank_file (
    id          uuid primary key,
    run_id      uuid        not null references payroll_run (id),
    batch_ref   text        not null unique,
    status      text        not null check (status in ('ACTIVE', 'VOID')),
    object_key  text        not null,
    sha256      text        not null,
    total       numeric(14,2) not null,
    rows        integer     not null,
    created_by  text        not null,
    approved_by text,
    created_at  timestamptz not null
);
-- at most one live bank file per run: generating twice returns the same file
create unique index bank_file_one_active on bank_file (run_id) where status = 'ACTIVE';

create table bank_file_reissue (
    id           uuid primary key,
    run_id       uuid        not null references payroll_run (id),
    requested_by text        not null,
    reason       text        not null,
    status       text        not null check (status in ('PENDING', 'APPROVED')),
    approved_by  text,
    created_at   timestamptz not null
);
create unique index bank_file_reissue_one_pending on bank_file_reissue (run_id) where status = 'PENDING';
