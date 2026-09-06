package com.dinesh.healing;

import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/**
 * {@link HealingCache#recover} is the reverse mapping (By -> LocatorStrategy/value)
 * that Phase 4's {@link LocatorPatchGenerator} depends on to write a healed locator
 * back into a properties file, so every strategy AppiumBy can produce needs its own
 * case here - a silent fallback to XPATH for an unrecognized shape would corrupt
 * the generated patch.
 */
public class HealingCacheTest {

    @Test
    public void recoversEveryLocatorStrategyFromItsByToString() {
        for (LocatorStrategy strategy : LocatorStrategy.values()) {
            String value = "sample-" + strategy.json();
            HealingCache.Recovered recovered = HealingCache.recover(strategy.toBy(value));
            assertEquals(recovered.strategy(), strategy, "Round-trip failed for " + strategy);
            assertEquals(recovered.value(), value, "Round-trip failed for " + strategy);
        }
    }

    @Test
    public void putByOverloadPersistsTheRecoveredStrategy() throws IOException {
        Path file = Files.createTempFile("healing-cache", ".json");
        Files.deleteIfExists(file);
        try {
            HealingCache cache = new HealingCache("build-1", file);
            cache.put("login.submitButton", LocatorStrategy.IOS_CLASS_CHAIN.toBy("**/XCUIElementTypeButton"));

            Optional<org.openqa.selenium.By> by = cache.get("login.submitButton");
            assertTrue(by.isPresent());
            assertTrue(by.get().toString().contains("iOSClassChain"));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    public void cacheRoundTripsThroughPersistAndLoad() throws IOException {
        Path file = Files.createTempFile("healing-cache", ".json");
        Files.deleteIfExists(file);
        try {
            HealingCache first = new HealingCache("build-1", file);
            first.put("k", LocatorStrategy.XPATH, "//button");
            first.persist();

            HealingCache second = new HealingCache("build-1", file);
            assertEquals(second.size(), 1);
            assertTrue(second.get("k").isPresent());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    public void cacheFromADifferentBuildIsDiscardedOnLoad() throws IOException {
        Path file = Files.createTempFile("healing-cache", ".json");
        Files.deleteIfExists(file);
        try {
            HealingCache first = new HealingCache("build-1", file);
            first.put("k", LocatorStrategy.XPATH, "//button");
            first.persist();

            HealingCache second = new HealingCache("build-2", file);
            assertFalse(second.get("k").isPresent(), "Cache from a different build must not carry over");
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
