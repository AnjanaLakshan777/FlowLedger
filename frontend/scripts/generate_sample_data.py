"""FlowLedger fictional sample data generator (LKR). Deterministic."""
import json, random, math
from datetime import date, datetime, timedelta

random.seed(42)
D = lambda s: date.fromisoformat(s)
TODAY = D("2026-10-10")
import os
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "data") + os.sep
def iso(d): return d.isoformat()
def r2(x): return round(x, 2)
def dump(name, rows):
    with open(OUT + name + ".json", "w") as f:
        json.dump(rows, f, separators=(",", ":"))
    print(name, len(rows) if isinstance(rows, list) else "")

# ---------------------------------------------------------------- daily sales
WD = {0: 1.55e6, 1: 1.45e6, 2: 1.50e6, 3: 1.60e6, 4: 1.95e6, 5: 2.30e6, 6: 1.20e6}
CASH_SHARE = 0.42
def season(d):
    if d.month == 12 and d.day >= 7: return 1.34
    if d.month == 1 and d.day <= 4: return 0.97
    return 1.0

users_fc = "Nadeesha Perera"
sales = []
d = D("2026-06-01")
while d < TODAY:  # today's sales not yet entered
    base = WD[d.weekday()] * season(d) * random.uniform(0.86, 1.14)
    cash = round(base * random.uniform(0.38, 0.46) / 10) * 10
    card = round((base - cash) / 10) * 10
    lag = 0
    roll = random.random()
    if roll < 0.08: lag = random.choice([2, 3, 4, 6])
    elif roll < 0.4: lag = 1
    ent_day = d + timedelta(days=lag)
    if ent_day > TODAY: ent_day = TODAY
    if lag == 0:
        t = datetime(ent_day.year, ent_day.month, ent_day.day, random.randint(19, 21), random.randint(0, 59))
    else:
        t = datetime(ent_day.year, ent_day.month, ent_day.day, random.randint(8, 10), random.randint(0, 59))
    by = users_fc if random.random() < 0.85 else "Ruwan Wickramasinghe"
    sales.append(dict(sales_date=iso(d), cash_sales=cash, card_sales=card, total=cash + card,
                      entered_at=t.strftime("%Y-%m-%dT%H:%M"), entered_by=by,
                      entry_type="Backdated" if lag >= 2 else "Standard",
                      note="Entered late - shop till report delayed" if lag >= 2 and random.random() < .6 else ""))
    d += timedelta(days=1)
sales.reverse()
dump("daily_sales", sales)
sales_by = {s["sales_date"]: s for s in sales}

# ------------------------------------------------------------------- cheques
PAYEES_SUP = [("Ruhunu Rice Mills (Pvt) Ltd", 1.9e6), ("Kelani Valley Foods", 1.4e6), ("Lanka Packaging Solutions", 0.85e6),
              ("Southern Plastics Ltd", 0.62e6), ("Matale Spice Growers", 0.74e6), ("Hill Country Dairy", 1.15e6),
              ("Negombo Beverages", 0.98e6), ("Island Logistics", 0.42e6), ("Colombo Household Supplies", 0.88e6)]
cheques = []
chq_no = [4410]
def add_chq(payee, cat, amount, cdate, clearing, status, notes="", account="Commercial Bank - Current"):
    chq_no[0] += random.randint(1, 3)
    days = {"Same day": 0, "1 business day": 1, "2-3 business days": 2}[clearing]
    ec = cdate
    k = 0
    while k < days or ec.weekday() >= 5:
        ec += timedelta(days=1)
        if ec.weekday() < 5: k += 1
    cheques.append(dict(cheque_no=f"{chq_no[0]:06d}", payee=payee, category=cat, amount=r2(amount),
                        cheque_date=iso(cdate), clearing=clearing, expected_clear_date=iso(ec), status=status,
                        account=account, notes=notes))

