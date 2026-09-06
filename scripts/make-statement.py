#!/usr/bin/env python3
"""Generate an invented Fidelity-shaped statement for the restore drill.

Invented data only: no real account, no real holding. The point is volume, so
the ledger the drill backs up and restores has enough rows that a recovery time
means something. Deterministic, so two runs produce byte-identical input.

    python3 scripts/make-statement.py 6000 [price-shift] > history.csv
"""
import sys

TRADES = int(sys.argv[1]) if len(sys.argv) > 1 else 6000
# A second argument shifts the price series, so two runs produce statements that
# are different rows rather than the same rows re-presented, which the ledger
# would deduplicate on its idempotency key.
SHIFT = int(sys.argv[2]) if len(sys.argv) > 2 else 0
SYMBOLS = ["ACME", "BOLT", "CRUX", "DYNE", "EMBER"]

rows = ["Run Date,Account,Account Number,Action,Symbol,Description,Type,Price ($),"
        "Quantity,Commission ($),Fees ($),Accrued Interest ($),Amount ($),Settlement Date"]


def day(n):
    # 2020-01-01 plus n days, walked by hand so no calendar library is involved.
    y, m, d = 2020, 1, 1
    for _ in range(n):
        d += 1
        if d > [31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31][m - 1]:
            d, m = 1, m + 1
            if m > 12:
                m, y = 1, y + 1
    return f"{m:02d}/{d:02d}/{y}"


rows.append(f"{day(0)},Individual,DRILL,ELECTRONIC FUNDS TRANSFER RECEIVED (Cash),,"
            f"No Description,Cash,,,,,,50000000,{day(0)}")

held = {s: 0 for s in SYMBOLS}
for i in range(TRADES):
    sym = SYMBOLS[i % len(SYMBOLS)]
    when = day(1 + i // 4)
    price = 20 + (i * 7 + SHIFT) % 480
    # Sell only what an earlier buy actually opened, so every disposal has a lot.
    if i % 5 == 4 and held[sym] >= 3:
        qty = 3
        held[sym] -= qty
        rows.append(f"{when},Individual,DRILL,YOU SOLD {sym} CORP ({sym}) (Cash),{sym},"
                    f"{sym} CORP,Cash,{price}.00,-{qty},,,,{price * qty}.00,{when}")
    else:
        qty = 10
        held[sym] += qty
        rows.append(f"{when},Individual,DRILL,YOU BOUGHT {sym} CORP ({sym}) (Cash),{sym},"
                    f"{sym} CORP,Cash,{price}.00,{qty},,,,-{price * qty}.00,{when}")
    if i % 50 == 0:
        rows.append(f"{when},Individual,DRILL,DIVIDEND RECEIVED ({sym}),{sym},{sym} CORP,"
                    f"Cash,,,,,,{12 + i % 9}.00,{when}")
    if i % 97 == 0:
        rows.append(f"{when},Individual,DRILL,FEE CHARGED,,ACCOUNT FEE,Cash,,,,,,-2.50,{when}")

print("\n".join(rows))
