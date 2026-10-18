import random
import hashlib
from datetime import datetime, timedelta, date
from xml.sax.saxutils import escape

random.seed(42)

ORG_ID = "WTORG"
COUNTRY_GEO = "SAU"      # assumes Geo exists in OFBiz
DEFAULT_CURR = "SAR"     # assumes Uom exists in OFBiz

# Use your unified statuses (must already be loaded)
STAT_ACTIVE = "P_ACT"
STAT_PAYSTR_ACTIVE = "PAYSTR_ACT"
STAT_ASG_ACTIVE = "PAY_ASG_ACT"
STAT_PERIOD_OPEN = "PER_OPEN"

# Bank file format status (from your unified status list)
BANK_FMT = "BF_CSV"

CURRENCIES = ["SAR", "USD", "EUR", "AED", "GBP", "INR", "EGP"]
NATIONALITIES = ["SAU", "EGY", "IND", "PAK", "PHL", "GBR", "USA", "ARE"]
EMPLOYMENT_TYPES = ["EMP_FULL", "EMP_PART", "EMP_CONT"]
CONTRACT_TYPES = ["CONT_PERM", "CONT_TEMP", "CONT_FIXED_12M", "CONT_FIXED_24M"]
PAYMENT_METHODS = ["PM_BANK", "PM_CASH"]  # create PaymentMethod rows
FIN_ACCOUNTS = ["FA_WT_BANK_SAR", "FA_WT_BANK_USD", "FA_WT_BANK_EUR"]  # create FinAccount rows

# IMPORTANT:
# - Derby errors showed truncation at 20 chars for some IDs (ex: EmployeeDeductionId).
# - Also showed FK errors because parent rows were not loaded before child rows.
# This script enforces:
#  1) Parent-first ordering
#  2) All generated IDs <= 20 chars (deterministic + unique)

MAX_ID = 20

def iso_dt(dt: datetime) -> str:
    return dt.strftime("%Y-%m-%d %H:%M:%S.000")

def iso_d(d: date) -> str:
    return d.strftime("%Y-%m-%d")

def emit(tag: str, attrs: dict) -> str:
    parts = [f'{k}="{escape(str(v))}"' for k, v in attrs.items() if v is not None]
    return f"    <{tag} " + " ".join(parts) + "/>"

def _hash6(s: str) -> str:
    return hashlib.sha1(s.encode("utf-8")).hexdigest()[:6]

def id20(prefix: str, *parts: str) -> str:
    """
    Build a deterministic, unique ID <= 20 chars.
    Strategy:
      - base = prefix + '_' + keyparts (sanitized)
      - if base fits -> use it
      - else -> prefix + '_' + (some tail) + '_' + hash6
    """
    p = (prefix or "").replace(" ", "").replace("-", "_")
    key = "_".join([str(x) for x in parts if x is not None])
    key = key.replace(" ", "").replace("-", "_")
    base = f"{p}_{key}" if key else p

    if len(base) <= MAX_ID:
        return base

    h = _hash6(base)

    # Keep as much human-readable tail as possible while staying <=20
    # Format: <prefix>_<tail>_<hash>
    # Reserve underscores + hash length: 1 + 1 + 6 = 8
    # So available for prefix+tail = 20 - 8 = 12
    avail = MAX_ID - 8
    if avail < 4:
        # fallback, extremely small (shouldn't happen)
        return (base[:MAX_ID-7] + "_" + h)[:MAX_ID]

    # Try keep prefix (up to 6) + tail from key
    pref_keep = min(6, len(p))
    tail_keep = max(0, avail - pref_keep)
    tail = key[-tail_keep:] if tail_keep > 0 else ""

    # Avoid leading underscore issues if tail empty
    if tail:
        out = f"{p[:pref_keep]}_{tail}_{h}"
    else:
        out = f"{p[:avail]}_{h}"

    return out[:MAX_ID]