start = D("2026-08-31")
end = D("2027-01-10")
d = start
while d <= end:
    if d.weekday() in (1, 3, 4):  # supplier cheques Tue, Thu, Fri
        for p, amt in random.sample(PAYEES_SUP, {1: 3, 3: 2, 4: 1}[d.weekday()]):
            a = round(amt * 1.17 * random.uniform(0.8, 1.2) * (1.15 if d.month == 12 else 1) / 100) * 100
            add_chq(p, "Supplier Payment", a, d, "1 business day", "Issued",
                    random.choice(["", "", "Invoice batch " + str(random.randint(2100, 2999)), "30-day credit terms"]))
    if d.day == 24:
        add_chq("Staff salaries - " + d.strftime("%B"), "Salary", 9.8e6 if d.month != 12 else 10.4e6, d, "1 business day", "Issued",
                "Monthly payroll, 142 staff" + (" incl. festive advance" if d.month == 12 else ""))
    if d.day == 14:
        add_chq("EPF / ETF contribution", "Tax", 1.62e6, d, "Same day", "Issued", "Statutory contributions")
    if d.day == 19:
        add_chq("Inland Revenue Department - VAT", "Tax", round(random.uniform(2.9e6, 3.4e6), -3), d, "Same day", "Issued", "Monthly VAT return")
    if d.day == 1:
        add_chq("Maradana Property Holdings", "Other Expenses", 1.25e6, d, "1 business day", "Issued", "Warehouse rent")
    if d.day == 8:
        add_chq("Ceylon Electricity Board", "Other Expenses", round(random.uniform(4.2e5, 5.1e5), -2), d, "Same day", "Issued", "Electricity - Main store")
    d += timedelta(days=1)
# one-offs
add_chq("Ruhunu Rice Mills (Pvt) Ltd", "Supplier Payment", 8.6e6, D("2026-10-23"), "1 business day", "Issued", "Bulk paddy-season rice order - 40 t")
add_chq("Colombo Household Supplies", "Supplier Payment", 7.8e6, D("2026-12-15"), "1 business day", "Issued", "Festive season stock")
add_chq("Negombo Beverages", "Supplier Payment", 5.2e6, D("2026-12-16"), "1 business day", "Issued", "Festive season stock")
add_chq("Inland Revenue Department - Income tax", "Tax", 2.8e6, D("2026-10-27"), "Same day", "Issued", "Q3 income tax instalment")
add_chq("Lanka Fleet Services", "Other Expenses", 2.35e6, D("2026-11-24"), "2-3 business days", "Issued", "Delivery van lease - half year")

cheques.sort(key=lambda c: (c["expected_clear_date"], c["cheque_no"]))
# statuses for the past
for c in cheques:
    ec = D(c["expected_clear_date"]); cd = D(c["cheque_date"])
    if ec < TODAY - timedelta(days=0):
        c["status"] = "Cleared"
    elif cd <= TODAY and ec >= TODAY:
        c["status"] = "Presented" if cd < TODAY else "Issued"
past = [c for c in cheques if c["status"] == "Cleared"]
random.choice([c for c in past if c["category"] == "Supplier Payment" and D(c["cheque_date"]) > D("2026-09-20")]).update(
    status="Bounced", notes="Returned 06 Oct - signature differs from mandate. Re-issue required.")
for c in random.sample([c for c in past if c["category"] == "Supplier Payment"], 2):
    c.update(status="Cancelled", notes="Cancelled - replaced by bank transfer")
# outstanding: issued past date but not yet presented (supplier holding cheque)
for c in random.sample([c for c in past if c["status"] == "Cleared" and D(c["cheque_date"]) > D("2026-09-28")], 3):
    c.update(status="Issued", expected_clear_date=iso(TODAY + timedelta(days=2)), notes="Not yet presented by payee - follow up")
add_chq("Ruhunu Rice Mills (Pvt) Ltd", "Supplier Payment", 6.2e6, D("2026-10-01"), "1 business day", "Cleared", "Maha season advance")
add_chq("Kelani Valley Foods", "Supplier Payment", 3.05e6, D("2026-09-29"), "1 business day", "Cleared", "Quarterly rebate settlement")
cheques.sort(key=lambda c: (c["expected_clear_date"], c["cheque_no"]))
cheques_out = cheques
dump("cheques", cheques)

