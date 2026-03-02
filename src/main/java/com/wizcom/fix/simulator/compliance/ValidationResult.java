package com.wizcom.fix.simulator.compliance;

/**
 * Result of spec validation or lifecycle check. Used to decide whether to send SPRE.
 */
public final class ValidationResult {
    private final boolean valid;
    private final int rejectCode;
    private final String rejectText;

    public static ValidationResult ok() {
        return new ValidationResult(true, 0, null);
    }

    public static ValidationResult fail(int rejectCode, String rejectText) {
        return new ValidationResult(false, rejectCode, rejectText);
    }

    private ValidationResult(boolean valid, int rejectCode, String rejectText) {
        this.valid = valid;
        this.rejectCode = rejectCode;
        this.rejectText = rejectText;
    }

    public boolean isValid() { return valid; }
    public int getRejectCode() { return rejectCode; }
    public String getRejectText() { return rejectText; }
}
