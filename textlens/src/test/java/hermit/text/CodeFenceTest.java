package hermit.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CodeFenceTest {

    @Test
    void extractsFencedBlockLanguageAndCodeAndRemovesItFromText() {
        String raw = "{\"branches\":[...]}\n```python\nprint('value: ' + str(p))\nx = {\"k\": 1}\n```";
        CodeFence.Extracted e = CodeFence.first(raw);
        assertEquals("python", e.language());
        assertTrue(e.code().contains("print('value: '"), e.code());
        assertTrue(e.code().contains("x = {\"k\": 1}"), e.code());   // quotes/braces need no escaping in a fence
        // the block is removed, leaving just the JSON for the parser
        assertFalse(e.textWithoutBlock().contains("print("), e.textWithoutBlock());
        assertTrue(e.textWithoutBlock().contains("branches"), e.textWithoutBlock());
    }

    @Test
    void noBlockReturnsTextUnchanged() {
        CodeFence.Extracted e = CodeFence.first("just a plan, no code");
        assertNull(e.language());
        assertNull(e.code());
        assertEquals("just a plan, no code", e.textWithoutBlock());
    }

    @Test
    void unterminatedFenceIsNotTreatedAsABlock() {
        CodeFence.Extracted e = CodeFence.first("text ```python\nprint(1)\n(no closing fence)");
        assertNull(e.code());
    }

    @Test
    void jsonFencedPlanIsNotMistakenForAProgram() {
        // A ```json (or plain ```) block is the PLAN, not code - must be left in place.
        CodeFence.Extracted e = CodeFence.first("```json\n{\"final_answer\":\"hi\"}\n```");
        assertNull(e.code(), "a ```json plan block must not be extracted as a program");
    }

    @Test
    void skipsJsonBlockAndFindsTheProgramBlock() {
        String raw = "```json\n{\"branches\":[]}\n```\nand the program:\n```python\nprint(42)\n```";
        CodeFence.Extracted e = CodeFence.first(raw);
        assertEquals("python", e.language());
        assertTrue(e.code().contains("print(42)"), e.code());
    }
}
