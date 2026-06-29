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
 * Only tags listed in the Match Status spec table are wired; no leftover append.
 */
public final class FinraMaBodyReorder {

	private static final Logger log = LoggerFactory.getLogger(FinraMaBodyReorder.class);

	/** §5.1.11 — identifiers and match metadata before Instrument. */
	private static final int[] PRE_MA = {
		1011, 571, 22011, 1003, 22027, 22028, 487, 856, 573, 570, 64
	};

	private static final int[] QTY_BLOCK = { 32, 31, 75, 60 };

	/** §5.1.11 — optional CopyMsgIndicator after NoSides (omit if not set). */
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

			Map<Integer, String> captured = new LinkedHashMap<>(full);
			for (int t : new int[] { 48, 22, 454, 455, 456 }) {
				captured.remove(t);
			}

			FinraAeBodyReorderUtil.applyOrderedTags(msg, PRE_MA, captured);
			if (hasInstrument) {
				msg.set(inst);
			}
			FinraAeBodyReorderUtil.applyOrderedTags(msg, QTY_BLOCK, captured);

			for (TradeCaptureReport.NoSides side : sides) {
				msg.addGroup(side);
			}

			FinraAeBodyReorderUtil.applyOrderedTags(msg, POST_MA, captured);
			// §5.1.11: do not append tags outside the spec table
		} catch (Exception e) {
			log.warn("FinraMaBodyReorder: {}", e.getMessage());
		}
	}
}
