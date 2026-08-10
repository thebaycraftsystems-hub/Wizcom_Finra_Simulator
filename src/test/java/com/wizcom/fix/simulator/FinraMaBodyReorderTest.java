package com.wizcom.fix.simulator;

import junit.framework.TestCase;
import quickfix.StringField;
import quickfix.field.LastPx;
import quickfix.field.LastQty;
import quickfix.field.MatchStatus;
import quickfix.field.MessageEventSource;
import quickfix.field.NoSecurityAltID;
import quickfix.field.NoSides;
import quickfix.field.OrderID;
import quickfix.field.PartyID;
import quickfix.field.PartyIDSource;
import quickfix.field.PartyRole;
import quickfix.field.PreviouslyReported;
import quickfix.field.SecurityAltID;
import quickfix.field.SecurityAltIDSource;
import quickfix.field.SecurityID;
import quickfix.field.SecurityIDSource;
import quickfix.field.Side;
import quickfix.field.TradeDate;
import quickfix.field.TradeReportID;
import quickfix.field.TradeReportTransType;
import quickfix.fix44.TradeCaptureReport;
import quickfix.fix44.component.Instrument;

/**
 * Verifies SPMA/CAMA body wire order matches FINRA RT:
 * {@code 22011 → 454/455/456 → 22027 → 22028 → 552}.
 */
public class FinraMaBodyReorderTest extends TestCase {

	public void testSpmaWireOrderHasMatchTagsAfterSecurityAltId() throws Exception {
		TradeCaptureReport ma = new TradeCaptureReport();
		ma.setField(new MessageEventSource("SPMA"));
		ma.setField(new TradeReportID("216:677E:00297268"));
		ma.setField(new StringField(22011, "20260806"));
		ma.setField(new StringField(1003, "1000000161"));
		ma.setField(new StringField(22027, "20260806"));
		ma.setField(new StringField(22028, "1000000161"));
		ma.setField(new TradeReportTransType(3));
		ma.setField(new StringField(856, "2"));
		ma.setField(new MatchStatus('0'));
		ma.setField(new PreviouslyReported(false));
		ma.setField(new LastQty(5000000.00));
		ma.setField(new LastPx(89.00));
		ma.setField(new TradeDate("20260806"));
		ma.setField(new StringField(60, "20260806-12:00:01"));
		ma.setField(new StringField(64, "20260807"));

		Instrument inst = new Instrument();
		inst.set(new SecurityID("01F0504A1"));
		inst.set(new SecurityIDSource(SecurityIDSource.CUSIP));
		inst.set(new NoSecurityAltID(1));
		TradeCaptureReport.NoSecurityAltID alt = new TradeCaptureReport.NoSecurityAltID();
		alt.set(new SecurityAltID("UMB54816535"));
		alt.set(new SecurityAltIDSource("8"));
		inst.addGroup(alt);
		ma.set(inst);

		TradeCaptureReport.NoSides buy = new TradeCaptureReport.NoSides();
		buy.set(new Side(Side.BUY));
		buy.set(new OrderID("NONE"));
		TradeCaptureReport.NoSides.NoPartyIDs p1 = new TradeCaptureReport.NoSides.NoPartyIDs();
		p1.set(new PartyID("JPMB"));
		p1.set(new PartyIDSource(PartyIDSource.GENERALLY_ACCEPTED_MARKET_PARTICIPANT_IDENTIFIER));
		p1.set(new PartyRole(1));
		buy.addGroup(p1);
		ma.addGroup(buy);

		TradeCaptureReport.NoSides sell = new TradeCaptureReport.NoSides();
		sell.set(new Side(Side.SELL));
		sell.set(new OrderID("NONE"));
		TradeCaptureReport.NoSides.NoPartyIDs p2 = new TradeCaptureReport.NoSides.NoPartyIDs();
		p2.set(new PartyID("JPMS"));
		p2.set(new PartyIDSource(PartyIDSource.GENERALLY_ACCEPTED_MARKET_PARTICIPANT_IDENTIFIER));
		p2.set(new PartyRole(17));
		sell.addGroup(p2);
		ma.addGroup(sell);
		ma.setField(new NoSides(2));

		FinraMaBodyReorder.reorderMaBody(ma);

		String wire = ma.toString().replace('\001', '|');
		int i22011 = wire.indexOf("22011=");
		int i454 = wire.indexOf("|454=");
		if (i454 < 0) {
			i454 = wire.indexOf("454=");
		}
		int i22027 = wire.indexOf("22027=");
		int i22028 = wire.indexOf("22028=");
		int i552 = wire.indexOf("|552=");
		if (i552 < 0) {
			i552 = wire.indexOf("552=");
		}

		assertTrue("22011 present", i22011 >= 0);
		assertTrue("454 present", i454 >= 0);
		assertTrue("22027 present", i22027 >= 0);
		assertTrue("22028 present", i22028 >= 0);
		assertTrue("552 present", i552 >= 0);

		assertTrue("22011 before 454: " + wire, i22011 < i454);
		assertTrue("454 before 22027: " + wire, i454 < i22027);
		assertTrue("22027 before 22028: " + wire, i22027 < i22028);
		assertTrue("22028 before 552: " + wire, i22028 < i552);
		assertTrue("22028 equals 1003 on wire", wire.contains("1003=1000000161") && wire.contains("22028=1000000161"));
	}
}
