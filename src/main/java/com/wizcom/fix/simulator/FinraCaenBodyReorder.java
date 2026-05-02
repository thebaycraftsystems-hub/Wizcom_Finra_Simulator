package com.wizcom.fix.simulator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import quickfix.FieldNotFound;
import quickfix.StringField;
import quickfix.fix44.TradeCaptureReport;
import quickfix.fix44.component.Instrument;

/**
 * Rebuilds outbound Trade Capture Report (35=AE) acknowledgement bodies (CAEN / similar EN suffix)
 * so root-level tags follow FINRA Corporates & Agencies FIX v2.1 §5.1.6 order:
 * identifiers and metadata → Instrument component → LastQty/LastPx/TradeDate/TransactTime → NoSides → trailing tags.
 * Repeating groups (552) and Instrument component are preserved; tag 80/12/13 must live inside NoSides (see GatewayAllocQtyEcho).
 */
public final class FinraCaenBodyReorder {

	private static final Logger log = LoggerFactory.getLogger(FinraCaenBodyReorder.class);

	/**
	 * §5.1.6 CAEN (Corporates & Agencies FIX v2.1, PDF pp. 31–32) — root tags before Instrument:
	 * MessageEventSource → TradeReportID / refs / TRACE ids → trans type / report type / flags → SettlDate → AsOfIndicator.
	 * Do not place trailing tags (9854, 22003–22004, …) here; those follow {@code NoSides} in the spec table.
	 */
	private static final int[] PRE_BLOCK = {
		1011, 571, 572, 1042, 22011, 1003, 487, 856, 570, 64, 1015
	};

	/** §5.1.6 — after Instrument, before NoSides. */
	private static final int[] QTY_DATE_TIME_BLOCK = { 32, 31, 75, 60 };

	/** §5.1.6 — optional trailing body tags after TrdCapRptSideGrp (PDF table order). */
	private static final int[] POST_SIDES_BLOCK = {
		5149, 9854, 22013, 22005, 22003, 22004, 22016, 22006, 22009, 22022, 22034, 22036, 797
	};

	private FinraCaenBodyReorder() {
	}

	/**
	 * Reorders body fields for FINRA CAEN-style AE (1011 ends with EN) or correction ack CR (1011 ends with CR).
	 */
	public static void reorderCaenAcknowledgementBody(TradeCaptureReport msg) {
		try {
			StringField sf1011 = new StringField(1011);
			if (!msg.isSetField(1011)) {
				return;
			}
			msg.getField(sf1011);
			String ev = sf1011.getValue();
			if (ev == null || ev.length() < 2) {
				return;
			}
			String suf = ev.substring(ev.length() - 2);
			if (!"EN".equals(suf) && !"CR".equals(suf)) {
				return;
			}
		} catch (FieldNotFound e) {
			return;
		}

		try {
			boolean hasInstrument = msg.isSetField(48) || msg.isSetField(22) || msg.isSetField(454);
			Instrument inst = new Instrument();
			try {
				msg.get(inst);
			} catch (FieldNotFound ignored) {
				hasInstrument = false;
			}

			int nSides = 0;
			try {
				nSides = msg.getNoSides().getValue();
			} catch (FieldNotFound ignored) {
				nSides = 0;
			}
			List<TradeCaptureReport.NoSides> sides = new ArrayList<>();
			for (int i = 1; i <= nSides; i++) {
				TradeCaptureReport.NoSides g = new TradeCaptureReport.NoSides();
				msg.getGroup(i, g);
				sides.add(g);
			}

			Map<Integer, String> full = snapshotRootScalars(msg);
			for (Integer tag : full.keySet()) {
				try {
					msg.removeField(tag.intValue());
				} catch (Exception e) {
					log.trace("remove tag {}: {}", tag, e.getMessage());
				}
			}
			try {
				msg.removeField(552);
			} catch (Exception ignored) {
			}

			Map<Integer, String> captured = new LinkedHashMap<>(full);
			for (int t : new int[] { 48, 22, 454, 455, 456 }) {
				captured.remove(t);
			}

			applyOrderedTags(msg, PRE_BLOCK, captured);
			if (hasInstrument) {
				msg.set(inst);
			}
			applyOrderedTags(msg, QTY_DATE_TIME_BLOCK, captured);

			for (TradeCaptureReport.NoSides side : sides) {
				msg.addGroup(side);
			}

			applyOrderedTags(msg, POST_SIDES_BLOCK, captured);

			for (Map.Entry<Integer, String> e : captured.entrySet()) {
				if (e.getKey() == null || e.getValue() == null) {
					continue;
				}
				try {
					msg.setField(new StringField(e.getKey(), e.getValue()));
				} catch (Exception ex) {
					log.trace("append leftover tag {}: {}", e.getKey(), ex.getMessage());
				}
			}
		} catch (Exception e) {
			log.warn("FinraCaenBodyReorder: could not reorder CAEN body: {}", e.getMessage());
		}
	}

	private static Map<Integer, String> snapshotRootScalars(TradeCaptureReport msg) throws FieldNotFound {
		Map<Integer, String> captured = new LinkedHashMap<>();
		for (int tag = 1; tag <= 23000; tag++) {
			if (tag == 552) {
				continue;
			}
			if (msg.isSetField(tag)) {
				StringField sf = new StringField(tag);
				msg.getField(sf);
				captured.put(tag, sf.getValue());
			}
		}
		return captured;
	}

	private static void applyOrderedTags(TradeCaptureReport msg, int[] order, Map<Integer, String> captured) {
		for (int tag : order) {
			String val = captured.remove(tag);
			if (val != null) {
				try {
					msg.setField(new StringField(tag, val));
				} catch (Exception e) {
					log.trace("set ordered tag {}: {}", tag, e.getMessage());
				}
			}
		}
	}
}
