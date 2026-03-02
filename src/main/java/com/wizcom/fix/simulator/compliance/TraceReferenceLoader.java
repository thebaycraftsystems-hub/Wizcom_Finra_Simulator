package com.wizcom.fix.simulator.compliance;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads TRACE SP FIX Reference from docs/TRACE_SP_FIX_Reference.xlsx (or .xls).
 * Reads all sheets; for each message sheet, extracts mandatory (Y/F in Req'd), optional (blank), and
 * required-but-ignored (Comment contains "ignored") per message type.
 */
public class TraceReferenceLoader {
    private static final Logger log = LoggerFactory.getLogger(TraceReferenceLoader.class);
    private static final String DOCS_XLSX = "docs" + File.separator + "TRACE_SP_FIX_Reference.xlsx";
    private static final String CLASSPATH_XLSX = "TRACE_SP_FIX_Reference.xlsx";
    private static final Pattern TAG_NUMBER = Pattern.compile("(\\d+)");

    private final Map<String, TraceSheetFields> sheetToFields = new HashMap<>();
    private final Map<Integer, String> globalTagToName = new HashMap<>();

    public TraceReferenceLoader() {
        load(null);
    }

    public TraceReferenceLoader(File baseDir) {
        load(baseDir != null ? baseDir : new File(System.getProperty("user.dir", ".")));
    }

    private void load(File baseDir) {
        File xlsx = new File(baseDir, DOCS_XLSX.replace("/", File.separator));
        try {
            if (xlsx.isFile()) {
                if (loadWorkbook(new FileInputStream(xlsx))) return;
            }
        } catch (Exception e) {
            log.warn("Could not read TRACE reference from docs: {}", e.getMessage());
        }
        try (InputStream is = TraceReferenceLoader.class.getClassLoader().getResourceAsStream(CLASSPATH_XLSX)) {
            if (is != null && loadWorkbook(is)) return;
        } catch (Exception e) {
            log.debug("Could not load TRACE reference from classpath: {}", e.getMessage());
        }
        log.warn("TRACE_SP_FIX_Reference.xlsx not loaded; validation will use YAML fallback");
    }

    private boolean loadWorkbook(InputStream is) {
        try (Workbook wb = new XSSFWorkbook(is)) {
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                Sheet sheet = wb.getSheetAt(i);
                String name = sheet.getSheetName();
                if (name == null) continue;
                if (name.startsWith("Overview") || name.contains("RejectCodes") || name.contains("CustomFields")
                    || name.contains("PartyRoles") || name.contains("Timestamps") || name.contains("Examples"))
                    continue;
                TraceSheetFields fields = parseSheet(sheet, name);
                if (!fields.getRequiredTags().isEmpty() || !fields.getTagToName().isEmpty()) {
                    sheetToFields.put(name, fields);
                    for (Map.Entry<Integer, String> e : fields.getTagToName().entrySet()) {
                        if (!globalTagToName.containsKey(e.getKey())) globalTagToName.put(e.getKey(), e.getValue());
                    }
                }
            }
            log.info("TRACE reference loaded: {} sheets from TRACE_SP_FIX_Reference.xlsx", sheetToFields.size());
            return !sheetToFields.isEmpty();
        } catch (Exception e) {
            log.warn("Failed to parse TRACE xlsx: {}", e.getMessage());
            return false;
        }
    }

    private TraceSheetFields parseSheet(Sheet sheet, String sheetName) {
        List<Integer> required = new ArrayList<>();
        Set<Integer> requiredButIgnored = new HashSet<>();
        Map<Integer, String> tagToName = new HashMap<>();
        int headerRow = -1;
        for (Row row : sheet) {
            Cell tagCell = row.getCell(0);
            String tagStr = getCellString(tagCell);
            if (tagStr == null) continue;
            if ("Tag #".equals(tagStr.trim()) || "Tag #".equals(tagStr)) {
                headerRow = row.getRowNum();
                continue;
            }
            if (headerRow < 0) continue;
            int tag = parseTagNumber(tagStr);
            if (tag <= 0) continue;
            String name = getCellString(row.getCell(1));
            if (name != null && !name.isEmpty()) tagToName.put(tag, name.trim());
            String req = getCellString(row.getCell(2));
            boolean isRequired = "Y".equalsIgnoreCase(req != null ? req.trim() : "") || "F".equalsIgnoreCase(req != null ? req.trim() : "");
            String comment = getCellString(row.getCell(5));
            boolean ignored = comment != null && comment.toLowerCase().contains("ignored");
            if (isRequired) {
                required.add(tag);
                if (ignored) requiredButIgnored.add(tag);
            }
        }
        return new TraceSheetFields(sheetName, required, requiredButIgnored, tagToName);
    }

    private static int parseTagNumber(String s) {
        if (s == null || s.isEmpty()) return 0;
        s = s.trim();
        Matcher m = TAG_NUMBER.matcher(s);
        int last = 0;
        while (m.find()) last = Integer.parseInt(m.group(1));
        return last;
    }

    private static String getCellString(Cell c) {
        if (c == null) return null;
        switch (c.getCellType()) {
            case STRING: return c.getStringCellValue();
            case NUMERIC: return String.valueOf((int) c.getNumericCellValue());
            case BOOLEAN: return String.valueOf(c.getBooleanCellValue());
            case FORMULA:
                try { return c.getStringCellValue(); } catch (Exception e) { return null; }
            default: return null;
        }
    }

    /** Sheet name for TradeCaptureReport by TransType: 0=New, 1=Cancel, 2=Correction, 4=Reversal. */
    public static String sheetForTradeReportTransType(int transType) {
        switch (transType) {
            case 0: return "08_TradeNew";
            case 1: return "09_TradeCancel";
            case 2: return "11_TradeCorrection";
            case 4: return "10_TradeReversal";
            default: return "08_TradeNew";
        }
    }

    public TraceSheetFields getSheetFields(String sheetName) {
        return sheetToFields.get(sheetName);
    }

    public Map<String, TraceSheetFields> getAllSheetFields() {
        return new HashMap<>(sheetToFields);
    }

    /** Field name for tag from any sheet (first occurrence in workbook). */
    public String getFieldName(int tag) {
        return globalTagToName.getOrDefault(tag, "Tag" + tag);
    }

    public boolean isLoaded() {
        return !sheetToFields.isEmpty();
    }
}