def id_seq(prefix: str, n: int, width: int = 5) -> str:
    s = f"{prefix}{str(n).zfill(width)}"
    return s if len(s) <= MAX_ID else (s[:MAX_ID-7] + "_" + _hash6(s))[:MAX_ID]


now = datetime(2026, 2, 11, 12, 0, 0)
from_dt = datetime(2026, 1, 1, 0, 0, 0)

lines = []
lines.append('<?xml version="1.0" encoding="UTF-8"?>')
lines.append("<entity-engine-xml>")
lines.append("    <!-- ===================================================== -->")
lines.append("    <!-- Payroll POC Seed Data (One File)                      -->")
lines.append("    <!-- FIXED: parent-first ordering + IDs <= 20 chars         -->")
lines.append("    <!-- Requires unified payrollstatusdata.xml loaded FIRST    -->")
lines.append("    <!-- ===================================================== -->")
lines.append("")

# ------------------------------------------------------------
# 0) Minimal standard references (PARENTS FIRST)
# ------------------------------------------------------------
lines.append("    <!-- 0) Minimal standard refs (parents first) -->")

# RoleType: Use short roleTypeId to avoid truncation (INTERNAL_ORGANIZATION is 21 chars)
lines.append(emit("RoleType", {"roleTypeId": "INTERNAL_ORG", "description": "Internal Organization"}))

# Minimal UserLogin for audit fields used in some entities
lines.append(emit("UserLogin", {"userLoginId": "admin", "enabled": "Y"}))

# PaymentMethod + FinAccount (assumes PaymentMethodType/FinAccountType exist in base seed)
for pm in PAYMENT_METHODS:
    # paymentMethodId <= 20 already
    lines.append(emit("PaymentMethod", {"paymentMethodId": pm, "paymentMethodTypeId": "CREDIT_CARD"}))
for fa in FIN_ACCOUNTS:
    lines.append(emit("FinAccount", {"finAccountId": fa, "finAccountTypeId": "BANK_ACCOUNT", "statusId": "FNACT_ACTIVE"}))

lines.append("")

# ------------------------------------------------------------
# 1) Org Party (PARENT -> CHILD)
# ------------------------------------------------------------
lines.append("    <!-- 1) Org Party (Party -> PartyGroup -> PartyRole) -->")
lines.append(emit("Party", {"partyId": ORG_ID, "partyTypeId": "PARTY_GROUP"}))
lines.append(emit("PartyGroup", {"partyId": ORG_ID, "groupName": "WatanTech Demo Company"}))
lines.append(emit("PartyRole", {"partyId": ORG_ID, "roleTypeId": "INTERNAL_ORG"}))
lines.append("")

# ------------------------------------------------------------
# 2) Payroll Organization + FX (ensure PayrollOrganization exists before any child that FK's to it)
# ------------------------------------------------------------
lines.append("    <!-- 2) Payroll Organization (must exist before child tables FK to ORG) -->")
lines.append(emit("PayrollOrganization", {
    "organizationPartyId": ORG_ID,
    "parentOrganizationPartyId": None,
    "orgName": "WatanTech",
    "defaultCurrencyUomId": DEFAULT_CURR,
    "countryGeoId": COUNTRY_GEO,
    "isActive": "Y"
}))
lines.append("")

lines.append("    <!-- 2) Payroll Currency Rates (POC) -->")
fx_base = {"SAR": 1.0, "USD": 3.75, "EUR": 4.05, "AED": 1.02, "GBP": 4.70, "INR": 0.045, "EGP": 0.078}
for i, cur in enumerate(CURRENCIES, start=1):
    if cur == "SAR":
        continue
    lines.append(emit("PayrollCurrencyRate", {
        "payrollCurrencyRateId": id_seq("FX", i),  # <=20
        "organizationPartyId": ORG_ID,
        "fromCurrencyUomId": cur,
        "toCurrencyUomId": "SAR",
        "rateDate": iso_d(date(2026, 1, 1)),
        "exchangeRate": fx_base[cur],
        "rateTypeId": "SPOT",
        "isActive": "Y"
    }))
