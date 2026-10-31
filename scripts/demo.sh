#!/usr/bin/env bash
# End-to-end walk through one month of payroll against a running stack (scripts/up.sh).
# Run it on a fresh stack: it settles last month's payroll, and a period can only be settled once.
# Everyone here is made up; addresses use the reserved example.test domain.
set -euo pipefail
cd "$(dirname "$0")"
source ./lib.sh
export TZ=Asia/Kolkata

PERIOD=$(date -d "$(date +%Y-%m-01) -1 month" +%Y-%m)
FIRST="$PERIOD-01"
LAST=$(date -d "$FIRST +1 month -1 day" +%F)
JOINED=$(date -d "$FIRST -1 month" +%F)
MIDJOIN="$PERIOD-15"
DAY=$LAST
[[ $(date -d "$DAY" +%u) == 7 ]] && DAY=$(date -d "$DAY -1 day" +%F) # the office is closed on Sundays
at() { date -u -d "$DAY $1 +0530" +%FT%TZ; }

echo "== PayrollForge demo: payroll for $PERIOD (dates in IST)"
tokens
ensure_location

echo "== 1. Hire four people"
ASHA=$(hire "ASHA$RANDOM" "Asha Rao" "$JOINED" 1800000.00)
RAVI=$(hire "RAVI$RANDOM" "Ravi Menon" "$JOINED" 960000.00 STD "$ASHA")
MEERA=$(hire "MEERA$RANDOM" "Meera Iyer" "$MIDJOIN" 720000.00 STD "$ASHA")
KABIR=$(hire "KABIR$RANDOM" "Kabir Shah" "$JOINED" 300000.00 INTERN "$ASHA")
subject_of() { call "$HR" GET "/v1/employees/$1" | head -n -1 | jq -r .email; }
ASHA_T=$(token "$(subject_of "$ASHA")")
RAVI_T=$(token "$(subject_of "$RAVI")")
echo "  Asha (manager), Ravi and Kabir (intern) joined $JOINED; Meera joined $MIDJOIN"

echo "== 2. Attendance on $DAY"
punches=$(jq -n --arg a "$ASHA" --arg r "$RAVI" --arg i "$(at 09:12)" --arg o "$(at 18:20)" --arg d "$DAY" '{punches: [
  {deviceEventId:("in-" + $d + "-" + $a), employeeId:$a, at:$i, source:"DEVICE"},
  {deviceEventId:("out-" + $d + "-" + $a), employeeId:$a, at:$o, source:"DEVICE"},
  {deviceEventId:("in-" + $d + "-" + $r), employeeId:$r, at:$i, source:"DEVICE"},
  {deviceEventId:("out-" + $d + "-" + $r), employeeId:$r, at:$o, source:"DEVICE"}]}')
first=$(expect 200 "$DEVICE" POST /v1/attendance/punches:batch "$punches" | jq -r '[.[].outcome] | join(",")')
again=$(expect 200 "$DEVICE" POST /v1/attendance/punches:batch "$punches" | jq -r '[.[].outcome] | join(",")')
check "terminal batch accepted" "$first" "ACCEPTED,ACCEPTED,ACCEPTED,ACCEPTED"
check "the same batch resent is recognised" "$again" "DUPLICATE,DUPLICATE,DUPLICATE,DUPLICATE"
far=$(expect 200 "$RAVI_T" POST /v1/attendance/punches:batch "$(jq -n --arg r "$RAVI" --arg i "$(at 10:00)" '{punches:[
  {deviceEventId:("mobile-chennai-" + $r), employeeId:$r, at:$i, source:"MOBILE", lat:13.0827, lng:80.2707}]}')" | jq -r '.[0].outcome')
check "mobile punch from Chennai is outside the Bengaluru geofence" "$far" "REJECTED"
expect 200 "$HR" POST /v1/attendance/days:close "{\"day\":\"$DAY\"}" > /dev/null
for who in ASHA RAVI MEERA KABIR; do
  day=$(expect 200 "$HR" GET "/v1/attendance/days?employeeId=${!who}&from=$DAY&to=$DAY" | jq -r '.[0] | "\(.status) payable=\(.payable)"')
  echo "  $who: $day"
done

echo "== 3. Leave and a reimbursement, both approved by Asha"
expect 200 "$HR" POST "/v1/employees/$RAVI/leave-grants" '{"type":"CASUAL","days":2,"ref":"demo-opening"}' > /dev/null
from=$(date -d "+20 days" +%F)
while [[ $(date -d "$from" +%u) == 7 ]]; do from=$(date -d "$from +1 day" +%F); done
to=$from; for _ in 1 2; do to=$(date -d "$to +1 day" +%F); [[ $(date -d "$to" +%u) == 7 ]] && to=$(date -d "$to +1 day" +%F); done
code=$(expect 409 "$RAVI_T" POST /v1/leaves "{\"type\":\"CASUAL\",\"from\":\"$from\",\"to\":\"$to\",\"reason\":\"family visit\"}" | jq -r .code)
check "three days of casual leave against a balance of two" "$code" "insufficient-balance"
leave=$(expect 201 "$RAVI_T" POST /v1/leaves "{\"type\":\"CASUAL\",\"from\":\"$from\",\"to\":\"$from\",\"reason\":\"family visit\"}" | jq -r .leave.id)
check "Asha approves the one-day leave" "$(expect 200 "$ASHA_T" POST "/v1/leaves/$leave/approve" | jq -r .leave.status)" "APPROVED"
check "Ravi has one casual day left" "$(expect 200 "$RAVI_T" GET "/v1/employees/$RAVI/leave-balances" | jq -r '.CASUAL')" "1.00"
claim=$(expect 201 "$RAVI_T" POST /v1/reimbursements "{\"category\":\"INTERNET\",\"amount\":1499.99,\"spentOn\":\"$DAY\",\"note\":\"home broadband\"}" | jq -r .id)
expect 403 "$RAVI_T" POST "/v1/reimbursements/$claim/approve" > /dev/null
check "Asha approves Ravi's broadband claim" "$(expect 200 "$ASHA_T" POST "/v1/reimbursements/$claim/approve" | jq -r .status)" "APPROVED"

