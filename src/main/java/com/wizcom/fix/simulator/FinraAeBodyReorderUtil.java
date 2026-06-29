package com.wizcom.fix.simulator;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import quickfix.FieldNotFound;
import quickfix.Message;
import quickfix.StringField;
import quickfix.field.NoSides;
import quickfix.field.OrderID;
import quickfix.field.Side;
import quickfix.fix44.TradeCaptureReport;

/**
 * Shared helpers for FINRA AE (35=AE) body reorder: NoSides-only tags must not appear on the message root
 * (JPMS rejects e.g. "Tag not defined for this message type, field=37"). QuickFIX/J exposes group fields via
 * {@link Message#isSetField(int)} on the parent, so reorder snapshot/leftover logic must exclude them.
 */
public final class FinraAeBodyReorderUtil {

	private static final Logger log = LoggerFactory.getLogger(FinraAeBodyReorderUtil.class);

	/** Valid only inside NoSides (552) or nested party/allocation groups — never on AE root. */
	private static final int[] NO_SIDES_ONLY_BODY_TAGS = {
		12, 13, 37, 44, 54, 58, 80, 447, 448, 452, 453, 523, 528, 583, 802, 803
	};

	private FinraAeBodyReorderUtil() {
	}

	public static boolean isNoSidesOnlyBodyTag(int tag) {
		for (int t : NO_SIDES_ONLY_BODY_TAGS) {
			if (t == tag) {
				return true;
			}
		}
		return false;
	}

	/** Remove NoSides-only tags from AE message root (first send and resend via {@code toApp}). */
	public static void stripNoSidesTagsFromRoot(Message msg) {
		if (msg == null) {
			return;
		}
		for (int tag : NO_SIDES_ONLY_BODY_TAGS) {
			try {
				msg.removeField(tag);
			} catch (Exception e) {
				log.trace("stripNoSidesTagsFromRoot remove {}: {}", tag, e.getMessage());
			}
		}
	}

