# Self-Healing Locators — Build Specification

A requirements/architecture spec for building a self-healing element locator layer
for Appium + Java + TestNG mobile test frameworks. This describes *what* to build and
*why* — not source code — so it can drive a fresh implementation.

## Problem statement

Mobile UI tests break constantly because developers rename resource-ids /
accessibility identifiers without updating the test suite. Most teams either:
(a) let tests go red and manually chase the locator fix, or (b) build brittle
self-healing hacks that guess wrong and mask real regressions.

**Goal:** a drop-in decorator around `driver.findElement()` that tries free,
deterministic recovery strategies first, optionally escalates to an LLM, and NEVER
silently guesses — every heal is logged, cached, and reported so the team fixes the
real locator file instead of accumulating hidden debt.

## Non-negotiable design guarantees

1. **Never guess.** Every candidate replacement locator must resolve to EXACTLY ONE
   element on screen, or it's rejected. Ambiguous matches are never used.
2. **Never mask a real bug.** If nothing heals, the ORIGINAL exception must surface
   unchanged — a healing failure must look identical to a pre-healing-layer failure.
3. **Never silent.** Every successful heal is logged at WARN, recorded to a JSON
   report, and mirrored to any registered listener (Slack/Teams/ExtentReports/etc.).
4. **Fast path has zero overhead.** When the primary locator resolves, healing code
   never runs — same performance as a plain `findElement`.
5. **Heals expire with the app build.** A persisted heal is scoped to a build
   identifier (version/APK checksum/CI build number); a new build discards it.

## Architecture — resolution order

For each element lookup by a `screen.element` key:
1. Resolve the primary locator from a per-platform properties repository → try it.
2. On `NoSuchElementException`: check a persisted **healing cache** (already healed
   earlier in this run/build) → if hit and still unique on screen, use it.
3. **Deterministic fallbacks** (free, no network) — see below.
4. **LLM healing engine** (optional, config-gated) — see below.
5. All failed → rethrow the ORIGINAL exception, never a healing artifact.

Every successful heal at any stage: cache it, log it, report it.

## Core module (always-on, no LLM)

- **Locator repository**: loads two properties files (one per platform: Android/iOS).
  Each key is a dotted `screen.element` identifier mapping to `<strategy>=<value>`
  (strategies: id, accessibilityId, xpath, className, androidUIAutomator,
  iOSNsPredicate, iOSClassChain) plus a `.description` companion key (human-readable
  description of what the element IS — drives deterministic text-matching and later
  becomes the LLM prompt). Keys should exist in both platform files; log (don't hard
  fail) when one is missing on a platform, since real apps can have platform-exclusive
  screens — but offer an opt-in strict check for teams that want a hard fail-fast gate.
- **Deterministic healing strategies**, tried in order, cheapest/most-reliable first:
  1. Cross-strategy swap: if the primary was `id`, also try the same value as
     `accessibilityId` (and vice versa) — catches the common id↔content-desc drift on
     Android, and a mis-authored strategy on iOS.
  2. Android-specific: if primary was `accessibilityId` (content-desc), try the same
     value as a `resource-id` contains-match, and vice versa — resource-id and
     content-desc are genuinely different Android attributes that drift independently.
  3. iOS-specific: if primary was `accessibilityId` (the `name` attribute), try the
     same value against `label` first, then `value` as SEPARATE candidates (not one
     combined predicate) — combining them risks one element matching via label and a
     different one via value, which would reject as ambiguous even when label alone
     would cleanly match.
  4. Partial-identifier match: extract the most "distinctive" word fragment from an id
     (skip generic words like "btn", numbers-only fragments) and try a contains-match
     on resource-id (Android) or name (iOS) — survives renames like `btn_login` →
     `button_login`.
  5. Text/description-based match: pull 2-3 distinctive keywords out of the element's
     human-readable description (skip stop-words) and try matching visible text /
     content-desc / label.
  Every candidate goes through the uniqueness gate before being accepted.