echo "== 4. Payroll for $PERIOD: maker computes, checker approves"
KEY=$(uuid)
until_eq 120 0 payroll_days_behind > /dev/null
run=$(expect 201 "$MAKER" POST /v1/payroll-runs "{\"period\":\"$PERIOD\"}" -H "Idempotency-Key: $KEY" | jq -r .id)
retry=$(expect 200 "$MAKER" POST /v1/payroll-runs "{\"period\":\"$PERIOD\"}" -H "Idempotency-Key: $KEY" | jq -r .id)
check "retrying the create with the same key returns the same run" "$retry" "$run"
expect 202 "$MAKER" POST "/v1/payroll-runs/$run/start" > /dev/null
until_eq 120 REVIEW run_status "$run" > /dev/null
echo "  computed; payslips:"
expect 200 "$MAKER" GET "/v1/payroll-runs/$run/payslips" |
  jq -r '.[] | "    \(.code)  payable \(.payableDays)/\(.periodDays) days  gross \(.gross)  deductions \(.deductions)  net \(.net)"'
ravi=$(expect 200 "$MAKER" GET "/v1/payroll-runs/$run/payslips/$RAVI")
check "Ravi's approved claim is on his payslip" "$(jq -r '.lines[] | select(.code=="REIMB") | .amount' <<< "$ravi")" "1499.99"
check "Ravi: net = gross - deductions" "$(jq -r '(.gross|tonumber) - (.deductions|tonumber) - (.net|tonumber) | fabs < 0.001' <<< "$ravi")" "true"
expect 200 "$MAKER" POST "/v1/payroll-runs/$run/submit" > /dev/null
check "the maker cannot approve their own run" "$(expect 403 "$MAKER" POST "/v1/payroll-runs/$run/approve" | jq -r .code)" "forbidden"
expect 200 "$CHECKER" POST "/v1/payroll-runs/$run/approve" > /dev/null
expect 200 "$CHECKER" POST "/v1/payroll-runs/$run/finalize" > /dev/null
check "run status" "$(run_status "$run")" "FINALIZED"
locked=$(call "$HR" POST /v1/attendance/days:close "{\"day\":\"$FIRST\"}" | tail -1)
check "closing a day in the finalized month is refused" "$locked" "409"
verify=$(expect 200 "$MAKER" POST "/v1/payroll-runs/$run:verify")
check "recomputing from the snapshots matches every payslip" "$(jq -r '.matched == .employees and .runHashOk' <<< "$verify")" "true"

echo "== 5. Payslip PDFs, the register and the bank file"
pdfs_ready() { call "$MAKER" GET "/v1/payroll-runs/$run/payslips" | head -n -1 | jq -r 'all(.pdfReady)'; }
until_eq 120 true pdfs_ready > /dev/null
curl -sf -H "Authorization: Bearer $RAVI_T" "$BASE/v1/payroll-runs/$run/payslips/$RAVI/pdf" -o /tmp/payrollforge-demo-payslip.pdf
check "Ravi downloads his own PDF" "$(head -c 4 /tmp/payrollforge-demo-payslip.pdf)" "%PDF"
rm -f /tmp/payrollforge-demo-payslip.pdf
expect 403 "$RAVI_T" GET "/v1/payroll-runs/$run/payslips/$ASHA" > /dev/null
echo "  ok   Ravi cannot open Asha's payslip"
register_total=$(curl -sf -H "Authorization: Bearer $MAKER" "$BASE/v1/payroll-runs/$run/register.csv" | awk -F, '$1=="TOTAL"{print $NF}')
bank=$(expect 200 "$MAKER" POST "/v1/payroll-runs/$run/bank-file")
again=$(expect 200 "$MAKER" POST "/v1/payroll-runs/$run/bank-file" | jq -r .batchRef)
check "generating the bank file twice gives the same batch" "$again" "$(jq -r .batchRef <<< "$bank")"
sha=$(curl -sf -H "Authorization: Bearer $MAKER" "$BASE/v1/payroll-runs/$run/bank-file/content" | sha256sum | cut -d' ' -f1)
check "bank file sha256 matches what the API recorded" "$sha" "$(jq -r .sha256 <<< "$bank")"
echo "  register total $register_total; bank file total $(jq -r .total <<< "$bank") over $(jq -r .rows <<< "$bank") rows"
expect 200 "$CHECKER" POST "/v1/payroll-runs/$run/mark-paid" "{\"batchRef\":\"$(jq -r .batchRef <<< "$bank")\"}" > /dev/null
check "run status" "$(run_status "$run")" "PAID"
check "the claim is now settled by this run" "$(expect 200 "$RAVI_T" GET "/v1/reimbursements/$claim" | jq -r .status)" "PAID"
check "Ravi sees the payslip under /v1/me/payslips" "$(expect 200 "$RAVI_T" GET /v1/me/payslips | jq -r --arg p "$PERIOD" '[.[] | select(.period==$p)] | length')" "1"
echo "== demo finished"
