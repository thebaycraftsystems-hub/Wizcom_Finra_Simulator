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
 * Treasury Securities outbound Trade Capture Report (35=AE) root tag order per FINRA
 * {@code FIX_Specification_for_Treasuries_v2.1.pdf} §5.2.2–§5.2.7 (TSEN, TSAL, TSCX, TSHX, TSCR, TSMA).
 * Differs from Corporates & Agencies: Tag 1041 (FirmTradeID), Tag 423 (PriceType), Tag 9854 position,
 * Tags 63/64 with qty/datetime block, and trailing sequence starting with 22006 before 5149.
 */
public final class FinraTsAckBodyReorder {

	private static final Logger log = LoggerFactory.getLogger(FinraTsAckBodyReorder.class);

	/** §5.2.2 TSEN — through AsOfIndicator (includes 1041 FirmTradeID). */
	private static final int[] PRE_TS_EN = {
		1011, 571, 572, 1041, 1042, 22011, 1003, 487, 856, 570, 1015
	};

	/** §5.2.3 TSAL — no 572 in opening table. */
	private static final int[] PRE_TS_AL = {
		1011, 571, 1042, 22011, 1003, 487, 856, 570, 1015
	};

	/** §5.2.4 TSCX — minimal metadata before qty (see §5.2.4 table). */
	private static final int[] PRE_TS_CX = {
		1011, 571, 572, 1041, 1003, 22011, 487, 856, 570
	};

	/** §5.2.5 TSHX — includes OrigControlDate / OrigTradeID per Treasury reversal layout. */
	private static final int[] PRE_TS_HX = {
		1011, 571, 572, 1041, 1042, 22012, 1126, 22011, 1003, 487, 856, 570, 1015
	};

	/** §5.2.6 TSCR — correction acknowledgement (§5.2.6 table). */
	private static final int[] PRE_TS_CR = {
		1011, 571, 572, 1041, 1042, 22011, 1003, 22012, 1126, 487, 856, 570, 1015
	};

	/** §5.2.7 TSMA — Match Status (same tag sequence as other TRACE products for this section). */
	private static final int[] PRE_TS_MA = {
		1011, 571, 22011, 1003, 22027, 22028, 487, 856, 573, 570, 64
	};

	/**
	 * §5.2.2 / §5.2.3 / §5.2.5 / §5.2.6 — block after Instrument, before NoSides (includes 423, 9854, 63, 64).
	 */
	private static final int[] MID_TS_FULL = { 32, 31, 423, 9854, 75, 60, 63, 64 };

	private static final int[] MID_TS_CX = { 32, 31, 75, 60 };

	private static final int[] MID_TS_MA = { 32, 31, 75, 60 };

	/**
	 * §5.2.2–§5.2.6 Treasury trailing body after TrdCapRptSideGrp (consolidated TS PDF order).
	 */
	private static final int[] POST_TS_TRAILING = {
		22006, 5149, 22013, 22005, 22002, 22004, 22009, 22022, 22034, 22036, 22003, 797
	};

	private static final int[] POST_TS_CX = { 797 };

	private static final int[] POST_TS_MA = { 797 };

	private FinraTsAckBodyReorder() {
	}

	/** §5.2.2 TSEN only — MessageEventSource must start with TS and end with EN. */
	public static void reorderTsenAcknowledgementBody(TradeCaptureReport msg) {
		try {
			StringField sf1011 = new StringField(1011);
			if (!msg.isSetField(1011)) {
				return;
			}
			msg.getField(sf1011);
			String ev = sf1011.getValue();
			if (ev == null || ev.length() < 4 || !ev.startsWith("TS") || !ev.endsWith("EN")) {
				return;
			}
		} catch (FieldNotFound e) {
			return;
		}
		reorderBody(msg, PRE_TS_EN, MID_TS_FULL, POST_TS_TRAILING, true);
	}