lines.append("")

# ------------------------------------------------------------
# 3) Policies
# ------------------------------------------------------------
lines.append("    <!-- 3) Policies -->")
lines.append(emit("OvertimePolicy", {
    "overtimePolicyId": id20("OTP", ORG_ID, "01"),  # <=20 safe
    "organizationPartyId": ORG_ID,
    "policyName": "WT Standard Overtime",
    "normalMultiplier": 1.25,
    "holidayMultiplier": 2.00,
    "weekendMultiplier": 1.50,
    "maxOvertimeHours": 40,
    "effectiveDate": iso_dt(from_dt),
    "thruDate": None,
    "statusId": STAT_ACTIVE
}))

HRSET_ID = id20("HRSET", ORG_ID, "SA01")
lines.append(emit("HrPolicySet", {
    "hrPolicySetId": HRSET_ID,
    "organizationPartyId": ORG_ID,
    "setName": "WT Saudi HR Policies",
    "countryGeoId": COUNTRY_GEO,
    "effectiveDate": iso_dt(from_dt),
    "thruDate": None,
    "statusId": STAT_ACTIVE
}))
lines.append(emit("HrPolicy", {
    "hrPolicyId": id20("HRPOL", ORG_ID, "01"),
    "hrPolicySetId": HRSET_ID,
    "policyName": "Working Hours",
    "policyTypeId": "HOURS",
    "policyContent": "Standard 5 days/week, 8 hours/day.",
    "sequenceNum": 10,
    "statusId": STAT_ACTIVE
}))
lines.append("")

# ------------------------------------------------------------
# 4) Payroll Structures
# ------------------------------------------------------------
lines.append("    <!-- 4) Payroll Structures -->")
OTP_ID = id20("OTP", ORG_ID, "01")

structures = [
    ("PSTR_SAR_M", "WT Monthly SAR", "SAR"),
    ("PSTR_USD_M", "WT Monthly USD", "USD"),
    ("PSTR_EUR_M", "WT Monthly EUR", "EUR"),
]
for ps_id, name, cur in structures:
    lines.append(emit("PayrollStructure", {
        "payrollStructureId": ps_id,  # already short
        "organizationPartyId": ORG_ID,
        "structureName": name,
        "description": f"POC structure in {cur}",
        "payFrequencyTypeId": "MONTHLY",
        "currencyUomId": cur,
        "fromDate": iso_dt(from_dt),
        "thruDate": None,
        "statusId": STAT_PAYSTR_ACTIVE,
        "overtimePolicyId": OTP_ID,
        "hrPolicySetId": HRSET_ID
    }))
lines.append("")

# ------------------------------------------------------------
# 5) Tax + SS config
# ------------------------------------------------------------
lines.append("    <!-- 5) Tax & Social Security Masters -->")
lines.append(emit("PayrollTaxType", {
    "taxTypeId": "PTX_INC",
    "taxTypeCode": "INC",
    "organizationPartyId": ORG_ID,
    "taxTypeName": "Income Tax",
    "description": "POC Income Tax",
    "isWithholding": "Y",
    "isActive": "Y",
    "statusId": STAT_ACTIVE,
    "createdByUserLoginId": "admin",
    "createdDate": iso_dt(now),
    "lastModifiedByUserLoginId": "admin",
    "lastModifiedDate": iso_dt(now)
}))
lines.append(emit("PayrollTaxType", {
    "taxTypeId": "PTX_SSC",
    "taxTypeCode": "SSC",
    "organizationPartyId": ORG_ID,
    "taxTypeName": "Social Security",
    "description": "POC Social Security",
    "isWithholding": "Y",
    "isActive": "Y",
    "statusId": STAT_ACTIVE,
    "createdByUserLoginId": "admin",
    "createdDate": iso_dt(now),
    "lastModifiedByUserLoginId": "admin",
    "lastModifiedDate": iso_dt(now)
}))

