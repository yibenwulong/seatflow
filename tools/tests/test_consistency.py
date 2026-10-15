"""Fixed data unit cases; these are not real database evidence."""
import copy
import unittest
from decimal import Decimal
from tools.consistency import inspect


def fixture(state="UNPAID"):
    return {"order": {"id": 7, "session_id": 101, "status": state,
                      "total_amount": Decimal("560.00"), "expire_at": "2099-01-01"},
            "items": [{"seat_id": x, "session_id": 101, "unit_price": Decimal("280.00")}
                      for x in (1, 2)],
            "seats": [{"seat_id": x, "session_id": 101, "status": "HELD",
                       "owner_order_id": 7, "hold_expires_at": "2099-01-01"} for x in (1, 2)],
            "payments": [], "refunds": [], "intents": []}


class ConsistencyTests(unittest.TestCase):
    def test_normal_unpaid_all_seats_held(self):
        self.assertTrue(inspect(fixture())["consistent"])

    def test_partial_seat_hold_is_violation(self):
        data = fixture()
        data["seats"][1].update(status="AVAILABLE", owner_order_id=None, hold_expires_at=None)
        self.assertIn("SEAT_OWNERSHIP", inspect(data)["violations"])

    def test_stale_owner_on_active_order_is_violation(self):
        data = fixture()
        data["seats"][0]["owner_order_id"] = 8
        self.assertFalse(inspect(data)["consistent"])

    def test_old_cancelled_order_allows_new_owner(self):
        data = fixture("CANCELLED")
        for seat in data["seats"]:
            seat["owner_order_id"] = 8
        self.assertTrue(inspect(data)["consistent"])

    def test_cancelled_order_still_owns_seats_is_violation(self):
        self.assertIn("TERMINAL_OWNERSHIP", inspect(fixture("CANCELLED"))["violations"])

    def test_refund_pending_is_visible_consistent_progress(self):
        data = self.paid("REFUNDING")
        data["refunds"] = [{"id": 9, "payment_record_id": 3, "type": "NORMAL",
                            "amount": Decimal("560.00"), "status": "PENDING"}]
        result = inspect(data)
        self.assertTrue(result["consistent"])
        self.assertEqual([9], result["funds"]["pendingRefundIds"])

    def test_unknown_refund_state_is_not_certified(self):
        data = self.paid("REFUNDING")
        data["refunds"] = [{"id": 9, "payment_record_id": 3, "type": "NORMAL",
                            "amount": Decimal("560.00"), "status": "CORRUPT"}]
        result = inspect(data)
        self.assertFalse(result["consistent"])
        self.assertIn("UNKNOWN_REFUND_STATE", result["violations"])

    def test_failed_refund_is_valid_retained_visible_progress(self):
        data = self.paid("REFUNDING")
        data["refunds"] = [{"id": 9, "payment_record_id": 3, "type": "NORMAL",
                            "amount": Decimal("560.00"), "status": "FAILED"}]
        result = inspect(data)
        self.assertTrue(result["consistent"])
        self.assertEqual([9], result["funds"]["pendingRefundIds"])
        self.assertEqual([9], result["funds"]["failedRefundIds"])

    def test_paid_order_requires_collection(self):
        data = self.paid()
        data["payments"] = []
        self.assertIn("MISSING_APPLIED_PAYMENT", inspect(data)["violations"])

    def test_late_collection_requires_durable_refund(self):
        data = fixture("CANCELLED")
        data["seats"] = []
        data["payments"] = [{"id": 3, "amount": Decimal("560.00"), "status": "COMPENSATING"}]
        self.assertIn("UNACCOUNTED_COLLECTION", inspect(data)["violations"])

    def test_refunded_requires_completed_refund_not_just_intent(self):
        data = self.paid("REFUNDED")
        data["seats"] = []
        data["payments"][0]["status"] = "REFUNDED"
        self.assertIn("MISSING_COMPLETED_NORMAL_REFUND", inspect(data)["violations"])

    def test_refunded_order_rejects_smaller_matching_collection_and_normal_refund(self):
        data = self.refunded()
        data["payments"][0]["amount"] = Decimal("1.00")
        data["refunds"][0]["amount"] = Decimal("1.00")
        self.assertIn("NORMAL_REFUND_ORDER_AMOUNT", inspect(data)["violations"])

    def test_refunded_order_requires_refunded_linked_collection_state(self):
        data = self.refunded()
        data["payments"][0]["status"] = "COMPENSATING"
        self.assertIn("REFUND_PAYMENT_STATE", inspect(data)["violations"])

    def test_completed_normal_refund_requires_refunded_order_state(self):
        data = self.refunded()
        data["order"]["status"] = "REFUNDING"
        data["payments"][0]["status"] = "APPLIED"
        for seat in data["seats"]:
            seat.update(status="SOLD", owner_order_id=7)
        self.assertIn("NORMAL_REFUND_ORDER_STATE", inspect(data)["violations"])

    def test_refunded_order_accepts_full_linked_collection_and_completed_normal(self):
        self.assertTrue(inspect(self.refunded())["consistent"])

    def test_price_sum_detects_partial_items(self):
        data = fixture()
        data["items"].pop()
        self.assertIn("ITEM_TOTAL", inspect(data)["violations"])

    def test_missing_order(self):
        self.assertEqual(["ORDER_NOT_FOUND"], inspect({"order": None})["violations"])

    def test_does_not_mutate_snapshot(self):
        data = fixture()
        before = copy.deepcopy(data)
        inspect(data)
        self.assertEqual(before, data)

    def test_unknown_order_state_is_not_certified(self):
        self.assertIn("UNKNOWN_ORDER_STATE", inspect(fixture("CORRUPT"))["violations"])

    def test_two_full_refund_tasks_for_one_receipt_is_overallocated(self):
        data = self.paid("REFUNDING")
        data["refunds"] = [{"id": n, "payment_record_id": 3, "type": kind,
                            "amount": Decimal("560.00"), "status": "PENDING"}
                           for n, kind in ((9, "NORMAL"), (10, "DUPLICATE_PAYMENT"))]
        self.assertIn("REFUND_OVERALLOCATION", inspect(data)["violations"])

    def test_unlisted_seat_still_owned_is_detected(self):
        data = fixture()
        data["seats"].append(dict(data["seats"][0], seat_id=3))
        self.assertIn("EXTRA_OWNED_SEAT", inspect(data)["violations"])

    @staticmethod
    def paid(state="PAID"):
        data = fixture(state)
        for seat in data["seats"]:
            seat.update(status="SOLD", hold_expires_at=None)
        data["payments"] = [{"id": 3, "amount": Decimal("560.00"), "status": "APPLIED"}]
        return data

    @classmethod
    def refunded(cls):
        data = cls.paid("REFUNDED")
        for seat in data["seats"]:
            seat.update(status="AVAILABLE", owner_order_id=None)
        data["payments"][0]["status"] = "REFUNDED"
        data["refunds"] = [{"id": 9, "payment_record_id": 3, "type": "NORMAL",
                            "amount": Decimal("560.00"), "status": "COMPLETED"}]
        return data
