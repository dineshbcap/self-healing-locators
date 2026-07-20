# Self-Healing Locators — Phase 1

A drop-in self-healing element locator layer for Appium + Java + Cucumber + TestNG frameworks.

**Phase 1 scope (this module):** locator repository, self-healing decorator, deterministic
(zero-cost) healing strategies, build-scoped healing cache, JSON + listener-based reporting.
**Phase 2 seam is already in place:** the `HealingEngine` interface — the LLM implementation
plugs in behind `healing.llm.enabled` without touching any page object.

## How it works

```
find(key)
  └─ primary locator from locators_<platform>.properties  ──found──▶ return (fast path, zero overhead)
       └─ NoSuchElementException
            ├─ 1. healing cache (heals from earlier in this build)
            ├─ 2. deterministic fallbacks (id fragments, text, content-desc, iOS predicates)
            ├─ 3. HealingEngine (Phase 2: LLM)          [healing.llm.enabled=true]
            └─ all failed ──▶ rethrow the ORIGINAL NoSuchElementException
```

Design guarantees:

- **Healing never guesses.** Every candidate must resolve to exactly one element or it is rejected.
- **Healing never masks real bugs.** If nothing heals, the original exception surfaces untouched.
- **Healing is never silent.** Every heal is logged WARN, recorded to `target/healing-report.json`,
  and mirrored to any registered listener (ExtentReports, Slack, etc.).
- **Heals expire with the app build.** The cache is keyed to a build id you supply.

## Integrating into your framework

### 1. Install to your local repo / add as a module

```bash
mvn clean install
```

Then in your framework's pom.xml:

```xml
<dependency>
    <groupId>com.dinesh.healing</groupId>
    <artifactId>self-healing-locators</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

(The module marks Appium `provided`, so it always uses YOUR framework's Appium/Selenium versions.
Requires java-client 9.x — the AppiumBy era.)

### 2. Create your locator repository

Copy `src/main/resources/locators/locators_android.properties` and `locators_ios.properties`
as a starting point. Migrate incrementally — pages you migrate get healing; pages still on
`@AndroidFindBy` behave exactly as before.

Each key is a dotted `screen.element` id. Per platform file, it carries a description line
plus a `<strategy>=<value>` line:

```properties
login.submitButton.description=Primary Sign In button on the login screen
login.submitButton=id=com.td.app:id/btn_login
```

Keep the same key present in both files — `LocatorRepository` resolves `(key, platform)` by
reading the matching properties file for that platform. The `description` field matters:
write what the element IS in plain English. It drives the text-based deterministic strategies
today and becomes the LLM prompt in Phase 2.

### 3. Wire it in your driver factory / hooks

```java
LocatorRepository repo = LocatorRepository.fromClasspath(
        "locators/locators_android.properties", "locators/locators_ios.properties");
HealingConfig config = new HealingConfig();

// Build id: version name, Jenkins BUILD_NUMBER, or APK checksum — anything that
// changes when the app under test changes.
HealingCache cache = new HealingCache(
        System.getProperty("app.build.id", "unknown"),
        Path.of(config.cacheFile()));
cache.persistOnShutdown();

SelfHealingElementLocator healing =
        new SelfHealingElementLocator(driver, repo, cache, config);
```

In your page object base class:

```java
protected WebElement el(String key) {
    return healing.find(key);
}
```

### 4. Mirror heals into ExtentReports

```java
HealingReporter.addListener(record ->
    ExtentManager.getTest().warning(
        "⚠ Locator healed: " + record.locatorKey() + " "
        + record.originalLocator() + " → " + record.healedLocator()
        + " (via " + record.healingStrategy() + ") — update locators_<platform>.properties"));