- **Healing cache**: thread-safe (concurrent-safe map), scoped to a build id, persisted
  to disk as JSON, reloaded next run — a heal found once isn't re-discovered (or
  re-billed to an LLM) every subsequent lookup within the same build.
- **Reporting**: an in-memory, thread-safe, ever-growing list of heal records
  (timestamp, key, platform, description, original locator, healed locator +
  strategy/value, healing technique used, source [cache/deterministic/llm]). Written
  to a JSON report file at suite end. A pluggable listener interface lets a consuming
  framework mirror each heal into its own reporting tool without this module taking a
  dependency on it.
- **Config**: a single config object reading defaults from a bundled properties file,
  overridable per-key via `-D` system properties (so CI jobs can flip flags without
  touching files). Master enable switch, plus a `failOnHeal` flag that escalates any
  successful heal into a hard failure — used for PR-gate jobs, left off for nightly
  regression so runs stay green while the report quietly accumulates debt visibility.

## Optional module: LLM-backed healing

A pluggable interface with three interchangeable HTTP-based implementations (a cloud
provider's chat API, a local self-hosted model server, and a generic OpenAI-compatible
self-hosted endpoint) selected via one config key — swapping providers must be a config
change, never a code change. All three:
- Send only a **pruned and PII-redacted** snapshot of the page source, never the raw
  dump — shrink the raw accessibility-tree XML to just locator-relevant attributes
  (drop bounds/coordinates/noise, drop invisible nodes, collapse trivial layout
  wrappers), then mask card/account numbers, currency amounts, emails, and phone
  numbers in the CUSTOMER-FACING attributes only (text/label/content-desc/value) —
  identifier attributes (resource-id, accessibility id/name, class, package) must
  survive completely untouched regardless of what their value looks like, since
  locating depends on them surviving byte-for-byte.
- Use a temperature-0, JSON-only response contract, but tolerate the model adding
  commentary around the JSON anyway (extract the first balanced JSON object, not just
  "first `{` to last `}`" — a naive scan breaks when trailing commentary or an
  in-string value itself contains braces).
- Reject any proposed xpath whose only discriminator is a positional index (e.g.
  `//Button[3]`) — those aren't stable across app rebuilds.
- Treat "element not present" as an explicit no-heal signal, never a guess.
- Gate on a confidence threshold BEFORE spending a uniqueness-check lookup on the
  driver — cheap reject before expensive validation.
- Degrade to empty/no-op on ANY failure (timeout, HTTP error, bad JSON, missing API
  key) — an LLM provider outage must never break the test run, it just falls through
  to the original exception like every other failed strategy.
- Share ALL of the above response-parsing logic across the three providers — they
  should differ ONLY in how they build the request and unwrap their response envelope,
  not in the underlying JSON-extraction/validation rules (a parity test asserting all
  three parse identical assistant text identically is worth having).

## Optional module: Reporting integrations

- A webhook publisher that posts a "N locators healed" summary (with a short list of
  what healed) to a Slack-compatible or Microsoft Teams-compatible incoming webhook at
  suite end. No-op (never fails the build) when disabled, misconfigured, or there's
  nothing to report. A CLI entry point lets a CI job print or post the same payload
  from an already-written report file, independent of the test JVM.
- A per-run metric (timestamp, build id, heal count) appended to a CSV for a
  healing-rate-per-release trend line.

## Optional module: Auto-generated locator fix

Given the report, generate a reviewable patch against the actual properties file(s):
- Operate on the file as PLAIN TEXT line-by-line, never via a generic properties
  parser's load-then-save round-trip — that would destroy comments, ordering, and
  blank lines. Only rewrite the value side of a matched key's line.
- Never touch a key's `.description` companion line.
- Render an actual unified diff (git-apply-compatible) for human review, plus an
  apply-directly mode.
