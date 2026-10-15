"""Exercise the real local/test HTTP API; prints business results, never bearer tokens."""
import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid


def run(base, username, password, timeout=45):
    token = None

    def call(method, path, body=None):
        headers = {"Accept": "application/json"}
        if token:
            headers["Authorization"] = "Bearer " + token
        payload = None
        if body is not None:
            headers["Content-Type"] = "application/json"
            payload = json.dumps(body).encode()
        request = urllib.request.Request(base.rstrip("/") + "/api/v1" + path,
                                         data=payload, headers=headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=10) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            raise RuntimeError(f"HTTP {error.code}: {method} {path}") from None

    login = call("POST", "/auth/login", {"username": username, "password": password})
    token = login["token"]
    shows = call("GET", "/shows")
    if not shows:
        raise RuntimeError("No demo show; use a fresh local profile database")
    sessions = call("GET", f"/shows/{shows[0]['id']}/sessions")
    if not sessions:
        raise RuntimeError("No demo session")
    session_id = sessions[0]["id"]
    seat_map = call("GET", f"/sessions/{session_id}/seats")
    order = call("POST", "/orders", {"requestId": "demo-" + str(uuid.uuid4()),
                 "sessionId": session_id, "mode": "ADJACENT", "count": 2})
    order_id = order["id"]
    selected = sorted((s for s in seat_map["seats"]
                       if s["seatId"] in {i["seatId"] for i in order["items"]}),
                      key=lambda seat: seat["position"])
    if len(selected) != 2 or any(selected[0][k] != selected[1][k]
                               for k in ("rowNo", "blockId", "priceZone")) \
            or selected[1]["position"] != selected[0]["position"] + 1:
        raise RuntimeError("Returned order failed the two adjacent seats invariant")
    payment = call("POST", f"/orders/{order_id}/payments")
    call("POST", f"/dev/payments/{payment['id']}/complete")
    paid = call("GET", f"/orders/{order_id}")
    if paid["status"] != "PAID":
        raise RuntimeError("Mock completion did not produce PAID")
    refund = call("POST", f"/orders/{order_id}/refunds")
    deadline = time.monotonic() + timeout
    while True:
        refunds = call("GET", f"/orders/{order_id}/refunds")
        current = next(r for r in refunds if r["id"] == refund["id"])
        if current["status"] == "COMPLETED":
            break
        if current["status"] == "FAILED" or time.monotonic() >= deadline:
            raise RuntimeError("Refund did not complete; check refund worker and persisted error")
        time.sleep(0.25)  # bounded observation only; never a concurrency correctness test
    final = call("GET", f"/orders/{order_id}")
    if final["status"] != "REFUNDED":
        raise RuntimeError("Completed normal refund did not produce REFUNDED")
    return {"source": "actual HTTP API", "sessionId": session_id,
            "orderId": order_id, "seats": [s["seatId"] for s in selected],
            "paidStatus": paid["status"], "refundStatus": current["status"],
            "finalStatus": final["status"]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://localhost:8080")
    args = parser.parse_args()
    try:
        print(json.dumps(run(args.base_url, os.getenv("SEATFLOW_DEMO_USER", "demo"),
                         os.getenv("SEATFLOW_DEMO_PASSWORD", "demo-seatflow")), indent=2))
        return 0
    except Exception as error:
        # No exception repr: HTTP libraries can include URLs and credentials.
        message = str(error) if isinstance(error, RuntimeError) else "Demo dependency or response failed"
        print(json.dumps({"error": "DEMO_FAILED", "message": message}), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
