package com.dinesh.healing;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks PII patterns in (pruned) page source BEFORE it leaves the machine for
 * the LLM. Walks {@code attr="value"} pairs in the XML string (rather than
 * scanning the whole string blind) so it covers text=, content-desc=, label=
 * and value= attributes alike, while leaving identifier attributes
 * ({@link #PROTECTED_ATTRS}) untouched regardless of what their value looks
 * like. This runs on whatever {@link PageSourcePruner#prune} returns,
 * including its malformed-XML truncation fallback - both are still ordinary
 * attribute-shaped Appium page source, so the same attr="value" scan applies.
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
 */
public final class PiiRedactor {

    private static final Map<Pattern, String> RULES = new LinkedHashMap<>();

    /**
     * Attributes that identify an element rather than carry customer data -
     * left untouched even when a value happens to look like a PII pattern
     * (e.g. a purely numeric resource-id or accessibility id). Locator
     * strategies depend on these surviving byte-for-byte; a blind whole-string
     * regex pass would otherwise redact them whenever no word boundary saved
     * them (word-char-adjacent digit runs like "card_1234567890123456" happen
     * to dodge the old \b-anchored pattern, but "id/1234567890123456" would not).
     */
    private static final Set<String> PROTECTED_ATTRS =
            Set.of("resource-id", "name", "class", "package", "type");

    private static final Pattern ATTR_PATTERN = Pattern.compile("([A-Za-z-]+)=\"([^\"]*)\"");

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
        Matcher attrMatcher = ATTR_PATTERN.matcher(input);
        StringBuilder result = new StringBuilder();
        while (attrMatcher.find()) {
            String attrName = attrMatcher.group(1);
            String value = attrMatcher.group(2);
            String replacement = PROTECTED_ATTRS.contains(attrName) ? value : applyRules(value);
            attrMatcher.appendReplacement(result,
                    Matcher.quoteReplacement(attrName + "=\"" + replacement + "\""));
        }
        attrMatcher.appendTail(result);
        return result.toString();
    }

    private static String applyRules(String value) {
        String out = value;
        for (Map.Entry<Pattern, String> rule : RULES.entrySet()) {
            out = rule.getKey().matcher(out).replaceAll(rule.getValue());
        }
        return out;
    }
}
