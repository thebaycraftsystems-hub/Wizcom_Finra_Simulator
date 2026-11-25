package com.wizcom.fix.simulator;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

import quickfix.FieldNotFound;
import quickfix.SessionID;
import quickfix.StringField;
import quickfix.fix44.TradeCaptureReport;

/**
 * Open dealer reports for the secondary simulator (JPMS gateway and JPMB gateway).
 * A match is the opposite side of the same economics, with the executing and contra MPIDs swapped.
 */
public final class TwoPartyMatchBook {

	private static final int PARTY_ROLE_EXECUTING = 1;
	private static final int PARTY_ROLE_CONTRA = 17;

	private final ConcurrentMap<String, Deque<OpenTrade>> openByEconomics = new ConcurrentHashMap<String, Deque<OpenTrade>>();
	private final ConcurrentMap<String, OpenTrade> openByAlias = new ConcurrentHashMap<String, OpenTrade>();
	private final ConcurrentMap<String, OpenTrade> matchedByAlias = new ConcurrentHashMap<String, OpenTrade>();
	private final AtomicLong matchSeq = new AtomicLong(1);

	public static final class OpenTrade {
		public final SessionID sessionID;
		public final String product;
		public final String executingMpid;
		public final String contraMpid;
		public final char executingSide;
		public final String economicsKey;
		public final String tradeId;
		public final String controlDate;
		public final String clientTradeReportId;
		public final TradeCaptureReport inbound;
		final List<String> aliases;
		public volatile boolean matched;
		public volatile OpenTrade counterparty;
		public volatile String matchTradeId;
		/** FINRA TradeReportID (571) on the entry acknowledgement. */
		public volatile String finraTradeReportId;
		/** TradeReportID of the allege sent to the contra gateway. */
		public volatile String allegeToContraReportId;
		/** Logged-on session for the contra MPID, when one is configured. */
		public volatile SessionID contraSessionID;

		OpenTrade(SessionID sessionID, String product, String executingMpid, String contraMpid, char executingSide,
				String economicsKey, String tradeId, String controlDate, String clientTradeReportId,
				TradeCaptureReport inbound, List<String> aliases) {
			this.sessionID = sessionID;
			this.product = product;
			this.executingMpid = executingMpid;
			this.contraMpid = contraMpid;
			this.executingSide = executingSide;
			this.economicsKey = economicsKey;
			this.tradeId = tradeId;
			this.controlDate = controlDate;
			this.clientTradeReportId = clientTradeReportId;
			this.inbound = inbound;
			this.aliases = aliases;
		}
	}

	/**
	 * @param finraTradeId control number assigned on the acknowledgement (tag 1003)
	 * @param controlDate control date assigned on the acknowledgement (tag 22011)
	 */
	public OpenTrade create(TradeCaptureReport inbound, SessionID sessionID, String product, String finraTradeId,
			String controlDate) {
		if (inbound == null || sessionID == null) {
			return null;
		}
		String exec = null;
		String contra = null;
		char execSide = 0;
		char contraSide = 0;
		try {
			int n = inbound.getNoSides().getValue();
			for (int i = 1; i <= n; i++) {
				TradeCaptureReport.NoSides side = new TradeCaptureReport.NoSides();
				inbound.getGroup(i, side);
				char sideVal = 0;
				try {
					if (side.isSetField(54)) {
						sideVal = side.getSide().getValue();
					}
				} catch (FieldNotFound ignored) {
				}
				int parties = 0;
				try {
					parties = side.getParties().getNoPartyIDs().getValue();
				} catch (FieldNotFound ignored) {
					continue;
				}
				for (int p = 1; p <= parties; p++) {
					TradeCaptureReport.NoSides.NoPartyIDs party = new TradeCaptureReport.NoSides.NoPartyIDs();
					side.getGroup(p, party);
					try {
						int role = party.getPartyRole().getValue();
						String mpid = party.getPartyID().getValue();
						if (mpid == null || mpid.trim().isEmpty()) {
							continue;
						}
						if (role == PARTY_ROLE_CONTRA) {
							contra = mpid.trim().toUpperCase();
							contraSide = sideVal;
						} else if (role == PARTY_ROLE_EXECUTING) {
							exec = mpid.trim().toUpperCase();
							execSide = sideVal;
						}
					} catch (FieldNotFound ignored) {
					}
				}
			}
		} catch (FieldNotFound ignored) {
			return null;
		}
		if (exec == null && sessionID.getTargetCompID() != null) {
			exec = sessionID.getTargetCompID().trim().toUpperCase();
		}
		if (exec == null || contra == null || exec.equals(contra) || execSide == 0) {
			return null;
		}
		String security = stringTag(inbound, 48);
		String qty = decimalTag(inbound, 32);
		String px = decimalTag(inbound, 31);
		String tradeDate = stringTag(inbound, 75);
		if (security == null || qty == null || px == null || tradeDate == null) {
			return null;
		}
		String settl = stringTag(inbound, 64);
		if (settl == null) {
			settl = "";
		}
		String economics = product + "|" + security.toUpperCase() + "|" + qty + "|" + px + "|" + tradeDate + "|" + settl
				+ "|" + pairKey(exec, contra);
		String clientId = stringTag(inbound, 571);
		List<String> aliases = new ArrayList<String>();
		addAlias(aliases, finraTradeId);
		addAlias(aliases, clientId);
		addAlias(aliases, stringTag(inbound, 572));
		addAlias(aliases, stringTag(inbound, 1126));
		return new OpenTrade(sessionID, product, exec, contra, execSide, economics, blankToNull(finraTradeId),
				blankToNull(controlDate), blankToNull(clientId), inbound, aliases);
	}

