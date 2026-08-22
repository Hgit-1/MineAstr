package com.mineastr;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class MineAstrAgentIdentityTest {
    @Test
    void prefersAstrBotDisplayNameWhenItIsAValidMinecraftUsername() {
        assertEquals("Aria", MineAstrAgentIdentity.resolve(true, "Aria", "MFMC", "MineAstrBot"));
    }

    @Test
    void fallsBackWhenDisplayNamesCannotBeMinecraftUsernames() {
        assertEquals("ConfiguredBot", MineAstrAgentIdentity.resolve(
                true, "阿里亚 助手", "我的服务器", "ConfiguredBot"));
    }

    @Test
    void manualModeKeepsTheDedicatedUsername() {
        assertEquals("DedicatedBot", MineAstrAgentIdentity.resolve(
                false, "Aria", "MFMC", "DedicatedBot"));
    }
}