- **Must be platform-aware**: since locator keys are shared by convention across both
  platform files, a fix sourced from an Android run must never be blindly applied to
  the iOS file for the same key (and vice versa) — tag each heal record with which
  platform it came from, and refuse/report (not silently apply) a cross-platform
  mismatch.
- Treat this as a human-reviewed PR generator, not an auto-merge — a heal is a
  candidate fix, not a guaranteed correct one.

## Optional module: Healing analytics

Rank "screens" (the part of a `screen.element` key before the first dot) by how often
they heal, using a per-event log (one row per heal: timestamp, build id, platform, key,
technique) accumulated across many runs. Rank by **distinct build count first, total
heal count second** — a locator healing 5 times in one flaky run is a very different
signal from one healing twice but in two separate builds; the latter is genuine
recurring instability worth investigating, the former is noise.

## Testing philosophy

- Every HTTP-calling class must accept its transport as an injectable dependency (a
  simple function/interface the test can fake) so the full request-build +
  response-parse path is exercised with zero network calls and zero API keys — CI-safe
  by construction, not by mocking a library.
- Concurrency matters: the cache and reporter are shared across every parallel worker
  thread in a parallel test run (one locator-decorator instance per thread, same
  shared cache/reporter underneath) — stress-test with many threads hammering
  read/write concurrently and assert zero lost writes, not just "the collection type
  looks thread-safe."
- CLI entry points should fail with a clean, one-line usage error on bad arguments,
  never an unhandled stack trace.
- Every config default, every parsing edge case (malformed input file, unknown
  strategy name, missing platform), and every "should this be skipped vs applied vs
  rejected" branch deserves a direct test — don't rely on incidental coverage through
  other classes' happy-path tests.

## Configuration surface (name the keys, keep every one independently overridable)

Master enable/disable, fail-on-heal, cache file path, report file path, LLM
enable/provider-selection/confidence-threshold/timeout/max-page-source-size,
per-provider connection settings (base URL / model / API-key-env-var-name — never the
key itself), webhook enable/url/format/timeout, metrics file path, events file path.

## What NOT to build

- No feature flags/back-compat shims for hypothetical future requirements.
- No silent fallback that could mask a real UI regression as a "successful heal."
- No new heavyweight dependencies where the JDK's own HTTP client + a JSON library
  suffice — this targets regulated/bank environments where dependency footprint and
  auditability matter.

---

# Phase-by-phase Copilot prompts

Paste ONE prompt at a time, in order, into Copilot Chat (agent/edit mode, so it can
create multiple files). Let each phase fully build + pass tests before moving to the
next — don't paste them all at once. Each prompt is self-contained (repeats the
guarantees it needs) so it also works if you start a fresh chat session per phase.

## Prompt 0 — Project scaffold

```
Set up a new Maven Java project named "self-healing-locators", Java 17, packaging jar.

Dependencies:
- io.appium:java-client (latest stable 9.x) — scope "provided" (this module must never
  fight the consuming framework's own Appium/Selenium version)
- com.fasterxml.jackson.core:jackson-databind (latest stable 2.x)
- org.slf4j:slf4j-api (latest stable 2.x)
Test-scope only: org.testng:testng (latest 7.x), org.mockito:mockito-core (latest 5.x),
org.slf4j:slf4j-simple (latest 2.x)

Configure maven-surefire-plugin so `mvn test` runs the TestNG suite.

Package: com.dinesh.healing (or your preferred group id — keep it consistent across
every subsequent prompt).

Create an empty README.md with just the project name and a one-paragraph description:
a drop-in self-healing element locator layer for Appium + Java + TestNG frameworks that
tries free deterministic recovery before ever guessing, and never hides a real failure.

Don't write any implementation classes yet — just the working, buildable skeleton.
```

## Prompt 1 — Phase 1: core module (no LLM)

