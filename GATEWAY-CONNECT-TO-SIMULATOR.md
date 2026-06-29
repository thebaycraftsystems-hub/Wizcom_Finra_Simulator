# Connect Gateway to Finra Simulator (acceptor)

The **Finra Simulator** is a FIX **acceptor**. It binds to **0.0.0.0** (all interfaces) and **accepts connections from any client IP**—e.g. 192.168.1.14, 192.168.1.66, 192.168.1.100, or any other. No client IP is fixed; the gateway can run on any machine.

---

## Port numbers for gateway to connect

Use the **IP of the machine where the simulator is running** and one of these **ports**:

| Session (acceptor)     | Port  | Gateway initiator: SocketConnectPort |
|------------------------|-------|--------------------------------------|
| FIX.4.4:FNRA/SP->JPMS/44B1 | **64034** | 64034 |
| FIX.4.4:FNRA/CA->JPMS/44B2 | **64093** | 64093 |
| FIX.4.4:FNRA/TS->JPMS/44B3 | **64094** | 64094 |

- **SocketConnectHost** = simulator host IP (e.g. 192.168.1.43 if simulator runs there; change if you run the simulator elsewhere).
- **SocketConnectPort** = **64034** (or 64093 / 64094 for the other sessions).

---

## 1. Simulator side (acceptor)

- **ConnectionType=acceptor**; binds to **SocketAcceptAddress=0.0.0.0** (all interfaces, accepts from any client IP).
- Ports and session IDs:

| Session           | Port   | Session ID (acceptor view)   |
|-------------------|--------|------------------------------|
| FNRA/SP -> JPMS/44B1 | 64034 | FIX.4.4:FNRA/SP->JPMS/44B1 |
| FNRA/CA -> JPMS/44B2 | 64093 | FIX.4.4:FNRA/CA->JPMS/44B2 |
| FNRA/TS -> JPMS/44B3 | 64094 | FIX.4.4:FNRA/TS->JPMS/44B3 |

- Ensure **firewall on the simulator host** allows inbound TCP on **64034**, **64093**, **64094** so that gateways from any IP can connect.

---

## 2. Gateway (initiator) config – wherever it runs

Configure the gateway as **initiator**. Replace the simulator host IP if it runs on a different machine.

Example for **one session** (FNRA/SP -> JPMS/44B1):

```ini
ConnectionType=initiator

# Connect TO the simulator (use the simulator host IP; port 64034 / 64093 / 64094 per session)
SocketConnectHost=<simulator host IP>
SocketConnectPort=64034

# Session must match simulator (acceptor): gateway is JPMS, simulator is FNRA
BeginString=FIX.4.4
SenderCompID=JPMS
TargetCompID=FNRA
SenderSubID=SP
TargetSubID=44B1

# Optional: same as simulator for consistency
HeartBtInt=30
```

For **all three sessions**, define three initiator sessions, each with the same `SocketConnectHost=<simulator host IP>` but different port and SubIDs:

| Session (gateway view) | SocketConnectHost | SocketConnectPort | SenderSubID | TargetSubID |
|------------------------|-------------------|-------------------|------------|-------------|
| JPMS/SP -> FNRA/44B1   | 192.168.1.43      | 64034             | SP         | 44B1        |
| JPMS/CA -> FNRA/44B2   | 192.168.1.43      | 64093             | CA         | 44B2        |
| JPMS/TS -> FNRA/44B3   | 192.168.1.43      | 64094             | TS         | 44B3        |

- **SenderCompID=JPMS**, **TargetCompID=FNRA** on the gateway matches the simulator’s **SenderCompID=FNRA**, **TargetCompID=JPMS** (acceptor side).

---

## 3. Quick checks

1. **Finra Simulator listening**  
   On the simulator host: `netstat -ano | findstr "64034 64093 64094"` should show LISTENING.

2. **Reachability from the gateway**  
   From the gateway machine (any IP—e.g. 192.168.1.14, 192.168.1.66, 192.168.1.100): run `telnet <simulator host IP> 64034` or in PowerShell `Test-NetConnection -ComputerName <simulator host IP> -Port 64034`. If it connects, firewall/network is fine and the simulator will accept that connection.

3. **Session IDs**  
   Initiator (gateway) session must be the mirror of acceptor (simulator):  
   - Acceptor: FNRA/SP -> JPMS/44B1  
   - Initiator: JPMS/SP -> FNRA/44B1  

---

## 4. Summary

| Role      | Binding / Connect to | Ports |
|-----------|----------------------|--------|
| **Acceptor** (simulator) | Binds **0.0.0.0** (all interfaces); does **not** bind to a client IP | **64034**, **64093**, **64094** |
| **Initiator** (gateway) | SocketConnectHost = **simulator host IP** (wherever simulator runs) | SocketConnectPort = **64034** or **64093** or **64094** |

The gateway (from any IP—192.168.1.14, 192.168.1.66, 192.168.1.100, etc.) connects to the Finra Simulator using the simulator’s host IP and one of the ports above. The Finra Simulator accepts connections from any client IP.

---

## 5. Primary and Secondary (Backup) — FINRA-style failover

The simulator can run as **Primary** or **Secondary (Backup)**. Both use the **same database** (`trace_fix`) and the **same FIX session identities** (e.g. FIX.4.4:FNRA/SP->JPMS/44B1). Only the **listen ports** differ so the gateway can connect to Primary first and, on failover, to Secondary.

| Role       | Config file                        | Ports (SP / CA / TS)   | How to start |
|------------|------------------------------------|-------------------------|--------------|
| **Primary**   |             | **64034**, **64093**, **64094** | `java -jar fix-simulator.jar` or `java -jar fix-simulator.jar quickfixj-server.cfg` |
| **Secondary** | quickfixj-server-secondary.cfg     | **64134**, **64193**, **64194** | `java -jar fix-simulator.jar secondary` |

- **Sequence numbers**: Primary and Secondary share the same JDBC store (same `TRACE_FIX_SESSIONS`, `TRACE_FIX_MESSAGES`). When the gateway fails over from Primary to Secondary, the session continues with the **same sequence numbers** (no reset).
- **Gateway (initiator)**: Configure **SocketConnectHost** / **SocketConnectPort** for **Primary** first. Configure a **second set** of host/port for **Secondary** (backup) so the engine tries Secondary when Primary is unreachable. Exact behavior depends on your initiator (e.g. SocketConnectHost2, SocketConnectPort2, or a backup session list).
- **Only one active per session**: For a given session (e.g. FNRA/SP->JPMS/44B1), only one of Primary or Secondary should have the TCP connection at a time. When Primary is up, the gateway connects there; when Primary is down, the gateway connects to Secondary. Both processes can run at once; the one that receives the connection serves that session using the shared DB.

See **PRIMARY-SECONDARY.md** for design details and sequence-number handling.
