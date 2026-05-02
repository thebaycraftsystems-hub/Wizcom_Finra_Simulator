package com.wizcom.fix.simulator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import quickfix.Message;
import quickfix.StringField;
import quickfix.field.AllocAccount;
import quickfix.field.AllocQty;
import quickfix.field.CommType;
import quickfix.field.Commission;
import quickfix.field.OrderCapacity;
import quickfix.field.Text;
import quickfix.fix44.TradeCaptureReport;
import quickfix.fix44.component.CommissionData;

/**
 * FINRA TRACE: Commission (12)/CommType (13) belong in {@link CommissionData}; AllocQty (80) belongs in the
 * {@code NoSides.NoAllocs} repeating group per FIX 4.4 / JPMS validation — not on the {@code NoSides} root.
 * Each {@code NoAllocs} instance must include delimiter {@link AllocAccount} (79) before {@link AllocQty} (80).
 */
public final class GatewayAllocQtyEcho {

	private static final Logger log = LoggerFactory.getLogger(GatewayAllocQtyEcho.class);

	private GatewayAllocQtyEcho() {
	}

	/** Values extracted from inbound AE for echo onto reporting side 1. */
	public static final class InboundSidePricing {
		public final Double commission;
		public final Character commType;
		public final Double allocQty;
		/** From inbound {@code NoSides.NoAllocs.AllocAccount} when present. */
		public final String allocAccount;

		public InboundSidePricing(Double commission, Character commType, Double allocQty, String allocAccount) {
			this.commission = commission;
			this.commType = commType;
			this.allocQty = allocQty;
			this.allocAccount = allocAccount;
		}
	}

	public static InboundSidePricing extractInboundSidePricing(TradeCaptureReport inbound) {
		if (inbound == null) {
			return new InboundSidePricing(null, null, null, null);
		}
		return new InboundSidePricing(
				extractNumericFromInbound(inbound, 12),
				extractCommTypeFromInbound(inbound),
				extractNumericFromInbound(inbound, 80),
				extractAllocAccountFromInbound(inbound));
	}

	/**
	 * JPMS rejects {@code NoAllocs} without delimiter 79; use inbound account when missing.
	 */
	public static String allocAccountForOutbound(String fromInbound) {
		if (fromInbound != null && !fromInbound.trim().isEmpty()) {
			return fromInbound.trim();
		}
		return "DEFAULT";
	}

	/**
	 * Legacy: set AllocQty on any outgoing {@link Message} root (used for AR rejects where applicable).
	 */
	public static void copyTag80IfPresent(Message gatewayRequest, Message simulatorResponse) {
		if (gatewayRequest == null || simulatorResponse == null) {
			return;
		}
		try {
			if (!gatewayRequest.isSetField(80)) {
				return;
			}
			double v = gatewayRequest.getDouble(80);
			simulatorResponse.setField(new AllocQty(v));
		} catch (Exception e) {
			try {
				if (gatewayRequest.isSetField(80)) {
					StringField sf = new StringField(80);
					gatewayRequest.getField(sf);
					simulatorResponse.setField(sf);
				}
			} catch (Exception e2) {
				log.trace("copyTag80 AllocQty: {}", e2.getMessage());
			}
		}
	}

	/**
	 * Rebuilds outbound side 1 with FIX 4.4 component order: OrderCapacity → CommissionData → Text → NoAllocs(AllocQty).
	 * Used when inbound carries 12/13/80 (HX/CX/Allege paths).
	 */
	public static void copyInboundPricingToReportingSide(TradeCaptureReport inbound, TradeCaptureReport outbound) {
		if (inbound == null || outbound == null) {
			return;
		}
		try {
			int n = outbound.getNoSides().getValue();
			if (n < 1) {
				return;
			}
			InboundSidePricing p = extractInboundSidePricing(inbound);
			if (p.commission == null && p.commType == null && p.allocQty == null) {
				return;
			}
			TradeCaptureReport.NoSides oldSide = new TradeCaptureReport.NoSides();
			outbound.getGroup(1, oldSide);
			TradeCaptureReport.NoSides rebuilt = rebuildReportingSideWithPricing(oldSide, p);
			outbound.replaceGroup(1, rebuilt);
		} catch (Exception e) {
			log.trace("copyInboundPricingToReportingSide: {}", e.getMessage());
		}
	}