# ------------------------------------------------------------------ accounts
accounts = [
    dict(account_id="ACC-COM", bank="Commercial Bank", account_name="Main operating account", account_type="Current",
         account_masked="**** 4821", branch="Colombo 03", balance=5452300.00, balance_as_of="2026-10-10T16:42",
         sync_status="Synced", last_sync="2026-10-10T16:42", connection="Bank API (read-only)", consent_expires="2027-03-31", sync_frequency="Every 2 hours", error=""),
    dict(account_id="ACC-SMP", bank="Sampath Bank", account_name="Cash deposit account", account_type="Current",
         account_masked="**** 0937", branch="Gampaha", balance=3214880.50, balance_as_of="2026-10-10T16:40",
         sync_status="Synced", last_sync="2026-10-10T16:40", connection="Bank API (read-only)", consent_expires="2027-01-15", sync_frequency="Every 2 hours", error=""),
    dict(account_id="ACC-HNB", bank="Hatton National Bank", account_name="Reserve savings", account_type="Savings",
         account_masked="**** 3365", branch="Kandy", balance=4500000.00, balance_as_of="2026-10-10T06:00",
         sync_status="Synced", last_sync="2026-10-10T06:00", connection="Statement import (daily)", consent_expires="", sync_frequency="Daily at 06:00", error=""),
    dict(account_id="ACC-NDB", bank="NDB Bank", account_name="Card collection account", account_type="Current",
         account_masked="**** 7102", branch="Colombo 07", balance=1148650.00, balance_as_of="2026-10-08T14:10",
         sync_status="Error", last_sync="2026-10-08T14:10", connection="Bank API (read-only)", consent_expires="2026-10-08", sync_frequency="Every 2 hours",
         error="Bank consent expired on 08 Oct 2026. Renew access to resume syncing."),
]
acc_name = {a["account_id"]: f'{a["bank"]} {a["account_masked"]}' for a in accounts}

# -------------------------------------------------------------- transactions
tx = []
tid = [9000]
def T(acc, td, vd, desc, ref, amount, ttype, status, record="", conf=None, dup="", note=""):
    tid[0] += 1
    tx.append(dict(txn_id=f"TX-{tid[0]}", account_id=acc, account=acc_name[acc], txn_date=iso(td),
                   value_date=iso(vd) if vd else "", description=desc, reference=ref, amount=r2(amount), txn_type=ttype,
                   match_status=status, matched_record=record, confidence=conf, duplicate=dup, note=note))

d = D("2026-09-28")
def prev_sales_days(d):
    # sales days settled on business day d: previous day, plus weekend if Monday
    if d.weekday() == 0: return [d - timedelta(days=3), d - timedelta(days=2), d - timedelta(days=1)]
    return [d - timedelta(days=1)]
while d <= TODAY:
    if d.weekday() < 6:  # Mon-Sat banking
        for sd in prev_sales_days(d):
            s = sales_by.get(iso(sd))
            if not s: continue
            amt = s["card_sales"]
            st, rec, conf, note = "Matched", f'Card sales {sd.strftime("%d %b")}', 1.0, ""
            if d == TODAY:
                st, conf = "Pending", None
                rec, note = "", "Value date not yet reached"
            T("ACC-NDB" if d < D("2026-10-09") else "ACC-COM", d, d + timedelta(days=1) if d == TODAY else d,
              "CARD SETTLEMENT LKPAY MERCH 551203", f"STL{sd.strftime('%y%m%d')}", amt, "Card settlement", st, rec, conf, note=note)
            T("ACC-SMP", d, d, "CASH DEPOSIT BR GAMPAHA", f"CD{sd.strftime('%y%m%d')}", s["cash_sales"], "Cash deposit",
              "Matched", f'Cash sales {sd.strftime("%d %b")}', 1.0)
    for c in cheques:
        if c["expected_clear_date"] == iso(d) and c["status"] in ("Cleared", "Bounced") and d < TODAY:
            T("ACC-COM", d, d, f'CHQ {c["cheque_no"]} CLEARED', c["cheque_no"], -c["amount"], "Cheque",
              "Matched", f'Cheque {c["cheque_no"]} - {c["payee"]}', 1.0)
            if c["status"] == "Bounced":
                T("ACC-COM", d + timedelta(days=1), d + timedelta(days=1), f'CHQ {c["cheque_no"]} RETURNED - SIG DIFF', c["cheque_no"],
                  c["amount"], "Cheque return", "Review", f'Cheque {c["cheque_no"]} - {c["payee"]}', 0.6, note="Cheque returned; confirm status Bounced")
    d += timedelta(days=1)

# fees / unmatched / review / duplicates
T("ACC-COM", D("2026-09-30"), D("2026-09-30"), "SERVICE CHARGES SEP", "SC0926", -4850.00, "Bank charge", "Unmatched", note="No FlowLedger record - bank charge")
T("ACC-NDB", D("2026-10-01"), D("2026-10-01"), "MERCHANT DISCOUNT FEE SEP", "MDR0926", -612430.00, "Bank charge", "Unmatched", note="Monthly card merchant fee")
T("ACC-COM", D("2026-10-06"), D("2026-10-06"), "CEFT CR K WIJESINGHE", "CEFT88213", 185000.00, "Transfer in", "Unmatched", note="Unknown sender - not a sales receipt")
T("ACC-COM", D("2026-10-08"), D("2026-10-08"), "CEFT DR ISLAND LOGISTICS IL-2291", "IL-2291", -245000.00, "Transfer out", "Review", "Island Logistics invoice IL-2291", 0.55,
  dup="Possible duplicate", note="Same amount, reference and date as TX below")