```
Continuing the self-healing-locators project. Implement the always-on core module —
no LLM involved yet. Design guarantees that apply to EVERYTHING below:
1. Never guess: any candidate replacement locator must resolve to exactly ONE element
   on screen or it's rejected — never pick between ambiguous matches.
2. Never mask a real bug: if nothing heals, rethrow the ORIGINAL exception unchanged.
3. Never silent: every successful heal is logged (WARN) and recorded.
4. Zero overhead on the fast path: when the primary locator resolves, no healing code
   runs at all.
5. A heal is scoped to an app build id; a new build discards old heals.

Build these pieces:

1. A Platform enum (ANDROID, IOS) detectable from Appium driver capabilities.

2. A locator strategy concept covering: id, accessibilityId, xpath, className,
   androidUIAutomator, iOSNsPredicate, iOSClassChain — each maps to the matching
   AppiumBy/By factory call.

3. A locator definition: key, human-readable description, strategy, value.

4. A locator repository loading TWO properties files, one per platform (classpath or
   filesystem). Each properties key is a dotted "screen.element" id mapped to
   "<strategy>=<value>" (only the FIRST "=" is the separator — values may contain
   their own "="), plus a companion "<key>.description=..." line. Resolve
   (key, platform) -> definition. If a key exists on one platform's file but not the
   other, don't hard-fail construction (real apps can have platform-exclusive
   screens) — log a clear warning listing the mismatched keys, and separately expose
   an opt-in method a caller can invoke from a startup smoke test to hard-fail on any
   mismatch. Fail loudly and clearly (not silently) on: resource not found, a line
   missing the strategy/value separator, or an unrecognized strategy name.

5. Deterministic (zero-network) healing strategies, tried cheapest-and-most-reliable
   first, given a failed primary locator:
   a. Cross-strategy swap: if primary was "id", also try the SAME value as
      "accessibilityId" (and vice versa).
   b. Android only: if primary was "accessibilityId" (content-desc), also try the
      same value as a resource-id CONTAINS-match (and vice versa) — these are
      genuinely different Android attributes that can drift independently.
   c. iOS only: if primary was "accessibilityId" (the `name` attribute), try the same
      value against `label`, THEN as a separate candidate against `value` — two
      separate candidates, not one combined OR predicate (combining them risks one
      element matching via label and a different one via value, which would reject as
      ambiguous even though label alone would have cleanly matched).
   d. Partial-identifier match: pull the most "distinctive" fragment out of an
      id/name (skip generic filler words and pure-number fragments) and try a
      CONTAINS-match against resource-id (Android) or name (iOS) — must survive a
      rename like "btn_login" -> "button_login".
   e. Text/description match: pull 2-3 distinctive keywords from the element's
      human-readable description (skip common stop-words) and try matching visible
      text / content-desc (Android) or label/name (iOS).
   Every candidate must pass a uniqueness check (exactly one match on screen) before
   being accepted; log at DEBUG (not fail) when a candidate is rejected for ambiguity.

6. A thread-safe healing cache: build-id-scoped, persisted to a JSON file, reloaded on
   startup (discarding entries from a DIFFERENT build id). Safe for concurrent
   read/write from many parallel test threads.

7. A thread-safe heal reporter: an ever-growing, concurrency-safe list of heal records
   (timestamp, locator key, platform, description, original locator, healed locator +
   its strategy/value pair, the healing technique used, and the source: cache /
   deterministic / llm). Writes the full list to a JSON report file on demand. Exposes
   a pluggable listener interface so a consuming framework can mirror each heal into
   its own reporting tool (e.g. ExtentReports) without this module depending on it —
   a listener throwing must never break recording.

8. A config object: reads defaults from a bundled properties file, every key
   individually overridable via a `-D` system property. Keys: master enable switch
   (default true), a "failOnHeal" switch (default false) that escalates any successful
   heal into a hard test failure instead of returning the healed element — used for
   PR-gate jobs; left off for nightly regression runs so they stay green while the
   report quietly accumulates visibility into locator debt. Plus file paths for the
   cache and the report.

9. The main entry point: a decorator wrapping a WebDriver + the repository + the cache
   + the config (+ later, an optional LLM engine — stub it as a no-op interface for
   now). `find(key)` resolves the primary locator; on NoSuchElementException, checks
   the cache, then tries the deterministic strategies in order; on any successful
   heal, caches it, reports it, and either returns the healed element or throws a
   distinct "this required healing" exception if failOnHeal is set; if nothing heals,
   rethrows the ORIGINAL exception.

Write TestNG unit tests for every piece above, especially:
- The repository's error paths (missing file, malformed line, unknown strategy,
  missing key, key missing on only one platform).
- Every deterministic strategy's happy path AND its "nothing distinctive enough,
  correctly yields no candidate" edge case.
- Cache: persist + reload round-trip, and a different build id discarding old entries.
- Reporter: a throwing listener must not break recording; concurrent recording from
  many threads must lose zero heal events (write an actual multi-threaded stress test
  with a thread pool, don't just assert the collection type looks thread-safe).
- Config: every getter's documented default, and that a system property overrides
  both the bundled file's value and the hardcoded default.
- The end-to-end decorator flow with a mocked/faked driver: primary fails, a
  deterministic strategy recovers it, the heal is cached and reported, an ambiguous
  candidate is rejected, and failOnHeal correctly escalates.

No LLM code yet. `mvn test` must be fully green with no network access required.
```

