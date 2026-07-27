package com.wizcom.fix.simulator;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Allocates FINRA TradeID / control numbers (tag 1003) that are unique per control date.
 * When a JDBC DataSource is available, the counter is persisted in {@code TRACE_FIX_CONTROL_NUMBERS}
 * so Primary/Secondary and JVM restarts share one sequence for the day. Without JDBC, falls back
 * to an in-memory counter that resets each control date (and seeds from time-of-day on first use
 * after a process start to reduce collision risk).
 */
public class ControlNumberAllocator {

	private static final Logger log = LoggerFactory.getLogger(ControlNumberAllocator.class);
	private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");
	private static final String DEFAULT_TABLE = "TRACE_FIX_CONTROL_NUMBERS";

	private final DataSource dataSource;
	private final String tableName;
	private final ZoneId controlDateZone;
	private final ConcurrentHashMap<String, AtomicInteger> memoryByKey = new ConcurrentHashMap<>();
	private volatile boolean tableReady;
	private volatile boolean dbDisabled;

	public ControlNumberAllocator(DataSource dataSource, ZoneId controlDateZone) {
		this(dataSource, DEFAULT_TABLE, controlDateZone);
	}

	public ControlNumberAllocator(DataSource dataSource, String tableName, ZoneId controlDateZone) {
		this.dataSource = dataSource;
		this.tableName = (tableName != null && !tableName.trim().isEmpty()) ? tableName.trim() : DEFAULT_TABLE;
		this.controlDateZone = controlDateZone != null ? controlDateZone : ZoneId.of("America/New_York");
		if (dataSource != null) {
			ensureTable();
		} else {
			log.info("ControlNumberAllocator: no JDBC DataSource — using in-memory per-day counters (not shared across restarts).");
		}
	}

	/** Today's control date (yyyyMMdd) in the configured session/control zone. */
	public String todayControlDate() {
		return LocalDate.now(controlDateZone).format(YYYYMMDD);
	}

	/**
	 * Allocates the next unique control number for {@code controlDate} + product {@code prefix}
	 * (e.g. prefix {@code 2} for CA → {@code 2000000008}).
	 */
	public String allocate(String controlDate, String prefix) {
		String date = (controlDate != null && !controlDate.trim().isEmpty())
				? controlDate.trim()
				: todayControlDate();
		String p = (prefix != null && !prefix.isEmpty()) ? prefix.substring(0, 1) : "1";
		int n = nextValue(date, p);
		return p + String.format("%09d", n);
	}

	private int nextValue(String controlDate, String prefix) {
		if (dataSource != null && !dbDisabled) {
			try {
				return nextValueFromDb(controlDate, prefix);
			} catch (Exception e) {
				log.warn("ControlNumberAllocator: DB allocate failed ({}={}/{}) — falling back to memory: {}",
						controlDate, prefix, e.getClass().getSimpleName(), e.getMessage());
				dbDisabled = true;
			}
		}
		return nextValueFromMemory(controlDate, prefix);
	}

	private int nextValueFromDb(String controlDate, String prefix) throws SQLException {
		ensureTable();
		// Atomic allocate: bump next_value and return the assigned number.
		String sql = "MERGE " + tableName + " WITH (HOLDLOCK) AS t "
				+ "USING (SELECT ? AS control_date, ? AS product_prefix) AS s "
				+ "ON t.control_date = s.control_date AND t.product_prefix = s.product_prefix "
				+ "WHEN MATCHED THEN UPDATE SET next_value = t.next_value + 1 "
				+ "WHEN NOT MATCHED THEN INSERT (control_date, product_prefix, next_value) "
				+ "VALUES (s.control_date, s.product_prefix, 1) "
				+ "OUTPUT inserted.next_value;";
		try (Connection c = dataSource.getConnection();
				PreparedStatement ps = c.prepareStatement(sql)) {
			ps.setString(1, controlDate);
			ps.setString(2, prefix);
			try (ResultSet rs = ps.executeQuery()) {
				if (rs.next()) {
					int v = rs.getInt(1);
					if (v < 1) {
						v = 1;
					}
					if (v > 999_999_999) {
						throw new SQLException("Control number sequence exhausted for " + controlDate + "/" + prefix);
					}
					return v;
				}
			}
		}
		throw new SQLException("MERGE returned no next_value for " + controlDate + "/" + prefix);
	}

	private int nextValueFromMemory(String controlDate, String prefix) {
		String key = controlDate + "|" + prefix;
		AtomicInteger counter = memoryByKey.computeIfAbsent(key, k -> {
			// Seed from seconds-of-day so a restart mid-day rarely reuses early numbers (1,2,3...).
			int seed = (LocalDate.now(controlDateZone).equals(LocalDate.parse(controlDate, YYYYMMDD))
					? java.time.LocalTime.now(controlDateZone).toSecondOfDay() * 100
					: 1) % 1_000_000_000;
			if (seed < 1) {
				seed = 1;
			}
			log.info("ControlNumberAllocator: in-memory counter start {} for date={} prefix={}", seed, controlDate, prefix);
			return new AtomicInteger(seed);
		});
		int v = counter.getAndIncrement();
		if (v > 999_999_999) {
			throw new IllegalStateException("Control number sequence exhausted for " + controlDate + "/" + prefix);
		}
		return v;
	}

	private synchronized void ensureTable() {
		if (tableReady || dataSource == null || dbDisabled) {
			return;
		}
		String ddl = "IF NOT EXISTS (SELECT * FROM sys.tables WHERE name = '" + tableName + "') "
				+ "CREATE TABLE dbo." + tableName + " ("
				+ "  control_date    CHAR(8) NOT NULL,"
				+ "  product_prefix  CHAR(1) NOT NULL,"
				+ "  next_value      INT NOT NULL,"
				+ "  PRIMARY KEY (control_date, product_prefix)"
				+ ")";
		try (Connection c = dataSource.getConnection();
				Statement st = c.createStatement()) {
			st.execute(ddl);
			tableReady = true;
			log.info("ControlNumberAllocator: ready (table={}) — FINRA control numbers unique per control date across restarts.",
					tableName);
		} catch (Exception e) {
			log.warn("ControlNumberAllocator: could not ensure table {} — using in-memory counters: {}",
					tableName, e.getMessage());
			dbDisabled = true;
		}
	}
}
