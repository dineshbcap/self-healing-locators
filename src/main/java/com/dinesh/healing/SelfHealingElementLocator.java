package com.dinesh.healing;

import org.openqa.selenium.By;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * The entry point page objects use instead of driver.findElement().
 *
 * Fast path: resolve the primary locator from the repository - identical
 * performance to a plain findElement. Healing only engages on
 * NoSuchElementException, in this order:
 *
 *   1. Healing cache (a locator already healed this build)
 *   2. Deterministic fallbacks (free: id fragments, text, content-desc)
 *   3. HealingEngine (Phase 2: LLM) - gated by healing.llm.enabled
 *
 * Every successful heal is cached, reported, and - if healing.failOnHeal=true -
 * escalated as a HealedLocatorException so PR-gate jobs still fail while the
 * report shows exactly what to fix.
 *
 * Usage in a page object base class:
 * <pre>
 * protected WebElement el(String key) {
 *     return healingLocator.find(key);
 * }
 * ...
 * el("login.submitButton").click();
 * </pre>
 */
public final class SelfHealingElementLocator {

    private static final Logger LOG = LoggerFactory.getLogger(SelfHealingElementLocator.class);

    /** Thrown instead of returning a healed element when healing.failOnHeal=true. */
    public static class HealedLocatorException extends RuntimeException {
        public HealedLocatorException(String message) {
            super(message);
        }
    }

    private final WebDriver driver;
    private final Platform platform;
    private final LocatorRepository repository;
    private final HealingCache cache;
    private final HealingEngine engine;
    private final HealingConfig config;

    public SelfHealingElementLocator(WebDriver driver,
                                     LocatorRepository repository,
                                     HealingCache cache,
                                     HealingEngine engine,
                                     HealingConfig config) {
        this.driver = driver;
        this.platform = Platform.fromDriver(driver);
        this.repository = repository;
        this.cache = cache;
        this.engine = engine != null ? engine : HealingEngine.NO_OP;
        this.config = config;
    }

    /** Phase 1 convenience constructor: no LLM engine. */
    public SelfHealingElementLocator(WebDriver driver,
                                     LocatorRepository repository,
                                     HealingCache cache,
                                     HealingConfig config) {
        this(driver, repository, cache, HealingEngine.NO_OP, config);
    }

    public WebElement find(String locatorKey) {
        LocatorDef def = repository.get(locatorKey, platform);
        try {
            return driver.findElement(def.toBy());
        } catch (NoSuchElementException original) {
            if (!config.enabled()) {
                throw original;
            }
            return heal(def, original);
        }
    }

    /** Escape hatch for steps that legitimately expect absence. */
    public boolean isPresent(String locatorKey) {
        LocatorDef def = repository.get(locatorKey, platform);
        return !driver.findElements(def.toBy()).isEmpty();
    }

    private WebElement heal(LocatorDef def, NoSuchElementException original) {
        LOG.info("Primary locator failed for '{}' - attempting heal", def.key());

        // 1. Cache
        Optional<By> cached = cache.get(def.key());
        if (cached.isPresent()) {
            Optional<WebElement> el = DeterministicHealer.findUnique(driver, cached.get());
            if (el.isPresent()) {
                LOG.info("Healing cache hit for '{}': {}", def.key(), cached.get());
                HealingReporter.record(def, cached.get().toString(), "cache", "cache");
                return afterHeal(def, el.get(), cached.get().toString(), "cache");
            }
            cache.evict(def.key());
        }

        // 2. Deterministic fallbacks
        Optional<DeterministicHealer.Healed> healed =
                DeterministicHealer.tryFallbacks(driver, platform, def);
        if (healed.isPresent()) {
            DeterministicHealer.Healed h = healed.get();
            cache.put(def.key(), h.healedBy());
            HealingReporter.record(def, h.healedBy().toString(), h.strategyName(), "deterministic");
            return afterHeal(def, h.element(), h.healedBy().toString(), h.strategyName());
        }

        // 3. LLM engine (Phase 2) - no-op until healing.llm.enabled + engine wired
        if (config.llmEnabled()) {
            Optional<HealingEngine.Proposal> proposal =
                    engine.propose(def.key(), def.description(), pageSourceForEngine());
            if (proposal.isPresent()
                    && proposal.get().confidence() >= config.llmConfidenceThreshold()) {
                By by = proposal.get().toBy();
                Optional<WebElement> el = DeterministicHealer.findUnique(driver, by);
                if (el.isPresent()) {
                    cache.put(def.key(), proposal.get().strategy(), proposal.get().value());
                    HealingReporter.record(def, by.toString(),
                            "llm(confidence=" + proposal.get().confidence() + ")", "llm");
                    return afterHeal(def, el.get(), by.toString(), "llm");
                }
                LOG.info("LLM proposal for '{}' rejected (not unique on screen): {}",
                        def.key(), by);
            }
        }

        // Healing failed: surface the ORIGINAL error, never a healing artifact.
        throw original;
    }

    private WebElement afterHeal(LocatorDef def, WebElement element,
                                 String healedLocator, String strategy) {
        if (config.failOnHeal()) {
            throw new HealedLocatorException(
                    "Locator '" + def.key() + "' required healing (" + strategy + "): "
                            + def + " -> " + healedLocator
                            + ". failOnHeal=true - update the locator repository.");
        }
        return element;
    }

    private String pageSourceForEngine() {
        try {
            // Order matters: prune first (smaller input for the regexes),
            // then redact PII - all BEFORE anything leaves the machine.
            return PiiRedactor.redact(PageSourcePruner.prune(driver.getPageSource()));
        } catch (RuntimeException e) {
            return "";
        }
    }
}