T("ACC-COM", D("2026-10-08"), D("2026-10-08"), "CEFT DR ISLAND LOGISTICS IL-2291", "IL-2291", -245000.00, "Transfer out", "Review", "", 0.0,
  dup="Possible duplicate", note="Same amount, reference and date as TX above")
T("ACC-COM", TODAY, None, "CHQ DEPOSIT - CLEARING", "CQD10102", 320000.00, "Cheque deposit", "Pending", note="Value date not provided by bank")
T("ACC-COM", TODAY, TODAY, "SMS ALERT CHARGES", "SMS1010", -350.00, "Bank charge", "Unmatched")
# mark a card settlement as review (amount differs)
for t in tx:
    if t["txn_type"] == "Card settlement" and t["txn_date"] == "2026-10-06":
        t["amount"] = r2(t["amount"] - 18250); t["match_status"] = "Review"; t["confidence"] = 0.82
        t["note"] = "LKR 18,250 less than recorded card sales - possible chargeback"
        break
for t in tx:
    if t["txn_type"] == "Cash deposit" and t["txn_date"] == "2026-10-09":
        t["match_status"] = "Review"; t["confidence"] = 0.74; t["amount"] = r2(t["amount"] - 5000)
        t["note"] = "Deposit LKR 5,000 below entered cash sales"
        break
# fix the dup record reference
isl = [c for c in cheques if c["payee"] == "Island Logistics"]
tx.sort(key=lambda t: (t["txn_date"], t["txn_id"]), reverse=True)
# forward simulation with twice-weekly sweeps into the operating account
tx = [t for t in tx if not (t["account_id"] == "ACC-NDB" and t["txn_date"] > "2026-10-08")]
START = {"ACC-COM": 5.9e6, "ACC-SMP": 0.9e6, "ACC-NDB": 1.0e6}
bal = dict(START)
out = []
for day in sorted(set(t["txn_date"] for t in tx)):
    todays = sorted([t for t in tx if t["txn_date"] == day], key=lambda t: -t["amount"])
    cr = [t for t in todays if t["amount"] > 0]; dr = [t for t in todays if t["amount"] <= 0]
    seq = list(cr)
    dd = D(day)
    if dd.weekday() in (0, 3) and dd < TODAY:
        for src, lbl in (("ACC-NDB", "NDB 7102"), ("ACC-SMP", "SMP 0937")):
            if src == "ACC-NDB" and dd > D("2026-10-08"): continue
            pend = sum(t["amount"] for t in cr if t["account_id"] == src)
            amt = round(bal[src] + pend - 0.9e6, -5)
            if amt > 0:
                ref = f"IT{dd.strftime('%y%m%d')}{src[-1]}"
                tid[0] += 1
                seq.append(dict(txn_id=f"TX-{tid[0]}", account_id=src, account=acc_name[src], txn_date=day, value_date=day,
                     description="INTERNAL TRF TO COMB 4821", reference=ref, amount=-amt, txn_type="Internal transfer",
                     match_status="Matched", matched_record="Transfer between own accounts", confidence=1.0, duplicate="", note=""))
                tid[0] += 1
                seq.append(dict(txn_id=f"TX-{tid[0]}", account_id="ACC-COM", account=acc_name["ACC-COM"], txn_date=day, value_date=day,
                     description="INTERNAL TRF FROM " + lbl, reference=ref, amount=amt, txn_type="Internal transfer",
                     match_status="Matched", matched_record="Transfer between own accounts", confidence=1.0, duplicate="", note=""))
    seq += dr
    for t in seq:
        if t["match_status"] == "Pending": t["balance_after"] = None
        else:
            bal[t["account_id"]] += t["amount"]; t["balance_after"] = r2(bal[t["account_id"]])
        out.append(t)
tx = out
for a in accounts:
    if a["account_id"] in bal: a["balance"] = r2(bal[a["account_id"]])
others = sum(a["balance"] for a in accounts if a["account_id"] != "ACC-HNB")
print("mins", {k: min(t["balance_after"] for t in tx if t["account_id"] == k and t["balance_after"] is not None) for k in bal}, "final", bal, "others", others)
next(a for a in accounts if a["account_id"] == "ACC-HNB")["balance"] = r2(round(14315830.5 - others, -4))
tx.reverse()
dump("transactions", tx)

