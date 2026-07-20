package com.dinesh.healing;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.appium.java_client.AppiumBy;
import org.openqa.selenium.By;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe cache of healed locators, safe for parallel TestNG execution.
 *
 * Entries are scoped to an app build identifier: when the build under test
 * changes, persisted entries from older builds are dropped on load, so a heal
 * never outlives the release it belonged to.
 *
 * Persisted as JSON so consecutive Jenkins runs on the same build reuse heals
 * without repeating the (Phase 2) LLM cost.
 */
public final class HealingCache {

    private static final Logger LOG = LoggerFactory.getLogger(HealingCache.class);
    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Entry(String strategy, String value) {
        By toBy() {
            return LocatorStrategy.fromJson(strategy).toBy(value);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CacheFile(String buildId, Map<String, Entry> entries) {
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final String buildId;
    private final Path file;

    /**
     * @param buildId anything that identifies the app build under test - a
     *                version name, build number, or APK/IPA checksum. Pass
     *                "unknown" if you have nothing better; the cache then
     *                behaves per-run only after each build change.
     */
    public HealingCache(String buildId, Path file) {
        this.buildId = buildId == null || buildId.isBlank() ? "unknown" : buildId;
        this.file = file;
        load();
    }

    private void load() {
        if (file == null || !Files.exists(file)) {
            return;
        }
        try {
            CacheFile persisted = MAPPER.readValue(Files.readString(file), CacheFile.class);
            if (buildId.equals(persisted.buildId()) && persisted.entries() != null) {
                entries.putAll(persisted.entries());
                LOG.info("Loaded {} healed locator(s) from {} for build {}",
                        entries.size(), file, buildId);
            } else {
                LOG.info("Discarding healing cache from build {} (current build {})",
                        persisted.buildId(), buildId);
            }
        } catch (IOException e) {
            LOG.warn("Could not read healing cache {}: {}", file, e.toString());
        }
    }

    public Optional<By> get(String locatorKey) {
        Entry entry = entries.get(locatorKey);
        if (entry == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(entry.toBy());
        } catch (RuntimeException e) {
            entries.remove(locatorKey);
            return Optional.empty();
        }
    }

    public void put(String locatorKey, LocatorStrategy strategy, String value) {
        entries.put(locatorKey, new Entry(strategy.json(), value));
    }

    /**
     * Convenience overload for By instances produced by AppiumBy factories.
     * Falls back to xpath persistence when the strategy cannot be recovered.
     */
    public void put(String locatorKey, By by) {
        Recovered recovered = recover(by);
        entries.put(locatorKey, new Entry(recovered.strategy().json(), recovered.value()));
    }

    public void evict(String locatorKey) {
        entries.remove(locatorKey);
    }

    public int size() {
        return entries.size();
    }

    /** Call once from a suite-level @AfterSuite / shutdown hook. */
    public synchronized void persist() {
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, MAPPER.writeValueAsString(
                    new CacheFile(buildId, new HashMap<>(entries))));
            LOG.info("Persisted {} healed locator(s) to {}", entries.size(), file);
        } catch (IOException e) {
            LOG.warn("Could not persist healing cache: {}", e.toString());
        }
    }

    record Recovered(LocatorStrategy strategy, String value) {
    }

    /**
     * Best-effort reverse mapping of a By back to (strategy, value) using its
     * toString() form, e.g. "AppiumBy.accessibilityId: loginButton".
     */
    static Recovered recover(By by) {
        String s = by.toString();
        int colon = s.indexOf(':');
        if (colon > 0) {
            String head = s.substring(0, colon).trim();
            String value = s.substring(colon + 1).trim();
            if (head.contains("accessibilityId")) {
                return new Recovered(LocatorStrategy.ACCESSIBILITY_ID, value);
            }
            if (head.endsWith("id") || head.endsWith("By.id")) {
                return new Recovered(LocatorStrategy.ID, value);
            }
            if (head.contains("xpath")) {
                return new Recovered(LocatorStrategy.XPATH, value);
            }
            if (head.contains("androidUIAutomator")) {
                return new Recovered(LocatorStrategy.ANDROID_UIAUTOMATOR, value);
            }
            if (head.contains("iOSNsPredicate")) {
                return new Recovered(LocatorStrategy.IOS_PREDICATE, value);
            }
            if (head.contains("className")) {
                return new Recovered(LocatorStrategy.CLASS_NAME, value);
            }
            return new Recovered(LocatorStrategy.XPATH, value);
        }
        return new Recovered(LocatorStrategy.XPATH, s);
    }

    // Register from your framework bootstrap if you want belt-and-braces persistence:
    public void persistOnShutdown() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::persist, "healing-cache-persist"));
    }

    static {
        // Ensure AppiumBy class is linked so toString() formats are the AppiumBy ones.
        @SuppressWarnings("unused")
        Class<?> touch = AppiumBy.class;
    }
}
