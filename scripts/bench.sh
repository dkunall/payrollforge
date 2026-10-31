#!/usr/bin/env bash
# Rough single-box timings against a running stack. Prints a markdown report (and writes it to $OUT if set).
# Run after demo.sh on the same stack: it settles the current month for N synthetic employees.
set -euo pipefail
OUT=${OUT:+$(realpath -m "$OUT")}
cd "$(dirname "$0")"
source ./lib.sh
export TZ=Asia/Kolkata
N=${N:-300}
BATCH=${BATCH:-100}
ms() { echo $(( $(date +%s%N) / 1000000 )); }
tmp=$(mktemp -d /tmp/payrollforge-bench.XXXX)
trap 'rm -rf "$tmp"' EXIT
touch "$tmp/punch_ms" "$tmp/close_ms"

tokens
ensure_location
PERIOD=$(date +%Y-%m)
echo "hiring $N synthetic employees..." >&2
JOINED=$(date -d "$(date +%Y-%m-01) -2 months" +%F)
for ((i = 0; i < N; i++)); do hire "B$RANDOM$i" "Bench Person $i" "$JOINED" "$((600000 + (i % 20) * 50000)).00" >> "$tmp/ids"; done

# Working days this month before today, at most five; attendance in a settled month can't change.
days=()
for back in 1 2 3 4 5 6 7; do
  d=$(date -d "-$back days" +%F)
  [[ $d < "$PERIOD-01" ]] && break
  [[ $(date -d "$d" +%u) == 7 ]] || days+=("$d")
  ((${#days[@]} == 5)) && break
done

punches=0 punch_ms=0
for d in "${days[@]}"; do
  jq -R --arg d "$d" --arg i "$(date -u -d "$d 09:05 +0530" +%FT%TZ)" --arg o "$(date -u -d "$d 18:10 +0530" +%FT%TZ)" \
    '. as $e | [{deviceEventId:("b-in-" + $d + "-" + $e), employeeId:$e, at:$i, source:"DEVICE"},
                {deviceEventId:("b-out-" + $d + "-" + $e), employeeId:$e, at:$o, source:"DEVICE"}]' "$tmp/ids" |
    jq -s 'add' > "$tmp/all.json"
  total=$(jq length "$tmp/all.json")
  for ((off = 0; off < total; off += BATCH)); do
    jq -c --argjson o "$off" --argjson n "$BATCH" '{punches: .[$o:$o+$n]}' "$tmp/all.json" > "$tmp/batch.json"
    t0=$(ms)
    expect 200 "$DEVICE" POST /v1/attendance/punches:batch "@$tmp/batch.json" > /dev/null
    t=$(($(ms) - t0)); echo "$t" >> "$tmp/punch_ms"; punch_ms=$((punch_ms + t))
  done
  punches=$((punches + total))
  t0=$(ms); expect 200 "$HR" POST /v1/attendance/days:close "{\"day\":\"$d\"}" > /dev/null; echo $(($(ms) - t0)) >> "$tmp/close_ms"
done

t0=$(ms); until_eq 300 0 payroll_days_behind > /dev/null; catchup_ms=$(($(ms) - t0))

run=$(expect 201 "$MAKER" POST /v1/payroll-runs "{\"period\":\"$PERIOD\"}" -H "Idempotency-Key: $(uuid)" | jq -r .id)
t0=$(ms); expect 202 "$MAKER" POST "/v1/payroll-runs/$run/start" > /dev/null
until_eq 600 REVIEW run_status "$run" > /dev/null; compute_ms=$(($(ms) - t0))
slips=$(expect 200 "$MAKER" GET "/v1/payroll-runs/$run/payslips" | jq length)
t0=$(ms); verify=$(expect 200 "$MAKER" POST "/v1/payroll-runs/$run:verify"); verify_ms=$(($(ms) - t0))
[[ $(jq -r '.matched == .employees' <<< "$verify") == true ]] || { echo "verify mismatch" >&2; exit 1; }
expect 200 "$MAKER" POST "/v1/payroll-runs/$run/submit" > /dev/null
expect 200 "$CHECKER" POST "/v1/payroll-runs/$run/approve" > /dev/null
t0=$(ms); expect 200 "$CHECKER" POST "/v1/payroll-runs/$run/finalize" > /dev/null; finalize_ms=$(($(ms) - t0))
pdfs_ready() { call "$MAKER" GET "/v1/payroll-runs/$run/payslips" | head -n -1 | jq -r 'all(.pdfReady)'; }
until_eq 900 true pdfs_ready > /dev/null; pdf_ms=$(($(ms) - t0))
t0=$(ms); bank=$(expect 200 "$MAKER" POST "/v1/payroll-runs/$run/bank-file"); bank_ms=$(($(ms) - t0))
expect 200 "$CHECKER" POST "/v1/payroll-runs/$run/mark-paid" "{\"batchRef\":\"$(jq -r .batchRef <<< "$bank")\"}" > /dev/null

report=$(cat <<MD
# PayrollForge bench, $(date '+%Y-%m-%d %H:%M IST')

One run of \`scripts/bench.sh\` (N=$N new employees, batches of $BATCH punches) against the compose stack on one machine
($(nproc) CPUs, $(free -g | awk '/Mem:/{print $2}') GiB RAM; app, Postgres, Kafka, RustFS and the bench client all share it).
Numbers are wall-clock from the client, one sample each unless a percentile is shown; treat them as a smoke-level
indication, not capacity figures.

| What | Result |
|---|---|
| Punches ingested | $punches in ${#days[@]} day(s), $(wc -l < "$tmp/punch_ms") batches |
| Punch batch latency p50 / p95 | $(percentile "$tmp/punch_ms" 50) ms / $(percentile "$tmp/punch_ms" 95) ms |
| Punch throughput (sequential batches) | $(( punches * 1000 / (punch_ms > 0 ? punch_ms : 1) )) punches/s |
| Day close, all employees, p50 | $(percentile "$tmp/close_ms" 50) ms |
| Wait for payroll's copy of closed days to catch up after the last close | $catchup_ms ms |
| Payroll compute for $slips payslips (start to REVIEW, polled every 0.5 s) | $compute_ms ms |
| Verify (recompute every payslip from snapshots) | $verify_ms ms |
| Finalize call | $finalize_ms ms |
| All $slips payslip PDFs stored after finalize (polled every 0.5 s) | $pdf_ms ms |
| Bank file generation | $bank_ms ms |
MD
)
[[ ${#days[@]} == 0 ]] && report+=$'\n\nNo working day earlier this month yet, so no punches were sent this time.'
echo "$report"
if [[ -n ${OUT:-} ]]; then echo "$report" > "$OUT"; fi
exit 0
