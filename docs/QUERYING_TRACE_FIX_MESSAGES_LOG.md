# Querying TRACE_FIX_MESSAGES_LOG

This note explains how to interpret and write queries against `TRACE_FIX_MESSAGES_LOG`, and how to verify sequence behavior (including why the simulator sent Logon with 34=1 and whether it sent ResendRequest).

---

## 1. Direction: sendercompid = who sent the message (tag 49)

The trigger sets **sendercompid** from FIX tag **49** in the raw message. So each row reflects the **actual** sender of that message.

For the configured session (**acceptor** simulator):

| Config (session) | FIX meaning | In the log |
|------------------|-------------|------------|
| SenderCompID=FNRA, TargetCompID=JPMS | Simulator (acceptor) sends as 49=FNRA, 56=JPMS | **sendercompid = 'FNRA'** = **outgoing** (simulator → gateway) |
| | Gateway (initiator) sends as 49=JPMS, 56=FNRA | **sendercompid = 'JPMS'** = **incoming** (gateway → simulator) |

So:

- **`sendercompid = 'FNRA'`** → messages **sent by the simulator** (outgoing).
- **`sendercompid = 'JPMS'`** → messages **sent by the gateway** (incoming).

---

## 2. Your query

```sql
SELECT id, time, MessageType, msgseqnum, simulator_instance, text
FROM dbo.TRACE_FIX_MESSAGES_LOG
WHERE sendercompid = 'FNRA'
ORDER BY time DESC;
```

**Assessment:**

- **Correct** for listing only **outgoing** messages (what the simulator sent).
- **Limitation:** You do **not** see incoming messages (gateway), so you cannot see the full conversation or the **incoming** Logon that triggered our Logon response.
- **Quotes:** In SQL Server, `"dbo"."TRACE_FIX_MESSAGES_LOG"` is valid; `dbo.TRACE_FIX_MESSAGES_LOG` (no quotes) is typical and fine.

To see **both** sides in time order (e.g. from id 416 to latest), use a time window or id range without filtering by sender, then use `sendercompid` to interpret direction (see section 5).

---

## 3. Why at 2026-03-06 03:26:52.747 “we” sent Logon with msgseqnum = 1

Rows with **sendercompid = 'FNRA'** are **outgoing**, so the Logon with **msgseqnum = 1** at that time is the **simulator sending** a Logon response with 34=1 to the gateway.

The simulator sends Logon with **34=1** when its **next sender sequence** at that moment is 1. That happens in these cases (see `WizFixApplication.fromAdmin` Logon handling and `SessionSequenceFromDB`):

1. **Fresh day**  
   No row in `TRACE_FIX_SESSIONS` for this session with **creation_time** on the **current date** (in `SessionDateZone`, default America/New_York). Then the code sets next sender to 1 and logs: *“Logon: fresh day (no session row in DB for current date); first message to initiator will be 34=1.”*

2. **DB had outgoing_seqnum = 1**  
   Session row existed for today and `outgoing_seqnum` was 1 (e.g. new session or previous run reset/restart).

3. **Initiator sent 789=1**  
   Gateway sent Logon with **NextExpectedMsgSeqNum(789)=1**. The simulator uses that and sends its next message with 34=1.

4. **ResetSequenceOnStart=Y**  
   If used at startup, all sessions in `TRACE_FIX_SESSIONS` are set to `incoming_seqnum=1, outgoing_seqnum=1`; next Logon response can then be 34=1.

So: **“We sent Logon = 1”** at that time because the simulator’s **outgoing** sequence was 1 when it built the Logon response (fresh day, DB state, or 789=1).

---

## 4. Is the simulator sending ResendRequest?

**ResendRequest** is message type **35=2**. It is sent **by the acceptor (simulator)** when the engine detects a gap (incoming MsgSeqNum &gt; expected). So ResendRequests appear as **outgoing** rows with **sendercompid = 'FNRA'**.

**Query: simulator-sent ResendRequests (e.g. from id 416 onward):**

```sql
SELECT id, time, MessageType, msgseqnum, simulator_instance, text
FROM dbo.TRACE_FIX_MESSAGES_LOG
WHERE sendercompid = 'FNRA'
  AND (MessageType = 'ResendRequest' OR MessageTypeTag = '2')
  AND id >= 416
ORDER BY time;
```

- If this returns rows → the simulator **did** send ResendRequest in that range.
- ResendRequest body contains **BeginSeqNo(7)** and **EndSeqNo(16)** (or see the raw `text` for 7= and 16=).

**Config:** The project has **SendRedundantResendRequests=Y** and **RefreshOnLogon=Y**, so the engine may send (and optionally repeat) ResendRequest when it detects a gap; see `docs/RESENDREQUEST_AND_SEQUENCE_GAPS.md`.

---

## 5. Analyzing from id = 416 to latest (both sides)

To see the full flow (incoming and outgoing) and correlate Logon, ResendRequest, Logout, etc., query by id range (or time) and **do not** filter by sender. Use **sendercompid** in the result to tell direction.

**Example: all messages from id 416 to latest, in time order:**

```sql
SELECT id, time,
       sendercompid,
       MessageType,
       msgseqnum,
       simulator_instance,
       LEFT(text, 200) AS text_preview
FROM dbo.TRACE_FIX_MESSAGES_LOG
WHERE id >= 416
ORDER BY time ASC, id ASC;
```

- **sendercompid = 'FNRA'** → simulator sent it (outgoing).
- **sendercompid = 'JPMS'** → gateway sent it (incoming).

From that you can see:

- Whether an **incoming** Logon (JPMS) with a given 34 preceded our **outgoing** Logon (FNRA) with 34=1.
- Whether any **outgoing** ResendRequest (FNRA, MessageType ResendRequest) appears after a gap.
- Sequence progression on both sides (msgseqnum and order).

---

## 6. Optional: same window, only key admin types

To focus on Logon, Logout, ResendRequest and sequence in the same window:

```sql
SELECT id, time, sendercompid, MessageType, msgseqnum, simulator_instance
FROM dbo.TRACE_FIX_MESSAGES_LOG
WHERE id >= 416
  AND MessageType IN ('Logon', 'Logout', 'ResendRequest', 'Heartbeat')
ORDER BY time ASC, id ASC;
```

Use this together with the full-message query above to confirm why the simulator sent Logon with 34=1 and whether it sent ResendRequest in the 416→latest range.
