package com.wizcom.fix.simulator;

import quickfix.StringField;

public class TradeModifier3 extends StringField {
	static final long serialVersionUID = 20050619;

    public static final int FIELD = 22003;
    public static final String T = "T";
    public static final String U = "U";
    public static final String Z = "Z";

    public TradeModifier3() {
            super(22003);
    }

    public TradeModifier3(String data) {
            super(22003, data);
    }
}
