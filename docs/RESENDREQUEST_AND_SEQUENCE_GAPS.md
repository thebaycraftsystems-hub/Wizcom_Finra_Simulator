# ResendRequest and "MsgSeqNum too high" / "Already sent ResendRequest"

## What you see

1. **"Already sent ResendRequest FROM: 1 TO: 1. Not sending another."**  
   QuickFIX/J (the engine) sent a ResendRequest for the missing range (e.g. message 1 only) and will not send another ResendRequest for that same gap.

2. **"MsgSeqNum too high, expecting 1 but received 4"**  
   The simulator expects the next incoming message to have MsgSeqNum = 1, but the initiator (e.g. JPMS) sent a message with MsgSeqNum = 4 (e.g. a Heartbeat).

## Why both happen together

- The **acceptor** (simulator) keeps an **expected incoming sequence number** (e.g. 1). It only advances this when it **receives and accepts** messages in order.
- When a message arrives with a **higher** seq num (e.g. 4), QuickFIX/J treats that as a **gap**: messages 1, 2, 3 are missing.
- The engine sends **one** ResendRequest for that gap (e.g. BeginSeqNo=1, EndSeqNo=3, or in your log FROM: 1 TO: 1 meaning "resend message 1 only"). By design it **does not send another** ResendRequest for the same gap ("Not sending another") to avoid flooding the initiator.
- The **initiator** is supposed to **resend** the requested messages (with PossDupFlag=Y) and then continue. If it does **not** resend (e.g. ignores ResendRequest, or the ResendRequest was lost), it will keep sending **new** messages (e.g. Heartbeat with seq 4).
- The simulator never receives message 1 (and 2, 3), so it **never advances** its expected seq num. Every time it gets another message with seq 4 (or 5, 6…), it logs again: **"MsgSeqNum too high, expecting 1 but received 4"**.

So: **"Already sent ResendRequest … Not sending another"** and **"MsgSeqNum too high, expecting 1 but received 4"** are consistent: one ResendRequest was sent, the gap was not filled by the initiator, and the simulator keeps rejecting later messages because it is still waiting for message 1.

### 3b. "MsgSeqNum too low, expecting N but received N-1" and Logon then disconnect

**What you see in TRACE_FIX_EVENT_LOG:** Accepting session → Refreshing message/state store at logon → Received logon → Responding to Logon request with tag **789=N** → then **MsgSeqNum too low, expecting N but received N-1** → Disconnecting (and often a reconnect loop).

**Why:** QuickFIX/J builds the Logon response **before** our `fromAdmin` can change the store. With **RefreshOnLogon=Y**, the engine loads state from DB (e.g. next expected = 30), then computes **789 = getNextTargetMsgSeqNum() + 1** and sends 789=30. The initiator sent Logon with MsgSeqNum=28, so their **next** message will be 29. We then reject that message: "expecting 30 but received 29".

**Fix (simulator):** In `fromAdmin(Logon)` we run **before** the engine builds the response (we are called from inside `verify()`). We set **setNextTargetMsgSeqNum(logonMsgSeqNum)** (e.g. 28), not M+1. The engine then reads 28, adds 1, and sends **789=29**. The engine later increments the store to 29. So we send 789=29 and accept the initiator's next message (29). This stops the "expecting 30 but received 29" loop.

**Initiator:** Should send the next message after Logon with MsgSeqNum = value in our 789 (i.e. M+1). If they send M again, we disconnect.

## What to do

### 1. Initiator (JPMS/gateway) must fill the gap

The correct fix is on the **initiator** side:

- On receiving a ResendRequest (msg type 2), it must **resend** the requested range (BeginSeqNo–EndSeqNo) with **PossDupFlag=Y** and **OrigSendingTime** set.
- Only after that should it send new messages (e.g. Heartbeat with the next seq num). If it sends seq 4 without having resent 1–3, the acceptor will keep expecting 1 and log "MsgSeqNum too high".

So: ensure the gateway responds to ResendRequest and that network/firewall does not drop the ResendRequest or the resent messages.

### 2. Simulator: allow redundant ResendRequests (workaround)

