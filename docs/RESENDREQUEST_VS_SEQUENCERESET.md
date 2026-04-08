# ResendRequest vs Sequence Number Reset — QuickFIX/J 2.3.0

This document aligns with the [QuickFIX/J 2.3.0 API](https://www.quickfixj.org/javadoc/2.3.0/) and explains the difference between **ResendRequest (35=2)** and **sequence number reset** (SequenceReset 35=4 or Logon 141=Y), and how the simulator uses them.

---

## 1. ResendRequest (35=2)

### What it is

- **Message type:** 35=2 (ResendRequest).
- **Purpose:** The receiver has detected a **gap** in sequence numbers (e.g. expected 5, received 8). It asks the sender to **resend** the missing messages in the range [BeginSeqNo, EndSeqNo].
- **No change to “current” sequence:** Both sides keep their notion of “next” sequence; we are **filling a gap** by retransmitting messages that were lost or never received.

### How QuickFIX/J uses it (from the API)

- **[MessageStore](https://www.quickfixj.org/javadoc/2.3.0/quickfix/MessageStore.html):**  
  `get(int startSequence, int endSequence, Collection<String> messages)` — *“Get messages within sequence number range (inclusive). Used for message resend requests.”*  
  So when we **receive** a ResendRequest, the engine uses the store to retrieve messages in that range and resends them (with PossDupFlag=Y, etc.). When we **send** a ResendRequest (we detected a gap), the **initiator** is expected to do the same on its side.
- **Session:** The engine sends ResendRequest when incoming MsgSeqNum > expected; it does not “set” our sequence to a new value—it asks for missing messages. After the gap is filled, we continue from the expected sequence.

### In our code

- **ResendRequest is handled entirely by QuickFIX/J** (no application logic in `WizFixApplication` for 35=2).
- **Config:** `SendRedundantResendRequests=Y` allows sending another ResendRequest for the same gap if the first didn’t result in a fill ([Session](https://www.quickfixj.org/javadoc/2.3.0/quickfix/Session.html) / resend behavior).
- **Store/DB:** With `UseJdbcStore=Y`, the same `MessageStore` (JdbcStore) backs session state and message storage; `get(start, end, messages)` is used for resend. Our DB (`TRACE_FIX_MESSAGES`, `TRACE_FIX_SESSIONS`) is updated by the engine/store.

**Summary:** ResendRequest = “please resend messages X through Y”; we do not jump sequence numbers—we request retransmission and then continue in order.

---

## 2. Sequence number reset (two mechanisms)

### A. Logon with ResetSeqNumFlag (141=Y)

- **Where:** In the **Logon (35=A)** message, tag **141=Y**.
- **Meaning:** Both sides agree to **reset** sequence numbers to **1** and start the session from 1 again.
- **QuickFIX/J:** With `ResetOnLogon=N`, we do not initiate reset ourselves but we **honor** the initiator’s 141=Y (acceptor resets and persists via store). Session/MessageStore state (and thus DB) is reset to 1.

### B. SequenceReset (35=4) with NewSeqNo (36)

- **Message type:** 35=4 (SequenceReset).
- **Purpose:** Sender tells receiver: “from now on, use **this** sequence number” (NewSeqNo). **No retransmission** of old messages—we **jump** the sequence to a new value.
- **GapFillFlag (123):**
  - **123=Y (Gap Fill):** Only the **receiver** advances its expected incoming sequence to NewSeqNo; the sender does **not** reset its own sequence. Used to skip a range without the sender resending.
  - **123=N or absent (Full reset):** Both sides set their sequence to NewSeqNo (receiver sets expected incoming; both typically set next outgoing to the new value).

### How QuickFIX/J uses it

- **Session:** [Session.setNextSenderMsgSeqNum(int)](https://www.quickfixj.org/javadoc/2.3.0/quickfix/Session.html#setNextSenderMsgSeqNum-int-) — *“Set the next outgoing message sequence number.”*  
  [Session.setNextTargetMsgSeqNum(int)](https://www.quickfixj.org/javadoc/2.3.0/quickfix/Session.html#setNextTargetMsgSeqNum-int-) — *“Set the next expected target message sequence number.”*
- **MessageStore:** [setNextSenderMsgSeqNum(int)](https://www.quickfixj.org/javadoc/2.3.0/quickfix/MessageStore.html#setNextSenderMsgSeqNum-int-), [setNextTargetMsgSeqNum(int)](https://www.quickfixj.org/javadoc/2.3.0/quickfix/MessageStore.html#setNextTargetMsgSeqNum-int-) persist the new sequence numbers (with JdbcStore, that updates the DB).

### In our code

- **SequenceReset (35=4)** is handled in **fromAdmin** in `WizFixApplication.java`:
  - We only adjust when it’s a **full** reset (GapFillFlag 123=N or absent).
  - We read **NewSeqNo(36)** and call **Session.setNextSenderMsgSeqNum(NewSeqNo + 1)** so we send the **next** message from **NewSeqNo+1** (NewSeqNo read from the message; no hardcoded values). The engine has already updated expected incoming (target) to NewSeqNo; we set our **sender** seq to NewSeqNo+1 (no hardcoded “next from NewSeqNo+1” values).
- **When the simulator sends a SequenceReset (35=4):** Handled in **toAdmin**. Before the message goes out we read NewSeqNo(36) and GapFillFlag(123); for a full reset (123=N or absent) we call Session.setNextSenderMsgSeqNum(NewSeqNo + 1) so the next message we send uses NewSeqNo+1 (value from message; no hardcoded values).
- **Logon 141=Y** is handled by QuickFIX/J; we do not add application code for it. Config `ResetOnLogon=N` means we honor the initiator’s reset but don’t initiate one ourselves.

**Summary:** Sequence reset = “set sequence to N (or N+1 for our next send)”; no resend of previous messages—we jump to the new number.

---

## 3. Differences at a glance

| Aspect | ResendRequest (35=2) | Sequence reset (35=4 or Logon 141=Y) |
|--------|------------------------|--------------------------------------|
| **Message** | ResendRequest: BeginSeqNo(7), EndSeqNo(16) | SequenceReset: NewSeqNo(36); or Logon: ResetSeqNumFlag(141)=Y |
| **Purpose** | Request **retransmission** of missing messages in a range | **Set** sequence number to a new value; **no** retransmission |
| **Sequence** | Expected seq does **not** jump; we fill the gap then continue in order | Expected/next seq **jump** to the new value (from NewSeqNo or Logon 141=Y) |
| **Store usage** | `MessageStore.get(start, end, messages)` to **resend** stored messages | `MessageStore.setNextSenderMsgSeqNum` / `setNextTargetMsgSeqNum` to **set** sequence |
| **Who acts** | Receiver sends ResendRequest; **sender** resends from its store | Sender sends SequenceReset (or Logon 141=Y); **receiver** (and sender) update sequence state |
| **Our app code** | None (engine only) | Set next sender to NewSeqNo+1: in `fromAdmin` when we **receive** 35=4; in `toAdmin` when we **send** 35=4 |

---

## 4. Config and API alignment (QuickFIX/J 2.3.0)

- **RefreshOnLogon:** [Session](https://www.quickfixj.org/javadoc/2.3.0/quickfix/Session.html) setting *“Requests that state and message data be refreshed from the message store at logon”* — we use **RefreshOnLogon=Y** so after restart we load expected seq from DB and don’t send ResendRequest(1,8) when DB already has 9.
- **MessageStore.refresh():** Used when refreshing from shared storage (e.g. DB); supports failover. Our JdbcStore persists to `TRACE_FIX_SESSIONS` / `TRACE_FIX_MESSAGES`.
- **Session.setNextSenderMsgSeqNum(int):** We use this in `fromAdmin` after receiving a full SequenceReset: we set next outgoing to **NewSeqNo+1** (NewSeqNo read from the message; nothing hardcoded). The underlying store (and thus DB) is updated by the engine.

Logic in the codebase (config + `WizFixApplication.fromAdmin`) is consistent with the QuickFIX/J 2.3.0 Session and MessageStore behavior described above.

---

## 5. Simulator behavior checklist — working as designed

Yes. The simulator is implemented to behave as required for ResendRequest and sequence reset. Summary:

| Behavior | Who does it | Simulator status |
|----------|-------------|------------------|
| **Send ResendRequest** | QuickFIX/J engine | ✅ Engine sends ResendRequest when incoming MsgSeqNum &gt; expected. Config: `RefreshOnLogon=Y` (load expected from DB on Logon so we don’t spuriously request 1..N-1), `SendRedundantResendRequests=Y` (can send another ResendRequest for same gap if first didn’t get filled). |
| **Receive ResendRequest** | QuickFIX/J engine + MessageStore | ✅ When the initiator sends ResendRequest(7, 16), the engine uses `MessageStore.get(7, 16, messages)` and resends those messages (with PossDupFlag=Y, etc.). With `UseJdbcStore=Y`, JdbcStore reads from `TRACE_FIX_MESSAGES` and resends. No application code in `WizFixApplication` for 35=2. |
| **Receive SequenceReset (35=4)** | WizFixApplication.fromAdmin + engine | ✅ On full reset (GapFillFlag 123=N or absent), we set next sender to **NewSeqNo+1** via `Session.setNextSenderMsgSeqNum`. Engine updates expected incoming; we align our outgoing. DB updated via store. |
| **Send SequenceReset (35=4)** | WizFixApplication.toAdmin + engine | ✅ Before sending a full SequenceReset we set next sender to **NewSeqNo+1** so the next message we send uses that value. |
| **Logon ResetSeqNumFlag (141=Y)** | QuickFIX/J | ✅ With `ResetOnLogon=N` we do not auto-reset; we **honor** the initiator’s 141=Y (engine resets and persists via store). |
| **Logon sequence alignment** | WizFixApplication.fromAdmin | ✅ On Logon we set next expected from initiator (789), next sender from DB or 789 or “fresh day” (1), and handle “initiator behind” so we don’t send a high seq when they sent 34=1. |
| **Logout 58 “expecting N”** | WizFixApplication.fromAdmin | ✅ We parse Logout(58) and set next sender to N (and persist to DB) so the next Logon uses 34=N. |

**Requirements for resend/reset to work:** `UseJdbcStore=Y` (so session and messages are in DB for refresh and resend), `RefreshOnLogon=Y`, and a valid DB schema so the engine does not fall back to file store. With that, sending/receiving ResendRequest and sequence reset handling work as designed.
