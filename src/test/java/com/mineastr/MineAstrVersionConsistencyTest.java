package com.mineastr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;

final class MineAstrVersionConsistencyTest {
    @Test
    void modAndEmbeddedAgentVersionsStayInSync() throws Exception {
        Properties gradle = new Properties();
        try (var reader = Files.newBufferedReader(Path.of("gradle.properties"))) {
            gradle.load(reader);
        }
        String version = gradle.getProperty("mod_version");
        assertEquals(version, MineAstr.MOD_VERSION);

        var packageJson = JsonParser.parseString(Files.readString(Path.of("src/agent/package.json")))
                .getAsJsonObject();
        assertEquals(version, packageJson.get("version").getAsString());
        assertTrue(Files.readString(Path.of("src/agent/index.js"))
                .contains("runtime_version: '" + version + "'"));
    }
}
