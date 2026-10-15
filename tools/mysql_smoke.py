"""Opt-in real MySQL checker test. Synthetic cases live only in a disposable UUID schema."""
import json
import os
from pathlib import Path
import uuid
import subprocess
import sys
from urllib.parse import urlsplit
from tools.consistency import inspect, read_snapshot


def main():
    import pymysql
    server = urlsplit(os.environ["SEATFLOW_IT_MYSQL_URL"].removeprefix("jdbc:"))
    options = dict(host=server.hostname, port=server.port or 3306,
                   user=os.environ["SEATFLOW_IT_MYSQL_USER"],
                   password=os.environ["SEATFLOW_IT_MYSQL_PASSWORD"], autocommit=True,
                   cursorclass=pymysql.cursors.DictCursor, connect_timeout=5, read_timeout=10)
    schema = "seatflow_it_" + uuid.uuid4().hex
    connection = pymysql.connect(**options)
    results = {}
    try:
        with connection.cursor() as cursor:
            cursor.execute("SELECT VERSION() AS version")
            version = cursor.fetchone()["version"]
            cursor.execute("CREATE DATABASE " + schema)
            cursor.execute("USE " + schema)
            resources = Path(__file__).resolve().parents[1] / "src/main/resources/db"
            for source in (resources / "migration/V1__seatflow_schema.sql", resources / "demo/V1_1__local_demo.sql"):
                # These checked-in migrations have no procedural bodies or semicolons within literals.
                sql = "\n".join(line for line in source.read_text(encoding="utf-8").splitlines()
                                if not line.lstrip().startswith("--"))
                for statement in sql.split(";"):
                    if statement.strip():
                        cursor.execute(statement)
            for order_id, request in ((7, "checker-smoke"), (8, "new-owner")):
                cursor.execute("INSERT INTO ticket_order(id,user_id,session_id,request_id,request_digest,selection_mode,status,total_amount,expire_at,created_at,updated_at) "
                               "VALUES(%s,1,101,%s,'fixture','ADJACENT','UNPAID',560,'2099-01-01','2026-10-15','2026-10-15')", (order_id, request))
            cursor.execute("INSERT INTO order_item(order_id,session_id,seat_id,row_no,seat_no,price_zone,unit_price) "
                           "SELECT 7,session_id,seat_id,row_no,seat_no,price_zone,price FROM session_seat WHERE session_id=101 AND seat_id IN (1,2)")
            cursor.execute("UPDATE session_seat SET status='HELD',owner_order_id=7,hold_expires_at='2099-01-01' WHERE session_id=101 AND seat_id IN (1,2)")
        result = inspect(read_snapshot(connection, 7))
        assert result["consistent"], result["violations"]
        results["normal"] = result["violations"]
        cli_env = dict(os.environ, SEATFLOW_CHECK_MYSQL_HOST=server.hostname,
                       SEATFLOW_CHECK_MYSQL_PORT=str(server.port or 3306),
                       SEATFLOW_CHECK_MYSQL_DATABASE=schema,
                       SEATFLOW_CHECK_MYSQL_USER=options["user"],
                       SEATFLOW_CHECK_MYSQL_PASSWORD=options["password"])
        def cli(expected):
            process = subprocess.run([sys.executable, "-m", "tools.consistency", "7"],
                       env=cli_env, capture_output=True, text=True, timeout=15,
                       cwd=Path(__file__).resolve().parents[1])
            assert process.returncode == expected, process.stderr
            document = json.loads(process.stdout)
            assert document["consistent"] == (expected == 0)
            if options["password"]:
                assert options["password"] not in process.stdout
            return process.returncode
        results["normalCliExit"] = cli(0)
        with connection.cursor() as cursor:
            cursor.execute("UPDATE session_seat SET status='AVAILABLE',owner_order_id=NULL,hold_expires_at=NULL WHERE session_id=101 AND seat_id=2")
        result = inspect(read_snapshot(connection, 7))
        assert "SEAT_OWNERSHIP" in result["violations"]
        results["partialSeats"] = result["violations"]
        results["partialCliExit"] = cli(1)
        with connection.cursor() as cursor:
            cursor.execute("UPDATE session_seat SET status='HELD',owner_order_id=8,hold_expires_at='2099-01-01' WHERE session_id=101 AND seat_id=2")
        result = inspect(read_snapshot(connection, 7))
        assert "SEAT_OWNERSHIP" in result["violations"]
        results["staleOwner"] = result["violations"]
        with connection.cursor() as cursor:
            cursor.execute("UPDATE ticket_order SET status='REFUNDING' WHERE id=7")
            cursor.execute("UPDATE session_seat SET status='SOLD',owner_order_id=7,hold_expires_at=NULL WHERE session_id=101 AND seat_id IN (1,2)")
            cursor.execute("INSERT INTO payment_intent(id,order_id,provider,merchant_request_no,amount,status,created_at,updated_at) VALUES(1,7,'mock','fixture-merchant',560,'COLLECTED',NOW(),NOW())")
            cursor.execute("INSERT INTO payment_record(id,payment_intent_id,order_id,provider,gateway_transaction_id,amount,status,received_at) VALUES(3,1,7,'mock','fixture-transaction',560,'APPLIED',NOW())")
            cursor.execute("INSERT INTO refund_record(id,refund_no,order_id,payment_record_id,type,amount,status,created_at,updated_at) VALUES(9,'fixture-refund',7,3,'NORMAL',560,'PENDING',NOW(),NOW())")
        result = inspect(read_snapshot(connection, 7))
        assert result["consistent"] and result["funds"]["pendingRefundIds"] == [9]
        results["pendingRefund"] = result["funds"]["pendingRefundIds"]
        with connection.cursor() as cursor:
            cursor.execute("UPDATE ticket_order SET status='REFUNDED' WHERE id=7")
            cursor.execute("UPDATE session_seat SET status='AVAILABLE',owner_order_id=NULL,hold_expires_at=NULL WHERE session_id=101 AND seat_id IN (1,2)")
            cursor.execute("UPDATE payment_record SET status='REFUNDED',amount=1 WHERE id=3")
            cursor.execute("UPDATE refund_record SET status='COMPLETED',amount=1,gateway_succeeded_at=NOW(),local_completed_at=NOW() WHERE id=9")
        result = inspect(read_snapshot(connection, 7))
        assert "NORMAL_REFUND_ORDER_AMOUNT" in result["violations"]
        results["underfundedTerminal"] = result["violations"]
        results["underfundedTerminalCliExit"] = cli(1)
        with connection.cursor() as cursor:
            cursor.execute("UPDATE payment_record SET amount=560 WHERE id=3")
            cursor.execute("UPDATE refund_record SET amount=560 WHERE id=9")
        result = inspect(read_snapshot(connection, 7))
        assert result["consistent"], result["violations"]
        results["fullRefundTerminalCliExit"] = cli(0)
        with connection.cursor() as cursor:
            cursor.execute("START TRANSACTION READ ONLY")
            try:
                cursor.execute("UPDATE ticket_order SET total_amount=0 WHERE id=7")
                raise AssertionError("Read-only transaction allowed a business update")
            except pymysql.err.OperationalError as error:
                assert error.args[0] == 1792, error.args[0]
                results["readOnlyWriteRejected"] = error.args[0]
            finally:
                connection.rollback()
        print(json.dumps({"source": "real MySQL, isolated induced SQL fixtures; not HTTP business evidence",
                          "version": version, "results": results}, indent=2))
    finally:
        connection.rollback()
        with connection.cursor() as cursor:
            cursor.execute("DROP DATABASE IF EXISTS " + schema)
        connection.close()


if __name__ == "__main__":
    main()
