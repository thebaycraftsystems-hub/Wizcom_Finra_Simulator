package com.wizcom.fix.simulator;

import junit.framework.TestCase;
import quickfix.SessionID;
import quickfix.field.LastPx;
import quickfix.field.LastQty;
import quickfix.field.PartyID;
import quickfix.field.PartyRole;
import quickfix.field.SecurityID;
import quickfix.field.SettlDate;
import quickfix.field.Side;
import quickfix.field.TradeDate;
import quickfix.field.TradeReportID;
import quickfix.fix44.TradeCaptureReport;

/**
 * JPMS (GW-1) contra JPMB, and JPMB (GW-2) contra JPMS, compare into one match.
 */
public class TwoPartyMatchBookTest extends TestCase {

	private final SessionID jpms = new SessionID("FIX.4.4", "FNRA", "SP", "JPMS", "677C");
	private final SessionID jpmb = new SessionID("FIX.4.4", "FNRA", "SP", "JPMB", "677E");

	public void testOppositeGatewaysMatchAndShareOneControlNumber() {
		TwoPartyMatchBook book = new TwoPartyMatchBook();
		TwoPartyMatchBook.OpenTrade sell = book.create(report("JPMS", '2', "JPMB", '1', "89.00", "6000"), jpms, "SP",
				"1000000081", "20260102");
		TwoPartyMatchBook.OpenTrade buy = book.create(report("JPMB", '1', "JPMS", '2', "89.0", "6000.00"), jpmb, "SP",
				"1000000082", "20260102");

		assertNotNull(sell);
		assertNotNull(buy);
		book.park(sell);
		TwoPartyMatchBook.OpenTrade contra = book.takeContra(buy);
		assertSame(sell, contra);
		String matchId = book.allocateMatchId("SP", true);
		String otherId = book.allocateMatchId("SP", true);
		assertFalse(matchId.equals(otherId));
		assertTrue(matchId.startsWith("1"));
		assertEquals(10, matchId.length());
		book.rememberPair(buy, contra, matchId);
		assertSame(sell, book.findReferenced(reportRef("1000000081")));
		assertEquals(matchId, sell.matchTradeId);
		assertEquals(matchId, buy.matchTradeId);
	}

	public void testSameSideDoesNotMatch() {
		TwoPartyMatchBook book = new TwoPartyMatchBook();
		TwoPartyMatchBook.OpenTrade first = book.create(report("JPMS", '2', "JPMB", '1', "89", "100"), jpms, "SP",
				"1000000001", "20260102");
		TwoPartyMatchBook.OpenTrade second = book.create(report("JPMS", '2', "JPMB", '1', "89", "100"), jpms, "SP",
				"1000000002", "20260102");
		book.park(first);
		assertNull(book.takeContra(second));
	}

	public void testDifferentPriceDoesNotMatch() {
		TwoPartyMatchBook book = new TwoPartyMatchBook();
		TwoPartyMatchBook.OpenTrade sell = book.create(report("JPMS", '2', "JPMB", '1', "89", "100"), jpms, "SP",
				"1000000003", "20260102");
		TwoPartyMatchBook.OpenTrade buy = book.create(report("JPMB", '1', "JPMS", '2', "90", "100"), jpmb, "SP",
				"1000000004", "20260102");
		book.park(sell);
		assertNull(book.takeContra(buy));
	}

	public void testUnmatchedControlNumberUsesProductPrefix() {
		TwoPartyMatchBook book = new TwoPartyMatchBook();
		assertTrue(book.allocateMatchId("CA", false).startsWith("221"));
		assertTrue(book.allocateMatchId("TS", false).startsWith("721"));
		assertTrue(book.allocateMatchId("SP", false).startsWith("121"));
		assertEquals(10, book.allocateMatchId("CA", true).length());
	}

	public void testCancelLookupFindsMatchedTradeByOriginalTradeId() {
		TwoPartyMatchBook book = new TwoPartyMatchBook();
		TwoPartyMatchBook.OpenTrade sell = book.create(report("JPMS", '2', "JPMB", '1', "89", "100"), jpms, "SP",
				"1000000081", "20260102");
		TwoPartyMatchBook.OpenTrade buy = book.create(report("JPMB", '1', "JPMS", '2', "89", "100"), jpmb, "SP",
				"1000000082", "20260102");
		book.rememberPair(sell, buy, "1000000107");
		TwoPartyMatchBook.OpenTrade found = book.findReferenced(reportRef("1000000081"));
		assertSame(sell, found);
		assertSame(buy, found.counterparty);
		book.forget(sell);
		book.forget(buy);
		book.park(buy);
		assertNull(book.findReferenced(reportRef("1000000081")));
		assertSame(buy, book.findReferenced(reportRef("1000000082")));
	}

	private static TradeCaptureReport report(String exec, char execSide, String contra, char contraSide, String px,
			String qty) {
		TradeCaptureReport msg = new TradeCaptureReport();
		msg.set(new TradeReportID("CLIENT-1"));
		msg.set(new SecurityID("01F0504A1"));
		msg.set(new LastQty(Double.parseDouble(qty)));
		msg.set(new LastPx(Double.parseDouble(px)));
		msg.set(new TradeDate("20260102"));
		msg.set(new SettlDate("20260103"));
		msg.addGroup(side(execSide, exec, 1));
		msg.addGroup(side(contraSide, contra, 17));
		return msg;
	}

	private static TradeCaptureReport reportRef(String tradeId) {
		TradeCaptureReport msg = new TradeCaptureReport();
		msg.setString(1003, tradeId);
		return msg;
	}

	private static TradeCaptureReport.NoSides side(char sideVal, String mpid, int role) {
		TradeCaptureReport.NoSides group = new TradeCaptureReport.NoSides();
		group.set(new Side(sideVal));
		TradeCaptureReport.NoSides.NoPartyIDs party = new TradeCaptureReport.NoSides.NoPartyIDs();
		party.set(new PartyID(mpid));
		party.set(new PartyRole(role));
		group.addGroup(party);
		return group;
	}
}
