package com.dinesh.healing;

import io.appium.java_client.AppiumBy;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deterministic (zero-cost, zero-network) healing strategies, tried in order
 * before any LLM call. These cover the most common real-world breakage:
 * a renamed resource-id, an id swapped for an accessibility id, or a text
 * label that survived a structural change.
 *
 * Every candidate is validated for UNIQUENESS: a locator that matches more
 * than one element is rejected. Healing must never guess between two buttons.
 */
public final class DeterministicHealer {

    private static final Logger LOG = LoggerFactory.getLogger(DeterministicHealer.class);

    /** Words too generic to identify an element by text. */
    private static final Set<String> STOP_WORDS = Set.of(
            "the", "a", "an", "on", "in", "of", "for", "to", "and", "or",
            "button", "field", "input", "label", "icon", "screen", "primary",
            "secondary", "main", "element", "text", "link", "tab", "item");

    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");

    private DeterministicHealer() {
    }

    /**
     * Result of a successful deterministic heal: the element plus the locator
     * that found it, so callers can cache and report it.
     */
    public record Healed(WebElement element, By healedBy, String strategyName) {
    }

    public static Optional<Healed> tryFallbacks(WebDriver driver, Platform platform, LocatorDef def) {
        for (Candidate candidate : buildCandidates(platform, def)) {
            Optional<WebElement> unique = findUnique(driver, candidate.by());
            if (unique.isPresent()) {
                LOG.info("Deterministic heal for '{}' via {} -> {}",
                        def.key(), candidate.name(), candidate.by());
                return Optional.of(new Healed(unique.get(), candidate.by(), candidate.name()));
            }
        }
        return Optional.empty();
    }

    record Candidate(String name, By by) {
    }

    static List<Candidate> buildCandidates(Platform platform, LocatorDef def) {
        List<Candidate> candidates = new ArrayList<>();
        String idToken = lastIdToken(def);
        List<String> keywords = descriptionKeywords(def.description());

        // 1. Cross-strategy swap: id <-> accessibility id using the same value/token.
        if (def.strategy() == LocatorStrategy.ID && idToken != null) {
            candidates.add(new Candidate("a11yIdFromIdToken", AppiumBy.accessibilityId(idToken)));
        }
        if (def.strategy() == LocatorStrategy.ACCESSIBILITY_ID) {
            if (platform == Platform.ANDROID) {
                candidates.add(new Candidate("resourceIdContainsA11yValue",
                        AppiumBy.xpath("//*[contains(@resource-id,'" + xpathSafe(def.value()) + "')]")));
            }
        }

        // 2. Partial resource-id / name match: survives btn_login -> button_login
        //    only when a distinctive fragment remains (e.g. 'login').
        if (idToken != null) {
            String fragment = distinctiveFragment(idToken);
            if (fragment != null) {
                if (platform == Platform.ANDROID) {
                    candidates.add(new Candidate("resourceIdContainsFragment",
                            AppiumBy.xpath("//*[contains(@resource-id,'" + xpathSafe(fragment) + "')]")));
                } else {
                    candidates.add(new Candidate("iosNameContainsFragment",
                            AppiumBy.iOSNsPredicateString(
                                    "name CONTAINS '" + predicateSafe(fragment) + "'")));
                }
            }
        }

        // 3. Text / label match from the human description keywords.
        for (String keyword : keywords) {
            if (platform == Platform.ANDROID) {
                candidates.add(new Candidate("androidTextContains:" + keyword,
                        AppiumBy.androidUIAutomator(
                                "new UiSelector().textContains(\"" + keyword + "\")")));
                candidates.add(new Candidate("androidDescContains:" + keyword,
                        AppiumBy.xpath("//*[contains(@content-desc,'" + xpathSafe(keyword) + "')]")));
            } else {
                candidates.add(new Candidate("iosLabelContains:" + keyword,
                        AppiumBy.iOSNsPredicateString(
                                "label CONTAINS[c] '" + predicateSafe(keyword) + "' OR name CONTAINS[c] '"
                                        + predicateSafe(keyword) + "'")));
            }
        }

        return candidates;
    }

    /**
     * Extracts the local id token from an Android resource-id or plain id.
     * "com.td.app:id/btn_login" -> "btn_login"; "loginButton" -> "loginButton".
     */
    static String lastIdToken(LocatorDef def) {
        if (def.strategy() != LocatorStrategy.ID
                && def.strategy() != LocatorStrategy.ACCESSIBILITY_ID) {
            return null;
        }
        String value = def.value();
        int slash = value.lastIndexOf('/');
        return slash >= 0 ? value.substring(slash + 1) : value;
    }

    /**
     * Picks the most distinctive word out of an id token: "btn_login" -> "login".
     * Returns null when nothing distinctive remains (e.g. "btn_1").
     */
    static String distinctiveFragment(String idToken) {
        String[] parts = NON_ALNUM.split(idToken.toLowerCase(Locale.ROOT)
                .replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT));
        String best = null;
        for (String part : parts) {
            if (part.length() >= 4 && !STOP_WORDS.contains(part)) {
                if (best == null || part.length() > best.length()) {
                    best = part;
                }
            }
        }
        return best;
    }

    /** Distinctive, capitalisation-preserving keywords from the description. */
    static List<String> descriptionKeywords(String description) {
        if (description == null || description.isBlank()) {
            return List.of();
        }
        Set<String> keywords = new LinkedHashSet<>();
        for (String raw : description.split("[^A-Za-z0-9]+")) {
            String lower = raw.toLowerCase(Locale.ROOT);
            if (raw.length() >= 4 && !STOP_WORDS.contains(lower)) {
                keywords.add(raw);
            }
            if (keywords.size() == 3) {
                break;
            }
        }
        return List.copyOf(keywords);
    }

    /** Uniqueness gate: exactly one match or nothing. */
    static Optional<WebElement> findUnique(WebDriver driver, By by) {
        try {
            List<WebElement> matches = driver.findElements(by);
            if (matches.size() == 1) {
                return Optional.of(matches.get(0));
            }
            if (matches.size() > 1) {
                LOG.debug("Rejected ambiguous candidate {} ({} matches)", by, matches.size());
            }
            return Optional.empty();
        } catch (RuntimeException e) {
            // Malformed candidate (bad xpath etc.) must never break the run.
            LOG.debug("Candidate {} threw {}", by, e.toString());
            return Optional.empty();
        }
    }

    private static String xpathSafe(String s) {
        return s.replace("'", "");
    }

    private static String predicateSafe(String s) {
        return s.replace("'", "\\'");
    }
}
