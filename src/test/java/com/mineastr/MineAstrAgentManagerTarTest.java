package com.mineastr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.junit.jupiter.api.Test;

final class MineAstrAgentManagerTarTest {
    @Test
    void extractsUstarPrefixWithoutCreatingTruncatedDirectory() throws Exception {
        String prefix = "node-v22.19.0-linux-x64/include/node/openssl/archs/darwin64-x86_64-cc/asm";
        String name = "providers/common/include/prov/der_rsa.h";
        byte[] content = "node-header".getBytes(StandardCharsets.US_ASCII);
        byte[] archive = tarEntry(prefix, name, content);
        Path target = Files.createTempDirectory("mineastr-tar-test-");
        try {
            Method untar = MineAstrAgentManager.class.getDeclaredMethod(
                    "untarStrippingFirstDirectory", java.io.InputStream.class, Path.class);
            untar.setAccessible(true);
            untar.invoke(null, new ByteArrayInputStream(archive), target);

            Path expected = target.resolve(
                    "include/node/openssl/archs/darwin64-x86_64-cc/asm/"
                            + "providers/common/include/prov/der_rsa.h");
            assertTrue(Files.isRegularFile(expected));
            assertArrayEquals(content, Files.readAllBytes(expected));
            assertFalse(Files.exists(target.resolve("providers/common/include/prov/der_rsa.h")));
        } finally {
            try (var paths = Files.walk(target)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static byte[] tarEntry(String prefix, String name, byte[] content) throws Exception {
        byte[] header = new byte[512];
        putAscii(header, 0, 100, name);
        putAscii(header, 124, 12, String.format("%011o", content.length));
        header[156] = '0';
        putAscii(header, 257, 6, "ustar");
        putAscii(header, 345, 155, prefix);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(header);
        output.write(content);
        output.write(new byte[(512 - content.length % 512) % 512]);
        output.write(new byte[1024]);
        return output.toByteArray();
    }

    private static void putAscii(byte[] target, int offset, int length, String value) {
        byte[] encoded = value.getBytes(StandardCharsets.US_ASCII);
        if (encoded.length > length) throw new IllegalArgumentException("tar field too long");
        System.arraycopy(encoded, 0, target, offset, encoded.length);
    }
}
