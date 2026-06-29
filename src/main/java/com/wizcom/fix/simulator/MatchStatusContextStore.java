package com.wizcom.fix.simulator;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import quickfix.FieldNotFound;
import quickfix.StringField;
import quickfix.fix44.TradeCaptureReport;

/**
 * In-memory ack context linking inbound client {@code 571} to FINRA ids for Match Status (MA) responses.
 */
public final class MatchStatusContextStore {

	private static final Logger log = LoggerFactory.getLogger(MatchStatusContextStore.class);

	private final ConcurrentMap<String, MatchStatusContext> byKey = new ConcurrentHashMap<>();

	public static final class MatchStatusContext {
		public final String clientTradeReportId;
		public final String finraTradeReportId;
		public final String tradeId;
		public final String controlDate;
		public final String productPrefix;
		public volatile boolean matched;
		public volatile String matchTradeId;

		public MatchStatusContext(String clientTradeReportId, String finraTradeReportId, String tradeId,
				String controlDate, String productPrefix) {
			this.clientTradeReportId = clientTradeReportId;
			this.finraTradeReportId = finraTradeReportId;
			this.tradeId = tradeId;
			this.controlDate = controlDate;
			this.productPrefix = productPrefix;
			this.matched = false;
		}
	}

	public void recordFromAck(TradeCaptureReport inboundReq, TradeCaptureReport ack, String productPrefix) {
		if (inboundReq == null || ack == null) {
			return;
		}
		try {
			String inboundId = inboundReq.getTradeReportID().getValue();
			String clientId = inboundId;
			if (inboundReq.isSetField(572)) {
				String ref = inboundReq.getTradeReportRefID().getValue();
				if (ref != null && !ref.trim().isEmpty()) {
					clientId = ref.trim();
				}
			}
			String finraId = ack.getTradeReportID().getValue();
			String tradeId = null;
			if (ack.isSetField(1003)) {
				tradeId = ack.getField(new StringField(1003)).getValue();
			}
			String controlDate = null;
			if (ack.isSetField(22011)) {
				controlDate = ack.getField(new StringField(22011)).getValue();
			}
			MatchStatusContext prior = byKey.get(clientId);
			MatchStatusContext ctx = new MatchStatusContext(clientId, finraId, tradeId, controlDate, productPrefix);
			if (prior != null) {
				ctx.matched = prior.matched;
				ctx.matchTradeId = prior.matchTradeId;
			}
			byKey.put(clientId, ctx);
			byKey.put(finraId, ctx);
			if (!clientId.equals(inboundId)) {
				byKey.put(inboundId, ctx);
			}
			log.debug("MatchStatusContext recorded: client571={} finra571={} tradeId={}", clientId, finraId, tradeId);
		} catch (FieldNotFound e) {
			log.trace("recordFromAck: {}", e.getMessage());
		}
	}

	public MatchStatusContext resolve(TradeCaptureReport inboundReq, TradeCaptureReport ack, String productPrefix) {
		MatchStatusContext ctx = lookup(inboundReq);
		if (ctx != null) {
			return ctx;
		}
		if (ack != null) {
			try {
				String finraId = ack.getTradeReportID().getValue();
				ctx = byKey.get(finraId);
				if (ctx != null) {
					return ctx;
				}
			} catch (FieldNotFound ignored) {
			}
		}
		try {
			String clientId = inboundReq.getTradeReportID().getValue();
			String tradeId = inboundReq.isSetField(1003)
					? inboundReq.getField(new StringField(1003)).getValue()
					: null;
			String controlDate = inboundReq.isSetField(22011)
					? inboundReq.getField(new StringField(22011)).getValue()
					: null;
			String finraId = ack != null ? ack.getTradeReportID().getValue() : clientId;
			ctx = new MatchStatusContext(clientId, finraId, tradeId, controlDate, productPrefix);
			byKey.put(clientId, ctx);
			return ctx;
		} catch (FieldNotFound e) {
			log.trace("resolve fallback: {}", e.getMessage());
			return null;
		}
	}

	public void markMatched(String clientOrFinraId, String matchTradeId) {
		MatchStatusContext ctx = byKey.get(clientOrFinraId);
		if (ctx != null) {
			ctx.matched = true;
			ctx.matchTradeId = matchTradeId;
		}
	}

	private MatchStatusContext lookup(TradeCaptureReport inboundReq) {
		try {
			if (inboundReq.isSetField(572)) {
				String ref = inboundReq.getTradeReportRefID().getValue();
				if (ref != null) {
					MatchStatusContext ctx = byKey.get(ref);
					if (ctx != null) {
						return ctx;
					}
				}
			}
			return byKey.get(inboundReq.getTradeReportID().getValue());
		} catch (FieldNotFound e) {
			return null;
		}
	}
}
