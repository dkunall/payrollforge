#!/usr/bin/env python3
"""Independent oracle for the golden payslips.

This does not parse formulas. Each salary structure used by the Java golden test is written out here again by
hand in plain Python with decimal.Decimal, following the documented rules (exact + - *, division to 10 places
HALF_EVEN, round() HALF_UP, earnings and deductions rounded to paise as computed). Run it to regenerate
golden-payslips.json; the Java test asserts every line matches to the paisa.
"""
import json
from decimal import Decimal as D, ROUND_HALF_UP, ROUND_HALF_EVEN, ROUND_CEILING, getcontext

getcontext().prec = 50


def div(a, b):
    return (a / b).quantize(D("1e-10"), rounding=ROUND_HALF_EVEN)


def rnd(x, n=0):
    return x.quantize(D(1).scaleb(-n), rounding=ROUND_HALF_UP)


def money(x):
    return x.quantize(D("0.01"), rounding=ROUND_HALF_UP)


def ceil(x):
    return x.quantize(D(1), rounding=ROUND_CEILING)


# Illustrative tax tables (same numbers as the Java test fixture, written independently).
TAX = {
    "NEW": dict(std=D(75000), cap=D(0), cess=D("0.04"), slabs=[
        (D(0), D(400000), D(0)), (D(400000), D(800000), D("0.05")), (D(800000), D(1200000), D("0.10")),
        (D(1200000), D(1600000), D("0.15")), (D(1600000), D(2000000), D("0.20")),
        (D(2000000), D(2400000), D("0.25")), (D(2400000), None, D("0.30"))]),
    "OLD": dict(std=D(50000), cap=D(150000), cess=D("0.04"), slabs=[
        (D(0), D(250000), D(0)), (D(250000), D(500000), D("0.05")), (D(500000), D(1000000), D("0.20")),
        (D(1000000), None, D("0.30"))]),
}


def annual_tax(regime, declared, income):
    t = TAX[regime]
    taxable = max(D(0), income - t["std"] - min(max(declared, D(0)), t["cap"]))
    tax = D(0)
    for lo, hi, rate in t["slabs"]:
        if taxable <= lo:
            break
        top = taxable if hi is None else min(taxable, hi)
        tax += (top - lo) * rate
    tax += tax * t["cess"]
    return rnd(tax, 0)


def std(i, regime, declared):
    """STD: basic 40% of CTC, HRA half of basic, PF on basic capped at 15k, special balances to CTC."""
    factor = div(i["PAYABLE_DAYS"], i["PERIOD_DAYS"])
    basic = money(rnd(i["CTC_MONTHLY"] * D("0.40") * factor, 0))
    hra = money(rnd(basic * D("0.50"), 0))
    pf_er = rnd(min(basic, D(15000)) * D("0.12"), 0)
    special = money(max(D(0), rnd(i["CTC_MONTHLY"] * factor, 0) - basic - hra - pf_er))
    encash = money(rnd(div(i["ENCASH_DAYS"] * i["CTC_MONTHLY"] * D("0.40"), D(30)), 0))
    arrears = money(i["ARREARS"])
    reimb = money(i["REIMBURSEMENT"])
    earnings = {"BASIC": basic, "HRA": hra, "SPECIAL": special, "LEAVE_ENCASH": encash, "ARREARS_PAY": arrears,
                "REIMB": reimb}
    gross = sum(earnings.values())
    taxable = basic + hra + special + encash + arrears
    pf = money(rnd(min(basic, D(15000)) * D("0.12"), 0))
    esi = money(ceil(gross * D("0.0075")) if gross <= D(21000) else D(0))
    pt = money(D(200) if gross > D(15000) else D(0))
    arr_ded = money(i["ARREARS_DEDUCTIONS"])
    recurring = basic + hra + special
    projected = i["YTD_TAXABLE"] + taxable + recurring * (i["MONTHS_LEFT_IN_FY"] - 1)
    tds = money(max(D(0), rnd(div(annual_tax(regime, declared, projected) - i["YTD_TDS"], i["MONTHS_LEFT_IN_FY"]), 0)))
    deductions = {"PF": pf, "ESI": esi, "PT": pt, "ARREARS_DED": arr_ded, "TDS": tds}
    return earnings, deductions, {"TDS"}


