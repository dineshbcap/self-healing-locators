package com.dinesh.healing;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Masks PII patterns in (pruned) page source BEFORE it leaves the machine for
 * the LLM. Applied to the whole XML string, so it covers text=, content-desc=,
 * label= and value= attributes alike.
 *
 * This is a defence-in-depth layer: the primary control remains running
 * against synthetic test accounts in lower environments. Redaction targets:
 *
 *   - 13-19 digit card/account numbers (with or without space/dash grouping)
 *   - transit + account patterns (5-digit transit, 7-12 digit account)
 *   - SIN-style 3-3-3 digit groups
 *   - currency amounts ($, CAD, USD prefixed)
 *   - email addresses
 *   - North American phone numbers
 *
 * Element ids/resource-ids are left untouched - they are needed for locating
 * and never contain customer data in a sanely built app.
 */
public final class PiiRedactor {

    private static final Map<Pattern, String> RULES = new LinkedHashMap<>();

    static {
        // Card / long account numbers: 13-19 digits, optionally grouped.
        RULES.put(Pattern.compile(
                        "\\b(?:\\d[ -]?){13,19}\\b"),
                "[REDACTED-NUM]");
        // SIN style: 123-456-789 or 123 456 789.
        RULES.put(Pattern.compile(
                        "\\b\\d{3}[ -]\\d{3}[ -]\\d{3}\\b"),
                "[REDACTED-NUM]");
        // Currency amounts: $1,234.56 / CAD 1234.56 / USD 99.
        RULES.put(Pattern.compile(
                        "(?i)(?:\\$|CAD\\s?|USD\\s?)\\s?\\d[\\d,]*(?:\\.\\d{2})?"),
                "[REDACTED-AMT]");
        // Email addresses.
        RULES.put(Pattern.compile(
                        "\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b"),
                "[REDACTED-EMAIL]");
        // NA phone numbers: (416) 555-0123, 416-555-0123, +1 416 555 0123.
        RULES.put(Pattern.compile(
                        "(?:\\+?1[ .-]?)?(?:\\(\\d{3}\\)|\\d{3})[ .-]?\\d{3}[ .-]?\\d{4}\\b"),
                "[REDACTED-PHONE]");
    }

    private PiiRedactor() {
    }

    public static String redact(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }
        String out = input;
        for (Map.Entry<Pattern, String> rule : RULES.entrySet()) {
            out = rule.getKey().matcher(out).replaceAll(rule.getValue());
        }
        return out;
    }
}
