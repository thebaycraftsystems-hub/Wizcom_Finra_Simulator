package com.wizcom.fix.simulator.compliance;

import java.util.*;

/**
 * Per-sheet field rules from TRACE_SP_FIX_Reference.xlsx: required tags, required-but-ignored, tag→name.
 */
public class TraceSheetFields {
    private final String sheetName;
    private final List<Integer> requiredTags;
    private final Set<Integer> requiredButIgnored;
    private final Map<Integer, String> tagToName;

    public TraceSheetFields(String sheetName, List<Integer> requiredTags, Set<Integer> requiredButIgnored, Map<Integer, String> tagToName) {
        this.sheetName = sheetName != null ? sheetName : "";
        this.requiredTags = requiredTags != null ? new ArrayList<>(requiredTags) : Collections.emptyList();
        this.requiredButIgnored = requiredButIgnored != null ? new HashSet<>(requiredButIgnored) : new HashSet<>();
        this.tagToName = tagToName != null ? new HashMap<>(tagToName) : new HashMap<>();
    }

    public String getSheetName() { return sheetName; }
    public List<Integer> getRequiredTags() { return requiredTags; }
    public Set<Integer> getRequiredButIgnored() { return requiredButIgnored; }
    public String getFieldName(int tag) { return tagToName.getOrDefault(tag, "Tag" + tag); }
    public Map<Integer, String> getTagToName() { return new HashMap<>(tagToName); }
}
