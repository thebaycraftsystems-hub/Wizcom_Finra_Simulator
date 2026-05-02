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
 * Securitized Products (TRACE SP) outbound AE tag order per FINRA
 * {@code FIX_Specification_for_Securitized_Products_v2.1a_Native_FIX.pdf} §5.1.6 SPEN.
 * <p>
 * Trailing body tags after {@code NoSides} differ from Corporates & Agencies (e.g. Tag 228 Factor,
 * Tag 22002 Trade Modifier 2, no Tag 22016 in the §5.1.6 table sequence).
 */
public final class FinraSpAckBodyReorder {

	private static final Logger log = LoggerFactory.getLogger(FinraSpAckBodyReorder.class);

	/** §5.1.6 SPEN — identifiers through AsOfIndicator (same logical block as CA §5.1.6). */
	private static final int[] PRE_BLOCK = {
		1011, 571, 572, 1042, 22011, 1003, 487, 856, 570, 64, 1015
	};

	private static final int[] QTY_DATE_TIME_BLOCK = { 32, 31, 75, 60 };

	/**
	 * §5.1.6 SPEN — optional trailing body after TrdCapRptSideGrp (PDF table order, lines consolidated from §5.1.6).
	 */
	private static final int[] POST_SIDES_BLOCK_SP = {
		5149, 9854, 22013, 228, 22005, 22002, 22003, 22004, 22006, 22009, 22022, 22034, 22036, 797
	};

	private FinraSpAckBodyReorder() {
	}

	/**
	 * Reorders SP trade-entry acknowledgement (MessageEventSource SP…EN e.g. SPEN). No-op if not SP prefix or EN suffix.
	 */
	public static void reorderSpenAcknowledgementBody(TradeCaptureReport msg) {
		try {
			StringField sf1011 = new StringField(1011);
			if (!msg.isSetField(1011)) {
				return;
			}
			msg.getField(sf1011);
			String ev = sf1011.getValue();
			if (ev == null || ev.length() < 4) {
				return;
			}
			if (!ev.startsWith("SP") || !ev.endsWith("EN")) {
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

			applyOrderedTags(msg, POST_SIDES_BLOCK_SP, captured);

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
			log.warn("FinraSpAckBodyReorder: could not reorder SPEN body: {}", e.getMessage());
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
