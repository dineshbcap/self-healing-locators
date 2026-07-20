package com.dinesh.healing;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/**
 * Loads locator definitions from a pair of per-platform .properties files
 * (classpath or filesystem) - one for Android, one for iOS.
 *
 * Properties shape (e.g. locators_android.properties):
 * <pre>
 * login.submitButton.description=Primary Sign In button on the login screen
 * login.submitButton=id=com.td.app:id/btn_login
 * </pre>
 *
 * Keys are dotted "screen.element" identifiers, mapped to "&lt;strategy&gt;=&lt;value&gt;"
 * (only the first '=' is treated as the separator, so values may contain '=').
 * Each platform file is self-contained (its own description/strategy/value
 * per key); keep the android and ios files in sync so every key resolves on
 * both platforms.
 */
public final class LocatorRepository {

    private static final String DESCRIPTION_SUFFIX = ".description";

    /** key -> (platform -> def) */
    private final Map<String, Map<Platform, LocatorDef>> locators = new HashMap<>();
    private final String sourceDescription;

    private LocatorRepository(Map<Platform, Properties> byPlatform, String sourceDescription) {
        this.sourceDescription = sourceDescription;
        byPlatform.forEach(this::parse);
    }

    public static LocatorRepository fromClasspath(String androidResourcePath, String iosResourcePath) {
        Map<Platform, Properties> byPlatform = new HashMap<>();
        byPlatform.put(Platform.ANDROID, loadClasspathProperties(androidResourcePath));
        byPlatform.put(Platform.IOS, loadClasspathProperties(iosResourcePath));
        return new LocatorRepository(byPlatform,
                "classpath:" + androidResourcePath + ", classpath:" + iosResourcePath);
    }

    public static LocatorRepository fromFiles(Path androidPath, Path iosPath) {
        Map<Platform, Properties> byPlatform = new HashMap<>();
        byPlatform.put(Platform.ANDROID, loadFileProperties(androidPath));
        byPlatform.put(Platform.IOS, loadFileProperties(iosPath));
        return new LocatorRepository(byPlatform, androidPath + ", " + iosPath);
    }

    private static Properties loadClasspathProperties(String resourcePath) {
        Properties props = new Properties();
        try (InputStream in = LocatorRepository.class.getClassLoader()
                .getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalArgumentException(
                        "Locator repository not found on classpath: " + resourcePath);
            }
            props.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read locator repository: " + resourcePath, e);
        }
        return props;
    }

    private static Properties loadFileProperties(Path path) {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            props.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read locator repository: " + path, e);
        }
        return props;
    }

    private void parse(Platform platform, Properties props) {
        Set<String> keys = new TreeSet<>();
        for (String propName : props.stringPropertyNames()) {
            if (!propName.endsWith(DESCRIPTION_SUFFIX)) {
                keys.add(propName);
            }
        }
        for (String key : keys) {
            String raw = props.getProperty(key);
            int sep = raw.indexOf('=');
            if (sep < 0) {
                throw new IllegalStateException(
                        "Locator '" + key + "' for platform " + platform
                                + " must be in '<strategy>=<value>' format in " + sourceDescription);
            }
            String strategyValue = raw.substring(0, sep);
            String value = raw.substring(sep + 1);
            String description = props.getProperty(key + DESCRIPTION_SUFFIX, "");
            LocatorStrategy strategy = LocatorStrategy.fromJson(strategyValue);
            LocatorDef def = new LocatorDef(key, description, strategy, value);
            locators.computeIfAbsent(key, k -> new HashMap<>()).put(platform, def);
        }
    }

    public LocatorDef get(String key, Platform platform) {
        Map<Platform, LocatorDef> perPlatform = locators.get(key);
        if (perPlatform == null) {
            throw new NoSuchElementException(
                    "No locator with key '" + key + "' in " + sourceDescription);
        }
        LocatorDef def = perPlatform.get(platform);
        if (def == null) {
            throw new NoSuchElementException(
                    "Locator '" + key + "' has no definition for platform " + platform);
        }
        return def;
    }

    public int size() {
        return locators.size();
    }
}
