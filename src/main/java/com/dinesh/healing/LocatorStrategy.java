package com.dinesh.healing;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import io.appium.java_client.AppiumBy;
import org.openqa.selenium.By;

/**
 * Supported locator strategies, serialised in locators_&lt;platform&gt;.properties
 * as lowerCamelCase strings (e.g. "accessibilityId"). Maps onto Appium 2/3-era
 * {@link AppiumBy} (MobileBy is gone in java-client 9.x).
 */
public enum LocatorStrategy {
    ID("id"),
    ACCESSIBILITY_ID("accessibilityId"),
    XPATH("xpath"),
    CLASS_NAME("className"),
    ANDROID_UIAUTOMATOR("androidUIAutomator"),
    IOS_PREDICATE("iOSNsPredicate"),
    IOS_CLASS_CHAIN("iOSClassChain");

    private final String json;

    LocatorStrategy(String json) {
        this.json = json;
    }

    @JsonValue
    public String json() {
        return json;
    }

    @JsonCreator
    public static LocatorStrategy fromJson(String value) {
        for (LocatorStrategy s : values()) {
            if (s.json.equalsIgnoreCase(value)) {
                return s;
            }
        }
        throw new IllegalArgumentException("Unknown locator strategy: " + value);
    }

    public By toBy(String value) {
        return switch (this) {
            case ID -> AppiumBy.id(value);
            case ACCESSIBILITY_ID -> AppiumBy.accessibilityId(value);
            case XPATH -> AppiumBy.xpath(value);
            case CLASS_NAME -> AppiumBy.className(value);
            case ANDROID_UIAUTOMATOR -> AppiumBy.androidUIAutomator(value);
            case IOS_PREDICATE -> AppiumBy.iOSNsPredicateString(value);
            case IOS_CLASS_CHAIN -> AppiumBy.iOSClassChain(value);
        };
    }
}