for b in range(1, 4):
    lines.append(emit("IncomeTaxBracket", {
        "taxBracketId": id20("TB", ORG_ID, str(b)),
        "organizationPartyId": ORG_ID,
        "fromAmount": (b - 1) * 10000,
        "toAmount": b * 10000,
        "taxRate": [5, 10, 15][b - 1],
        "effectiveFromDate": iso_dt(from_dt),
        "effectiveThruDate": None
    }))

lines.append(emit("SocialSecurityConfig", {
    "ssConfigId": id20("SSC", ORG_ID, "26"),
    "organizationPartyId": ORG_ID,
    "countryGeoId": COUNTRY_GEO,
    "effectiveDate": iso_dt(from_dt),
    "thruDate": None,
    "employeeRate": 0.095,
    "employerRate": 0.115,
    "maxContributionBase": 45000,
    "minContributionBase": 0,
    "currencyUomId": "SAR",
    "statusId": STAT_ACTIVE
}))
lines.append("")

# ------------------------------------------------------------
# 6) Allowance / Deduction types
# ------------------------------------------------------------
lines.append("    <!-- 6) Allowance & Deduction Types -->")
allow_types = [
    ("ALW_HOU", "Housing Allowance", "Y", 1500, "SAR"),
    ("ALW_TRA", "Transport Allowance", "Y", 600, "SAR"),
    ("ALW_EDU", "Education Allowance", "N", 800, "SAR"),
]
for aid, desc, taxable, amt, cur in allow_types:
    lines.append(emit("AllowanceType", {
        "allowanceTypeId": aid,
        "organizationPartyId": ORG_ID,
        "description": desc,
        "isTaxable": taxable,
        "calculationMethodId": "FIXED",
        "defaultAmount": amt,
        "currencyUomId": cur,
        "statusId": STAT_ACTIVE
    }))

ded_types = [
    ("DED_LATE", "Late Penalty", "N", 100, "SAR"),
    ("DED_ADV", "Advance Recovery", "N", 300, "SAR"),
]
for did, desc, pretax, amt, cur in ded_types:
    lines.append(emit("DeductionType", {
        "deductionTypeId": did,
        "organizationPartyId": ORG_ID,
        "description": desc,
        "isPreTax": pretax,
        "calculationMethodId": "FIXED",
        "defaultAmount": amt,
        "currencyUomId": cur,
        "statusId": STAT_ACTIVE
    }))
lines.append("")

# ------------------------------------------------------------
# 7) Bank Transfer Config
# ------------------------------------------------------------
lines.append("    <!-- 7) Bank Transfer Config (no batches) -->")
lines.append(emit("BankTransferConfig", {
    "bankTransferConfigId": id20("BTCFG", ORG_ID, "01"),
    "organizationPartyId": ORG_ID,
    "bankName": "WT Bank",
    "bankCode": "WTB",
    "fileFormatId": BANK_FMT,
    "headerTemplate": "HDR|{runId}|{date}",
    "detailTemplate": "DTL|{iban}|{amount}|{empId}",
    "footerTemplate": "FTR|{count}|{total}",
    "fieldSeparator": "|",
    "statusId": STAT_ACTIVE,
    "isActive": "Y"
}))
lines.append("")

# ------------------------------------------------------------
# 8) Payroll Periods
# ------------------------------------------------------------
lines.append("    <!-- 8) Payroll Period Types & Periods (no PayrollRun) -->")
lines.append(emit("PayrollPeriodType", {
    "payrollPeriodTypeId": "PT_M",
    "description": "Monthly",
    "isActive": "Y"
}))
for m in range(1, 13):
    pid = f"P260{m:02d}"  # <=20
    start = date(2026, m, 1)
    end = date(2026, m, 28) + timedelta(days=4)
    end = end - timedelta(days=end.day)
    pay = end
    lines.append(emit("PayrollPeriod", {
        "payrollPeriodId": pid,
        "organizationPartyId": ORG_ID,
        "periodName": f"2026-{m:02d}",
        "payrollPeriodTypeId": "PT_M",
        "startDate": iso_d(start),
        "endDate": iso_d(end),
        "payDate": iso_d(pay),
        "statusId": STAT_PERIOD_OPEN
    }))