def intern(i, regime, declared):
    """INTERN: stipend pro-rated to the paisa, reimbursements, TDS on the stipend only."""
    stipend = money(rnd(div(i["CTC_MONTHLY"] * i["PAYABLE_DAYS"], i["PERIOD_DAYS"]), 2))
    reimb = money(i["REIMBURSEMENT"])
    arrears = money(i["ARREARS"])
    earnings = {"STIPEND": stipend, "ARREARS_PAY": arrears, "REIMB": reimb}
    taxable = stipend + arrears
    projected = i["YTD_TAXABLE"] + taxable + stipend * (i["MONTHS_LEFT_IN_FY"] - 1)
    tds = money(max(D(0), rnd(div(annual_tax(regime, declared, projected) - i["YTD_TDS"], i["MONTHS_LEFT_IN_FY"]), 0)))
    return earnings, {"TDS": tds}, {"TDS"}


STRUCTURES = {"STD": std, "INTERN": intern}


def compute(structure, inputs, regime, declared):
    e, d, tax = STRUCTURES[structure](inputs, regime, declared)
    return e, d, tax


def run(case):
    inputs = {k: D(v) for k, v in case["inputs"].items()}
    arr_e, arr_d = D(0), D(0)
    breakdown = []
    for item in case.get("arrears", []):
        ii = {k: D(v) for k, v in item["inputs"].items()}
        e, d, tax = compute(item["structure"], ii, case["regime"], D(case["declared"]))
        for kind, lines in (("EARNING", e), ("DEDUCTION", d)):
            for code, amt in lines.items():
                if code in tax:
                    continue
                delta = money(amt - D(item["baseline"].get(code, "0")))
                if delta != 0:
                    breakdown.append({"period": item["period"], "component": code, "kind": kind, "amount": str(delta)})
                    if kind == "EARNING":
                        arr_e += delta
                    else:
                        arr_d += delta
    inputs["ARREARS"] = money(arr_e)
    inputs["ARREARS_DEDUCTIONS"] = money(arr_d)
    e, d, _ = compute(case["structure"], inputs, case["regime"], D(case["declared"]))
    gross = sum(e.values())
    ded = sum(d.values())
    out = dict(case)
    out["expected"] = {"lines": {k: str(v) for k, v in {**e, **d}.items()}, "gross": str(money(gross)),
                       "deductions": str(money(ded)), "net": str(money(gross - ded)), "arrears": breakdown}
    return out


def base_inputs(**kw):
    i = {"CTC_ANNUAL": "1200000", "CTC_MONTHLY": "100000.00", "PERIOD_DAYS": "30", "PAYABLE_DAYS": "30",
         "LOP_DAYS": "0", "ENCASH_DAYS": "0", "REIMBURSEMENT": "0", "MONTHS_LEFT_IN_FY": "12", "YTD_TAXABLE": "0",
         "YTD_TDS": "0"}
    i.update({k: str(v) for k, v in kw.items()})
    return i


def baseline_of(structure, inputs, regime, declared):
    ii = {k: D(v) for k, v in inputs.items()}
    ii.setdefault("ARREARS", D(0))
    ii.setdefault("ARREARS_DEDUCTIONS", D(0))
    e, d, _ = compute(structure, ii, regime, D(declared))
    return {k: str(v) for k, v in {**e, **d}.items()}, e, d


april = base_inputs(ARREARS="0", ARREARS_DEDUCTIONS="0")
april_base, april_e, april_d = baseline_of("STD", april, "NEW", "0")
april_taxable = sum(v for k, v in april_e.items() if k != "REIMB")
april_retro = dict(april, CTC_ANNUAL="1320000", CTC_MONTHLY="110000.00")

