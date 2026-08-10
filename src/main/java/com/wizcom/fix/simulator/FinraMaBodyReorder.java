package com.wizcom.fix.simulator;

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
 * FINRA Corporates & Agencies / SP §5.1.11 (CAMA/SPMA) outbound AE body order.
 * Wire order matches FINRA Real-time SPMA: MatchControlDate/MatchTradeID (22027/22028)
 * appear <b>after</b> NoSecurityAltID (454/455/456) and <b>before</b> NoSides (552).
 */
public final class FinraMaBodyReorder {

	private static final Logger log = LoggerFactory.getLogger(FinraMaBodyReorder.class);

	/**
	 * FINRA RT observed body order for SPMA (root delimiters only; 455/456 live inside group 454).
	 * Example: {@code ...1011|22011|454=1|455|456|22027|22028|552=2...}
	 */
	private static final int[] FINRA_MA_WIRE_ORDER = {
		22, 31, 32, 48, 60, 64, 75,
		487, 570, 571, 573, 856, 1003, 1011, 22011,
		454,
		22027, 22028,
		552,
		797
	};

	/** Scalars before Instrument / NoSecurityAltID group. */
	private static final int[] PRE_INSTRUMENT = {
		22, 31, 32, 48, 60, 64, 75,
		487, 570, 571, 573, 856, 1003, 1011, 22011
	};

	/** Match IDs after SecurityAltID group (FINRA RT). */
	private static final int[] POST_INSTRUMENT_MATCH = { 22027, 22028 };

	/** Optional CopyMsgIndicator after NoSides. */
	private static final int[] POST_MA = { 797 };

	private FinraMaBodyReorder() {
	}

	public static void reorderMaBody(TradeCaptureReport msg) {
		if (msg == null) {
			return;
		}
		try {
			StringField sf1011 = new StringField(1011);
			if (!msg.isSetField(1011)) {
				return;
			}
			msg.getField(sf1011);
			String ev = sf1011.getValue();
			if (ev == null || !ev.endsWith("MA")) {
				return;
			}
		} catch (FieldNotFound e) {
			return;
		}

		try {
			boolean hasInstrument = msg.isSetField(48) || msg.isSetField(22) || msg.isSetField(454);
			Instrument inst = new Instrument();
			if (hasInstrument) {
				try {
					msg.get(inst);
				} catch (FieldNotFound ignored) {
					hasInstrument = false;
				}
			}

			List<TradeCaptureReport.NoSides> sides = FinraAeBodyReorderUtil.snapshotNoSidesGroups(msg, 2);

			Map<Integer, String> full = FinraAeBodyReorderUtil.snapshotRootScalars(msg);
			for (Integer tag : full.keySet()) {
				try {
					msg.removeField(tag.intValue());
				} catch (Exception e) {
					log.trace("remove tag {}: {}", tag, e.getMessage());
				}
			}
			FinraAeBodyReorderUtil.clearNoSides(msg);

			// Empty body: install FINRA field order, then re-add so QF/J emits 22027/22028 after 454.
			FinraAeBodyReorderUtil.applyBodyFieldOrder(msg, FINRA_MA_WIRE_ORDER);

			Map<Integer, String> captured = new LinkedHashMap<>(full);
			for (int t : new int[] { 48, 22, 454, 455, 456 }) {
				captured.remove(t);
			}

			FinraAeBodyReorderUtil.applyOrderedTags(msg, PRE_INSTRUMENT, captured);
			if (hasInstrument) {
				msg.set(inst);
			}
			FinraAeBodyReorderUtil.applyOrderedTags(msg, POST_INSTRUMENT_MATCH, captured);

			for (TradeCaptureReport.NoSides side : sides) {
				msg.addGroup(side);
			}

			FinraAeBodyReorderUtil.applyOrderedTags(msg, POST_MA, captured);
			FinraAeBodyReorderUtil.finalizeMaNoSidesCount(msg);
		} catch (Exception e) {
			log.warn("FinraMaBodyReorder: {}", e.getMessage());
		}
	}
}