	/** §5.2.3–§5.2.7 — TSAL, TSCX, TSHX, TSCR, TSMA (suffix AL, CX, HX, CR, MA). */
	public static void reorderFinraTsAckBody(TradeCaptureReport msg) {
		String suf = suffixFrom1011(msg);
		if (suf == null) {
			return;
		}
		switch (suf) {
		case "AL":
			reorderBody(msg, PRE_TS_AL, MID_TS_FULL, POST_TS_TRAILING, true);
			break;
		case "CX":
			reorderBody(msg, PRE_TS_CX, MID_TS_CX, POST_TS_CX, false);
			break;
		case "HX":
			reorderBody(msg, PRE_TS_HX, MID_TS_FULL, POST_TS_TRAILING, true);
			break;
		case "CR":
			reorderBody(msg, PRE_TS_CR, MID_TS_FULL, POST_TS_TRAILING, true);
			break;
		case "MA":
			reorderBody(msg, PRE_TS_MA, MID_TS_MA, POST_TS_MA, true);
			break;
		default:
			break;
		}
	}

	private static void reorderBody(TradeCaptureReport msg, int[] preBlock, int[] midBlock, int[] postBlock,
			boolean wireInstrument) {
		try {
			boolean hasInstrument = wireInstrument && (msg.isSetField(48) || msg.isSetField(22) || msg.isSetField(454));
			Instrument inst = new Instrument();
			if (hasInstrument) {
				try {
					msg.get(inst);
				} catch (FieldNotFound ignored) {
					hasInstrument = false;
				}
			}

			int nSides = 0;
			try {
				nSides = msg.getNoSides().getValue();
			} catch (FieldNotFound ignored) {
				nSides = 0;
			}
			boolean isMa = false;
			try {
				if (msg.isSetField(1011)) {
					StringField sf = new StringField(1011);
					msg.getField(sf);
					String ev = sf.getValue();
					isMa = ev != null && ev.endsWith("MA");
				}
			} catch (FieldNotFound ignored) {
			}
			List<TradeCaptureReport.NoSides> sides = FinraAeBodyReorderUtil.snapshotNoSidesGroups(msg, isMa ? 2 : -1);

			Map<Integer, String> full = FinraAeBodyReorderUtil.snapshotRootScalars(msg);
			for (Integer tag : full.keySet()) {
				try {
					msg.removeField(tag.intValue());
				} catch (Exception e) {
					log.trace("remove tag {}: {}", tag, e.getMessage());
				}
			}
			FinraAeBodyReorderUtil.clearNoSides(msg);

			Map<Integer, String> captured = new LinkedHashMap<>(full);
			for (int t : new int[] { 48, 22, 454, 455, 456 }) {
				captured.remove(t);
			}

			FinraAeBodyReorderUtil.applyOrderedTags(msg, preBlock, captured);
			if (hasInstrument) {
				msg.set(inst);
			}
			FinraAeBodyReorderUtil.applyOrderedTags(msg, midBlock, captured);

			for (TradeCaptureReport.NoSides side : sides) {
				msg.addGroup(side);
			}

			FinraAeBodyReorderUtil.applyOrderedTags(msg, postBlock, captured);
			if (isMa) {
				FinraAeBodyReorderUtil.finalizeMaNoSidesCount(msg);
			} else {
				FinraAeBodyReorderUtil.appendLeftoverRootTags(msg, captured);
			}
		} catch (Exception e) {
			log.warn("FinraTsAckBodyReorder: {}", e.getMessage());
		}
	}

	private static String suffixFrom1011(TradeCaptureReport msg) {
		try {
			if (!msg.isSetField(1011)) {
				return null;
			}
			StringField sf = new StringField(1011);
			msg.getField(sf);
			String ev = sf.getValue();
			if (ev == null || ev.length() < 2) {
				return null;
			}
			return ev.substring(ev.length() - 2);
		} catch (FieldNotFound e) {
			return null;
		}
	}

}
