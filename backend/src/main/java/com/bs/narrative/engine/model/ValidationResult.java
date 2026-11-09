package com.bs.narrative.engine.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** IntentValidator 返回。若 reasons 空则通过；否则失败+每条原因中文。 */
public final class ValidationResult {
    private final boolean valid;
    private final List<String> reasons;

    private ValidationResult(boolean valid, List<String> reasons) {
        this.valid = valid;
        this.reasons = List.copyOf(reasons);
    }

    public static ValidationResult ok() { return new ValidationResult(true, List.of()); }
    public static Builder builder() { return new Builder(); }

    public boolean isValid() { return valid; }
    public List<String> reasons() { return reasons; }
    public String firstReason() { return reasons.isEmpty() ? "" : reasons.get(0); }

    public static final class Builder {
        private final List<String> reasons = new ArrayList<>();
        public Builder add(String r) { if (r != null && !r.isBlank()) reasons.add(r); return this; }
        public ValidationResult build() {
            return new ValidationResult(reasons.isEmpty(), Collections.unmodifiableList(reasons));
        }
    }
}
