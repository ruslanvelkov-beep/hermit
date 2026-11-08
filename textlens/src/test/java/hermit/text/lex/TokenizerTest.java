package hermit.text.lex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hermit.text.model.TextRange;
import hermit.text.model.Token;
import hermit.text.model.Token.Shape;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TokenizerTest {

    private static final Tokenizer TOKENIZER = Tokenizer.standard();

    /** Each token of {@code text} as its surface and its shape, {@code surface:SHAPE}. */
    private static List<String> shapes(String text) {
        List<String> out = new ArrayList<>();
        for (Token t : TOKENIZER.tokenize(text)) {
            out.add(text.substring(t.range().start(), t.range().end()) + ":" + t.shape());
        }
        return out;
    }

    @Test
    void everyCharacterButWhitespaceLiesInExactlyOneToken() {
        String text = "Run ./gradlew test --offline -q; then read AgentLoop.java:1281 (see `RepeatGuard#signature`).";
        int at = 0;
        for (Token t : TOKENIZER.tokenize(text)) {
            assertTrue(t.range().start() >= at && t.range().end() > t.range().start(), t.toString());
            assertTrue(text.substring(at, t.range().start()).isBlank(), t.toString());
            at = t.range().end();
        }
        assertTrue(text.substring(at).isBlank());
    }

    @Test
    void aCliticIsATokenOfItsOwnAndANegatedFormIsNot() {
        assertEquals(List.of("We:WORD", "'ll:WORD", "ship:WORD", "it:WORD", ".:PUNCT"), shapes("We'll ship it."));
        assertEquals(List.of("It:WORD", "\u2019s:WORD", "the:WORD", "agent:WORD", "'s:WORD", "log:WORD", ",:PUNCT",
                "but:WORD", "we:WORD", "don't:WORD", "know:WORD", ".:PUNCT"),
                shapes("It\u2019s the agent's log, but we don't know."));
        assertEquals(List.of("She:WORD", "'d:WORD", "shipped:WORD", "and:WORD", "they:WORD", "'re:WORD", "done:WORD"),
                shapes("She'd shipped and they're done"));
        assertEquals(List.of("TreeEdit:IDENT", "'s:WORD", "ops:WORD"), shapes("TreeEdit's ops"));
    }

    @Test
    void tokensHaveIdsInTextOrderAndTheTokenizersProvenance() {
        List<Token> tokens = TOKENIZER.tokenize("Edit the loop.");
        assertEquals(List.of("t1", "t2", "t3", "t4"), tokens.stream().map(Token::id).toList());
        for (Token t : tokens) {
            assertEquals(Tokenizer.PROVENANCE, t.provenance());
            assertTrue(t.candidates().isEmpty() && !t.guessed(), t.toString());
        }
    }

    @Test
    void offsetsAreUtf16AndASurrogatePairStaysWhole() {
        List<Token> tokens = TOKENIZER.tokenize("Done 🚀 now");
        assertEquals(List.of(new TextRange(0, 4), new TextRange(5, 7), new TextRange(8, 11)),
                tokens.stream().map(Token::range).toList());
        assertEquals(Shape.SYMBOL, tokens.get(1).shape());
    }

    @Test
    void aCombiningMarkStaysWithTheTokenBeforeIt() {
        assertEquals(List.of("café:WORD", "menu:WORD"), shapes("café menu"));
        assertEquals(List.of("3⃣:NUMBER"), shapes("3⃣"));
    }

    @Test
    void pathKeepsItsDots() {
        assertEquals(List.of("Open:WORD", "src/main/java/hermit/text/CodeFence.java:PATH", ",:PUNCT", "./gradlew:PATH",
                "and:WORD", "notes/plan.md:PATH", ".:PUNCT"),
                shapes("Open src/main/java/hermit/text/CodeFence.java, ./gradlew and notes/plan.md."));
        assertEquals(List.of("../docs/plan.md:PATH", "~/notes:PATH"), shapes("../docs/plan.md ~/notes"));
    }

    @Test
    void aFileNameIsAPathAndOtherSlashesAreNot() {
        assertEquals(List.of("CodeFence.java:PATH", "and:WORD", "/:SYMBOL", "or:WORD", "10:NUMBER", "/:SYMBOL",
                "28:NUMBER"), shapes("CodeFence.java and/or 10/28"));
    }

    @Test
    void aUrlIsReadBeforeAPath() {
        assertEquals(List.of("See:WORD", "https://example.org/v1/models:URL", ".:PUNCT"),
                shapes("See https://example.org/v1/models."));
    }

    @Test
    void memberRefWithParamsIsOneToken() {
        assertEquals(List.of("ClaimsGate#claimsTextOf(String):MEMBER_REF", "calls:WORD",
                "RepeatGuard#signature:MEMBER_REF", "and:WORD", "TaskPlan.Verdict:MEMBER_REF"),
                shapes("ClaimsGate#claimsTextOf(String) calls RepeatGuard#signature and TaskPlan.Verdict"));
    }

    @Test
    void aClosingParenthesisTheTokenDidNotOpenStaysOutside() {
        assertEquals(List.of("(:PUNCT", "see:WORD", "hermit.text.model.Token:MEMBER_REF", "):PUNCT"),
                shapes("(see hermit.text.model.Token)"));
        assertEquals(List.of("(:PUNCT", "notes/plan.md:PATH", "):PUNCT"), shapes("(notes/plan.md)"));
    }

    @Test
    void aFileLineMayHoldAColumn() {
        assertEquals(List.of("Reader.kt:14:9:FILE_LINE", ".:PUNCT", "X.java:44-60:3:FILE_LINE"),
                shapes("Reader.kt:14:9. X.java:44-60:3"));
    }

    @Test
    void aFileLineMayHoldALineRange() {
        assertEquals(List.of("AgentLoop.java:1281:FILE_LINE", "and:WORD", "src/X.java:44-60:FILE_LINE", ",:PUNCT"),
                shapes("AgentLoop.java:1281 and src/X.java:44-60,"));
    }

    @Test
    void flagsKeepTheirValue() {
        assertEquals(List.of("Run:WORD", "with:WORD", "-Dhermit.context.tiered=true:FLAG", "--offline:FLAG",
                "-q:FLAG", ".:PUNCT"), shapes("Run with -Dhermit.context.tiered=true --offline -q."));
    }

    @Test
    void aHyphenBetweenLettersIsNoFlag() {
        assertEquals(List.of("a:WORD", "well-known:WORD", "re-run:WORD", "-:PUNCT", "not:WORD", "a:WORD", "flag:WORD"),
                shapes("a well-known re-run - not a flag"));
    }

    @Test
    void dottedNumbersAndNamedVersionsAreVersions() {
        assertEquals(List.of("Upgrade:WORD", "to:WORD", "2.21.1:VERSION", ",:PUNCT", "v3:VERSION", "and:WORD",
                "Qwen3.8-27B:VERSION"), shapes("Upgrade to 2.21.1, v3 and Qwen3.8-27B"));
    }

    @Test
    void aTwoPartNumberIsAVersionOnlyAfterACue() {
        assertEquals(List.of("Gradle:WORD", "8.10:VERSION", "runs:WORD", "0.95:NUMBER", "of:WORD", "the:WORD",
                "time:WORD", ";:PUNCT", "version:WORD", "1.4:VERSION", ".:PUNCT"),
                shapes("Gradle 8.10 runs 0.95 of the time; version 1.4."));
    }

    @Test
    void aHashNeedsADigitAndALetter() {
        assertEquals(List.of("62d9a9b:HASH", "deadbeef:WORD", "1234567:NUMBER"), shapes("62d9a9b deadbeef 1234567"));
    }

    @Test
    void numbersKeepGroupsExponentsAndSuffixes() {
        assertEquals(List.of("3,500:NUMBER", "rows:WORD", ",:PUNCT", "1e-9:NUMBER", "and:WORD", "78k:NUMBER",
                "calls:WORD", ";:PUNCT", "3:NUMBER", ",:PUNCT", "500:NUMBER"),
                shapes("3,500 rows, 1e-9 and 78k calls; 3, 500"));
    }

    @Test
    void aSignBelongsToANumberOnlyAtAWordStart() {
        assertEquals(List.of("from:WORD", "-1:NUMBER", "to:WORD", "+2:NUMBER", ",:PUNCT", "10:NUMBER", "-:PUNCT",
                "20:NUMBER"), shapes("from -1 to +2, 10-20"));
    }

    @Test
    void codeNamesAreIdentsAndOrdinalsAreWords() {
        assertEquals(List.of("processRequest:IDENT", "RepeatGuard:IDENT", "CHARS_PER_TOKEN:IDENT", "gpt-oss-20b:IDENT",
                "x86:IDENT", "3rd:WORD", "API:WORD"),
                shapes("processRequest RepeatGuard CHARS_PER_TOKEN gpt-oss-20b x86 3rd API"));
    }

    @Test
    void aCodeSpanKeepsItsBackticks() {
        assertEquals(List.of("Call:WORD", "`x = run(1)`:CODE_SPAN", "now:WORD"), shapes("Call `x = run(1)` now"));
        assertEquals(List.of("``a`b``:CODE_SPAN"), shapes("``a`b``"));
    }

    @Test
    void theInsideOfACodeSpanIsTokenizedAgain() {
        String text = "See `RepeatGuard#signature returns null` here";
        List<Token> tokens = TOKENIZER.tokenize(text);
        Token span = tokens.get(1);
        List<Token> inside = TOKENIZER.inside(text, span);
        assertEquals(List.of("t2.1", "t2.2", "t2.3"), inside.stream().map(Token::id).toList());
        assertEquals(List.of(Shape.MEMBER_REF, Shape.WORD, Shape.WORD), inside.stream().map(Token::shape).toList());
        assertEquals(new TextRange(5, 26), inside.get(0).range());
        assertEquals(new TextRange(35, 39), inside.get(2).range());
        assertTrue(TOKENIZER.inside(text, tokens.get(0)).isEmpty(), "a word has no inside");
    }

    @Test
    void anUnpairedBacktickAndAFenceArePunctuation() {
        assertEquals(List.of("```:PUNCT", "java:WORD"), shapes("```java"));
        assertEquals(List.of("a:WORD", "`:PUNCT", "b:WORD"), shapes("a ` b"));
    }

    @Test
    void anAbbreviationKeepsItsPeriods() {
        assertEquals(List.of("Use:WORD", "a:WORD", "lexicon:WORD", ",:PUNCT", "e.g.:WORD", "a:WORD", "list:WORD",
                ",:PUNCT", "etc.:WORD"), shapes("Use a lexicon, e.g. a list, etc."));
        assertEquals(List.of("Say:WORD", "no:WORD", ".:PUNCT", "No.:WORD", "5:NUMBER"), shapes("Say no. No. 5"));
    }

    @Test
    void operatorRunsAreOneSymbol() {
        assertEquals(List.of("a:WORD", "->:SYMBOL", "b:WORD", "!=:SYMBOL", "c:WORD", "&&:SYMBOL", "d:WORD", "%:SYMBOL",
                "e:WORD", "!:PUNCT"), shapes("a -> b != c && d % e!"));
    }

    @Test
    void dashesAndEllipsesArePunctuation() {
        assertEquals(List.of("Wait:WORD", "...:PUNCT", "then:WORD", "—:PUNCT", "done:WORD", "…:PUNCT",
                "--:PUNCT", "ok:WORD"), shapes("Wait... then — done … -- ok"));
    }

    @Test
    void aNonBreakingSpaceIsNoWhitespace() {
        // whitespace is what Character.isWhitespace says, as for the document's own check between tokens
        assertEquals(List.of("a:WORD", " :SYMBOL", "b:WORD"), shapes("a b"));
    }

    @Test
    void shapeOfReadsOneWholeToken() {
        assertEquals(Shape.PATH, TOKENIZER.shapeOf("rewrite/notes/X.md"));
        assertEquals(Shape.FILE_LINE, TOKENIZER.shapeOf("X.java:44-60"));
        assertEquals(Shape.HASH, TOKENIZER.shapeOf("d4016e8"));
        assertEquals(Shape.NUMBER, TOKENIZER.shapeOf("8.10"), "no version cue before it");
        assertNull(TOKENIZER.shapeOf("two words"));
        assertNull(TOKENIZER.shapeOf(""));
    }

    @Test
    void aBlankTextHasNoTokens() {
        assertTrue(TOKENIZER.tokenize("").isEmpty());
        assertTrue(TOKENIZER.tokenize(" \n\t ").isEmpty());
    }

    @Test
    void aUrlLeavesAnAngleBracketOrAnEscapedOneItDidNotOpen() {
        assertEquals(List.of("Feeds:WORD", "come:WORD", "from:WORD", "<:SYMBOL", "https://example.net/feed.xml:URL",
                ">:SYMBOL", ".:PUNCT"), shapes("Feeds come from <https://example.net/feed.xml>."));
        assertEquals(List.of("Read:WORD", "https://example.net/a:URL", "&:SYMBOL", "gt:WORD", ";:PUNCT", "now:WORD"),
                shapes("Read https://example.net/a&gt; now"));
    }

    @Test
    void anyUrlSchemeIsAUrl() {
        assertEquals(List.of("Connect:WORD", "to:WORD", "ws://example.net:9000/live:URL", "or:WORD",
                "ssh://git@example.net/repo:URL", ".:PUNCT"),
                shapes("Connect to ws://example.net:9000/live or ssh://git@example.net/repo."));
    }

    @Test
    void aLocalHostOrAnIpv4AddressIsAUrl() {
        assertEquals(List.of("Serve:WORD", "on:WORD", "localhost:8080:URL", "and:WORD", "203.0.113.7:9000/health:URL",
                ",:PUNCT", "or:WORD", "127.0.0.1:URL", ".:PUNCT"),
                shapes("Serve on localhost:8080 and 203.0.113.7:9000/health, or 127.0.0.1."));
        assertEquals(List.of("Release:WORD", "1.2.3.4:VERSION", "and:WORD", "300.1.1.1:VERSION", ";:PUNCT",
                "localhostname:WORD"), shapes("Release 1.2.3.4 and 300.1.1.1; localhostname"));
    }

    @Test
    void aCapitalisedNameWithJsIsAWordAndAFileNameIsAPath() {
        assertEquals(List.of("The:WORD", "Node.js:WORD", "client:WORD", "and:WORD", "app.js:PATH", "differ:WORD",
                "from:WORD", "Vue.js:WORD", ".:PUNCT"), shapes("The Node.js client and app.js differ from Vue.js."));
    }

    @Test
    void aMissingSpaceAfterASentenceEndIsNoMemberRef() {
        assertEquals(List.of("The:WORD", "build:WORD", "passed:WORD", ".:PUNCT", "Then:WORD", "it:WORD", "stopped:WORD",
                ";:PUNCT", "see:WORD", "config.Settings:MEMBER_REF", ".:PUNCT"),
                shapes("The build passed.Then it stopped; see config.Settings."));
    }

    @Test
    void aBareHostNameIsAUrlAndAPackageNameIsNot() {
        assertEquals(List.of("See:WORD", "example.org:URL", ",:PUNCT", "api.example.com:8443/v1:URL", "and:WORD",
                "build-1.example.dev:URL", ".:PUNCT"),
                shapes("See example.org, api.example.com:8443/v1 and build-1.example.dev."));
        assertEquals(List.of("Packages:WORD", "org:WORD", ".:PUNCT", "example:WORD", "and:WORD", "hermit:WORD",
                ".:PUNCT", "text:WORD", "stay:WORD", "words:WORD", ".:PUNCT"),
                shapes("Packages org.example and hermit.text stay words."));
    }
}