	/**
	 * Snapshot scalar root tags for reorder; skips 552 and tags that belong in NoSides only.
	 */
	public static Map<Integer, String> snapshotRootScalars(TradeCaptureReport msg) throws FieldNotFound {
		Map<Integer, String> captured = new LinkedHashMap<>();
		for (int tag = 1; tag <= 23000; tag++) {
			if (tag == 552 || isNoSidesOnlyBodyTag(tag)) {
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

	/** Append leftover captured tags that are safe on AE root (never NoSides-only tags). */
	public static void appendLeftoverRootTags(TradeCaptureReport msg, Map<Integer, String> captured) {
		for (Map.Entry<Integer, String> e : captured.entrySet()) {
			if (e.getKey() == null || e.getValue() == null) {
				continue;
			}
			if (isNoSidesOnlyBodyTag(e.getKey())) {
				continue;
			}
			try {
				msg.setField(new StringField(e.getKey(), e.getValue()));
			} catch (Exception ex) {
				log.trace("append leftover tag {}: {}", e.getKey(), ex.getMessage());
			}
		}
	}

	public static void applyOrderedTags(TradeCaptureReport msg, int[] order, Map<Integer, String> captured) {
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

	/** NoSides groups that have Side (54) set — matches what JPMS counts on the wire. */
	public static int countPopulatedNoSides(TradeCaptureReport msg) {
		try {
			int declared = msg.getNoSides().getValue();
			int populated = 0;
			for (int i = 1; i <= declared; i++) {
				TradeCaptureReport.NoSides g = new TradeCaptureReport.NoSides();
				try {
					msg.getGroup(i, g);
					if (g.isSetField(new Side())) {
						populated++;
					}
				} catch (Exception ignored) {
					// missing or empty group slot
				}
			}
			return populated;
		} catch (FieldNotFound e) {
			return 0;
		}
	}

	/** Set 552 to the number of populated NoSides groups (avoids 373=16 Incorrect NumInGroup). */
	public static void syncNoSidesCountToPopulatedGroups(TradeCaptureReport msg) {
		int populated = countPopulatedNoSides(msg);
		if (populated < 1) {
			return;
		}
		try {
			msg.setField(new NoSides(populated));
		} catch (Exception e) {
			log.trace("syncNoSidesCount: {}", e.getMessage());
		}
	}

	/**
	 * Rebuild NoSides from at most {@code maxGroups} populated groups and set 552 (FIX44 allows only 1 or 2).
	 */
	public static void finalizeNoSidesCount(TradeCaptureReport msg, int maxGroups) {
		if (msg == null || maxGroups < 1) {
			return;
		}
		List<TradeCaptureReport.NoSides> sides = snapshotNoSidesGroups(msg, maxGroups);
		clearNoSides(msg);
		for (TradeCaptureReport.NoSides side : sides) {
			msg.addGroup(side);
		}
		int populated = countPopulatedNoSides(msg);
		if (populated < 1) {
			return;
		}
		int n = Math.min(populated, maxGroups);
		if (n > 2) {
			n = 2;
		}
		try {
			msg.setField(new NoSides(n));
		} catch (Exception e) {
			log.trace("finalizeNoSidesCount: {}", e.getMessage());
		}
	}

	/**
	 * CAMA/SPMA/TSMA: JPMS FIX44 allows only {@code 552=1} or {@code 552=2}. Rebuild from at most two
	 * populated groups so reorder/resend cannot leave {@code 552=5} with extra NoSides on the wire.
	 */
	public static void finalizeMaNoSidesCount(TradeCaptureReport msg) {
		finalizeNoSidesCount(msg, 2);
	}

	public static boolean hasRootSideTags(Message msg) {
		return msg != null && (msg.isSetField(54) || msg.isSetField(37) || msg.isSetField(453));
	}

	public static void clearNoSides(TradeCaptureReport msg) {
		if (msg == null) {
			return;
		}
		try {
			for (int attempt = 0; attempt < 32; attempt++) {
				int n;
				try {
					n = msg.getNoSides().getValue();
				} catch (FieldNotFound e) {
					break;
				}
				if (n < 1) {
					break;
				}
				TradeCaptureReport.NoSides g = new TradeCaptureReport.NoSides();
				msg.getGroup(1, g);
				msg.removeGroup(g);
			}
		} catch (Exception e) {
			log.trace("clearNoSides groups: {}", e.getMessage());
		}
		try {
			msg.removeField(552);
		} catch (Exception e) {
			log.trace("clearNoSides: {}", e.getMessage());
		}
	}

	/**
	 * Copy populated {@code NoSides} groups before reorder. For MA, keep at most two groups that have Side (54).
	 */
	public static java.util.List<TradeCaptureReport.NoSides> snapshotNoSidesGroups(TradeCaptureReport msg,
			int maxGroups) {
		java.util.List<TradeCaptureReport.NoSides> sides = new java.util.ArrayList<>();
		try {
			int n = msg.getNoSides().getValue();
			for (int i = 1; i <= n; i++) {
				if (maxGroups > 0 && sides.size() >= maxGroups) {
					break;
				}
				TradeCaptureReport.NoSides g = new TradeCaptureReport.NoSides();
				msg.getGroup(i, g);
				if (maxGroups > 0) {
					try {
						if (!g.isSetField(new Side())) {
							continue;
						}
					} catch (Exception ignored) {
						continue;
					}
				}
				sides.add(g);
			}
		} catch (FieldNotFound ignored) {
		}
		return sides;
	}

	/**
	 * FINRA §5.1.6 / §5.2.x body tag order — same path for first send and PossDup resend (43=Y).
	 * Strips illegal root 12/13/80 first, then reorders root blocks and preserves {@code NoSides} groups.
	 */
	public static void reorderOutboundAeBody(TradeCaptureReport msg) {
		if (msg == null) {
			return;
		}
		GatewayAllocQtyEcho.stripRootLevelPricingTags(msg);
		try {
			if (!msg.isSetField(1011)) {
				return;
			}
			StringField sf1011 = new StringField(1011);
			msg.getField(sf1011);
			String ev = sf1011.getValue();
			if (ev == null || ev.length() < 2) {
				return;
			}
			String suffix = ev.substring(ev.length() - 2);
			String prefix = ev.length() >= 2 ? ev.substring(0, 2) : "";
			if ("MA".equals(suffix)) {
				if ("TS".equals(prefix)) {
					FinraTsAckBodyReorder.reorderFinraTsAckBody(msg);
				} else {
					FinraMaBodyReorder.reorderMaBody(msg);
				}
			} else if ("EN".equals(suffix) || "CR".equals(suffix)) {
				if ("SP".equals(prefix)) {
					FinraSpAckBodyReorder.reorderSpenAcknowledgementBody(msg);
				} else if ("TS".equals(prefix)) {
					FinraTsAckBodyReorder.reorderTsenAcknowledgementBody(msg);
				} else {
					FinraCaenBodyReorder.reorderCaenAcknowledgementBody(msg);
				}
			} else if ("TS".equals(prefix)) {
				FinraTsAckBodyReorder.reorderFinraTsAckBody(msg);
			} else {
				FinraTraceAeAckBodyReorder.reorderFinraAckBody(msg);
			}
		} catch (FieldNotFound e) {
			log.trace("reorderOutboundAeBody: {}", e.getMessage());
		} catch (Exception e) {
			log.warn("reorderOutboundAeBody: {}", e.getMessage());
		}
	}
}