## Prompt 2 — Phase 2: LLM-backed healing (optional, pluggable)

```
Continuing self-healing-locators (core module from Phase 1 already exists and is
tested). Add an OPTIONAL, config-gated LLM healing escalation as the last resort after
deterministic strategies fail; it must plug into the existing decorator's resolution
order without changing anything about Phase 1.

Design a small interface: given a locator key, its description, and a page-source
string, return an optional proposed replacement (strategy + value + a 0.0-1.0
confidence score). Ship a real do-nothing implementation for when no LLM is
configured.

Implement THREE interchangeable HTTP-based providers behind that interface, selectable
by ONE config key so swapping providers is purely a config change:
1. A cloud provider's chat-completions API (pick one, e.g. Anthropic's Messages API).
2. A locally-hosted model server (e.g. Ollama), no API key required by default but
   support an optional bearer token for an authenticated tunnel/proxy in front of it.
3. A generic self-hosted OpenAI-compatible chat-completions endpoint (for teams
   running their own inference server on rented GPU capacity).

Shared, provider-agnostic pieces (used identically by all three):

A) Page-source shrinker: parse the raw Appium getPageSource() XML defensively (never
   let a parse failure break the healing path — degrade to a truncated raw string
   instead of throwing), keep only locator-relevant attributes (resource-id,
   content-desc, text, class, package, name, label, value, type, and a few state
   flags), drop invisible/not-displayed nodes, and collapse layout-only wrapper
   elements that add no identifying value and have at most one child. Target a
   meaningful size reduction (aim for 5-10x on a typical screen).

B) PII redactor: mask card/account-like digit runs (13-19 digits, optionally grouped),
   SIN-style digit groups, currency amounts, email addresses, and North American phone
   numbers — but ONLY inside customer-facing attributes (text, content-desc, label,
   value). Identifier attributes (resource-id, name, class, package, type) must be
   left completely untouched even when their value happens to look numeric/PII-like —
   don't rely on regex word-boundaries alone to protect them (a resource-id
   immediately preceded by a non-word character like "/" can still accidentally match
   a blind digit-run regex); redact per-attribute, not via one blind whole-string
   pass. Run this on whatever the shrinker returns, INCLUDING its degraded/truncated
   fallback output — a parsing failure must never let unredacted data through.

C) Shared prompt template + response parser: build a temperature-0, JSON-only prompt
   ("respond only with JSON: {strategy, value, confidence}, or {strategy:'none'} if
   the element isn't present — never an index-based xpath like //Button[3]"). Parse
   the model's raw text tolerating commentary the model adds anyway: extract the
   FIRST BALANCED JSON object using brace-DEPTH tracking (increment on '{', decrement
   on '}', stop at zero), also tracking whether you're inside a quoted string
   (respecting backslash-escapes) so a brace inside a string value doesn't miscount —
   a naive "first '{' to last '}'" substring grab breaks when trailing commentary
   itself contains braces. Reject any xpath whose only discriminator is a trailing
   positional index (e.g. ends in "[3]" with no attribute predicate). Treat
   strategy "none" as an explicit empty result, never an error.

Each provider implementation: builds its own request envelope, unwraps its own
response envelope down to the assistant's raw text, then hands that text to the
SHARED parser from (C) — providers must never duplicate the JSON-extraction logic.
The HTTP call itself must be injectable (a simple functional interface) so tests never
touch the network. On ANY failure (timeout, non-2xx HTTP, malformed JSON, missing API
key/config) return empty — an LLM outage must never break the test run.

Wire the confidence threshold check to happen BEFORE the expensive
find-elements-on-screen uniqueness check — cheap rejection before expensive
validation.

Add a factory that builds the configured provider from the config's provider-selection
key, returning the no-op implementation for an unrecognized value (log a warning, not
an exception).

Write TestNG tests:
- For each provider: full request-build + response-parse path via a fake injected
  transport (valid proposal, markdown-fenced JSON, prose before AND after the JSON,
  "none" strategy, index-based xpath rejection, HTTP failure, garbage response, missing
  config short-circuit).
- The shared parser directly: trailing commentary containing its own braces, braces
  inside a string value, no JSON at all.
- The redactor: a purely numeric resource-id/name value survives untouched even
  without a helpful word boundary, customer-facing attributes get masked, and
  redaction still applies when the shrinker degrades to its truncated-raw fallback.
- A parity test: identical assistant text, wrapped in each of the three providers'
  real envelope shapes, must parse to the identical proposal across all three.
- The factory: provider selection is case-insensitive, unknown value and
  LLM-disabled both yield the no-op implementation.

`mvn test` must stay fully green with zero network calls and zero real API keys.
```