	public synchronized OpenTrade findReferenced(TradeCaptureReport inbound) {
		if (inbound == null) {
			return null;
		}
		String[] ids = new String[] { stringTag(inbound, 1003), stringTag(inbound, 572), stringTag(inbound, 571),
				stringTag(inbound, 1126) };
		for (int i = 0; i < ids.length; i++) {
			OpenTrade matched = alias(matchedByAlias, ids[i]);
			if (matched != null) {
				return matched;
			}
		}
		for (int i = 0; i < ids.length; i++) {
			OpenTrade open = alias(openByAlias, ids[i]);
			if (open != null) {
				return open;
			}
		}
		return null;
	}

	public synchronized void park(OpenTrade trade) {
		if (trade == null) {
			return;
		}
		forget(trade);
		trade.matched = false;
		trade.counterparty = null;
		trade.matchTradeId = null;
		bucket(trade.economicsKey).addLast(trade);
		index(openByAlias, trade);
	}

	/** Removes the oldest opposite-side report and returns it. */
	public synchronized OpenTrade takeContra(OpenTrade mine) {
		if (mine == null) {
			return null;
		}
		Deque<OpenTrade> deque = openByEconomics.get(mine.economicsKey);
		if (deque == null || deque.isEmpty()) {
			return null;
		}
		for (OpenTrade other : deque) {
			if (other == mine) {
				continue;
			}
			if (mine.executingMpid.equals(other.contraMpid) && mine.contraMpid.equals(other.executingMpid)
					&& mine.executingSide != other.executingSide) {
				deque.remove(other);
				unindex(openByAlias, other);
				if (deque.isEmpty()) {
					openByEconomics.remove(mine.economicsKey, deque);
				}
				return other;
			}
		}
		return null;
	}

	public synchronized void rememberPair(OpenTrade left, OpenTrade right, String matchId) {
		if (left == null || right == null) {
			return;
		}
		unindex(openByAlias, left);
		unindex(openByAlias, right);
		left.matched = true;
		right.matched = true;
		left.counterparty = right;
		right.counterparty = left;
		left.matchTradeId = matchId;
		right.matchTradeId = matchId;
		index(matchedByAlias, left);
		index(matchedByAlias, right);
	}

	/** Drops this report from the open and matched books. Does not touch the counterparty. */
	public synchronized void forget(OpenTrade trade) {
		if (trade == null) {
			return;
		}
		Deque<OpenTrade> deque = openByEconomics.get(trade.economicsKey);
		if (deque != null) {
			deque.remove(trade);
			if (deque.isEmpty()) {
				openByEconomics.remove(trade.economicsKey, deque);
			}
		}
		unindex(openByAlias, trade);
		unindex(matchedByAlias, trade);
		trade.matched = false;
		trade.counterparty = null;
	}

	public String allocateMatchId(String product, boolean matched) {
		long n = matchSeq.getAndIncrement() % 10_000_000L;
		if (n <= 0) {
			n = 1;
		}
		String p = product == null ? "" : product;
		if ("CA".equalsIgnoreCase(p)) {
			return matched ? String.format("2%09d", n) : String.format("221%07d", n);
		}
		if ("TS".equalsIgnoreCase(p)) {
			return matched ? String.format("7%09d", n) : String.format("721%07d", n);
		}
		return matched ? String.format("1%09d", n) : String.format("121%07d", n);
	}

	private Deque<OpenTrade> bucket(String key) {
		Deque<OpenTrade> existing = openByEconomics.get(key);
		if (existing != null) {
			return existing;
		}
		Deque<OpenTrade> created = new ArrayDeque<OpenTrade>();
		Deque<OpenTrade> raced = openByEconomics.putIfAbsent(key, created);
		return raced != null ? raced : created;
	}

	private static void index(ConcurrentMap<String, OpenTrade> map, OpenTrade trade) {
		for (int i = 0; i < trade.aliases.size(); i++) {
			map.put(trade.aliases.get(i), trade);
		}
	}

	private static void unindex(ConcurrentMap<String, OpenTrade> map, OpenTrade trade) {
		for (int i = 0; i < trade.aliases.size(); i++) {
			String alias = trade.aliases.get(i);
			OpenTrade current = map.get(alias);
			if (current == trade) {
				map.remove(alias, trade);
			}
		}
	}

	private static OpenTrade alias(ConcurrentMap<String, OpenTrade> map, String id) {
		if (id == null || id.isEmpty()) {
			return null;
		}
		return map.get(id);
	}

	private static void addAlias(List<String> aliases, String id) {
		String v = blankToNull(id);
		if (v != null && !aliases.contains(v)) {
			aliases.add(v);
		}
	}

	private static String pairKey(String a, String b) {
		if (a.compareTo(b) <= 0) {
			return a + "+" + b;
		}
		return b + "+" + a;
	}

	private static String blankToNull(String v) {
		if (v == null) {
			return null;
		}
		String t = v.trim();
		return t.isEmpty() ? null : t;
	}

	private static String stringTag(TradeCaptureReport msg, int tag) {
		try {
			if (!msg.isSetField(tag)) {
				return null;
			}
			String v = msg.getField(new StringField(tag)).getValue();
			return blankToNull(v);
		} catch (FieldNotFound e) {
			return null;
		}
	}

	private static String decimalTag(TradeCaptureReport msg, int tag) {
		String raw = stringTag(msg, tag);
		if (raw == null) {
			return null;
		}
		try {
			return new BigDecimal(raw).stripTrailingZeros().toPlainString();
		} catch (NumberFormatException e) {
			return raw;
		}
	}
}