	/**
	 * FIX 4.4 {@code NoSides} order for AE: parties … OrderCapacity → CommissionData (optional) → Text → NoAllocs (optional).
	 */
	static TradeCaptureReport.NoSides rebuildReportingSideWithPricing(TradeCaptureReport.NoSides template,
			InboundSidePricing p) {
		TradeCaptureReport.NoSides neu = new TradeCaptureReport.NoSides();
		try {
			neu.set(template.getSide());
			neu.set(template.getOrderID());
			int npc = template.getParties().getNoPartyIDs().getValue();
			for (int pi = 1; pi <= npc; pi++) {
				TradeCaptureReport.NoSides.NoPartyIDs partyGrp = new TradeCaptureReport.NoSides.NoPartyIDs();
				template.getGroup(pi, partyGrp);
				neu.addGroup(partyGrp);
			}
			if (template.isSetOrderCapacity()) {
				neu.set(template.getOrderCapacity());
			} else {
				neu.set(new OrderCapacity(OrderCapacity.PRINCIPAL));
			}
			if (p.commission != null || p.commType != null) {
				CommissionData cd = new CommissionData();
				if (p.commission != null) {
					cd.set(new Commission(p.commission.doubleValue()));
				}
				if (p.commType != null) {
					cd.set(new CommType(p.commType.charValue()));
				}
				neu.set(cd);
			}
			if (template.isSetField(58)) {
				neu.set(template.getText());
			} else {
				neu.set(new Text("UBS"));
			}
			if (p.allocQty != null) {
				TradeCaptureReport.NoSides.NoAllocs allocGrp = new TradeCaptureReport.NoSides.NoAllocs();
				allocGrp.set(new AllocAccount(allocAccountForOutbound(p.allocAccount)));
				allocGrp.set(new AllocQty(p.allocQty.doubleValue()));
				neu.addGroup(allocGrp);
			}
		} catch (Exception e) {
			log.warn("rebuildReportingSideWithPricing: {}", e.getMessage());
		}
		return neu;
	}

	/** Removes misplaced root-level pricing tags on AE body. */
	public static void stripRootLevelPricingTags(TradeCaptureReport msg) {
		if (msg == null) {
			return;
		}
		try {
			msg.removeField(12);
		} catch (Exception e) {
			log.trace("stripRoot 12: {}", e.getMessage());
		}
		try {
			msg.removeField(13);
		} catch (Exception e) {
			log.trace("stripRoot 13: {}", e.getMessage());
		}
		try {
			msg.removeField(80);
		} catch (Exception e) {
			log.trace("stripRoot 80: {}", e.getMessage());
		}
	}

	static Double extractNumericFromInbound(TradeCaptureReport m, int tag) {
		try {
			if (m.isSetField(tag)) {
				StringField sf = new StringField(tag);
				m.getField(sf);
				return Double.parseDouble(sf.getValue());
			}
		} catch (Exception e) {
			log.trace("extractNumeric root {}: {}", tag, e.getMessage());
		}
		try {
			if (m.getNoSides().getValue() >= 1) {
				TradeCaptureReport.NoSides g = new TradeCaptureReport.NoSides();
				m.getGroup(1, g);
				if (g.isSetField(tag)) {
					StringField sf = new StringField(tag);
					g.getField(sf);
					return Double.parseDouble(sf.getValue());
				}
				// AllocQty may appear inside NoAllocs on inbound
				if (tag == 80 && g.getNoAllocs().getValue() >= 1) {
					TradeCaptureReport.NoSides.NoAllocs ag = new TradeCaptureReport.NoSides.NoAllocs();
					g.getGroup(1, ag);
					if (ag.isSetAllocQty()) {
						return ag.getAllocQty().getValue();
					}
				}
			}
		} catch (Exception e) {
			log.trace("extractNumeric side/allocs {}: {}", tag, e.getMessage());
		}
		return null;
	}

	static String extractAllocAccountFromInbound(TradeCaptureReport m) {
		try {
			if (m.getNoSides().getValue() >= 1) {
				TradeCaptureReport.NoSides g = new TradeCaptureReport.NoSides();
				m.getGroup(1, g);
				if (g.getNoAllocs().getValue() >= 1) {
					TradeCaptureReport.NoSides.NoAllocs ag = new TradeCaptureReport.NoSides.NoAllocs();
					g.getGroup(1, ag);
					if (ag.isSetAllocAccount()) {
						return ag.getAllocAccount().getValue();
					}
				}
			}
		} catch (Exception e) {
			log.trace("extractAllocAccount side/allocs: {}", e.getMessage());
		}
		return null;
	}

	static Character extractCommTypeFromInbound(TradeCaptureReport m) {
		try {
			if (m.isSetField(13)) {
				return m.getField(new CommType()).getValue();
			}
		} catch (Exception e) {
			log.trace("extractCommType root: {}", e.getMessage());
		}
		try {
			if (m.getNoSides().getValue() >= 1) {
				TradeCaptureReport.NoSides g = new TradeCaptureReport.NoSides();
				m.getGroup(1, g);
				if (g.isSetField(13)) {
					return g.getField(new CommType()).getValue();
				}
			}
		} catch (Exception e) {
			log.trace("extractCommType side1: {}", e.getMessage());
		}
		return null;
	}
}
