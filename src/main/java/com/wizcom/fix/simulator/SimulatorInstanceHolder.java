package com.wizcom.fix.simulator;

/**
 * Holds the current simulator instance label (Primary or Secondary) for log formatting.
 * Set at startup so every log line in logs/simulator.log can show the instance.
 */
public final class SimulatorInstanceHolder {
	private static volatile String instance = "";

	public static void set(String label) {
		instance = label != null ? label : "";
	}

	public static String get() {
		String s = instance;
		return (s != null && !s.isEmpty()) ? s : "?";
	}

	private SimulatorInstanceHolder() {}
}