```

### 5. Suite teardown (TestNG)

```java
@AfterSuite(alwaysRun = true)
public void writeHealingArtifacts() {
    HealingReporter.writeReport(Path.of(new HealingConfig().reportFile()));
    // cache.persist() already covered by persistOnShutdown(), or call explicitly here.
}
```

### 6. Jenkins

- Nightly regression: defaults (`healing.failOnHeal=false`) — runs stay green, report shows debt.
- PR gate: `mvn test -Dhealing.failOnHeal=true` — a heal fails the check, forcing the locator fix.
- Post-build: parse `target/healing-report.json`; if non-empty, post a summary
  ("N locators healed — POM updates recommended") to Slack/Teams.

## Configuration (healing.properties or -D overrides)

| Key | Default | Purpose |
|-----|---------|---------|
| `healing.enabled` | `true` | Master switch |
| `healing.failOnHeal` | `false` | Escalate heals as failures (PR gates) |
| `healing.cache.file` | `target/healing-cache.json` | Cache persistence path |
| `healing.report.file` | `target/healing-report.json` | Report output path |
| `healing.llm.enabled` | `false` | Phase 2 switch |
| `healing.llm.confidence.threshold` | `0.7` | Phase 2 minimum confidence |

## Running the demo tests

```bash
mvn test
```

`SelfHealingFlowTest` mocks the driver to simulate a dev renaming `btn_login` →
`button_login`: the primary lookup fails, the fragment strategy recovers it, the heal is
cached/reported, ambiguity is rejected, and `failOnHeal` escalates. No device needed.

## Roadmap

- **Phase 2:** `LlmHealingEngine` (Claude Messages API via `java.net.http`), `PageSourcePruner`
  (5–10× XML shrink), `PiiRedactor` (mask account/card/currency patterns before anything
  leaves the machine), confidence gating.
- **Phase 3:** richer Jenkins reporting, healing metrics per release, parallel-run hardening review.
- **Phase 4:** auto-generated locators properties patch from the healing report (one-click PR to fix debt).

---

## Phase 2 — LLM healing (added)

New classes:
- `PageSourcePruner` — shrinks `getPageSource()` XML 5–10× (keeps only locator-relevant
  attributes, drops invisible nodes, collapses empty layout wrappers). Degrades to
  truncation on malformed XML — never breaks the healing path.
- `PiiRedactor` — masks card/account numbers, SIN patterns, currency amounts, emails,
  and phone numbers in the pruned XML **before it leaves the machine**. Resource-ids
  survive untouched. Defence-in-depth on top of synthetic test data.
- `LlmHealingEngine` — `HealingEngine` implementation calling the Anthropic Messages API
  via `java.net.http` + Jackson only (no new dependencies). Temperature 0, JSON-only
  response contract, rejects index-based xpaths, treats "element not present" as
  no-heal (never guesses), and degrades to empty on any API failure so the run
  falls through to the original NoSuchElementException.

### Enabling LLM healing

1. Export the key (never commit it):
   ```bash
   export ANTHROPIC_API_KEY=sk-ant-...
   ```
   On Jenkins: a Secret Text credential bound to the env var.
2. Flip the flag: `healing.llm.enabled=true` (or `-Dhealing.llm.enabled=true`).
3. Pass the engine in your driver factory:
   ```java
   HealingConfig config = new HealingConfig();
   SelfHealingElementLocator healing = new SelfHealingElementLocator(
           driver, repo, cache, new LlmHealingEngine(config), config);
   ```

Order of operations per heal attempt is unchanged:
cache → deterministic → **LLM** → rethrow original. The LLM proposal must clear
`healing.llm.confidence.threshold` AND resolve uniquely on screen before it is used.

New config keys: `healing.llm.model`, `healing.llm.apiKeyEnv`,
`healing.llm.timeoutSeconds`, `healing.llm.maxPageSourceChars`.

### Phase 2 tests (CI-safe, no network)
- `PageSourcePrunerAndRedactorTest` — prune/redact pipeline incl. end-to-end
- `LlmHealingEngineTest` — full prompt-build + response-parse path via fake Transport:
  valid proposal, fenced JSON, "none", index-xpath rejection, HTTP failure, garbage
  response, missing API key short-circuit
