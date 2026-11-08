package hermit.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hermit.api.AgentListener;
import hermit.memory.HashingEmbedder;
import hermit.memory.MemoryService;
import hermit.memory.SnippetMemory;
import hermit.memory.SnippetStore;
import org.junit.jupiter.api.Test;

/** The /remember approve-hook ingests the last program into the snippet memory. */
class AgentMemoryTest {

    private AgentLoop agentWithMemory(SnippetStore store) {
        AgentLoop agent = new AgentLoop(new AgentListener() {});   // no-op listener
        agent.setMemoryService(new MemoryService(
                new SnippetMemory(store, new HashingEmbedder()), 3, 0.3));
        return agent;
    }

    @Test
    void approveStoresTheLastProgram() {
        try (SnippetStore store = SnippetStore.inMemory()) {
            AgentLoop agent = agentWithMemory(store);
            assertTrue(agent.memoryEnabled());
            agent.noteProgramForMemory(
                    "write a python program that fetches the current bitcoin price in usd",
                    "def fetch():\n    \"\"\"fetch current bitcoin price in usd from coingecko\"\"\"\n    return get(URL)");
            assertTrue(agent.approveLastProgram(), "approve should store");
            assertTrue(store.count() >= 1, "memory should hold the ingested unit(s)");
        }
    }

    @Test
    void approveIsNoOpWithoutAProgram() {
        try (SnippetStore store = SnippetStore.inMemory()) {
            AgentLoop agent = agentWithMemory(store);
            assertFalse(agent.approveLastProgram(), "nothing to store yet");
        }
    }

    @Test
    void memoryDisabledByDefault() {
        AgentLoop agent = new AgentLoop(new AgentListener() {});
        assertFalse(agent.memoryEnabled(), "off unless -Dhermit.snippets.enabled");
        assertFalse(agent.approveLastProgram());
    }
}
