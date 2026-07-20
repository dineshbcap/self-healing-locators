package com.dinesh.healing;

import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class PageSourcePrunerAndRedactorTest {

    private static final String SAMPLE_ANDROID_SOURCE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <hierarchy rotation="0">
              <android.widget.FrameLayout index="0" package="com.td.app" class="android.widget.FrameLayout"
                  bounds="[0,0][1080,2400]" displayed="true" scrollable="false" long-clickable="false">
                <android.widget.LinearLayout index="0" class="android.widget.LinearLayout"
                    bounds="[0,0][1080,2400]" displayed="true">
                  <android.widget.Button index="1" class="android.widget.Button"
                      resource-id="com.td.app:id/button_login" text="Sign In"
                      clickable="true" enabled="true" bounds="[100,900][980,1050]" displayed="true"/>
                  <android.widget.TextView index="2" class="android.widget.TextView"
                      resource-id="com.td.app:id/balance" text="$12,345.67"
                      bounds="[100,300][980,380]" displayed="true"/>
                  <android.widget.TextView index="3" class="android.widget.TextView"
                      resource-id="com.td.app:id/hidden_promo" text="Promo"
                      bounds="[0,0][0,0]" displayed="false"/>
                </android.widget.LinearLayout>
              </android.widget.FrameLayout>
            </hierarchy>
            """;

    @Test
    public void prunerStripsNoiseAttributesAndInvisibleNodes() {
        String pruned = PageSourcePruner.prune(SAMPLE_ANDROID_SOURCE);

        assertTrue(pruned.contains("button_login"), "Must keep resource-id");
        assertTrue(pruned.contains("Sign In"), "Must keep text");
        assertFalse(pruned.contains("bounds"), "Must strip bounds");
        assertFalse(pruned.contains("long-clickable"), "Must strip noise attrs");
        assertFalse(pruned.contains("hidden_promo"), "Must drop invisible nodes");
        assertTrue(pruned.length() < SAMPLE_ANDROID_SOURCE.length(),
                "Pruned output must be smaller");
    }

    @Test
    public void prunerDegradesGracefullyOnBrokenXml() {
        String broken = "<hierarchy><unclosed";
        String result = PageSourcePruner.prune(broken);
        assertEquals(result, broken, "Broken XML falls back to raw (truncated) text");
    }

    @Test
    public void prunerHandlesNullAndBlank() {
        assertEquals(PageSourcePruner.prune(null), "");
        assertEquals(PageSourcePruner.prune("  "), "");
    }

    @Test
    public void redactorMasksBankingPatterns() {
        String input = """
                text="Balance $12,345.67" desc="Card 4520 1234 5678 9012"
                label="Contact dinesh@example.com or (416) 555-0123"
                value="SIN 123-456-789" resource-id="com.td.app:id/balance"
                """;
        String out = PiiRedactor.redact(input);

        assertFalse(out.contains("12,345.67"), "Currency must be masked");
        assertFalse(out.contains("4520"), "Card number must be masked");
        assertFalse(out.contains("dinesh@example.com"), "Email must be masked");
        assertFalse(out.contains("555-0123"), "Phone must be masked");
        assertFalse(out.contains("123-456-789"), "SIN pattern must be masked");
        assertTrue(out.contains("com.td.app:id/balance"),
                "Resource ids must survive redaction");
        assertTrue(out.contains("[REDACTED-AMT]"));
        assertTrue(out.contains("[REDACTED-NUM]"));
    }

    @Test
    public void redactionRunsAfterPruningEndToEnd() {
        String pruned = PageSourcePruner.prune(SAMPLE_ANDROID_SOURCE);
        String redacted = PiiRedactor.redact(pruned);
        assertFalse(redacted.contains("12,345.67"),
                "Balance must not survive prune+redact pipeline");
        assertTrue(redacted.contains("button_login"),
                "Locatable ids must survive the pipeline");
    }
}
