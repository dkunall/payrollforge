# Shared helpers for demo.sh and bench.sh. Source, don't run.
BASE=${BASE:-http://127.0.0.1:8880}
OAUTH=${OAUTH:-http://127.0.0.1:8881}
HR_SUB=${HR_SUB:-hr@payrollforge.example.test}
MAKER_SUB=${MAKER_SUB:-maker@payrollforge.example.test}
CHECKER_SUB=${CHECKER_SUB:-checker@payrollforge.example.test}
DEVICE_SUB=${DEVICE_SUB:-terminal-blr@payrollforge.example.test}

# The app validates the issuer as http://oauth:8080/payrollforge (its in-network name), and mock-oauth2-server
# derives the issuer from the Host header. The client_id becomes the token subject.
token() {
  curl -sf -H "Host: oauth:8080" -d grant_type=client_credentials -d "client_id=$1" -d client_secret=unused \
    -d scope=payrollforge "$OAUTH/payrollforge/token" | jq -r .access_token
}

# call TOKEN METHOD PATH [BODY] [EXTRA_CURL_ARGS...]; prints the body, then the status on its own line.
call() {
  local tok=$1 method=$2 path=$3 body=${4:-}
  shift 4 2> /dev/null || shift $#
  curl -s -w '\n%{http_code}' -X "$method" "$BASE$path" ${tok:+-H "Authorization: Bearer $tok"} \
    -H 'Content-Type: application/json' ${body:+-d "$body"} "$@"
}

# expect STATUS TOKEN METHOD PATH [BODY] [EXTRA...]; prints the body or fails loudly (never prints the token).
expect() {
  local want=$1 out status
  shift
  out=$(call "$@")
  status=${out##*$'\n'}
  if [[ $status != "$want" ]]; then
    echo "FAIL: $2 $3 -> $status (wanted $want): ${out%$'\n'*}" >&2
    exit 1
  fi
  printf '%s' "${out%$'\n'*}"
}

check() {
  if [[ $2 == "$3" ]]; then echo "  ok   $1"; else echo "  FAIL $1: got '$2', wanted '$3'" >&2; exit 1; fi
}

# until_eq SECONDS WANT CMD...; reruns CMD until it prints WANT, or fails after SECONDS.
until_eq() {
  local limit=$1 want=$2 got=""
  shift 2
  for ((i = 0; i < limit * 2; i++)); do
    got=$("$@" 2> /dev/null || true)
    [[ $got == "$want" ]] && { echo "$got"; return 0; }
    sleep 0.5
  done
  echo "timed out after ${limit}s; last value '$got', wanted '$want'" >&2
  return 1
}

run_status() { call "$MAKER" GET "/v1/payroll-runs/$1" | head -n -1 | jq -r .run.status; }

uuid() { cat /proc/sys/kernel/random/uuid; }

percentile() {
  [[ -s $1 ]] || { echo n/a; return; }
  sort -n "$1" | awk -v p="$2" '{a[NR]=$1} END {i=int((p/100)*NR+0.999); if (i<1) i=1; print a[i]}'
}

# ensure_location: the Bengaluru office with Sunday off.
ensure_location() {
  local out status
  out=$(call "$HR" POST /v1/locations '{"code":"BLR1","name":"Bengaluru office","timezone":"Asia/Kolkata",
    "lat":12.9716,"lng":77.5946,"radiusMetres":250,"weeklyOff":["SUNDAY"]}')
  status=${out##*$'\n'}
  [[ $status == 201 || $status == 409 ]] || { echo "FAIL: create location -> $status" >&2; exit 1; }
}

# hire CODE NAME JOINED CTC [STRUCTURE] [MANAGER_ID]; prints the employee id. Synthetic people only.
hire() {
  local n=$((RANDOM % 9000 + 1000))
  expect 201 "$HR" POST /v1/employees "$(jq -n --arg c "$1" --arg n "$2" --arg j "$3" --arg ctc "$4" \
    --arg s "${5:-STD}" --arg m "${6:-}" --arg acct "5000$n$((RANDOM % 9000 + 1000))" --arg pan "PFXDE${n}Z" '{
      code:$c, name:$n, email:(($c|ascii_downcase) + "@payrollforge.example.test"),
      subject:(($c|ascii_downcase) + "@payrollforge.example.test"), joinedOn:$j,
      bankAccount:$acct, bankIfsc:"SYNT0000042", pan:$pan, grade:"L2", dept:"ENG", location:"BLR1",
      ctcAnnual:($ctc|tonumber), structureCode:$s} + (if $m == "" then {} else {managerId:$m} end)')" | jq -r .id
}

tokens() {
  HR=$(token "$HR_SUB")
  MAKER=$(token "$MAKER_SUB")
  CHECKER=$(token "$CHECKER_SUB")
  DEVICE=$(token "$DEVICE_SUB")
}

# Payroll reads closed days from its own Kafka-fed copy, and approval sends a run back to DRAFT if that copy moved
# after the run started. Scripts wait for it to catch up first; peeking at the database is a demo/bench shortcut.
payroll_days_behind() {
  docker compose -p payrollforge exec -T db psql -U payrollforge -d payrollforge -tAc \
    "select count(*) from attendance_day a left join payroll_day p using (employee_id, day)
     where p.version is distinct from a.version"
}
