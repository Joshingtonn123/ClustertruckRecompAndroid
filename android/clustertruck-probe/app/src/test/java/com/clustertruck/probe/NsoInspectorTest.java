package com.clustertruck.probe;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

public final class NsoInspectorTest {
    private static final String START = "_ZN2nn4init5StartEmmPFvvES2_";

    @Test
    public void parsesCompressedNsoImportsStartupExportAndRelocations() throws Exception {
        byte[] raw = makeNso();
        String report = NsoInspector.inspect(new ByteArrayInputStream(raw), "exefs/main", raw.length);

        assertNotNull(report);
        assertTrue(report, report.contains("1 imports, 1 exports, 1 relocations"));
        assertTrue(report, report.contains("nn::init::Start export: PRESENT"));
        assertTrue(report, report.contains("Needed libraries: libnn_sdk.so"));
        assertTrue(report, report.contains("nn::TestImport"));
        assertTrue(report, report.contains("0x402 × 1"));
    }

    private static byte[] makeNso() throws Exception {
        byte[] text = new byte[0x100];
        byte[] rodata = new byte[0x100];
        byte[] data = new byte[0x200];

        put32(text, 4, 0x20);                 // NSO image offset of MOD0
        ascii(text, 0x20, "MOD0");
        put32(text, 0x24, 0x40);             // MOD0-relative dynamic table offset

        byte[] strings = makeStrings();
        int needed = 1;
        int startupName = 1 + "libnn_sdk.so".length() + 1;
        int importName = startupName + START.length() + 1;
        put32(rodata, 0x40, 1);              // DT_HASH: nbucket
        put32(rodata, 0x44, 3);              // DT_HASH: nchain (symbol count)
        putString(rodata, 0x80, strings);

        // Three ELF64 dynamic symbols: null, one undefined import, one defined startup export.
        put32(rodata, 0xa0 + 24, importName);
        rodata[0xa0 + 24 + 4] = 0x12;
        put16(rodata, 0xa0 + 24 + 6, 0);
        put32(rodata, 0xa0 + 48, startupName);
        rodata[0xa0 + 48 + 4] = 0x12;
        put16(rodata, 0xa0 + 48 + 6, 1);

        // A single R_AARCH64_JUMP_SLOT relocation (type 1026 / 0x402).
        put64(data, 0x30, 0x50);
        put64(data, 0x38, 0x402);
        put64(data, 0x40, 0);

        long[][] dynamic = {
                {1, needed},          // DT_NEEDED
                {4, 0x140},           // DT_HASH
                {5, 0x180},           // DT_STRTAB
                {6, 0x1a0},           // DT_SYMTAB
                {7, 0x230},           // DT_RELA
                {8, 24},              // DT_RELASZ
                {10, strings.length}, // DT_STRSZ
                {0, 0}                // DT_NULL
        };
        for (int i = 0; i < dynamic.length; i++) {
            put64(text, 0x60 + i * 16, dynamic[i][0]);
            put64(text, 0x68 + i * 16, dynamic[i][1]);
        }

        // Store .text as a valid LZ4 literal-only block to exercise decompression too.
        int textFile = 0x100;
        int textStored = 2 + text.length;
        int roFile = textFile + textStored;
        int dataFile = roFile + rodata.length;
        byte[] raw = new byte[dataFile + data.length];

        ascii(raw, 0, "NSO0");
        put32(raw, 4, 0x20);
        put32(raw, 0x0c, 1);                // .text is LZ4-compressed
        putSegment(raw, 0x10, textFile, 0, text.length);
        putSegment(raw, 0x20, roFile, 0x100, rodata.length);
        putSegment(raw, 0x30, dataFile, 0x200, data.length);
        put32(raw, 0x60, textStored);
        put32(raw, 0x64, rodata.length);
        put32(raw, 0x68, data.length);
        raw[textFile] = (byte) 0xf0;         // 15 literal bytes + 241 extension bytes = 256
        raw[textFile + 1] = (byte) 0xf1;
        System.arraycopy(text, 0, raw, textFile + 2, text.length);
        System.arraycopy(rodata, 0, raw, roFile, rodata.length);
        System.arraycopy(data, 0, raw, dataFile, data.length);
        return raw;
    }

    private static byte[] makeStrings() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0);
        out.write("libnn_sdk.so".getBytes(StandardCharsets.UTF_8));
        out.write(0);
        out.write(START.getBytes(StandardCharsets.UTF_8));
        out.write(0);
        out.write("nn::TestImport".getBytes(StandardCharsets.UTF_8));
        out.write(0);
        return out.toByteArray();
    }

    private static void putSegment(byte[] out, int at, int file, int memory, int size) {
        put32(out, at, file);
        put32(out, at + 4, memory);
        put32(out, at + 8, size);
    }

    private static void putString(byte[] out, int at, byte[] value) {
        System.arraycopy(value, 0, out, at, value.length);
    }

    private static void ascii(byte[] out, int at, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, out, at, bytes.length);
    }

    private static void put16(byte[] out, int at, int value) {
        out[at] = (byte) value;
        out[at + 1] = (byte) (value >>> 8);
    }

    private static void put32(byte[] out, int at, long value) {
        for (int i = 0; i < 4; i++) out[at + i] = (byte) (value >>> (i * 8));
    }

    private static void put64(byte[] out, int at, long value) {
        for (int i = 0; i < 8; i++) out[at + i] = (byte) (value >>> (i * 8));
    }
}
