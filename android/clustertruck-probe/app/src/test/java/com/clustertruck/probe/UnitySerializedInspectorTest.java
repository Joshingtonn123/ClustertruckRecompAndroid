package com.clustertruck.probe;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

public final class UnitySerializedInspectorTest {
    private static final String SHADER_HASH = "8db760073e1392061e4a36995f0735af";

    @Test
    public void reportsLegacyUnityVersionAndStrippedShaderHash() {
        byte[] file = serializedFile(17, false);
        String report = UnitySerializedInspector.inspect(file, file.length, "Data/unity default resources");

        assertNotNull(report);
        assertTrue(report, report.contains("Unity engine 5.3.4f1"));
        assertTrue(report, report.contains("Unity SerializedFile format 17"));
        assertTrue(report, report.contains("Shader class 48"));
        assertTrue(report, report.contains(SHADER_HASH));
        assertTrue(report, report.contains("type tree absent"));
        assertTrue(report, report.contains("type trees stripped"));
    }

    @Test
    public void reportsWhenShaderTypeTreeIsEmbedded() {
        byte[] file = serializedFile(19, true);
        String report = UnitySerializedInspector.inspect(file, file.length, "Data/sharedassets0.assets");

        assertNotNull(report);
        assertTrue(report, report.contains(SHADER_HASH));
        assertTrue(report, report.contains("type tree present"));
        assertTrue(report, report.contains("type trees enabled"));
    }

    @Test
    public void doesNotMisidentifyArbitraryFiles() {
        assertNull(UnitySerializedInspector.inspect("not a unity file".getBytes(StandardCharsets.UTF_8),
                16, "random.bin"));
    }

    @Test
    public void reportsTruncatedTypeTableWithoutCrashing() {
        byte[] full = serializedFile(17, false);
        byte[] truncated = new byte[full.length - 5];
        System.arraycopy(full, 0, truncated, 0, truncated.length);
        String report = UnitySerializedInspector.inspect(truncated, full.length, "Data/unity default resources");

        assertNotNull(report);
        assertTrue(report, report.contains("partial type-table read"));
    }

    private static byte[] serializedFile(int format, boolean withTypeTree) {
        byte[] version = "5.3.4f1".getBytes(StandardCharsets.US_ASCII);
        int treeExtra = withTypeTree ? 8 + 32 : 0;
        int length = 20 + version.length + 1 + 4 + 1 + 4 + 4 + 1 + 2 + 16 + treeExtra;
        byte[] bytes = new byte[length];

        ByteBuffer header = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        header.putInt(0, length - 20);
        header.putInt(4, length);
        header.putInt(8, format);
        header.putInt(12, length);
        bytes[16] = 0; // little-endian serialized data

        ByteBuffer body = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        body.position(20);
        body.put(version).put((byte) 0);
        body.putInt(0); // target platform
        body.put((byte) (withTypeTree ? 1 : 0));
        body.putInt(1); // type count
        body.putInt(48); // Shader class ID
        body.put((byte) 0); // not stripped
        body.putShort((short) 0);
        body.put(hexBytes(SHADER_HASH));
        if (withTypeTree) {
            body.putInt(1);  // one type-tree node
            body.putInt(0);  // empty string buffer
            body.position(body.position() + 32); // format 19 node is 32 bytes
        }
        return bytes;
    }

    private static byte[] hexBytes(String value) {
        byte[] bytes = new byte[value.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }
}
