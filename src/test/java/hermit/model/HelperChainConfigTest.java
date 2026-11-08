package hermit.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The helper chain's lan entry follows the RemoteAgent configuration unless told otherwise. */
class HelperChainConfigTest {

    @AfterEach
    void clear() {
        for (String p : new String[] {"hermit.remote.baseUrl", "hermit.remote.model", "hermit.chain.lan.baseUrl",
                "hermit.chain.lan.model"}) {
            System.clearProperty(p);
        }
    }

    @Test
    void theChainsLanFollowsTheRemoteAgentConfigurationUnlessOverridden() {
        System.setProperty("hermit.remote.baseUrl", "http://127.0.0.1:1235/v1");
        System.setProperty("hermit.remote.model", "deepseek-coder-v2-lite-instruct-awq");
        assertEquals("http://127.0.0.1:1235/v1", HelperChain.lanBaseUrl());
        assertEquals("deepseek-coder-v2-lite-instruct-awq", HelperChain.lanModel());
        System.setProperty("hermit.chain.lan.baseUrl", "http://10.0.0.5:1234/v1");
        System.setProperty("hermit.chain.lan.model", "other-model");
        assertEquals("http://10.0.0.5:1234/v1", HelperChain.lanBaseUrl());
        assertEquals("other-model", HelperChain.lanModel());
    }
}