## Prompt 3 — Phase 3: reporting integrations

```
Continuing self-healing-locators (core + LLM modules exist and are tested). Add
suite-end reporting on top of the existing heal reporter/report-writing from Phase 1.

1. A per-run metric: append one row (timestamp, build id, heal count) to a CSV file
   every suite run — the raw data for a healing-rate-per-release trend line. A
   zero-heal run still appends a row (a flat trend line is itself signal).

2. A webhook publisher: build and POST a "N locators healed - review recommended"
   summary (list a capped number of what healed, e.g. up to 20, with a "+N more"
   suffix past that) to a Slack-compatible incoming webhook OR a Microsoft Teams
   workflow webhook, selectable by a format config key, plus a legacy Teams
   "MessageCard" format option. No-op (log, never throw, never fail the build) when
   disabled, misconfigured (blank URL), or there's nothing to report. The HTTP call
   must be injectable for testing.

3. A small CLI entry point (a `main` method) that reads an already-written JSON report
   file and either prints the same webhook payload to stdout (so a CI pipeline can
   pipe it into its own curl call using a securely-injected credential) or posts it
   directly given a URL argument — useful because the reporting step often needs to
   run as a separate CI stage, independent of the test JVM that produced the report.
   Handle missing/malformed CLI arguments with a clean one-line usage message and a
   non-zero exit code, never an unhandled exception stack trace.

Write TestNG tests: payload shape per format, singular vs. plural wording, list
truncation past the cap, and the full no-op decision matrix (disabled, blank URL, zero
heals, transport failure swallowed without throwing, correct URL+body on success).

`mvn test` must stay fully green, zero network calls.
```

## Prompt 4 — Phase 4: auto-generated locator patch

