"""
Secondary simulator match / unmatch demo.

Start the secondary simulator first (run-secondary.bat), then:

    python scripts/secondary_match_unmatch_demo.py

JPMS logs on to port 45010 as SenderCompID=JPMS, SenderSubID=677C, TargetCompID=FNRA, TargetSubID=SP.
JPMB logs on to port 45013 as SenderCompID=JPMB, SenderSubID=677E, TargetCompID=FNRA, TargetSubID=SP.

The script runs two FINRA cases on securitized products:
  1. Both sides match, then JPMS cancels. Both get SPCX and an Unmatched SPMA (573=1, 22028 starts with 121).
  2. Both sides match, then JPMB corrects the price. Both get SPCR and an Unmatched SPMA, and no new match.
"""

import socket
import sys
import threading
import time
from datetime import datetime, timedelta, timezone

SOH = "\x01"
HOST = "127.0.0.1"


class Gateway:
    def __init__(self, name, port, sender, sender_sub):
        self.name = name
        self.port = port
        self.sender = sender
        self.sender_sub = sender_sub
        self.seq = 1
        self.sock = None
        self.buf = ""
        self.messages = []
        self.lock = threading.Lock()
        self.alive = True

    def connect(self):
        self.sock = socket.create_connection((HOST, self.port), timeout=5)
        self.sock.settimeout(1.0)
        self._reader = threading.Thread(target=self._read_loop, name="read-" + self.name, daemon=True)
        self._reader.start()
        self.send("A", [("98", "0"), ("108", "30"), ("141", "Y")])
        logon = self.wait_for(lambda m: tag(m, "35") == "A", 25, "logon")
        if logon is None:
            raise SystemExit(self.name + " did not receive Logon on port " + str(self.port)
                             + ". Start the secondary simulator first: run-secondary.bat")
        print(self.name + " logged on")

    def send_trade(self, client_id, trans_type, side, contra_side, px, extra=None):
        fields = [
            ("571", client_id),
            ("487", str(trans_type)),
            ("856", "0" if trans_type == 0 else ("5" if trans_type == 2 else "6")),
            ("570", "N"),
            ("22", "1"),
            ("48", "01F0504A1"),
            ("32", "6000"),
            ("31", px),
            ("75", trade_date()),
            ("64", settl_date()),
            ("60", sending_time()),
            ("552", "2"),
            ("54", side),
            ("453", "1"),
            ("448", self.sender),
            ("447", "C"),
            ("452", "1"),
            ("54", contra_side),
            ("453", "1"),
            ("448", "JPMB" if self.sender == "JPMS" else "JPMS"),
            ("447", "C"),
            ("452", "17"),
        ]
        if extra:
            fields[1:1] = extra
        self.send("AE", fields)
        print(self.name + " sent 487=" + str(trans_type) + " 571=" + client_id + " 31=" + px)

    def send(self, msg_type, body_fields):
        fields = [
            ("35", msg_type),
            ("34", str(self.seq)),
            ("49", self.sender),
            ("50", self.sender_sub),
            ("56", "FNRA"),
            ("57", "SP"),
            ("52", sending_time()),
        ] + body_fields
        self.seq += 1
        raw = encode(fields)
        self.sock.sendall(raw.encode("ascii"))

    def wait_for(self, predicate, seconds, label):
        deadline = time.time() + seconds
        while time.time() < deadline:
            with self.lock:
                for msg in self.messages:
                    if predicate(msg):
                        return msg
            time.sleep(0.1)
        print("TIMEOUT " + self.name + " waiting for " + label)
        self.dump()
        return None

    def dump(self):
        with self.lock:
            for msg in self.messages:
                if tag(msg, "35") in ("0", "1", "A", "5"):
                    continue
                print("  " + self.name + " " + tag(msg, "1011") + " 573=" + tag(msg, "573")
                      + " 1003=" + tag(msg, "1003") + " 22028=" + tag(msg, "22028")
                      + " 572=" + tag(msg, "572"))

    def close(self):
        self.alive = False
        try:
            self.send("5", [])
        except Exception:
            pass
        try:
            self.sock.close()
        except Exception:
            pass

    def _read_loop(self):
        while self.alive:
            try:
                data = self.sock.recv(65535)
            except socket.timeout:
                continue
            except OSError:
                return
            if not data:
                return
            self.buf += data.decode("ascii", errors="ignore")
            while SOH + "10=" in self.buf or self.buf.find("10=") != -1:
                end = self.buf.find(SOH, self.buf.find("10="))
                if end < 0:
                    break
                raw = self.buf[:end + 1]
                self.buf = self.buf[end + 1:]
                if "35=" not in raw:
                    continue
                msg_type = tag(raw, "35")
                if msg_type == "1":
                    self.send("0", [("112", tag(raw, "112"))])
                with self.lock:
                    self.messages.append(raw)


