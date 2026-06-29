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
 * FINRA Corporates & Agencies FIX v2.1 outbound Trade Capture Report (35=AE) body tag order for
 * acknowledgement types other than CAEN (§5.1.6). CAEN remains handled exclusively by
 * {@link FinraCaenBodyReorder} — do not duplicate CAEN logic here.
 * <p>
 * Corporates & Agencies: §5.1.7 CAAL, §5.1.8 CACX, §5.1.9 CAHX, §5.1.10 CACR, §5.1.11 CAMA.
 * Securitized Products (SP): same § numbers use SP-specific trailing blocks where the SP PDF differs (§5.1.7 SPAL, etc.).
 */
public final class FinraTraceAeAckBodyReorder {

	private static final Logger log = LoggerFactory.getLogger(FinraTraceAeAckBodyReorder.class);

	/** §5.1.7 CAAL — no TradeReportRefID (572) in opening block per spec table; echo from inbound elsewhere. */
	private static final int[] PRE_AL = {
		1011, 571, 1042, 22011, 1003, 487, 856, 570, 64, 1015
	};

	/** §5.1.8 CACX — minimal metadata; no Instrument / SettlDate block on outbound cancel ack in spec sample. */
	private static final int[] PRE_CX = {
		1011, 571, 572, 22011, 1003, 487, 856, 570
	};

	/** §5.1.9 CAHX — same opening structure as §5.1.6-style ack with refs. */
	private static final int[] PRE_HX = {
		1011, 571, 572, 1042, 22011, 1003, 487, 856, 570, 64, 1015
	};

	/** §5.1.10 CACR — includes original trade identifiers before trans/type block. */
	private static final int[] PRE_CR = {
		1011, 571, 572, 1042, 22011, 1003, 22012, 1126, 487, 856, 570, 64, 1015
	};

	/** §5.1.11 CAMA — Match Status; includes 573 and match control tags before Instrument. */
	private static final int[] PRE_MA = {
		1011, 571, 22011, 1003, 22027, 22028, 487, 856, 573, 570, 64
	};

	private static final int[] QTY_BLOCK = { 32, 31, 75, 60 };

	/** §5.1.7 / §5.1.9 / §5.1.10 C&A — trailing body after TrdCapRptSideGrp (same block as §5.1.6 CAEN). */
	private static final int[] POST_AL_HX_CR = {
		5149, 9854, 22013, 22005, 22003, 22004, 22016, 22006, 22009, 22022, 22034, 22036, 797
	};

	/** §5.1.7 SPAL / §5.1.9 SPHX / §5.1.10 SPCR — SP v2.1a trailing after NoSides (includes 228, 22002; sequence per SP PDF). */
	private static final int[] POST_SP_AL_HX_CR = {
		5149, 9854, 22013, 228, 22005, 22002, 22003, 22004, 22006, 22009, 22022, 22034, 22036, 797
	};

	private static final int[] POST_CX = { 797 };

	/** §5.1.11 — after sides, CopyMsgIndicator only in spec table fragment. */
	private static final int[] POST_MA = { 797 };

	private FinraTraceAeAckBodyReorder() {
	}

	/**
	 * Reorders root scalars for outbound acks (SP, CA, TS, etc.) whose {@code MessageEventSource} (1011) ends with
	 * AL, CX, HX, CR, or MA. No-op for unknown suffixes. Does not handle EN (CAEN) — use {@link FinraCaenBodyReorder}.
	 */
	public static void reorderFinraAckBody(TradeCaptureReport msg) {
		String suf = suffixFrom1011(msg);
		if (suf == null) {
			return;
		}
		boolean spProduct = "SP".equals(productPrefixFrom1011(msg));
		int[] pre;
		int[] post;
		boolean wireInstrument;
		switch (suf) {
		case "AL":
			pre = PRE_AL;
			post = spProduct ? POST_SP_AL_HX_CR : POST_AL_HX_CR;
			wireInstrument = true;
			break;
		case "CX":
			pre = PRE_CX;
			post = POST_CX;
			wireInstrument = false;
			break;
		case "HX":
			pre = PRE_HX;
			post = spProduct ? POST_SP_AL_HX_CR : POST_AL_HX_CR;
			wireInstrument = true;
			break;
		case "CR":
			pre = PRE_CR;
			post = spProduct ? POST_SP_AL_HX_CR : POST_AL_HX_CR;
			wireInstrument = true;
			break;
		case "MA":
			pre = PRE_MA;
			post = POST_MA;
			wireInstrument = true;
			break;
		default:
			return;
		}

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
			List<TradeCaptureReport.NoSides> sides = new ArrayList<>();
			for (int i = 1; i <= nSides; i++) {
				TradeCaptureReport.NoSides g = new TradeCaptureReport.NoSides();
				msg.getGroup(i, g);
				if ("MA".equals(suf) && !g.isSetField(54)) {
					continue;
				}
				sides.add(g);
			}
			if ("MA".equals(suf) && sides.size() > 2) {
				sides = new ArrayList<>(sides.subList(0, 2));
			}

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

			FinraAeBodyReorderUtil.applyOrderedTags(msg, pre, captured);
			if (hasInstrument) {
				msg.set(inst);
			}
			FinraAeBodyReorderUtil.applyOrderedTags(msg, QTY_BLOCK, captured);

			for (TradeCaptureReport.NoSides side : sides) {
				msg.addGroup(side);
			}

			FinraAeBodyReorderUtil.applyOrderedTags(msg, post, captured);
			if (!"MA".equals(suf)) {
				FinraAeBodyReorderUtil.appendLeftoverRootTags(msg, captured);
			}
		} catch (Exception e) {
			log.warn("FinraTraceAeAckBodyReorder: {}", e.getMessage());
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

	/** First two characters of MessageEventSource (e.g. SP, CA, TS). */
	private static String productPrefixFrom1011(TradeCaptureReport msg) {
		try {
			if (!msg.isSetField(1011)) {
				return "";
			}
			StringField sf = new StringField(1011);
			msg.getField(sf);
			String ev = sf.getValue();
			if (ev == null || ev.length() < 2) {
				return "";
			}
			return ev.substring(0, 2);
		} catch (FieldNotFound e) {
			return "";
		}
	}

}
