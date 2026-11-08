package hermit.tool.codehost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Pure, network-free tests for CodeHostSkill's deterministic reference resolution,
 * intent inference, and error classification - the parts that used to be left to
 * the LLM and produced the last-mile failures this skill removes. */
class CodeHostSkillTest {

    @Test
    void parsesFullPrUrl_andStripsTrailingChangesSegment() {
        // The exact URL from the session that broke the 2B/3B brains.
        CodeHostSkill.Ref r = CodeHostSkill.parseRef(
                "https://github.com/example-org/orders-services/pull/500/changes", null, null);
        assertNotNull(r);
        assertEquals("example-org", r.owner());
        assertEquals("orders-services", r.repo());
        assertEquals("pull", r.kind());
        assertEquals(500, r.number());
        // canonical URL drops the /changes tail - what gh actually wants
        assertEquals("https://github.com/example-org/orders-services/pull/500", r.canonicalUrl());
    }

    @Test
    void parsesIssueUrl_andShorthand_andRepoPlusNumber() {
        assertEquals("issues", CodeHostSkill.parseRef("https://github.com/o/r/issues/7", null, null).kind());

        CodeHostSkill.Ref sh = CodeHostSkill.parseRef("octo/repo#42", null, null);
        assertEquals("octo", sh.owner());
        assertEquals(42, sh.number());

        CodeHostSkill.Ref byNum = CodeHostSkill.parseRef("500", "example-org/orders-services", null);
        assertNotNull(byNum);
        assertEquals(500, byNum.number());
        assertEquals("example-org", byNum.owner());
    }

    @Test
    void returnsNullWhenNoReferencePresent() {
        assertNull(CodeHostSkill.parseRef("just some words", null, null));
        assertNull(CodeHostSkill.parseRef("500", null, null)); // bare number, no repo hint
    }

    @Test
    void extractsFirstReferenceFromConversationContext() {
        String ctx = "User: Can you use curl?\nUser: take a look at "
                + "https://github.com/example-org/orders-services/pull/500/changes\n"
                + "User: Use the gh command with the provided link.";
        CodeHostSkill.Ref r = CodeHostSkill.extractFromContext(ctx);
        assertNotNull(r);
        assertEquals(500, r.number());
        assertEquals("orders-services", r.repo());
    }

    @Test
    void inferIntent_defaultsFromUrlKind_andHonoursExplicit() {
        CodeHostSkill.Ref pr = CodeHostSkill.parseRef("https://github.com/o/r/pull/1", null, null);
        CodeHostSkill.Ref iss = CodeHostSkill.parseRef("https://github.com/o/r/issues/1", null, null);
        assertEquals("viewPR", CodeHostSkill.inferIntent(null, pr));
        assertEquals("viewIssue", CodeHostSkill.inferIntent(null, iss));
        assertEquals("diffPR", CodeHostSkill.inferIntent("diffPR", pr));
        assertEquals("diffPR", CodeHostSkill.inferIntent("DiffPR", pr)); // case-normalised
    }

    @Test
    void classifiesAuthAndNotFoundStderr() {
        assertTrue(CodeHostSkill.looksLikeAuthError("gh: To get started with GitHub CLI, please run: gh auth login"));
        assertTrue(CodeHostSkill.looksLikeAuthError("HTTP 401: Bad credentials"));
        assertFalse(CodeHostSkill.looksLikeAuthError("some unrelated error"));

        assertTrue(CodeHostSkill.looksLikeNotFound("GraphQL: Could not resolve to a PullRequest"));
        assertTrue(CodeHostSkill.looksLikeNotFound("HTTP 404: Not Found"));
        assertFalse(CodeHostSkill.looksLikeNotFound("gh auth login required"));
    }

    @Test
    void groundedGuard_rejectsHallucinatedRefNotInSource() {
        String src = "look at https://github.com/example-org/orders-services/pull/500";
        CodeHostSkill.Ref real = CodeHostSkill.parseRef(
                "https://github.com/example-org/orders-services/pull/500", null, null);
        CodeHostSkill.Ref fake = CodeHostSkill.parseRef("https://github.com/evil/other/pull/999", null, null);
        assertTrue(CodeHostSkill.grounded(real, src));
        assertFalse(CodeHostSkill.grounded(fake, src)); // repo+number absent from source -> rejected
    }
}