CASES = [
    {"name": "std-full-month-new-regime", "structure": "STD", "regime": "NEW", "declared": "0",
     "inputs": base_inputs()},
    {"name": "std-two-lop-days", "structure": "STD", "regime": "NEW", "declared": "0",
     "inputs": base_inputs(PAYABLE_DAYS=28, LOP_DAYS=2)},
    {"name": "std-old-regime-with-declarations", "structure": "STD", "regime": "OLD", "declared": "150000",
     "inputs": base_inputs(CTC_ANNUAL=600000, CTC_MONTHLY="50000.00", PERIOD_DAYS=31, PAYABLE_DAYS=31)},
    {"name": "std-esi-band", "structure": "STD", "regime": "NEW", "declared": "0",
     "inputs": base_inputs(CTC_ANNUAL=240000, CTC_MONTHLY="20000.00", PERIOD_DAYS=31, PAYABLE_DAYS="30.5",
                           LOP_DAYS="0.5")},
    {"name": "std-retro-raise-arrears", "structure": "STD", "regime": "NEW", "declared": "0",
     "inputs": base_inputs(CTC_ANNUAL=1320000, CTC_MONTHLY="110000.00", PERIOD_DAYS=31, PAYABLE_DAYS=31,
                           MONTHS_LEFT_IN_FY=11, YTD_TAXABLE=str(april_taxable),
                           YTD_TDS=april_base["TDS"]),
     "arrears": [{"period": "2026-04", "structure": "STD",
                  "inputs": {**april_retro}, "baseline": april_base}]},
    {"name": "std-encashment-and-reimbursement-mid-year", "structure": "STD", "regime": "OLD", "declared": "80000",
     "inputs": base_inputs(CTC_ANNUAL=1800000, CTC_MONTHLY="150000.00", PERIOD_DAYS=31, PAYABLE_DAYS=31,
                           ENCASH_DAYS=5, REIMBURSEMENT="3500.50", MONTHS_LEFT_IN_FY=6,
                           YTD_TAXABLE="891000", YTD_TDS="98000")},
    {"name": "std-mid-month-raise-prorated", "structure": "STD", "regime": "NEW", "declared": "0",
     "inputs": base_inputs(CTC_ANNUAL=1500000, CTC_MONTHLY="117741.94", PERIOD_DAYS=31, PAYABLE_DAYS=31,
                           MONTHS_LEFT_IN_FY=10)},
    {"name": "std-march-true-up-never-negative", "structure": "STD", "regime": "NEW", "declared": "0",
     "inputs": base_inputs(MONTHS_LEFT_IN_FY=1, PERIOD_DAYS=31, PAYABLE_DAYS=31, YTD_TAXABLE="1100000",
                           YTD_TDS="90000")},
    {"name": "intern-lop-31-day-month", "structure": "INTERN", "regime": "NEW", "declared": "0",
     "inputs": base_inputs(CTC_ANNUAL=420000, CTC_MONTHLY="35000.00", PERIOD_DAYS=31, PAYABLE_DAYS=28,
                           LOP_DAYS=3, REIMBURSEMENT=1200, MONTHS_LEFT_IN_FY=9)},
    {"name": "intern-high-stipend-new-regime", "structure": "INTERN", "regime": "NEW", "declared": "0",
     "inputs": base_inputs(CTC_ANNUAL=1080000, CTC_MONTHLY="90000.00", PERIOD_DAYS=30, PAYABLE_DAYS="29.5",
                           LOP_DAYS="0.5", MONTHS_LEFT_IN_FY=12)},
]

if __name__ == "__main__":
    with open(__file__.replace("oracle.py", "golden-payslips.json"), "w") as f:
        json.dump([run(c) for c in CASES], f, indent=2)
        f.write("\n")
