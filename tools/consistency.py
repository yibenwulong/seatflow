"""Inspect the current deployment only; never repairs or rewrites business data."""
import argparse
import datetime as dt
from decimal import Decimal
import json
import os
import sys


def inspect(snapshot):
    order = snapshot["order"]
    if order is None:
        return {"consistent": False, "violations": ["ORDER_NOT_FOUND"], "snapshot": snapshot}
    errors = set()
    items, seats = snapshot["items"], snapshot["seats"]
    payments, refunds = snapshot["payments"], snapshot["refunds"]
    status, order_id = order["status"], order["id"]
    if status not in {"UNPAID", "PAID", "CANCELLED", "REFUNDING", "REFUNDED"}:
        errors.add("UNKNOWN_ORDER_STATE")
    item_ids = {i["seat_id"] for i in items}
    seat_map = {(s["session_id"], s["seat_id"]): s for s in seats}
    if not 1 <= len(items) <= 4 or len(item_ids) != len(items):
        errors.add("ITEM_COUNT")
    if sum((i["unit_price"] for i in items), Decimal(0)) != order["total_amount"]:
        errors.add("ITEM_TOTAL")
    for item in items:
        if item["session_id"] != order["session_id"]:
            errors.add("ITEM_SESSION")
        if status in {"UNPAID", "PAID", "REFUNDING"}:
            seat = seat_map.get((item["session_id"], item["seat_id"]))
            expected = "HELD" if status == "UNPAID" else "SOLD"
            if not seat or seat["owner_order_id"] != order_id or seat["status"] != expected:
                errors.add("SEAT_OWNERSHIP")
            elif expected == "HELD" and seat["hold_expires_at"] != order["expire_at"]:
                errors.add("HOLD_EXPIRY")
    for seat in seats:
        if seat["owner_order_id"] == order_id:
            if status in {"CANCELLED", "REFUNDED"}:
                errors.add("TERMINAL_OWNERSHIP")
            if seat["seat_id"] not in item_ids or seat["session_id"] != order["session_id"]:
                errors.add("EXTRA_OWNED_SEAT")
    applied = [p for p in payments if p["status"] == "APPLIED"]
    if status in {"PAID", "REFUNDING"} and (
            len(applied) != 1 or applied[0]["amount"] != order["total_amount"]):
        errors.add("MISSING_APPLIED_PAYMENT")
    for payment in payments:
        matching = [r for r in refunds if r["payment_record_id"] == payment["id"]]
        if sum((r["amount"] for r in matching), Decimal(0)) > payment["amount"]:
            errors.add("REFUND_OVERALLOCATION")
        covered = any(r["amount"] == payment["amount"] for r in matching)
        if payment["status"] == "COMPENSATING" and not covered:
            errors.add("UNACCOUNTED_COLLECTION")
        if payment["status"] == "REFUNDED" and not any(
                r["status"] == "COMPLETED" and r["amount"] == payment["amount"] for r in matching):
            errors.add("UNACCOUNTED_COLLECTION")
        if payment["status"] == "APPLIED" and status not in {"PAID", "REFUNDING"}:
            errors.add("UNACCOUNTED_COLLECTION")
        if payment["status"] not in {"APPLIED", "COMPENSATING", "REFUNDED"}:
            errors.add("UNKNOWN_PAYMENT_STATE")
    normal = [r for r in refunds if r["type"] == "NORMAL"]
    if status == "REFUNDING" and not normal:
        errors.add("MISSING_NORMAL_REFUND")
    if status == "REFUNDED" and not any(r["status"] == "COMPLETED" for r in normal):
        errors.add("MISSING_COMPLETED_NORMAL_REFUND")
    receipts = {p["id"]: p for p in payments}
    for refund in refunds:
        if refund["status"] not in {"PENDING", "PROCESSING", "GATEWAY_SUCCEEDED", "COMPLETED", "FAILED"}:
            errors.add("UNKNOWN_REFUND_STATE")
        payment = receipts.get(refund["payment_record_id"])
        if not payment or refund["amount"] != payment["amount"]:
            errors.add("REFUND_PAYMENT_MISMATCH")
        if refund["status"] == "COMPLETED" and (not payment or payment["status"] != "REFUNDED"):
            errors.add("REFUND_PAYMENT_STATE")
        if refund["type"] == "NORMAL":
            if refund["amount"] != order["total_amount"] or (
                    not payment or payment["amount"] != order["total_amount"]):
                errors.add("NORMAL_REFUND_ORDER_AMOUNT")
            expected = "REFUNDED" if refund["status"] == "COMPLETED" else "REFUNDING"
            if status != expected:
                errors.add("NORMAL_REFUND_ORDER_STATE")
    return {"consistent": not errors, "violations": sorted(errors),
            "funds": {"collected": sum((p["amount"] for p in payments), Decimal(0)),
                      "completedRefunds": sum((r["amount"] for r in refunds if r["status"] == "COMPLETED"), Decimal(0)),
                      "pendingRefundIds": [r["id"] for r in refunds if r["status"] != "COMPLETED"],
                      "failedRefundIds": [r["id"] for r in refunds if r["status"] == "FAILED"]},
            "snapshot": snapshot}


