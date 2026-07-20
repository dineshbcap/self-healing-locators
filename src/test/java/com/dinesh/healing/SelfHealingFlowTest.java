package com.dinesh.healing;

import io.appium.java_client.AppiumBy;
import org.mockito.Mockito;
import org.openqa.selenium.By;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

/**
 * Demonstrates the full Phase 1 healing flow WITHOUT a device: a mocked
 * WebDriver simulates a dev renaming "btn_login" so the primary id lookup
 * fails, while an element remains findable via the 'login' fragment.
 *
 * Run: mvn test
 */
public class SelfHealingFlowTest {

    private WebDriver driver;
    private LocatorRepository repo;
    private HealingConfig config;
    private Path cacheFile;

    @BeforeMethod
    public void setUp() throws Exception {
        HealingReporter.reset();
        System.clearProperty("healing.failOnHeal");
        driver = mock(WebDriver.class);
        repo = LocatorRepository.fromClasspath(
                "locators/locators_android.properties", "locators/locators_ios.properties");
        config = new HealingConfig();
        cacheFile = Files.createTempFile("healing-cache", ".json");
        Files.delete(cacheFile);
    }

    @AfterMethod
    public void tearDown() throws Exception {
        Files.deleteIfExists(cacheFile);
    }

    private SelfHealingElementLocator locator() {
        return new SelfHealingElementLocator(
                driver, repo, new HealingCache("build-1.2.3", cacheFile), config);
    }

    @Test
    public void fastPathReturnsElementWithoutHealing() {
        WebElement button = mock(WebElement.class);
        when(driver.findElement(AppiumBy.id("com.td.app:id/btn_login"))).thenReturn(button);

        WebElement found = locator().find("login.submitButton");

        assertEquals(found, button);
        assertEquals(HealingReporter.healCount(), 0, "Fast path must not record heals");
    }

    @Test
    public void renamedResourceIdIsHealedViaFragment() {
        // Primary id fails - dev renamed btn_login to button_login.
        when(driver.findElement(any(By.class)))
                .thenThrow(new NoSuchElementException("no btn_login"));
        // Nothing matches by default...
        when(driver.findElements(any(By.class))).thenReturn(List.of());
        // ...but exactly one element still contains the distinctive 'login' fragment.
        WebElement renamed = mock(WebElement.class);
        when(driver.findElements(
                AppiumBy.xpath("//*[contains(@resource-id,'login')]")))
                .thenReturn(List.of(renamed));

        WebElement healed = locator().find("login.submitButton");

        assertEquals(healed, renamed);
        assertEquals(HealingReporter.healCount(), 1);
        HealingReporter.HealingRecord record = HealingReporter.records().get(0);
        assertEquals(record.locatorKey(), "login.submitButton");
        assertEquals(record.source(), "deterministic");
        assertTrue(record.healedLocator().contains("login"));
    }

    @Test
    public void ambiguousCandidatesAreRejected() {
        when(driver.findElement(any(By.class)))
                .thenThrow(new NoSuchElementException("gone"));
        // Two elements match the fragment -> healing must refuse to guess.
        WebElement a = mock(WebElement.class);
        WebElement b = mock(WebElement.class);
        when(driver.findElements(any(By.class))).thenReturn(List.of(a, b));

        assertThrows(NoSuchElementException.class,
                () -> locator().find("login.submitButton"));
        assertEquals(HealingReporter.healCount(), 0);
    }

    @Test
    public void healedLocatorIsCachedForNextLookup() {
        HealingCache cache = new HealingCache("build-1.2.3", cacheFile);
        SelfHealingElementLocator loc = new SelfHealingElementLocator(driver, repo, cache, config);

        when(driver.findElement(any(By.class)))
                .thenThrow(new NoSuchElementException("gone"));
        when(driver.findElements(any(By.class))).thenReturn(List.of());
        WebElement renamed = mock(WebElement.class);
        when(driver.findElements(
                AppiumBy.xpath("//*[contains(@resource-id,'login')]")))
                .thenReturn(List.of(renamed));

        loc.find("login.submitButton");
        assertEquals(cache.size(), 1, "Heal must be cached");

        // Second lookup: cache is consulted before fallback strategies.
        WebElement second = loc.find("login.submitButton");
        assertEquals(second, renamed);

        // Persist + reload survives for the same build id...
        cache.persist();
        HealingCache reloaded = new HealingCache("build-1.2.3", cacheFile);
        assertEquals(reloaded.size(), 1);

        // ...and is dropped for a new build.
        HealingCache newBuild = new HealingCache("build-1.3.0", cacheFile);
        assertEquals(newBuild.size(), 0, "Cache must not outlive its app build");
    }

    @Test
    public void failOnHealEscalatesInsteadOfReturning() {
        System.setProperty("healing.failOnHeal", "true");
        try {
            when(driver.findElement(any(By.class)))
                    .thenThrow(new NoSuchElementException("gone"));
            when(driver.findElements(any(By.class))).thenReturn(List.of());
            when(driver.findElements(
                    AppiumBy.xpath("//*[contains(@resource-id,'login')]")))
                    .thenReturn(List.of(mock(WebElement.class)));

            assertThrows(SelfHealingElementLocator.HealedLocatorException.class,
                    () -> locator().find("login.submitButton"));
        } finally {
            System.clearProperty("healing.failOnHeal");
        }
    }

    @Test
    public void healingDisabledSurfacesOriginalError() {
        System.setProperty("healing.enabled", "false");
        try {
            when(driver.findElement(any(By.class)))
                    .thenThrow(new NoSuchElementException("original error"));

            assertThrows(NoSuchElementException.class,
                    () -> locator().find("login.submitButton"));
            Mockito.verify(driver, Mockito.never()).findElements(any(By.class));
        } finally {
            System.clearProperty("healing.enabled");
        }
    }

    @Test
    public void reportFileIsWritten() throws Exception {
        when(driver.findElement(any(By.class)))
                .thenThrow(new NoSuchElementException("gone"));
        when(driver.findElements(any(By.class))).thenReturn(List.of());
        when(driver.findElements(
                AppiumBy.xpath("//*[contains(@resource-id,'login')]")))
                .thenReturn(List.of(mock(WebElement.class)));

        locator().find("login.submitButton");

        Path report = Files.createTempFile("healing-report", ".json");
        HealingReporter.writeReport(report);
        String json = Files.readString(report);
        assertTrue(json.contains("login.submitButton"));
        assertNotNull(json);
        Files.deleteIfExists(report);
    }
}