If the first ResendRequest is lost or the initiator does not respond, QuickFIX/J will not send another one by default. You can allow **redundant** ResendRequests so the simulator can try again:

In **\[default]** (or the relevant session) in both `quickfixj-server.cfg` and `quickfixj-server-secondary.cfg` add:

```ini
# Allow sending another ResendRequest for the same gap if the first one didn't result in a fill (e.g. lost or initiator didn't respond).
SendRedundantResendRequests=Y
```

Then restart the simulator. You may still see "MsgSeqNum too high" until the initiator actually resends the missing messages; with this setting, the simulator will at least send another ResendRequest (e.g. when the next "high" message arrives) instead of only logging "Not sending another".

### 3. After Primary (or Secondary) restart: "expecting 1" and ResendRequest(1,8)

**What you see:** Primary shuts down (e.g. last message was seq 8). After Primary restarts, the initiator reconnects and sends Logon with seq 9. The simulator sends **ResendRequest FROM: 1 TO: 8** and then **"MsgSeqNum too high, expecting 1 but received 10"**. You know the DB already has the correct sequence numbers (e.g. `TRACE_FIX_SESSIONS.incoming_seqnum = 9`), so the simulator **should** expect 9, not 1.

**Why:** Without **RefreshOnLogon=Y**, the acceptor does not reload session state from the store (DB) when it receives a Logon. So it can end up using a newly created in-memory session with seq 1, or stale state, instead of the persisted state (incoming_seqnum=9). So it expects 1, sees Logon(9), sends ResendRequest(1,8), and then keeps rejecting later messages (e.g. Heartbeat 10) with "expecting 1 but received 10".