lines.append("")

# ------------------------------------------------------------
# 9) Employees (Party/Person/Role) + Assignments + Profiles + Allow/Ded/Loans
#    NOTE: inside each employee we also keep parent->child ordering.
# ------------------------------------------------------------
lines.append("    <!-- 9) Employees: Party/Person/Role + Assignments + Profiles -->")

def pick_structure_for_currency(cur: str) -> str:
    if cur == "USD":
        return "PSTR_USD_M"
    if cur == "EUR":
        return "PSTR_EUR_M"
    return "PSTR_SAR_M"

for i in range(1, 161):
    emp_party = f"PAY_EMP{i:04d}"
    first = f"Emp{i:04d}"
    last = random.choice(["Saleh", "Hassan", "Ali", "Khan", "Singh", "Santos", "Smith", "Ibrahim"])

    # Employee Party -> Person -> PartyRole
    lines.append(emit("Party", {"partyId": emp_party, "partyTypeId": "PERSON"}))
    lines.append(emit("Person", {"partyId": emp_party, "firstName": first, "lastName": last}))
    lines.append(emit("PartyRole", {"partyId": emp_party, "roleTypeId": "EMPLOYEE"}))

    # choose currency and salary
    cur = random.choices(CURRENCIES, weights=[50, 15, 10, 8, 5, 7, 5], k=1)[0]
    base = random.randint(4500, 45000)
    if cur != "SAR":
        base = int(base / fx_base[cur])

    employment = random.choice(EMPLOYMENT_TYPES)
    contract = random.choice(CONTRACT_TYPES)
    pay_method = random.choice(PAYMENT_METHODS)
    fin_acct = random.choice(FIN_ACCOUNTS)

    # IDs (<=20 enforced)
    asg_id = id20("ASG", emp_party)                 # was ASG_PAY_EMP0001 (ok) but keep consistent
    taxp_id = id20("TAXP", emp_party)               # was TAXP_PAY_EMP0001 (ok)
    ps_id = pick_structure_for_currency(cur)

    # Assignment
    lines.append(emit("EmployeePayrollAssignment", {
        "assignmentId": asg_id,
        "organizationPartyId": ORG_ID,
        "partyId": emp_party,
        "payrollStructureId": ps_id,
        "employmentTypeId": employment,
        "contractTypeId": contract,
        "baseSalary": base,
        "salaryCurrencyUomId": cur,
        "paymentMethodId": pay_method,
        "finAccountId": fin_acct,
        "effectiveDate": iso_dt(from_dt),
        "thruDate": None,
        "statusId": STAT_ASG_ACTIVE,
        "comments": f"POC assignment {employment}/{contract}",
        "createdByUserLoginId": "admin",
        "lastModifiedByUserLoginId": "admin"
    }))

    # Tax Profile
    lines.append(emit("EmployeeTaxProfile", {
        "taxProfileId": taxp_id,
        "organizationPartyId": ORG_ID,
        "partyId": emp_party,
        "countryGeoId": COUNTRY_GEO,
        "taxIdNumber": f"TIN-{i:06d}",
        "taxFilingStatusId": STAT_ACTIVE,
        "dependentsCount": random.randint(0, 5),
        "taxExempt": "N",
        "exemptReason": None,
        "effectiveDate": iso_dt(from_dt),
        "thruDate": None
    }))

    # International info for ~25%
    if random.random() < 0.25:
        nat = random.choice(NATIONALITIES)
        lines.append(emit("InternationalEmployeeInfo", {
            "partyId": emp_party,
            "nationalityCountryGeoId": nat,
            "residenceCountryGeoId": COUNTRY_GEO,
            "homeCountryGeoId": nat,
            "hostCountryGeoId": COUNTRY_GEO,
            "assignmentTypeId": "INTL",
            "taxEqualizationFlag": "N",
            "visaTypeId": "WORK",
            "visaNumber": f"VISA-{i:06d}",
            "visaExpiryDate": iso_d(date(2027, random.randint(1, 12), 1)),
            "workPermitNumber": f"WP-{i:06d}",
            "workPermitExpiryDate": iso_d(date(2027, random.randint(1, 12), 15)),
            "housingAllowance": random.randint(0, 2500),
            "educationAllowance": random.randint(0, 2000),
            "transportationAllowance": random.randint(0, 1200),
            "repatriationAllowance": random.randint(0, 3000)
        }))

    # Allowances for ~60%
    if random.random() < 0.60:
        at = random.choice(allow_types)[0]
        lines.append(emit("EmployeeAllowance", {
            "employeeAllowanceId": id20("EALW", emp_party, at),   # FIX: <=20
            "organizationPartyId": ORG_ID,
            "partyId": emp_party,
            "allowanceTypeId": at,
            "amount": random.randint(300, 2500),
            "currencyUomId": "SAR",
            "effectiveDate": iso_dt(from_dt),
            "thruDate": None,
            "statusId": STAT_ACTIVE
        }))

    # Deductions for ~35%
    if random.random() < 0.35:
        dt = random.choice(ded_types)[0]
        lines.append(emit("EmployeeDeduction", {
            "employeeDeductionId": id20("EDED", emp_party, dt),   # FIX: <=20 (your error)
            "organizationPartyId": ORG_ID,
            "partyId": emp_party,
            "deductionTypeId": dt,
            "amount": random.randint(50, 600),
            "currencyUomId": "SAR",
            "effectiveDate": iso_dt(from_dt),
            "thruDate": None,
            "statusId": STAT_ACTIVE
        }))

    # Loans for ~15%
    if random.random() < 0.15:
        loan_id = id20("LN", emp_party)  # <=20
        loan_amt = random.randint(2000, 40000)
        inst = random.choice([6, 12, 18, 24])
        monthly = round(loan_amt / inst, 2)

        lines.append(emit("EmployeeLoan", {
            "loanId": loan_id,
            "organizationPartyId": ORG_ID,
            "partyId": emp_party,
            "loanTypeId": "LOAN_STD",
            "loanAmount": loan_amt,
            "currencyUomId": "SAR",
            "interestRate": 0.0,
            "totalInstallments": inst,
            "monthlyInstallment": monthly,
            "startDate": iso_dt(datetime(2026, 2, 1, 0, 0, 0)),
            "endDate": iso_dt(datetime(2026, 2, 1, 0, 0, 0) + timedelta(days=30 * inst)),
            "statusId": STAT_ACTIVE,
            "approvedByUserLoginId": "admin",
            "approvedDate": iso_dt(now)
        }))

        for k in range(1, inst + 1):
            due = datetime(2026, 2, 1, 0, 0, 0) + timedelta(days=30 * k)
            # installmentId was already <=20 for your pattern, but enforce anyway
            inst_id = id20("INST", emp_party, f"{k:02d}")
            lines.append(emit("LoanInstallment", {
                "installmentId": inst_id,
                "loanId": loan_id,
                "installmentNumber": k,
                "dueDate": iso_dt(due),
                "installmentAmount": monthly,
                "principalAmount": monthly,
                "interestAmount": 0.0,
                "statusId": "Q_NEW",   # must exist as StatusItem in your unified statuses
                "paidDate": None
            }))

lines.append("")
lines.append("</entity-engine-xml>")

out_path = "payroll_poc_onefile.xml"
with open(out_path, "w", encoding="utf-8") as f:
    f.write("\n".join(lines))

print(f"Generated: {out_path} ({len(lines)} lines)")
