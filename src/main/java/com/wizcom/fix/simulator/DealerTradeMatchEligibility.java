package com.wizcom.fix.simulator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import quickfix.FieldNotFound;
import quickfix.StringField;
import quickfix.fix44.TradeCaptureReport;

/**
 * FINRA §5.1.11 / §5.2.7: Match Status (SPMA/CAMA/TSMA) applies to dealer-to-dealer trades only.
 * Customer ({@code 448=C}), affiliate ({@code 448=A}), and locked-in trades do not receive MA.
 */
public final class DealerTradeMatchEligibility {

	private static final Logger log = LoggerFactory.getLogger(DealerTradeMatchEligibility.class);

	private static final int PARTY_ROLE_CONTRA = 17;

	private DealerTradeMatchEligibility() {
	}

	public static boolean isEligibleForMatchStatus(TradeCaptureReport msg) {
		if (msg == null) {
			return false;
		}
		if (isLockedIn(msg)) {
			log.debug("Match Status skipped: locked-in trade (22013=Y or 1042 on NEW).");
			return false;
		}
		if (hasCustomerOrAffiliateContra(msg)) {
			log.debug("Match Status skipped: customer or affiliate contra party (448=C or A).");
			return false;
		}
		return true;
	}

	private static boolean isLockedIn(TradeCaptureReport msg) {
		try {
			if (msg.isSetField(22013)) {
				StringField sf = new StringField(22013);
				msg.getField(sf);
				String v = sf.getValue();
				if (v != null && ("Y".equalsIgnoreCase(v.trim()) || "1".equals(v.trim()))) {
					return true;
				}
			}
		} catch (FieldNotFound ignored) {
		}
		try {
			int transType = msg.getTradeReportTransType().getValue();
			if (transType == 0 && msg.isSetField(1042)) {
				StringField sf = new StringField(1042);
				msg.getField(sf);
				if (sf.getValue() != null && !sf.getValue().trim().isEmpty()) {
					return true;
				}
			}
		} catch (FieldNotFound ignored) {
		}
		return false;
	}

	private static boolean hasCustomerOrAffiliateContra(TradeCaptureReport msg) {
		try {
			int n = msg.getNoSides().getValue();
			for (int i = 1; i <= n; i++) {
				TradeCaptureReport.NoSides side = new TradeCaptureReport.NoSides();
				msg.getGroup(i, side);
				int parties = 0;
				try {
					parties = side.getParties().getNoPartyIDs().getValue();
				} catch (FieldNotFound ignored) {
					continue;
				}
				for (int p = 1; p <= parties; p++) {
					TradeCaptureReport.NoSides.NoPartyIDs party = new TradeCaptureReport.NoSides.NoPartyIDs();
					side.getGroup(p, party);
					try {
						if (party.getPartyRole().getValue() != PARTY_ROLE_CONTRA) {
							continue;
						}
						String partyId = party.getPartyID().getValue();
						if (partyId != null) {
							String id = partyId.trim();
							if ("C".equalsIgnoreCase(id) || "A".equalsIgnoreCase(id)) {
								return true;
							}
						}
					} catch (FieldNotFound ignored) {
					}
				}
			}
		} catch (FieldNotFound ignored) {
		}
		return false;
	}
}