# reconciliation summary
recon = []
for a in accounts:
    ts = [t for t in tx if t["account_id"] == a["account_id"]]
    unm = [t for t in ts if t["match_status"] == "Unmatched"]
    rev = [t for t in ts if t["match_status"] == "Review"]
    pen = [t for t in ts if t["match_status"] == "Pending"]
    diff = -sum(t["amount"] for t in unm) - sum(t["amount"] for t in rev if t["duplicate"] and not t["matched_record"])
    for t in rev:
        if "less than" in t["note"]: diff += 18250
        if "below entered" in t["note"]: diff += 5000
    recon.append(dict(account_id=a["account_id"], account=acc_name[a["account_id"]], statement_balance=a["balance"],
                      flowledger_balance=r2(a["balance"] + diff), difference=r2(-diff),
                      matched=len([t for t in ts if t["match_status"] == "Matched"]), unmatched=len(unm), review=len(rev), pending=len(pen),
                      statement_date=a["balance_as_of"][:10], status="Not reconciled" if (unm or rev or abs(diff) > 0.005) else "Reconciled"))
dump("reconciliation", recon)
dump("accounts", accounts)

# ------------------------------------------------------------------ forecast
confirmed = sum(a["balance"] for a in accounts)
# expected sales: weekday average of last 12 weeks
hist = [s for s in sales if D(s["sales_date"]) >= TODAY - timedelta(days=84)]
avg = {}
for wd in range(7):
    xs = [s for s in hist if D(s["sales_date"]).weekday() == wd]
    avg[wd] = (sum(x["cash_sales"] for x in xs) / len(xs), sum(x["card_sales"] for x in xs) / len(xs))
fstart = D("2026-10-12")
days = []
bal_ = confirmed
var = 0.0; cum_in = 0.0
outstanding = [c for c in cheques if c["status"] in ("Issued", "Presented")]
for i in range(91):
    d = fstart + timedelta(days=i)
    cash, card = avg[d.weekday()]
    cash *= season(d); card *= season(d)
    cash = round(cash, -3); card = round(card, -3)
    cs = [c for c in outstanding if c["expected_clear_date"] == iso(d)]
    # Sunday 11 Oct and today's not-yet-presented handled: anything expected before fstart is moved to fstart
    if i == 0:
        cs += [c for c in outstanding if c["expected_clear_date"] < iso(fstart)]
    out = sum(c["amount"] for c in cs)
    opening = bal_
    bal_ = opening + cash + card - out
    var += (0.09 * (cash + card)) ** 2; cum_in += cash + card
    band = 1.28 * math.sqrt(var) + 0.012 * cum_in
    big = sorted(cs, key=lambda c: -c["amount"])[:3]
    cause = "; ".join(f'{c["payee"]} cheque {c["cheque_no"]} (LKR {c["amount"]/1e6:.2f}M)' for c in big)
    days.append(dict(date=iso(d), week=i // 7 + 1, weekday=d.strftime("%a"), opening=r2(opening), cash_in=cash, card_in=card,
                     inflow=cash + card, cheque_out=r2(out), cheques=len(cs), closing=r2(bal_), low=r2(bal_ - band), high=r2(bal_ + band),
                     top_outflows=cause))
# shortages: contiguous negative runs
shorts = []
run = []
for x in days + [dict(closing=1)]:
    if x["closing"] < 0: run.append(x)
    elif run:
        lo = min(run, key=lambda r: r["closing"])
        first = run[0]
        # cause: largest outflows on first day + preceding 3 days
        idx = days.index(first)
        window = [c for c in outstanding if days[max(0, idx - 3)]["date"] <= c["expected_clear_date"] <= first["date"]]
        window.sort(key=lambda c: -c["amount"])
        main = window[0]
        cause = f'{main["payee"]} ({main["category"]}) cheque {main["cheque_no"]} for LKR {main["amount"]:,.0f} clears {D(main["expected_clear_date"]).strftime("%a %d %b")}'
        others = sum(c["amount"] for c in window[1:])
        shorts.append(dict(shortage_id=f"SH-{len(shorts)+1}", first_date=first["date"], lowest_date=lo["date"],
                           shortage_amount=r2(-lo["closing"]), days_negative=len(run), week=first["week"],
                           main_cause=cause, other_outflows=r2(others), cheques_in_window=len(window),
                           week_end_balance=next(dd["closing"] for dd in days if dd["week"] == first["week"] and dd["weekday"] == "Sun"),
                           severity="Critical" if -lo["closing"] > 2e6 else "Warning"))
        run = []
