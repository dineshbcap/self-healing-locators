package com.dinesh.healing;

import org.testng.annotations.Test;

import java.util.Optional;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/**
 * Targets {@link LlmResponseParser#parseProposal} directly, in particular the
 * brace-depth JSON extraction - the shared logic every {@link HealingEngine}
 * provider relies on once it has unwrapped its own HTTP envelope.
 */
public class LlmResponseParserTest {

    @Test
    public void plainJsonIsParsed() {
        Optional<HealingEngine.Proposal> proposal = LlmResponseParser.parseProposal(
                "{\"strategy\":\"id\",\"value\":\"loginBtn\",\"confidence\":0.9}");

        assertTrue(proposal.isPresent());
        assertEquals(proposal.get().strategy(), LocatorStrategy.ID);
        assertEquals(proposal.get().value(), "loginBtn");
    }

    @Test
    public void trailingCommentaryContainingBracesIsIgnored() {
        // The valid proposal comes first; the trailing prose has its own,
        // unrelated brace pair. A naive first-'{'-to-last-'}' scan would
        // capture everything up to that trailing '}', corrupting the JSON.
        Optional<HealingEngine.Proposal> proposal = LlmResponseParser.parseProposal(
                "{\"strategy\":\"id\",\"value\":\"loginBtn\",\"confidence\":0.9} "
                        + "Note: this assumes the {login} button keeps its id.");

        assertTrue(proposal.isPresent());
        assertEquals(proposal.get().strategy(), LocatorStrategy.ID);
        assertEquals(proposal.get().value(), "loginBtn");
        assertEquals(proposal.get().confidence(), 0.9, 0.001);
    }

    @Test
    public void bracesInsideAStringValueDoNotUnbalanceTheScan() {
        // An xpath predicate value legitimately containing braces.
        Optional<HealingEngine.Proposal> proposal = LlmResponseParser.parseProposal(
                "{\"strategy\":\"xpath\",\"value\":\"//*[@data-id='{loginBtn}']\",\"confidence\":0.7}");

        assertTrue(proposal.isPresent());
        assertEquals(proposal.get().strategy(), LocatorStrategy.XPATH);
        assertEquals(proposal.get().value(), "//*[@data-id='{loginBtn}']");
    }

    @Test
    public void leadingProseBeforeJsonIsSkipped() {
        Optional<HealingEngine.Proposal> proposal = LlmResponseParser.parseProposal(
                "I'll use the id attribute here. "
                        + "{\"strategy\": \"id\", \"value\": \"loginBtn\", \"confidence\": 0.8}");

        assertTrue(proposal.isPresent());
        assertEquals(proposal.get().strategy(), LocatorStrategy.ID);
    }

    @Test
    public void noBraceAtAllYieldsEmpty() {
        assertTrue(LlmResponseParser.parseProposal("no json here").isEmpty());
    }
}