def encode(fields):
    body = SOH.join(k + "=" + v for k, v in fields) + SOH
    head = "8=FIX.4.4" + SOH + "9=" + str(len(body.encode("ascii"))) + SOH + body
    checksum = sum(head.encode("ascii")) % 256
    return head + "10=" + ("%03d" % checksum) + SOH


def tag(msg, number):
    key = SOH + number + "="
    idx = msg.find(key)
    if idx < 0:
        if msg.startswith(number + "="):
            idx = -1
        else:
            return ""
    start = idx + len(key)
    end = msg.find(SOH, start)
    return msg[start:end] if end >= 0 else ""


def sending_time():
    return datetime.now(timezone.utc).strftime("%Y%m%d-%H:%M:%S")


def trade_date():
    return datetime.now(timezone.utc).strftime("%Y%m%d")


def settl_date():
    return (datetime.now(timezone.utc) + timedelta(days=1)).strftime("%Y%m%d")


def is_match(msg, status):
    return tag(msg, "35") == "AE" and tag(msg, "1011").endswith("MA") and tag(msg, "573") == status


def require(condition, text):
    print(("PASS " if condition else "FAIL ") + text)
    if not condition:
        raise SystemExit(1)


def main():
    jpms = Gateway("JPMS", 45010, "JPMS", "677C")
    jpmb = Gateway("JPMB", 45013, "JPMB", "677E")
    try:
        print("Connecting. Secondary logon is delayed about 10 seconds.")
        jpms.connect()
        jpmb.connect()

        print("\n--- Case 2: match, then JPMS cancels ---")
        jpms.send_trade("JPMS-C2", 0, "2", "1", "89")
        jpmb_allege = jpmb.wait_for(lambda m: tag(m, "1011") == "SPAL", 15, "SPAL for JPMS trade")
        require(jpmb_allege is not None, "JPMB received SPAL for the JPMS trade")
        jpms_en = jpms.wait_for(lambda m: tag(m, "1011") == "SPEN", 15, "SPEN")
        require(jpms_en is not None, "JPMS received SPEN")
        jpmb.send_trade("JPMB-C2", 0, "1", "2", "89")
        jpms_ma = jpms.wait_for(lambda m: is_match(m, "0"), 15, "matched SPMA")
        jpmb_ma = jpmb.wait_for(lambda m: is_match(m, "0"), 15, "matched SPMA")
        require(jpms_ma is not None and jpmb_ma is not None, "both gateways received 573=0")
        require(tag(jpms_ma, "22028") == tag(jpmb_ma, "22028") and tag(jpms_ma, "22028").startswith("1"),
                "matched 22028 is shared and is not the unmatched prefix: " + tag(jpms_ma, "22028"))
        require(tag(jpms_ma, "1003") == tag(jpms_en, "1003"), "JPMS match is keyed to the JPMS TradeID")
        jpmb_en = None
        with jpmb.lock:
            for msg in jpmb.messages:
                if tag(msg, "1011") == "SPEN":
                    jpmb_en = msg
        require(jpmb_en is not None and tag(jpmb_ma, "1003") == tag(jpmb_en, "1003"),
                "JPMB match is keyed to the JPMB TradeID")

        jpms.send("AE", [
            ("571", "JPMS-CX"),
            ("487", "1"),
            ("856", "6"),
            ("570", "N"),
            ("1003", tag(jpms_en, "1003")),
            ("22011", tag(jpms_en, "22011")),
            ("22", "1"),
            ("48", "01F0504A1"),
            ("32", "6000"),
            ("31", "89"),
            ("75", trade_date()),
            ("60", sending_time()),
            ("552", "1"),
            ("54", "2"),
            ("453", "1"),
            ("448", "JPMS"),
            ("447", "C"),
            ("452", "1"),
        ])
        print("JPMS sent cancel for 1003=" + tag(jpms_en, "1003"))
        jpms_cx = jpms.wait_for(lambda m: tag(m, "1011") == "SPCX", 15, "SPCX")
        jpmb_cx = jpmb.wait_for(lambda m: tag(m, "1011") == "SPCX", 15, "SPCX copy")
        require(jpms_cx is not None, "JPMS received SPCX")
        require(jpmb_cx is not None, "JPMB received the SPCX copy")
        require(tag(jpmb_cx, "572") == tag(jpmb_allege, "571"),
                "JPMB SPCX 572 is the allege TradeReportID")
        jpms_um = jpms.wait_for(lambda m: is_match(m, "1") and tag(m, "1003") == tag(jpms_en, "1003"), 15, "JPMS unmatch")
        jpmb_um = jpmb.wait_for(lambda m: is_match(m, "1") and tag(m, "1003") == tag(jpmb_en, "1003"), 15, "JPMB unmatch")
        require(jpms_um is not None and jpmb_um is not None, "both gateways received 573=1 for their own TradeID")
        require(tag(jpms_um, "22028") == tag(jpmb_um, "22028") and tag(jpms_um, "22028").startswith("121"),
                "unmatched 22028 is shared and starts with 121: " + tag(jpms_um, "22028"))
        require(tag(jpms_um, "22028") != tag(jpms_ma, "22028"), "unmatched 22028 is not the earlier match number")

        print("\n--- Case 1: match, then JPMB corrects the price ---")
        jpms.send_trade("JPMS-C1", 0, "2", "1", "89")
        jpmb.send_trade("JPMB-C1", 0, "1", "2", "89")
        jpms_ma2 = jpms.wait_for(lambda m: is_match(m, "0") and tag(m, "1003") != tag(jpms_en, "1003"), 15, "second match")
        jpmb_ma2 = jpmb.wait_for(lambda m: is_match(m, "0") and tag(m, "1003") != tag(jpmb_en, "1003"), 15, "second match")
        require(jpms_ma2 is not None and jpmb_ma2 is not None, "second trade matched on both gateways")
        jpmb_en2 = None
        with jpmb.lock:
            for msg in jpmb.messages:
                if tag(msg, "1011") == "SPEN" and tag(msg, "1003") == tag(jpmb_ma2, "1003"):
                    jpmb_en2 = msg
        require(jpmb_en2 is not None, "JPMB entry acknowledgement found for the second trade")
        jpms_al2 = None
        with jpms.lock:
            for msg in jpms.messages:
                if tag(msg, "1011") == "SPAL" and tag(msg, "1003") == tag(jpmb_en2, "1003"):
                    jpms_al2 = msg
        require(jpms_al2 is not None, "JPMS received SPAL for the JPMB trade")
        before = len(jpms.messages)
        jpmb.send("AE", [
            ("571", "JPMB-CR"),
            ("487", "2"),
            ("856", "5"),
            ("570", "N"),
            ("1003", tag(jpmb_en2, "1003")),
            ("22011", tag(jpmb_en2, "22011")),
            ("22", "1"),
            ("48", "01F0504A1"),
            ("32", "6000"),
            ("31", "91"),
            ("75", trade_date()),
            ("64", settl_date()),
            ("60", sending_time()),
            ("552", "2"),
            ("54", "1"),
            ("453", "1"),
            ("448", "JPMB"),
            ("447", "C"),
            ("452", "1"),
            ("54", "2"),
            ("453", "1"),
            ("448", "JPMS"),
            ("447", "C"),
            ("452", "17"),
        ])
        print("JPMB sent correction 31=91 for 1003=" + tag(jpmb_en2, "1003"))
        jpmb_cr = jpmb.wait_for(lambda m: tag(m, "1011") == "SPCR", 15, "SPCR")
        jpms_cr = jpms.wait_for(lambda m: tag(m, "1011") == "SPCR", 15, "SPCR copy")
        require(jpmb_cr is not None, "JPMB received SPCR with a new TradeID " + tag(jpmb_cr or "", "1003"))
        require(jpms_cr is not None, "JPMS received the SPCR copy")
        require(tag(jpms_cr, "572") == tag(jpms_al2, "571"), "JPMS SPCR 572 is the allege TradeReportID")
        require(tag(jpmb_cr, "1126") == tag(jpmb_en2, "1003"), "SPCR OrigTradeID is the original TradeID")
        jpms_um2 = jpms.wait_for(lambda m: is_match(m, "1") and tag(m, "1003") == tag(jpms_ma2, "1003"), 15, "unmatch of original")
        jpmb_um2 = jpmb.wait_for(lambda m: is_match(m, "1") and tag(m, "1003") == tag(jpmb_en2, "1003"), 15, "unmatch of original")
        require(jpms_um2 is not None and jpmb_um2 is not None, "both gateways received Unmatched for the original trade")
        require(tag(jpms_um2, "22028").startswith("121") and tag(jpms_um2, "22028") == tag(jpmb_um2, "22028"),
                "correction unmatch 22028 is shared and starts with 121")
        time.sleep(1)
        rematch = False
        with jpms.lock:
            for msg in jpms.messages[before:]:
                if is_match(msg, "0") and tag(msg, "31") == "91":
                    rematch = True
        require(not rematch, "price change does not create a new match")
        print("\nSecondary match and unmatch demo passed.")
    finally:
        jpms.close()
        jpmb.close()


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(1)