weeks = []
for w in range(1, 14):
    ds = [x for x in days if x["week"] == w]
    lo = min(ds, key=lambda r: r["closing"])
    weeks.append(dict(week=w, week_start=ds[0]["date"], week_end=ds[-1]["date"], opening=ds[0]["opening"],
                      cash_in=r2(sum(x["cash_in"] for x in ds)), card_in=r2(sum(x["card_in"] for x in ds)),
                      inflow=r2(sum(x["inflow"] for x in ds)), cheque_out=r2(sum(x["cheque_out"] for x in ds)),
                      closing=ds[-1]["closing"], low=ds[-1]["low"], high=ds[-1]["high"],
                      lowest_balance=lo["closing"], lowest_date=lo["date"], shortage_days=sum(1 for x in ds if x["closing"] < 0)))
print("confirmed", confirmed)
for w in weeks: print(w["week"], w["week_start"], round(w["opening"]/1e6,2), round(w["inflow"]/1e6,2), round(w["cheque_out"]/1e6,2), round(w["closing"]/1e6,2), round(w["lowest_balance"]/1e6,2), w["shortage_days"])
for s in shorts: print(s)
dump("forecast_daily", days); dump("forecast_weekly", weeks); dump("shortages", shorts)

# --------------------------------------------------------- forecast vs actual
fva = []
ws = D("2026-08-03")
while ws + timedelta(days=6) < D("2026-10-05"):
    wsales = [sales_by[iso(ws + timedelta(days=k))] for k in range(7)]
    a_in = sum(s["total"] for s in wsales)
    f_in = round(sum(WD[(ws + timedelta(days=k)).weekday()] for k in range(7)) * random.uniform(0.97, 1.03), -3)
    f_out = round(random.uniform(8.6e6, 11.5e6) + (9.8e6 if any((ws + timedelta(days=k)).day == 25 for k in range(7)) else 0), -3)
    a_out = round(f_out * random.uniform(0.9, 1.08), -3)
    fva.append(dict(week_start=iso(ws), week_end=iso(ws + timedelta(days=6)), forecast_in=f_in, actual_in=r2(a_in),
                    diff_in=r2(a_in - f_in), forecast_out=f_out, actual_out=a_out, diff_out=r2(a_out - f_out),
                    net_diff=r2((a_in - f_in) - (a_out - f_out)),
                    main_reason=random.choice(["Card settlement timing", "Supplier cheque presented late", "Lower weekday footfall",
                                               "Poya day trading hours", "Cheque cleared earlier than expected", "In line with forecast"])))
    ws += timedelta(days=7)
dump("forecast_vs_actual", fva)

# ------------------------------------------------------------ import preview
imp = []
d = D("2023-01-01"); row = 2
while d <= D("2025-12-31"):
    base = WD[d.weekday()] * 0.82 * (1 + (d.year - 2023) * 0.07) * random.uniform(0.85, 1.15)
    cash = round(base * 0.45, -1); card = round(base * 0.55, -1)
    imp.append(dict(row=row, date=iso(d), cash_sales=cash, card_payments=card, status="Valid", issue=""))
    row += 1; d += timedelta(days=1)
def dupe(idx):
    r = dict(imp[idx]); r["cash_sales"] = round(r["cash_sales"] * 1.04, -1)
    imp.insert(idx + 1, r)
for i in (412, 788):
    dupe(i)
for i, r in enumerate(imp): r["row"] = i + 2
seen = {}
for r in imp:
    if r["date"] in seen:
        r["status"] = "Error"; r["issue"] = f'Duplicate date - also on row {seen[r["date"]]}'
        imp[seen[r["date"]] - 2]["status"] = "Error"; imp[seen[r["date"]] - 2]["issue"] = f'Duplicate date - also on row {r["row"]}'
    else: seen[r["date"]] = r["row"]
