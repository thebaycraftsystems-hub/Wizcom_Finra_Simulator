package com.wizcom.fix.simulator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import quickfix.Message;
import quickfix.StringField;
import quickfix.field.AllocQty;

/**
 * FINRA TRACE (SP, CA, TS): FIX tag 80 = AllocQty (FIX 4.4). When the gateway sends 80 on an incoming AE,
 * the simulator echoes the same value on outgoing {@code TradeCaptureReport} (AE) and {@code TradeCaptureReportAck} (AR).
 */
public final class GatewayAllocQtyEcho {

	private static final Logger log = LoggerFactory.getLogger(GatewayAllocQtyEcho.class);

	private GatewayAllocQtyEcho() {
	}

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
}
