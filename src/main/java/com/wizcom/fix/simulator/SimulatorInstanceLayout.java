package com.wizcom.fix.simulator;

import org.apache.log4j.PatternLayout;
import org.apache.log4j.spi.LoggingEvent;

/**
 * Log4j layout that injects the simulator instance (Primary/Secondary) into each line
 * so logs/simulator.log clearly shows which instance produced each message.
 */
public class SimulatorInstanceLayout extends PatternLayout {

	public SimulatorInstanceLayout() {
		super("%5p --> %d [%t] (%F:%L) - %m%n");
	}

	public SimulatorInstanceLayout(String pattern) {
		super(pattern);
	}

	@Override
	public String format(LoggingEvent event) {
		String line = super.format(event);
		String inst = SimulatorInstanceHolder.get();
		return line.replaceFirst(" -->", " [" + inst + "] -->");
	}
}
