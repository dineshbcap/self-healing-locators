package com.dinesh.healing;

import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.NoSuchElementException;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

/**
 * {@link LocatorRepository} is the entry point every heal ultimately goes through
 * (repository.get(key, platform) resolves the primary locator before healing ever
 * engages), so its parsing and error paths deserve direct coverage - previously
 * exercised only incidentally through other tests.
 */
public class LocatorRepositoryTest {

    @Test
    public void fromClasspathResolvesTheProjectsOwnDemoLocatorFiles() {
        LocatorRepository repo = LocatorRepository.fromClasspath(
                "locators/locators_android.properties", "locators/locators_ios.properties");

        LocatorDef android = repo.get("login.submitButton", Platform.ANDROID);
        assertEquals(android.strategy(), LocatorStrategy.ID);
        assertEquals(android.value(), "com.td.app:id/btn_login");

        LocatorDef ios = repo.get("login.submitButton", Platform.IOS);
        assertEquals(ios.strategy(), LocatorStrategy.ACCESSIBILITY_ID);
        assertEquals(ios.value(), "loginButton");

        assertTrue(repo.keysMissingOnAnyPlatform().isEmpty(),
                "This project's own demo files are kept in sync across platforms");
        repo.assertKeysSyncedAcrossPlatforms(); // must not throw
    }

    @Test
    public void missingClasspathResourceThrowsAClearError() {
        try {
            LocatorRepository.fromClasspath("locators/does-not-exist.properties",
                    "locators/locators_ios.properties");
            fail("Expected IllegalArgumentException for a missing classpath resource");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("does-not-exist.properties"));
        }
    }

    @Test
    public void fromFilesLoadsFromTheFilesystem() throws IOException {
        Path android = writeTempProperties("login.submitButton.description=Sign in\n"
                + "login.submitButton=id=btn_login\n");
        Path ios = writeTempProperties("login.submitButton.description=Sign in\n"
                + "login.submitButton=accessibilityId=loginButton\n");
        try {
            LocatorRepository repo = LocatorRepository.fromFiles(android, ios);
            assertEquals(repo.get("login.submitButton", Platform.ANDROID).value(), "btn_login");
            assertEquals(repo.get("login.submitButton", Platform.IOS).value(), "loginButton");
        } finally {
            Files.deleteIfExists(android);
            Files.deleteIfExists(ios);
        }
    }

    @Test
    public void valueContainingAnEqualsSignSurvivesIntact() throws IOException {
        // Only the FIRST '=' is the strategy/value separator - an iOS predicate like
        // "name == 'x'" legitimately contains '=' characters of its own.
        Path android = writeTempProperties("k=id=btn\n");
        Path ios = writeTempProperties("k=iOSNsPredicate=name == 'accountsList'\n");
        try {
            LocatorRepository repo = LocatorRepository.fromFiles(android, ios);
            assertEquals(repo.get("k", Platform.IOS).value(), "name == 'accountsList'");
        } finally {
            Files.deleteIfExists(android);
            Files.deleteIfExists(ios);
        }
    }

    @Test
    public void malformedLineWithNoEqualsInTheValuePartThrows() throws IOException {
        Path android = writeTempProperties("k=idOnlyNoSeparator\n");
        Path ios = writeTempProperties("k=id=btn\n");
        try {
            LocatorRepository.fromFiles(android, ios);
            fail("Expected IllegalStateException for a line missing the strategy/value separator");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("'<strategy>=<value>'"));
        } finally {
            Files.deleteIfExists(android);
            Files.deleteIfExists(ios);
        }
    }

    @Test
    public void unknownStrategyNameThrows() throws IOException {
        Path android = writeTempProperties("k=bogusStrategy=value\n");
        Path ios = writeTempProperties("k=id=btn\n");
        try {
            assertThrows(IllegalArgumentException.class, () -> LocatorRepository.fromFiles(android, ios));
        } finally {
            Files.deleteIfExists(android);
            Files.deleteIfExists(ios);
        }
    }

    @Test
    public void unknownKeyThrowsNoSuchElement() {
        LocatorRepository repo = LocatorRepository.fromClasspath(
                "locators/locators_android.properties", "locators/locators_ios.properties");
        assertThrows(NoSuchElementException.class, () -> repo.get("no.such.key", Platform.ANDROID));
    }

    @Test
    public void keyMissingOnOnePlatformIsReportedAndThrowsOnLookup() throws IOException {
        Path android = writeTempProperties("android.only=id=btn\nshared=id=btn\n");
        Path ios = writeTempProperties("shared=accessibilityId=btn\n");
        try {
            LocatorRepository repo = LocatorRepository.fromFiles(android, ios);

            List<String> missing = repo.keysMissingOnAnyPlatform();
            assertEquals(missing.size(), 1);
            assertTrue(missing.get(0).contains("android.only"));
            assertTrue(missing.get(0).contains("IOS"));

            assertThrows(NoSuchElementException.class, () -> repo.get("android.only", Platform.IOS));
            assertThrows(IllegalStateException.class, repo::assertKeysSyncedAcrossPlatforms);
        } finally {
            Files.deleteIfExists(android);
            Files.deleteIfExists(ios);
        }
    }

    @Test
    public void sizeCountsDistinctKeysNotPlatformEntries() throws IOException {
        Path android = writeTempProperties("a=id=x\nb=id=y\n");
        Path ios = writeTempProperties("a=accessibilityId=x\nb=accessibilityId=y\n");
        try {
            LocatorRepository repo = LocatorRepository.fromFiles(android, ios);
            assertEquals(repo.size(), 2, "Two keys, each with two platform defs, is still size() == 2");
        } finally {
            Files.deleteIfExists(android);
            Files.deleteIfExists(ios);
        }
    }

    private static Path writeTempProperties(String content) throws IOException {
        Path path = Files.createTempFile("locators", ".properties");
        Files.writeString(path, content);
        return path;
    }
}
