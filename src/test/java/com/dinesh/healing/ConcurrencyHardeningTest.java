package com.dinesh.healing;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/**
 * Phase 3 roadmap flagged "parallel-run hardening review" as open - a real concern
 * since {@link HealingCache} and {@link HealingReporter} are shared across every
 * {@link SelfHealingElementLocator} instance in a parallel TestNG suite (one instance
 * per thread/device, all backed by the SAME cache/reporter). This exercises that
 * sharing under actual concurrent load rather than reasoning about it from the
 * collection types alone.
 *
 * Findings, confirmed rather than assumed:
 *   - HealingCache: ConcurrentHashMap entries + synchronized persist() - no lost
 *     updates for distinct keys under concurrent put(), and concurrent put()+persist()
 *     never corrupts the JSON file or throws.
 *   - HealingReporter: CopyOnWriteArrayList RECORDS - no lost writes under concurrent
 *     record() calls. Note (not a bug, just a real characteristic): CopyOnWriteArrayList
 *     copies the whole list on every add, so this scales fine for realistic heal
 *     volumes (a handful to low hundreds per run) but isn't the right structure if a
 *     single run ever produced thousands of heals.
 *   - LocatorRepository: HashMap populated only during construction, never mutated
 *     after - safe for concurrent get() by multiple threads PROVIDED the repository
 *     is built and handed to worker threads before they start (exactly the pattern
 *     the README's driver-factory example uses: build once, pass into each
 *     SelfHealingElementLocator). Confirmed here via a final-field handoff through
 *     an ExecutorService, which is the same kind of safe publication a TestNG
 *     parallel thread pool provides.
 */
public class ConcurrencyHardeningTest {

    private static final int THREADS = 16;
    private static final int OPS_PER_THREAD = 200;

    @AfterMethod
    public void resetReporter() {
        HealingReporter.reset();
    }

    @Test
    public void cacheHandlesConcurrentPutsToDistinctKeysWithoutLostUpdates() throws Exception {
        HealingCache cache = new HealingCache("build-1", null); // null file: exercise in-memory map only
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int t = 0; t < THREADS; t++) {
                int threadId = t;
                futures.add(pool.submit(() -> {
                    for (int i = 0; i < OPS_PER_THREAD; i++) {
                        cache.put("key-" + threadId + "-" + i, LocatorStrategy.XPATH, "//value" + i);
                    }
                }));
            }
            awaitAll(futures);

            assertEquals(cache.size(), THREADS * OPS_PER_THREAD, "Every distinct key must survive");
            for (int t = 0; t < THREADS; t++) {
                Optional<org.openqa.selenium.By> by = cache.get("key-" + t + "-0");
                assertTrue(by.isPresent());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void cacheConcurrentPutAndPersistNeverCorruptsTheFile() throws Exception {
        Path file = Files.createTempFile("healing-cache-concurrent", ".json");
        Files.deleteIfExists(file);
        try {
            HealingCache cache = new HealingCache("build-1", file);
            ExecutorService pool = Executors.newFixedThreadPool(THREADS);
            List<Future<?>> futures = new java.util.ArrayList<>();
            try {
                for (int t = 0; t < THREADS; t++) {
                    int threadId = t;
                    futures.add(pool.submit(() -> {
                        for (int i = 0; i < OPS_PER_THREAD; i++) {
                            cache.put("key-" + threadId + "-" + i, LocatorStrategy.XPATH, "//v" + i);
                            if (i % 20 == 0) {
                                cache.persist(); // concurrent writers racing the same file
                            }
                        }
                    }));
                }
                awaitAll(futures);
            } finally {
                pool.shutdownNow();
            }

            cache.persist(); // final, deterministic snapshot
            HealingCache reloaded = new HealingCache("build-1", file);
            assertEquals(reloaded.size(), THREADS * OPS_PER_THREAD,
                    "The persisted file must be valid JSON with every entry, not a torn/corrupted write");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    public void reporterRecordHasNoLostWritesUnderConcurrentHeals() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int t = 0; t < THREADS; t++) {
                int threadId = t;
                futures.add(pool.submit(() -> {
                    for (int i = 0; i < OPS_PER_THREAD; i++) {
                        LocatorDef def = new LocatorDef("key-" + threadId + "-" + i, "desc",
                                LocatorStrategy.ID, "old");
                        HealingReporter.record(def, Platform.ANDROID, "healed",
                                LocatorStrategy.XPATH, "//new", "deterministic", "deterministic");
                    }
                }));
            }
            awaitAll(futures);

            assertEquals(HealingReporter.healCount(), THREADS * OPS_PER_THREAD,
                    "No heal event may be lost under concurrent recording");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void reporterListenersAllFireExactlyOncePerHealUnderConcurrency() throws Exception {
        AtomicInteger listenerCalls = new AtomicInteger();
        HealingReporter.addListener(r -> listenerCalls.incrementAndGet());

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int t = 0; t < THREADS; t++) {
                int threadId = t;
                futures.add(pool.submit(() -> {
                    for (int i = 0; i < OPS_PER_THREAD; i++) {
                        LocatorDef def = new LocatorDef("key-" + threadId + "-" + i, "desc",
                                LocatorStrategy.ID, "old");
                        HealingReporter.record(def, Platform.ANDROID, "healed",
                                LocatorStrategy.XPATH, "//new", "deterministic", "deterministic");
                    }
                }));
            }
            awaitAll(futures);

            assertEquals(listenerCalls.get(), THREADS * OPS_PER_THREAD);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void repositoryBuiltBeforeThreadsStartIsSafelyReadableFromAllOfThem() throws Exception {
        // Mirrors the real-world pattern: build the repository once, then hand it to
        // one SelfHealingElementLocator per parallel thread via a final field.
        LocatorRepository repo = LocatorRepository.fromClasspath(
                "locators/locators_android.properties", "locators/locators_ios.properties");

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Callable<LocatorDef>> reads = new java.util.ArrayList<>();
            for (int i = 0; i < THREADS * OPS_PER_THREAD; i++) {
                reads.add(() -> repo.get("login.submitButton", Platform.ANDROID));
            }
            List<Future<LocatorDef>> futures = pool.invokeAll(reads, 30, TimeUnit.SECONDS);
            for (Future<LocatorDef> f : futures) {
                assertEquals(f.get().value(), "com.td.app:id/btn_login");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static void awaitAll(List<Future<?>> futures) throws Exception {
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
    }
}
