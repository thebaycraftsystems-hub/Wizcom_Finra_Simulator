package com.wizcom.fix.simulator;

import java.io.IOException;
import java.lang.reflect.Method;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import quickfix.FieldNotFound;
import quickfix.Message;
import quickfix.MessageStore;
import quickfix.Session;
import quickfix.field.MsgSeqNum;

/**
 * Reads the engine's next outgoing MsgSeqNum (same semantics as JdbcStore {@code outgoing_seqnum}).
 */
public final class SessionSequenceUtil {

	private static final Logger log = LoggerFactory.getLogger(SessionSequenceUtil.class);

	private SessionSequenceUtil() {
	}

	/**
	 * Next sender sequence the engine will assign (QuickFIX/J {@link Session#getNextSenderMsgSeqNum()} /
	 * {@link MessageStore#getNextSenderMsgSeqNum()}). After sending Logout at N, this returns N+1.
	 */
	public static int getNextSenderMsgSeqNum(Session session) {
		if (session == null) {
			return -1;
		}
		Method m = null;
		try {
			m = Session.class.getMethod("getNextSenderMsgSeqNum");
		} catch (NoSuchMethodException e) {
			// QFJ 2.1.x: use MessageStore below
		}
		if (m != null) {
			try {
				Object v = m.invoke(session);
				if (v != null) {
					int n = ((Number) v).intValue();
					if (n >= 1) {
						return n;
					}
				}
			} catch (Exception e) {
				log.trace("getNextSenderMsgSeqNum on Session: {}", e.toString());
			}
		}
		try {
			MessageStore store = session.getStore();
			if (store != null) {
				return store.getNextSenderMsgSeqNum();
			}
		} catch (IOException e) {
			log.debug("getNextSenderMsgSeqNum from MessageStore: {}", e.getMessage());
		}
		return -1;
	}

	/**
	 * Value for {@code TRACE_FIX_SESSIONS.outgoing_seqnum} after sending a message at seq N.
	 * Prefer the assigned header seq (reliable after {@link Session#sendToTarget}); fall back to engine.
	 */
	public static int nextOutgoingSeqAfterSent(Message sentMessage, Session session) {
		if (sentMessage != null) {
			try {
				if (sentMessage.getHeader().isSetField(MsgSeqNum.FIELD)) {
					int sentSeq = sentMessage.getHeader().getInt(MsgSeqNum.FIELD);
					if (sentSeq >= 1) {
						return sentSeq + 1;
					}
				}
			} catch (FieldNotFound e) {
				log.trace("MsgSeqNum on sent message: {}", e.toString());
			}
		}
		return getNextSenderMsgSeqNum(session);
	}
}