```
Continuing self-healing-locators. Add a utility that turns a healing report into a
reviewable patch against the actual locator properties file(s) — "here's your tech
debt" becomes "here's the diff that fixes it."

Requirements:
- Take the report's heal records and the target platform's properties file content.
  Only records carrying a genuinely usable strategy+value pair can be applied —
  anything else is reported as skipped, never guessed at from a display string.
- Operate on the properties file as PLAIN TEXT, matching lines by key, NOT via a
  generic Properties-object load-then-save round-trip (that destroys comments,
  ordering, and blank lines). Rewrite only the value side of a matched
  "key=strategy=value" line. A key's separate ".description" companion line must
  never be touched, even though it shares the same key prefix.
- Must be PLATFORM-AWARE: locator keys are shared by convention across both platform
  files (same key exists in both, with platform-specific strategy/value) — so a heal
  record must carry which platform it came from, and applying a patch to one
  platform's file must SKIP AND REPORT (never silently apply) any record tagged for
  the other platform. This is the single most important correctness requirement here
  — verify it explicitly: feed an Android-sourced report into a patch run against the
  iOS file and confirm the iOS file is completely untouched.
- Report back, separately: which keys were actually changed, which were skipped
  (unusable data), which weren't found in this file at all, and which belonged to the
  other platform.
- Render an actual git-apply-compatible unified diff (correct hunk headers, context
  lines, adjacent changes merged into one hunk, far-apart changes in separate hunks)
  as well as supporting a direct apply-in-place mode.
- A CLI entry point: default behavior prints the diff (or writes it to a file) without
  touching anything; an explicit flag applies the patch in place. Requires the target
  platform as an explicit argument (don't infer it from the filename).

Write TestNG tests: single and multiple key patches, the description line never
touched, all other lines byte-for-byte unchanged, a missing key reported not silently
ignored, unusable-data records skipped not guessed, an other-platform record REJECTED
with the file left untouched (this is the test that matters most), last-record-wins
when the same key appears twice in one report, and the unified-diff hunk-grouping
logic (adjacent changes merge, far-apart changes don't).

`mvn test` must stay fully green.
```

## Prompt 5 — Phase 5: healing analytics

```
Continuing self-healing-locators. Add a "which screens keep breaking" analysis on top
of a new per-event log.

1. Extend the reporting from Phase 3 with a finer-grained log: one CSV row per
   individual heal (timestamp, build id, platform, locator key, healing technique) —
   deliberately separate from the existing per-run-total metric, since that one can't
   say WHICH screen churned, only that something did. Skip writing anything on a
   zero-heal run (nothing to log).

2. An analyzer that reads the accumulated event log (spanning many runs/builds) and
   groups rows by "screen" — the part of a locator key before its first dot. For each
   screen, compute: total heal count, DISTINCT build-id count, the list of distinct
   locator keys involved, and the most recent timestamp.

3. Rank screens by DISTINCT BUILD COUNT FIRST, total heal count second (not the other
   way around) — a locator healing 5 times within one single flaky build is a much
   weaker signal than one healing twice but across two separate builds; the latter
   represents genuine recurring instability and should rank higher even though its
   raw heal count is lower. Verify this ranking rule with an explicit test case built
   around exactly that scenario.

4. The analyzer can only report on screens that appear in the log at least once — it
   has no way to enumerate "screens that never break" without reading the full locator
   repository, and it should NOT take that dependency; document that absence from the
   ranking means "no recorded heals," not "proven stable."

5. A CLI entry point that reads the event log and prints a ranked, capped
   (e.g. top N, configurable) plain-text report.

Write TestNG tests: correct screen grouping from the key convention, the
distinct-build-vs-total-heals ranking behavior (the flaky-single-build vs.
chronic-cross-build scenario above, asserted explicitly), a key with no dot being its
own screen, malformed/blank log rows skipped rather than crashing the whole analysis,
and report truncation to top-N with an accurate "and N more" count.

`mvn test` must stay fully green.
```
