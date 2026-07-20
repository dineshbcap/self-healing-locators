package com.dinesh.healing;

import org.openqa.selenium.Capabilities;
import org.openqa.selenium.HasCapabilities;
import org.openqa.selenium.WebDriver;

/**
 * Mobile platform under test. Detected from driver capabilities so the
 * calling code never has to pass it around explicitly.
 */
public enum Platform {
    ANDROID,
    IOS;

    public static Platform fromDriver(WebDriver driver) {
        if (driver instanceof HasCapabilities hc) {
            Capabilities caps = hc.getCapabilities();
            String platformName = String.valueOf(caps.getPlatformName());
            if (platformName.toLowerCase().contains("ios")) {
                return IOS;
            }
        }
        return ANDROID;
    }
}