imp[201]["card_payments"] = -imp[201]["card_payments"]; imp[201].update(status="Error", issue="card_payments cannot be negative")
imp[530]["cash_sales"] = None; imp[530].update(status="Error", issue="cash_sales is empty")
imp[955].update(status="Warning", issue="Total 3.1x the 30-day average - check value"); imp[955]["card_payments"] *= 3.4
imp[1020].update(status="Warning", issue="Sunday with zero sales - store closed?"); imp[1020]["cash_sales"] = 0; imp[1020]["card_payments"] = 0
dump("import_rows", imp)
dump("import_history", [
    dict(import_id="IMP-0003", file_name="lotuslane_sales_2023-2025.xlsx", uploaded_at="2026-10-10T15:58", uploaded_by="Kasun Jayawardena",
         period="01 Jan 2023 - 31 Dec 2025", rows=len(imp), status="Awaiting approval", approved_by=""),
    dict(import_id="IMP-0002", file_name="sales_history_2022.csv", uploaded_at="2026-09-18T11:20", uploaded_by="Kasun Jayawardena",
         period="01 Jan 2022 - 31 Dec 2022", rows=365, status="Approved", approved_by="Nadeesha Perera"),
    dict(import_id="IMP-0001", file_name="sales_2022_draft.csv", uploaded_at="2026-09-17T16:05", uploaded_by="Kasun Jayawardena",
         period="01 Jan 2022 - 31 Dec 2022", rows=371, status="Rejected", approved_by="Nadeesha Perera"),
])

# --------------------------------------------------------------------- users
dump("users", [
    dict(user_id="U1", name="Nadeesha Perera", email="nadeesha@lotuslane.lk", role="Finance Controller", status="Active", last_active="2026-10-10T17:12", mfa="On"),
    dict(user_id="U2", name="Kasun Jayawardena", email="kasun@lotuslane.lk", role="Company Admin", status="Active", last_active="2026-10-10T16:01", mfa="On"),
    dict(user_id="U3", name="Ruwan Wickramasinghe", email="ruwan@lotuslane.lk", role="Finance Controller", status="Active", last_active="2026-10-09T18:44", mfa="On"),
    dict(user_id="U4", name="Tharindu Silva", email="tharindu@lotuslane.lk", role="Cashier", status="Active", last_active="2026-10-10T13:30", mfa="Off"),
    dict(user_id="U5", name="Ishara Fernando", email="ishara@lotuslane.lk", role="Cashier", status="Invited", last_active="", mfa="Off"),
    dict(user_id="U6", name="Dilan Rathnayake", email="dilan@lotuslane.lk", role="Cashier", status="Deactivated", last_active="2026-08-30T12:05", mfa="Off"),
])
dump("settings", [
    dict(key="company_name", section="Company", label="Company name", value="Lotus Lane Distributors (Pvt) Ltd"),
    dict(key="reg_no", section="Company", label="Business registration no.", value="PV 00123456"),
    dict(key="currency", section="Company", label="Reporting currency", value="LKR - Sri Lankan Rupee"),
    dict(key="timezone", section="Company", label="Time zone", value="Asia/Colombo (UTC+05:30)"),
    dict(key="week_start", section="Forecast", label="Forecast week", value="Monday to Sunday"),
    dict(key="horizon", section="Forecast", label="Forecast horizon", value="13 weeks"),
    dict(key="sales_method", section="Forecast", label="Expected sales method", value="Same-weekday average, last 12 weeks"),
    dict(key="min_buffer", section="Forecast", label="Alert when balance falls below", value="LKR 0 (negative balance)"),
    dict(key="match_tolerance", section="Reconciliation", label="Auto-match amount tolerance", value="Exact amount only"),
    dict(key="match_window", section="Reconciliation", label="Auto-match date window", value="+/- 2 business days"),
])

# --------------------------------------------------------------------- audit
acts = [
    ("2026-10-10T17:12", "Nadeesha Perera", "Finance Controller", "Updated", "Cheque 00" , "status: Issued -> Presented", "Payee confirmed deposit"),
]
audit = []
def A(at, user, role, action, entity, change, reason=""):
    audit.append(dict(event_id=f"EV-{5200+len(audit)}", at=at, user=user, role=role, action=action, entity=entity, change=change, reason=reason))