def read_snapshot(connection, order_id):
    """One nonlocking InnoDB consistent snapshot, independent of global defaults."""
    try:
        with connection.cursor() as cursor:
            cursor.execute("SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ")
            cursor.execute("START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY")
            cursor.execute("SELECT * FROM ticket_order WHERE id=%s", (order_id,))
            order = cursor.fetchone()
            snapshot = {"order": order}
            if order is None:
                return snapshot
            for key, table in (("items", "order_item"), ("intents", "payment_intent"),
                               ("payments", "payment_record"), ("refunds", "refund_record")):
                cursor.execute(f"SELECT * FROM {table} WHERE order_id=%s ORDER BY id", (order_id,))
                snapshot[key] = cursor.fetchall()
            # Includes unexpected owned rows as well as historical item seats now owned by others.
            cursor.execute("SELECT s.* FROM session_seat s WHERE s.owner_order_id=%s OR EXISTS "
                           "(SELECT 1 FROM order_item i WHERE i.order_id=%s AND "
                           "i.session_id=s.session_id AND i.seat_id=s.seat_id) ORDER BY s.id",
                           (order_id, order_id))
            snapshot["seats"] = cursor.fetchall()
            return snapshot
    finally:
        connection.rollback()


def json_value(value):
    if isinstance(value, Decimal):
        return format(value, "f")
    if isinstance(value, dt.datetime):
        return value.replace(tzinfo=dt.timezone.utc).isoformat().replace("+00:00", "Z")
    raise TypeError(type(value).__name__)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("order_id", type=int)
    args = parser.parse_args()
    try:
        import pymysql
        # Deliberately separate from JDBC URL parsing; every field must identify this deployment.
        connection = pymysql.connect(host=os.environ["SEATFLOW_CHECK_MYSQL_HOST"],
                    port=int(os.environ["SEATFLOW_CHECK_MYSQL_PORT"]),
                    user=os.environ["SEATFLOW_CHECK_MYSQL_USER"],
                    password=os.environ["SEATFLOW_CHECK_MYSQL_PASSWORD"],
                    database=os.environ["SEATFLOW_CHECK_MYSQL_DATABASE"],
                    cursorclass=pymysql.cursors.DictCursor, autocommit=True,
                    connect_timeout=5, read_timeout=10, charset="utf8mb4")
        try:
            result = inspect(read_snapshot(connection, args.order_id))
        finally:
            connection.close()
        print(json.dumps(result, default=json_value, indent=2))
        return 0 if result["consistent"] else 1
    except (ImportError, KeyError, ValueError):
        print(json.dumps({"error": "CHECK_CONFIGURATION", "message": "Install tools/requirements.txt and set all SEATFLOW_CHECK_MYSQL_* fields"}), file=sys.stderr)
        return 2
    except Exception:
        # Driver errors may contain the username, address or connection options.
        print(json.dumps({"error": "CHECK_DATABASE", "message": "Snapshot unavailable; inspect database connectivity and SELECT grants"}), file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
