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

A key missing from one platform's file doesn't fail the build (real apps can have
legitimate platform-exclusive screens) — it logs a WARN at repository load time via
`LocatorRepository.keysMissingOnAnyPlatform()`, so a typo surfaces immediately in the
console instead of as a `NoSuchElementException` the first time some test happens to hit
that key on the missing platform. If you want a mismatch to fail the build outright, call
`repository.assertKeysSyncedAcrossPlatforms()` from a startup smoke test.

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
    HealingConfig config = new HealingConfig();
    String buildId = System.getProperty("app.build.id", "unknown");
    HealingReporter.writeReport(Path.of(config.reportFile()));
    HealingReporter.appendMetric(buildId, Path.of(config.metricsFile()));
    HealingReporter.appendEvents(buildId, Path.of(config.eventsFile())); // Phase 5 input
    HealingReportPublisher.publish(HealingReporter.records(), config); // no-op unless webhook.enabled=true
    // cache.persist() already covered by persistOnShutdown(), or call explicitly here.
}
```

### 6. Jenkins

- Nightly regression: defaults (`healing.failOnHeal=false`) — runs stay green, report shows debt.
- PR gate: `mvn test -Dhealing.failOnHeal=true` — a heal fails the check, forcing the locator fix.
- Post-build: either let `HealingReportPublisher.publish(...)` above post directly (set
  `healing.report.webhook.enabled=true` + `.url`), or keep posting a pipeline concern and call
  `HealingReportPublisherCli` from the Jenkinsfile instead — see
  [Phase 3 — Reporting & Jenkins integration](#phase-3--reporting--jenkins-integration-added) and
  `jenkins/Jenkinsfile.healing-notify` for a ready-to-copy post-build stage.

## Configuration (healing.properties or -D overrides)

| Key | Default | Purpose |
|-----|---------|---------|
| `healing.enabled` | `true` | Master switch |
| `healing.failOnHeal` | `false` | Escalate heals as failures (PR gates) |
| `healing.cache.file` | `target/healing-cache.json` | Cache persistence path |
| `healing.report.file` | `target/healing-report.json` | Report output path |
| `healing.llm.enabled` | `false` | Phase 2 switch |
| `healing.llm.confidence.threshold` | `0.7` | Phase 2 minimum confidence |
| `healing.metrics.file` | `target/healing-metrics.csv` | Phase 3: healing-rate-per-release CSV, one row per run |
| `healing.events.file` | `target/healing-events.csv` | Phase 5: one row per heal event, input to `HealingChurnAnalyzer` |
| `healing.report.webhook.enabled` | `false` | Phase 3: post a heal summary to Slack/Teams at suite end |
| `healing.report.webhook.url` | *(blank)* | Slack incoming-webhook or Teams workflow-webhook URL |
| `healing.report.webhook.format` | `slack` | `slack` \| `teams` \| `teams-messagecard` (legacy) |
| `healing.report.webhook.maxItems` | `20` | Heals listed in the message before truncating to "...and N more" |

## Running the demo tests

```bash
mvn test
```

`SelfHealingFlowTest` mocks the driver to simulate a dev renaming `btn_login` →
`button_login`: the primary lookup fails, the fragment strategy recovers it, the heal is
cached/reported, ambiguity is rejected, and `failOnHeal` escalates. No device needed.

## Development setup

One-time, per clone:

```bash
git config core.hooksPath .githooks
```

This activates `.githooks/prepare-commit-msg`, which drafts a commit message from
your staged diff via the [Claude Code CLI](https://claude.com/claude-code) (`claude -p`)
whenever you commit without `-m`. It's a no-op — leaves the message blank, never
blocks the commit — if `claude` isn't installed/authenticated, the diff is empty, or
you already supplied a message. Works from the terminal and from IDEs that run local
git hooks (in IntelliJ, enable `Settings → Version Control → Git → Run git hooks`).

## Roadmap

- **Phase 2:** `LlmHealingEngine` (Claude Messages API via `java.net.http`), `PageSourcePruner`
  (5–10× XML shrink), `PiiRedactor` (mask account/card/currency patterns before anything
  leaves the machine), confidence gating.
- **Phase 3 (added):** `HealingReportPublisher` (Slack/Teams webhook summary), `HealingReporter.appendMetric`
  (healing-rate-per-release CSV), `HealingReportPublisherCli` + `jenkins/Jenkinsfile.healing-notify`
  (post-build wiring). Parallel-run hardening review still open.
- **Phase 4 (added):** `LocatorPatchGenerator` + `LocatorPatchCli` — a ready-to-review unified diff
  (or direct `--apply`) against `locators_<platform>.properties` from the healing report, platform-aware
  so an Android heal can never overwrite the iOS file's line for the same key.
- **Phase 5 (added):** `HealingChurnAnalyzer` + `HealingChurnCli` — ranks screens by heal
  frequency across builds from `healing.events.file` (a new, finer-grained per-event log;
  Phase 3's `metricsFile` only has a per-run total, not enough to say which screen churned).
  Distinct-build count outranks raw heal count, so one flaky run healing the same locator
  5 times doesn't outrank a locator that genuinely recurs across 3 separate builds.

---

## Phase 2 — LLM healing (added)

New classes:
- `PageSourcePruner` — shrinks `getPageSource()` XML 5–10× (keeps only locator-relevant
  attributes, drops invisible nodes, collapses empty layout wrappers). Degrades to
  truncation on malformed XML — never breaks the healing path.
- `PiiRedactor` — masks card/account numbers, SIN patterns, currency amounts, emails,
  and phone numbers **before anything leaves the machine**. Attribute-aware: it walks
  `attr="value"` pairs and only redacts inside customer-facing attributes (text,
  content-desc, label, value); identifier attributes (resource-id, name, class,
  package, type) are never touched, even when their value happens to look like a
  PII pattern (e.g. a purely numeric resource-id). Runs on whatever `PageSourcePruner`
  returns, including its malformed-XML truncation fallback, so a pruning failure
  never lets unredacted page source through. Defence-in-depth on top of synthetic
  test data. Runs regardless of which LLM provider is selected below.
- `LlmResponseParser` — the provider-agnostic prompt template and response parser
  shared by every engine below: temperature-0 JSON-only contract, tolerates
  commentary the model adds around the JSON via a brace-depth scan (so trailing
  prose that itself contains braces can't corrupt the extracted object), rejects
  index-based xpaths, treats "element not present" as no-heal (never guesses).
- `LlmHealingEngine` — Anthropic Messages API (cloud).
- `OllamaHealingEngine` — local Ollama server, no API key.
- `VastAiHealingEngine` — self-hosted OpenAI-compatible chat-completions endpoint
  (the reference case is a vast.ai GPU rental, but any vLLM / text-generation-webui /
  LM Studio server speaking the OpenAI wire format works the same way).
- `LlmHealingEngineFactory` — builds whichever of the three is configured via
  `healing.llm.provider`, so switching providers is a config change, not a code change.

All three degrade to empty on any failure (timeout, HTTP error, bad JSON) so the
run always falls through to the original `NoSuchElementException` — an LLM outage
never breaks the suite.

### Enabling LLM healing

1. Flip the flag: `healing.llm.enabled=true` (or `-Dhealing.llm.enabled=true`).
2. Pick a provider and configure it (see the three sections below).
3. Wire the factory into your driver factory — this one line never needs to change
   again when you switch providers, only the properties file does:
   ```java
   HealingConfig config = new HealingConfig();
   SelfHealingElementLocator healing = new SelfHealingElementLocator(
           driver, repo, cache, LlmHealingEngineFactory.create(config), config);
   ```

Order of operations per heal attempt is unchanged:
cache → deterministic → **LLM** → rethrow original. The LLM proposal must clear
`healing.llm.confidence.threshold` AND resolve uniquely on screen before it is used.

Common config keys (apply to all providers): `healing.llm.enabled`,
`healing.llm.provider`, `healing.llm.confidence.threshold`,
`healing.llm.timeoutSeconds`, `healing.llm.maxPageSourceChars`.

#### Provider: `anthropic` (default) — Claude Messages API, cloud

```properties
healing.llm.provider=anthropic
healing.llm.model=claude-sonnet-4-6
healing.llm.apiKeyEnv=ANTHROPIC_API_KEY
```
```bash
export ANTHROPIC_API_KEY=sk-ant-...   # never commit it; on Jenkins, a Secret Text credential
```

#### Provider: `ollama` — local model, nothing leaves the machine/network

Requires [Ollama](https://ollama.com) installed and a model pulled locally:
```bash
ollama pull llama3.1
ollama serve            # usually already running as a background service
```
```properties
healing.llm.provider=ollama
healing.llm.ollama.baseUrl=http://localhost:11434
healing.llm.ollama.model=llama3.1
```
No API key — Ollama is expected to run unauthenticated on localhost or a host you
control. This is the option for teams where even the pruned/redacted page source
must never leave the corporate network.

#### Provider: `vastai` — self-hosted model on rented GPU capacity

vast.ai itself only rents GPU instances; there's no fixed "vast.ai API". You deploy
an OpenAI-compatible inference server on the rented box — e.g.
[vLLM](https://github.com/vllm-project/vllm) (`vllm serve <model> --port 8000`),
text-generation-webui in OpenAI mode, or Ollama's own OpenAI-compatible endpoint —
and point this engine at that instance's public URL:
```properties
healing.llm.provider=vastai
healing.llm.vastai.baseUrl=http://<instance-ip>:8000/v1
healing.llm.vastai.model=meta-llama/Llama-3.1-70B-Instruct
healing.llm.vastai.apiKeyEnv=VASTAI_API_KEY
```
```bash
export VASTAI_API_KEY=...   # the bearer token your inference server was started with
```
Because that URL is reachable from the open internet (unlike local Ollama), always
put an API key/token in front of the server (most inference servers accept
`--api-key` on startup) — the engine sends it as `Authorization: Bearer <token>`.

### Switching providers on a consumer project

Nothing in `SelfHealingElementLocator` or your page objects changes. The only
moving part is `healing.properties` (or a `-D` override), because the driver
factory calls `LlmHealingEngineFactory.create(config)` instead of `new
LlmHealingEngine(config)`:

- **Default to Claude in `healing.properties`**, then let individual runs flip
  provider without touching the file:
  ```bash
  mvn test -Dhealing.llm.provider=ollama
  mvn test -Dhealing.llm.provider=vastai -Dhealing.llm.vastai.baseUrl=http://1.2.3.4:8000/v1
  ```
- **Per-environment properties files** (e.g. `healing-local.properties` for devs on
  Ollama, `healing-ci.properties` for Jenkins on Anthropic) — point
  `new HealingConfig("healing-local.properties")` at the one you want.
- An unknown `healing.llm.provider` value, or `healing.llm.enabled=false`, makes
  the factory return `HealingEngine.NO_OP` — the suite still runs, just without
  LLM healing, and a WARN is logged so misconfiguration is visible in the console.

### Phase 2 tests (CI-safe, no network)
- `PageSourcePrunerAndRedactorTest` — prune/redact pipeline incl. end-to-end, the
  numeric-resource-id/name boundary case, and redaction still applying on the
  malformed-XML truncation fallback path
- `LlmResponseParserTest` — the shared brace-depth JSON extraction directly:
  trailing commentary that itself contains braces, braces inside a string value
  (e.g. an xpath predicate), leading prose, no-JSON-at-all
- `LlmHealingEngineTest` — Anthropic engine: full prompt-build + response-parse
  path via fake Transport (valid proposal, fenced JSON, prose before JSON, "none",
  index-xpath rejection, HTTP failure, garbage response, missing API key short-circuit)
- `OllamaHealingEngineTest` — same coverage against the Ollama `/api/chat` envelope
- `VastAiHealingEngineTest` — same coverage against the OpenAI-compatible
  chat-completions envelope, plus missing-`baseUrl` short-circuit
- `LlmProviderParityTest` — feeds identical assistant text (valid proposal, fenced
  JSON, prose before JSON, "none", no JSON) through all three providers' real
  envelope shapes and asserts they parse to the same `Proposal`, guarding against
  one provider silently drifting from the others
- `LlmHealingEngineFactoryTest` — `healing.llm.provider` selects the right engine
  (case-insensitive), unknown values and `healing.llm.enabled=false` both yield `NO_OP`

---

## Phase 3 — Reporting & Jenkins integration (added)

New classes:
- `HealingReportPublisher` — builds a "N locators healed - POM updates recommended"
  message and posts it to a Slack incoming-webhook or Teams webhook from the same
  `@AfterSuite` hook that writes the JSON report. No-ops (never fails the build) when
  the webhook is disabled, the URL is blank, or there were zero heals this run. The
  HTTP layer is injectable (`Transport`), same pattern as the Phase 2 engines.
- `HealingReportPublisherCli` — the same payload logic as a Jenkins-callable `main()`:
  reads `healing-report.json` and either prints the webhook JSON to stdout (so the
  pipeline owns the `curl` call and credential binding) or posts it directly with
  `--post <url>`.
- `HealingReporter.appendMetric(buildId, file)` — appends one CSV row (timestamp,
  build id, heal count) per run to `healing.metrics.file`. That's the raw data behind
  a healing-rate-per-release trend line (Phase 5 turns it into an actual trend).

### Wiring it in

Call from the same `@AfterSuite` hook as `HealingReporter.writeReport` (see
[Suite teardown](#5-suite-teardown-testng) above):

```java
HealingConfig config = new HealingConfig();
HealingReporter.writeReport(Path.of(config.reportFile()));
HealingReporter.appendMetric(System.getProperty("app.build.id", "unknown"), Path.of(config.metricsFile()));
HealingReportPublisher.publish(HealingReporter.records(), config);
```

`HealingReportPublisher.publish(...)` is inert until you set:
```properties
healing.report.webhook.enabled=true
healing.report.webhook.url=https://hooks.slack.com/services/...
healing.report.webhook.format=slack
```

### Payload formats

- `slack` (default) — Slack incoming-webhook body: `{"text": "..."}`.
- `teams` — the current supported Teams integration path: a Power Automate
  "when a webhook request is received" workflow, posted the same `{"text": "..."}`
  shape as Slack. Microsoft's schema for that trigger is whatever your specific Flow
  is built to accept, so **verify with a real test payload** before relying on it -
  this isn't a fixed platform contract the way the Slack shape is.
- `teams-messagecard` — the legacy Office 365 Connector `MessageCard` schema.
  Microsoft has been retiring these connectors since 2024/2025; only use this if
  your tenant still has a working one.

### Jenkins

`jenkins/Jenkinsfile.healing-notify` is a copy-paste reference post-build stage
(not run by this repo's own build) that:
1. Restores the previous run's `healing-metrics.csv` before the test stage, so the
   per-run CSV rows accumulate into a real cross-build trend (`target/` is wiped by
   `mvn clean` each build, so this module alone can't persist it across runs).
2. Archives `healing-report.json` / `healing-metrics.csv` as build artifacts.
3. Runs `HealingReportPublisherCli` and posts the result to a webhook URL held in a
   Jenkins credential (never hard-code the URL in the Jenkinsfile).

Either let the Java-side `HealingReportPublisher.publish(...)` post directly, or keep
posting a pipeline concern via the CLI - not both, to avoid double-posting.

### Phase 3 tests (CI-safe, no network)
- `HealingReportPublisherTest` — payload shape per format (slack/teams/teams-messagecard,
  unrecognized-format fallback), singular/plural + truncation in the summary text, and
  the publish no-op matrix (webhook disabled, blank URL, zero heals, transport failure
  swallowed, correct URL/body on a successful call)
- `HealingReporterTest` — `record()` notifies listeners and survives a throwing listener,
  `writeReport()` produces parseable JSON, `appendMetric()` writes the CSV header once
  then appends rows, and CSV-escapes a build id containing commas/newlines

---

## Phase 4 — Auto-generated locator patch (added)

New classes:
- `LocatorPatchGenerator` — turns a healing report into a patch against a
  `locators_<platform>.properties` file. Operates on the file as plain text (never via
  `java.util.Properties` load/store), so comments, ordering, and blank lines survive -
  only the value side of a matched `key=strategy=value` line is rewritten. Also renders
  a `git apply` / `patch -p1` compatible unified diff.
- `LocatorPatchCli` — the Jenkins-callable `main()`: prints the diff (review-only,
  default) or applies it in place with `--apply`.

`HealingReporter.HealingRecord` gained two fields to make this possible:
`healedStrategy`/`healedValue` — the machine-usable `(LocatorStrategy, value)` pair
behind a heal, recovered from the raw Selenium `By` via `HealingCache.recover` (the
same reverse-mapping the cache already used internally) wherever the LLM's own
`Proposal.strategy()/value()` wasn't already available directly. A record missing
either (e.g. a report written before this field existed) is reported as **skipped**,
never guessed at from the human-readable `healedLocator` string.

**Platform-aware by construction:** locator keys are shared across both platform
properties files by convention (same key, different strategy/value per platform) - so
`HealingRecord` also gained a `platform` field, and `LocatorPatchGenerator.apply` takes
the target `Platform` explicitly. A record for the other platform is reported as
**wrongPlatformKeys** and never applied, which is what stops an Android heal from
silently overwriting the iOS file's line for the same key (this was caught by testing
the CLI against both files with the same report - worth being deliberate about when
wiring your own tooling on top of the report, not just this CLI).

### Usage

```bash
# Review only - prints a unified diff, touches nothing:
java -cp self-healing-locators.jar com.dinesh.healing.LocatorPatchCli \
    target/healing-report.json src/main/resources/locators/locators_android.properties android