fcs = [c for c in cheques if c["status"] == "Presented"]
A("2026-10-10T17:12", "Nadeesha Perera", "Finance Controller", "Confirmed match", "Bank txn CD261005 - Sampath **** 0937", "Review -> Matched (Cash sales 05 Oct)", "Deposit slip checked against till report")
A("2026-10-10T16:42", "System", "System", "Bank sync", "Commercial Bank **** 4821", f"Balance updated to LKR {accounts[0]['balance']:,.2f}", "")
A("2026-10-10T16:40", "System", "System", "Bank sync", "Sampath Bank **** 0937", f"Balance updated to LKR {accounts[1]['balance']:,.2f}", "")
A("2026-10-10T15:58", "Kasun Jayawardena", "Company Admin", "Uploaded", "Historical import IMP-0003", "lotuslane_sales_2023-2025.xlsx (1,098 rows)", "Initial history for forecasting")
A("2026-10-10T14:05", "Nadeesha Perera", "Finance Controller", "Edited", "Cheque " + fcs[0]["cheque_no"] if fcs else "Cheque", "amount: LKR 1,240,000.00 -> LKR " + (f'{fcs[0]["amount"]:,.2f}' if fcs else ""), "Invoice corrected by supplier")
A("2026-10-10T11:30", "Nadeesha Perera", "Finance Controller", "Created", "Cheque - Ruhunu Rice Mills", "New cheque LKR 6,400,000.00 dated 23 Oct 2026", "Bulk rice order")
A("2026-10-10T10:12", "Nadeesha Perera", "Finance Controller", "Created", "Daily sales 08 Oct 2026", "New entry (sales date 08 Oct, entered 10 Oct)", "")
A("2026-10-09T20:45", "Nadeesha Perera", "Finance Controller", "Created", "Daily sales 09 Oct 2026", "New entry", "")
A("2026-10-09T18:44", "Ruwan Wickramasinghe", "Finance Controller", "Marked bounced", "Cheque " + next(c["cheque_no"] + " - " + c["payee"] for c in cheques if c["status"] == "Bounced"), "status: Cleared -> Bounced", "Bank return advice received 06 Oct")
A("2026-10-09T09:15", "Kasun Jayawardena", "Company Admin", "Invited user", "Ishara Fernando", "role: Cashier", "New cashier - Gampaha store")
A("2026-10-08T14:10", "System", "System", "Bank sync failed", "NDB Bank **** 7102", "Consent expired", "")
A("2026-10-08T11:02", "Nadeesha Perera", "Finance Controller", "Rejected match", "Bank txn CEFT88213", "Suggested: Daily sales 05 Oct -> Unmatched", "Not a sales receipt - personal transfer")
A("2026-10-07T15:30", "Nadeesha Perera", "Finance Controller", "Edited", "Daily sales 06 Oct 2026", "card_sales: LKR 1,102,400.00 -> LKR 1,120,650.00", "Second terminal batch missed")
A("2026-10-06T09:40", "Kasun Jayawardena", "Company Admin", "Changed setting", "Reconciliation", "match window: 1 -> 2 business days", "Weekend settlements")
A("2026-10-05T19:20", "Tharindu Silva", "Cashier", "Viewed report", "Cash-flow forecast", "Exported weekly summary (PDF)", "")
A("2026-10-04T10:05", "Nadeesha Perera", "Finance Controller", "Cancelled", "Cheque - Southern Plastics Ltd", "status: Issued -> Cancelled", "Paid by bank transfer instead")
A("2026-10-02T16:50", "Nadeesha Perera", "Finance Controller", "Created", "Daily sales 28 Sep 2026", "Backdated entry", "Till report delayed")
A("2026-09-30T12:00", "Kasun Jayawardena", "Company Admin", "Changed role", "Ruwan Wickramasinghe", "role: Cashier -> Finance Controller", "Promotion approved by CFO")
A("2026-09-29T08:30", "Kasun Jayawardena", "Company Admin", "Deactivated user", "Dilan Rathnayake", "status: Active -> Deactivated", "Left the company")
A("2026-09-18T11:40", "Nadeesha Perera", "Finance Controller", "Approved import", "Historical import IMP-0002", "365 rows added (2022)", "Checked against audited accounts")
A("2026-09-17T16:30", "Nadeesha Perera", "Finance Controller", "Rejected import", "Historical import IMP-0001", "6 duplicate dates", "Duplicate dates in draft file")
A("2026-09-15T09:00", "Kasun Jayawardena", "Company Admin", "Connected bank", "Sampath Bank **** 0937", "Bank API (read-only) consent granted", "")
dump("audit", audit)

dump("notifications", [
    dict(id="N1", at="2026-10-10T16:45", level="critical", title="Cash shortage expected " + D(shorts[0]["first_date"]).strftime("%a %d %b") if shorts else "", body="Expected balance falls below zero."),
    dict(id="N2", at="2026-10-08T14:10", level="error", title="NDB Bank **** 7102 sync failed", body="Bank consent expired. Renew access."),
    dict(id="N3", at="2026-10-10T15:58", level="info", title="Historical import awaiting your approval", body="IMP-0003 - 1,098 rows, 6 issues."),
    dict(id="N4", at="2026-10-09T18:44", level="warn", title="Cheque marked bounced", body="Re-issue or cancel the returned cheque."),
])
