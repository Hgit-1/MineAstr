package com.mineastr;

import java.util.List;

/** Resolves the offline Mineflayer identity without weakening Minecraft name rules. */
final class MineAstrAgentIdentity {
    private static final String FALLBACK = "MineAstrBot";

    private MineAstrAgentIdentity() {}

    static String resolve(
            boolean useBotDisplayName,
            String astrBotDisplayName,
            String serverName,
            String configuredUsername) {
        if (useBotDisplayName) {
            for (String candidate : List.of(
                    safe(astrBotDisplayName), safe(serverName), safe(configuredUsername), FALLBACK)) {
                if (isValid(candidate)) return candidate;
            }
        }
        String configured = safe(configuredUsername);
        return isValid(configured) ? configured : FALLBACK;
    }

    static boolean isValid(String value) {
        return value != null && value.matches("[A-Za-z0-9_]{3,16}");
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
