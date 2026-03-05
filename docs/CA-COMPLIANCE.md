# FINRA TRACE CA (Corporates and Agencies) – Session Compliance

The simulator relies on **QuickFIX/J** for session-level behavior. No change to existing DB message persistence.

## Admin message handling (35=A, 0, 1, 2, 5, 4)

- **Logon (35=A)** – QuickFIX/J validates required fields (98, 108, 141); ResetSeqNumFlag(141)=Y resets sequences when configured.
- **Heartbeat (35=0)** – Optional TestReqID(112).
- **TestRequest (35=1)** – Required TestReqID(112).
- **ResendRequest (35=2)** – Required BeginSeqNo(7), EndSeqNo(16); engine performs resend.
- **Logout (35=5)** – Optional Text(58).
- **SequenceReset (35=4)** – NewSeqNo(36) required; on full reset we set next outgoing to NewSeqNo+1; GapFill(123)=Y means Gap Fill (do not reset sender’s sequence).

## Config enforcement (quickfixj-server.cfg)

Strict CA behavior is enforced by these settings (do not relax for certification):

| Setting | Value | Purpose |
|--------|--------|--------|
| ResetOnLogon | N | Do not auto-reset on logon unless client sends ResetSeqNumFlag=Y |
| ResetOnLogout | N | Preserve sequence across logout |
| ResetOnDisconnect | N | Preserve sequence across disconnect |
| ResetOnError | N | Do not auto-reset on error |
| UseDataDictionary | Y | Validate message structure |
| PersistMessages | Y | Store messages for resend |

QuickFIX/J performs:

- **Sequence number validation** (incoming vs expected).
- **PossDupFlag(43)** – Accept retransmitted messages when 43=Y.
- **GapFill(123)** – In SequenceReset, 123=Y indicates Gap Fill; 123=N indicates full sequence reset.

Rule definitions (no hardcoding): `config/finra/ca_admin_rules.yaml`.