**Fix:** Set **RefreshOnLogon=Y** in `[default]` (already set in the project's `quickfixj-server.cfg` and `quickfixj-server-secondary.cfg`). QuickFIX/J will then **reload session state from the database when a Logon is received**. After Primary restart, when the initiator sends Logon(9), the simulator will load from `TRACE_FIX_SESSIONS` and expect 9, so it will **not** send ResendRequest(1,8) and will accept the Logon and subsequent messages.

If you still see **"MsgSeqNum too high, expecting 1 but received 2"** (or similar) when you believe sequences are matched (e.g. after simulator restart while initiator sends Logon with seq 2), verify the following (aligned with [QuickFIX/J 2.3.0 Session](https://www.quickfixj.org/javadoc/2.3.0/quickfix/Session.html)):

1. **RefreshOnLogon=Y** in `[default]` (so the engine reloads session state from the store when a Logon is received).
2. **UseJdbcStore=Y** and the JDBC store schema is valid. Check logs for "Falling back to file store"; if the simulator falls back to file store, the file store is per-process and empty after restart, so you get seq 1.
3. **Session row exists** in `TRACE_FIX_SESSIONS` with the **correct session key**. The key is from the acceptor's perspective: `beginstring`, `sendercompid`/`sendersubid`/`senderlocid` = our IDs (e.g. FNRA, SP, ""), `targetcompid`/`targetsubid`/`targetlocid` = initiator IDs (e.g. JPMS, 44B1, ""), `session_qualifier` (often ""). If the row is missing or the key does not match, the engine creates a new session with expected seq 1.
4. **incoming_seqnum** in that row must equal the **MsgSeqNum the initiator will send** on the next Logon. Example: initiator will send Logon with 34=2 → row must have `incoming_seqnum = 2`. If the simulator restarted and the previous run had already received message 1, the row should have been updated to `incoming_seqnum = 2`; if the process was killed before persist, the row may still be 1.

**Optional workaround when DB has wrong value:** If you know the initiator will send Logon with MsgSeqNum = N (e.g. 2), you can set the DB before they connect:

```sql
UPDATE TRACE_FIX_SESSIONS
SET incoming_seqnum = 2  -- use the seq num the initiator will send in Logon
WHERE beginstring = 'FIX.4.4'
  AND sendercompid = 'FNRA' AND sendersubid = 'SP' AND senderlocid = ''
  AND targetcompid = 'JPMS' AND targetsubid = '44B1' AND targetlocid = ''
  AND session_qualifier = '';
```

Then restart the simulator (or leave it running; RefreshOnLogon loads on next Logon). After that, when the initiator sends Logon(2), the engine will load expected = 2 and accept, so you will **not** see "MsgSeqNum too high, expecting 1 but received 2".

**Config:** The project sets **EnableNextExpectedMsgSeqNum=Y** so Logon can carry NextExpectedMsgSeqNum(789) for sequence sync when both sides support it.

**QuickFIX/J 789 semantics (acceptor):** The engine sends our Logon response with 789 = `getNextTargetMsgSeqNum() + 1`, then increments the store. So in `fromAdmin(Logon)` we set `setNextTargetMsgSeqNum(logonMsgSeqNum)` (the sequence of the Logon we received); the engine then sends 789 = logonMsgSeqNum+1 and later increments the store, so we accept the initiator's next message (logonMsgSeqNum+1) and the "MsgSeqNum too low" loop is avoided.

**On receiving Logon: fetch max sequence from DB (current date only) and check if sequence numbers are missing:** When **UseJdbcStore=Y**, the simulator on each **Logon** (in `fromAdmin`) fetches the session row from `TRACE_FIX_SESSIONS` (incoming_seqnum, outgoing_seqnum, creation_time). It uses the row **only when creation_time is on the current date** (in the configured session date time zone, default America/New_York), so max sequence is always **as of that day**. It then: (1) uses the **max** of (Logon MsgSeqNum+1) and DB incoming_seqnum as the next expected incoming sequence, and sets it via [Session.setNextTargetMsgSeqNum](https://www.quickfixj.org/javadoc/2.3.0/quickfix/Session.html#setNextTargetMsgSeqNum-int-); (2) sets next sender sequence from DB via [Session.setNextSenderMsgSeqNum](https://www.quickfixj.org/javadoc/2.3.0/quickfix/Session.html#setNextSenderMsgSeqNum-int-) so the store/DB remains the source of truth; (3) if the initiator sent a higher MsgSeqNum than we had in DB (logonMsgSeqNum > db incoming_seqnum), logs that sequence numbers were missing (the engine will have sent ResendRequest for the gap when configured). This aligns with the [QuickFIX/J 2.3.0 Session](https://www.quickfixj.org/javadoc/2.3.0/quickfix/Session.html) and MessageStore API.

### 3d. Logout and Logon with same MsgSeqNum in TRACE_FIX_MESSAGES_LOG (34=N for both)

**What you see:** In `TRACE_FIX_MESSAGES_LOG`, the simulator (49=FNRA) sends Logout with 34=233 and later sends Logon with 34=233. You expect Logon to have 34=234 (incremented by 1).

**Why:** (1) **SendLogout_at_Shutdown=Y:** The simulator sends Logout and then the process exits; the JdbcStore may not flush the incremented sequence to `TRACE_FIX_SESSIONS` before exit, so the next run loads 233 and sends Logon with 233 again. (2) **SendLogout_at_Shutdown=N:** The initiator disconnects (with or without sending Logout). If the initiator sends Logout, we never persisted 234. If the initiator just drops, we load from DB (possibly stale 233) and may reuse 233 for the next Logon.

**Fix (simulator):** When **UseJdbcStore=Y**:

- **SendLogout_at_Shutdown=Y:** After sending each Logout, the simulator writes next sender (current from DB + 1) to `TRACE_FIX_SESSIONS.outgoing_seqnum` so after restart the next Logon uses 34=(N+1). See `Simulator.sendLogoutToAllSessions()`.
- **SendLogout_at_Shutdown=N:** (1) When we **receive** Logout from the initiator, we persist next sender = engine’s current + 1 so the next Logon uses 34=(N+1). (2) On **Logon**, we use the **max** of (DB value, engine’s current next sender) so we never send a lower seq when the initiator reconnects without us having sent Logout (same process, session still in memory). See `WizFixApplication.fromAdmin` (Logout 35=5 and Logon 35=A).

### 4. When the initiator sends a sequence reset (ResetSeqNumFlag 141=Y)

If the **initiator** sends a Logon with **ResetSeqNumFlag(141)=Y**, it is requesting a sequence number reset: both sides should set their sequence numbers back to 1 and continue from there.

**What the simulator does:** With **ResetOnLogon=N** (current setting), the simulator does *not* auto-reset on every logon, but it **does honor** the initiator's reset request. When the acceptor (simulator) receives a Logon with 141=Y, QuickFIX/J resets the session's incoming and outgoing sequence numbers (and persists them to the DB via JdbcStore). So after that Logon, both sides expect seq 1 for the next message. No ResendRequest is needed for the "gap" because the reset is intentional.

**Summary:** Sequence reset from the initiator (141=Y in Logon) is supported; the simulator resets and updates the DB, and the session continues from 1.

### 5. When the initiator sends SequenceReset (35=4) with NewSeqNo(36)

Sometimes the initiator sends a **SequenceReset (35=4)** with **NewSeqNo(36)** set to some value (e.g. higher than our current DB max). Our DB might have a lower max sequence. In that case we must:

1. **Reset our sequence state to match** — Set expected incoming and next outgoing to the new value (NewSeqNo). QuickFIX/J does this when it processes the SequenceReset.
2. **Persist to the database** — With **UseJdbcStore=Y**, the engine updates the session in the store, so `TRACE_FIX_SESSIONS.incoming_seqnum` and `outgoing_seqnum` are updated (typically to NewSeqNo so the *next* message from us is NewSeqNo; some specs use NewSeqNo as "next seq", so we send from NewSeqNo+1).
3. **Send next message from the updated seq** — After the reset, our next outgoing message will use the new sequence number (NewSeqNo). If the counterparty interprets NewSeqNo as "current seq", then the next message would be NewSeqNo+1; we set next outgoing to NewSeqNo+1 in fromAdmin (value from message, not hardcoded).

**What the simulator does:** QuickFIX/J handles **SequenceReset (35=4)** in the engine: it updates the session's expected incoming and next outgoing sequence numbers and writes them to the MessageStore (JdbcStore → DB). We also set next sender sequence to NewSeqNo+1 in fromAdmin (value from message; no hardcoded values). After the initiator sends SequenceReset with NewSeqNo, the simulator's session state and `TRACE_FIX_SESSIONS` are updated, and the next message we send will use that sequence number.

**GapFill(123):** If the message has **GapFill(123)=Y**, it is a gap fill (we advance our expected incoming to NewSeqNo but do not reset our outgoing). If **123=N** (or absent), it is a full sequence reset (both sides set to NewSeqNo).

**When the simulator sends a SequenceReset (35=4):** If the simulator (acceptor) sends a full SequenceReset with NewSeqNo(36), we set our next sender sequence to NewSeqNo+1 in **toAdmin** (before the message goes out) so that the next message we send uses that value. Same logic as when we receive a reset: value from the message, no hardcoded numbers.

### 6. Session / sequence reset we initiate (last resort)

If the session is stuck and the initiator will not or cannot resend:

- **ResetOnLogon=Y** would make the simulator send ResetSeqNumFlag=Y on every logon (we initiate reset every time). Use only if you accept losing in-flight state. Keeping **ResetOnLogon=N** and letting the initiator send 141=Y when it wants a reset is the normal setup.
- Or clear the session state (e.g. update the row in `TRACE_FIX_SESSIONS` for that session) and/or restart the simulator so the next logon starts from a clean state. This is environment-specific and should be done only when you understand the impact.

## Summary

| Log message | Meaning |
|-------------|--------|
| **Already sent ResendRequest FROM: X TO: Y. Not sending another.** | Engine sent one ResendRequest for that gap and will not send another (unless `SendRedundantResendRequests=Y`). |
| **MsgSeqNum too high, expecting A but received B** | Next expected incoming seq num is A; a message arrived with seq num B. Gap A..B-1 was requested with ResendRequest; if the initiator doesn't resend, the simulator keeps expecting A and will keep logging this for every new message with seq ≥ B. |

The underlying fix is: **initiator must respond to ResendRequest by resending the requested range**. The simulator options **SendRedundantResendRequests=Y** and **RefreshOnLogon=Y** (reload from DB on Logon) are set in the project configs.