# Apply directly (e.g. a Jenkins job that commits the result as a PR):
java -cp self-healing-locators.jar com.dinesh.healing.LocatorPatchCli \
    target/healing-report.json src/main/resources/locators/locators_ios.properties ios --apply
```

Run once per platform file. A mixed Android+iOS report naturally produces
`wrongPlatformKeys` warnings for the other platform's keys when you run it against
this one - expected, not an error.

### Phase 4 tests (CI-safe, no network)
- `HealingCacheTest` — `HealingCache.recover` round-trips every `LocatorStrategy`
  through its real `AppiumBy`/`By` `toString()` shape (this closes a latent gap where
  `iOSClassChain` silently fell back to being recovered as `xpath`), plus basic
  put/get/persist/load and cross-build cache discarding
- `LocatorPatchGeneratorTest` — single/multiple key patches, the `.description` line
  never touched, byte-for-byte survival of untouched lines, missing-key →
  `unmatchedKeys`, blank strategy/value → `skippedKeys`, other-platform record →
  `wrongPlatformKeys` (and the file staying untouched), last-record-wins on a repeated
  key, and the unified-diff hunk grouping (adjacent changes merge, far-apart changes
  split into separate hunks)

---

## Phase 5 — Healing analytics (added)

New classes:
- `HealingChurnAnalyzer` — reads the accumulated `healing.events.file` CSV and ranks
  "screens" (the part of a `screen.element` locator key before the first `.`) by heal
  frequency. Ranks by **distinct build count first, then total heal count** - a single
  flaky run healing the same locator 5 times looks very different from a locator that
  genuinely recurs across 3 separate builds, and the latter is the real churn signal
  worth investigating (unstable screen, or an under-owned one nobody's updating the
  POM for).
- `HealingChurnCli` — prints the ranked report from the command line / a Jenkins step.

`HealingReporter.appendEvents(buildId, file)` is the new writer behind this: one CSV
row per individual heal (`timestamp,buildId,platform,locatorKey,healingStrategy`).
This is deliberately separate from Phase 3's `appendMetric` - that one only has a
per-run *total*, which can't tell you WHICH screen is churning, only that something
did. Call it alongside `appendMetric`/`writeReport` in the same `@AfterSuite` hook (see
[Suite teardown](#5-suite-teardown-testng) above). Like `metricsFile`, this lives under
`target/` by default, so it only accumulates within one build unless your Jenkins job
restores the previous run's copy first - extend `jenkins/Jenkinsfile.healing-notify`'s
`copyArtifacts` step to also grab `healing-events.csv`.

**What it can't tell you:** the analyzer only ranks screens that healed at least once
somewhere in the log - it has no way to enumerate "screens that never heal" without
reading the full locator repository, which it deliberately never touches (keeps it a
pure CSV-in, report-out utility, no classpath/properties-file coupling). Absence from
the ranking means "no recorded heals", not "provably stable".

### Usage

```bash
java -cp self-healing-locators.jar com.dinesh.healing.HealingChurnCli \
    target/healing-events.csv --top 10
```

```
Healing churn by screen (top 2 of 3):
1. login                  3 heal(s) across 3 build(s) - keys: login.submitButton
2. accounts               5 heal(s) across 1 build(s) - keys: accounts.transferButton
...and 1 more screen(s)
```

### Phase 5 tests (CI-safe, no network)
- `HealingChurnAnalyzerTest` — screen grouping from the locator key convention,
  distinct-build vs. total-heal-count semantics (including the flaky-run-vs-chronic-issue
  ranking case above), a key with no `.` being its own screen, malformed/blank CSV rows
  skipped rather than crashing, and report truncation to `--top N`
- `HealingReporterTest` — `appendEvents()` writes the CSV header once then one row per
  heal, and writes nothing at all on a zero-heal run
