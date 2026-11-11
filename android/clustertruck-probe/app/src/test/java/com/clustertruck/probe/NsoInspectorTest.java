package com.clustertruck.probe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

public final class NsoInspectorTest {
    private static final String START = "_ZN2nn4init5StartEmmPFvvES2_";

    @Test
    public void ignoresLargeUnusedFileTailWhenRequiredSegmentsFitSafetyLimit() throws Exception {
        byte[] raw = makeNso();
        // Simulate a provider reporting a large padded/extraction file. The NSO header's actual
        // segment data still fits in the bounded prefix and can be parsed without reading the tail.
        String report = NsoInspector.inspect(
                new ByteArrayInputStream(raw), "exefs/main", (64L * 1024 * 1024) + 1);
        assertNotNull(report);
        assertTrue(report, report.contains("1 imports, 1 exports, 1 relocations"));
        assertTrue(report, report.contains("nn::init::Start export: PRESENT"));
    }

    @Test
    public void parsesCompressedNsoImportsStartupExportAndRelocations() throws Exception {
        byte[] raw = makeNso();
        String report = NsoInspector.inspect(new ByteArrayInputStream(raw), "exefs/main", raw.length);
        System.out.println("SYNTHETIC NSO REPORT:\\n" + report);

        assertNotNull(report);
        assertTrue(report, report.contains("1 imports, 1 exports, 1 relocations"));
        assertTrue(report, report.contains("nn::init::Start export: PRESENT"));
        assertTrue(report, report.contains("Needed libraries: libnn_sdk.so"));
        assertTrue(report, report.contains("nn::TestImport"));
        assertTrue(report, report.contains("0x402 × 1"));
    }

    @Test
    public void detailedInspectionRetainsSymbolsAndAuditsCrossModuleExports() throws Exception {
        byte[] raw = makeNso();
        NsoInspector.ModuleReport main = NsoInspector.inspectDetailed(
                new ByteArrayInputStream(raw), "exefs/main", raw.length);
        assertNotNull(main);
        assertTrue(main.metadataParsed);
        assertTrue(main.imports.contains("nn::TestImport"));
        assertTrue(main.exports.contains(START));
        assertTrue(main.neededLibraries.contains("libnn_sdk.so"));
        assertTrue(main.relocationTypes.containsKey(0x402));

        NsoInspector.ModuleReport sdk = new NsoInspector.ModuleReport("exefs/sdk");
        sdk.metadataParsed = true;
        sdk.exports.add("nn::TestImport");

        String audit = NsoInspector.linkAudit(Arrays.asList(main, sdk));
        assertTrue(audit, audit.contains("main: 1 imports; 1 found in mapped module exports; 0 not found"));
        assertTrue(audit, audit.contains("main, sdk and subsdk0 only"));
    }

    @Test
    public void parsesGnuHashWithoutSysvHashAndCountsPackedRelrTargets() throws Exception {
        byte[] raw = makeNso(true, true);
        NsoInspector.ModuleReport report = NsoInspector.inspectDetailed(
                new ByteArrayInputStream(raw), "exefs/main", raw.length);

        assertNotNull(report);
        assertTrue(report.metadataParsed);
        assertEquals("GNU", report.symbolHashStyle);
        assertTrue(report.imports.contains("nn::TestImport"));
        assertTrue(report.exports.contains(START));
        assertEquals(1L, report.packedRelativeRelocationCount);
        assertTrue(report.text, report.text.contains("Symbol hash table: GNU"));
        assertTrue(report.text, report.text.contains("DT_RELR): 1 targets"));
        assertTrue(NsoInspector.supportsPackedRelativeRelocations());
    }

    @Test
    public void recognizesTheFourRelocationTypesImplementedByCurrentLoader() {
        assertTrue(NsoInspector.supportsRuntimeRelocation(0x101));
        assertTrue(NsoInspector.supportsRuntimeRelocation(0x401));
        assertTrue(NsoInspector.supportsRuntimeRelocation(0x402));
        assertTrue(NsoInspector.supportsRuntimeRelocation(0x403));
        assertFalse(NsoInspector.supportsRuntimeRelocation(0x404));
    }

    @Test
    public void parsesSegmentsAfterLargeFileOffsetGapWithoutBufferingPadding() throws Exception {
        byte[] compact = makeNso();
        int gap = 65 * 1024 * 1024;
        long rodataOffset = u32(compact, 0x20);
        long dataOffset = u32(compact, 0x30);
        byte[] padded = new byte[compact.length + gap];
        System.arraycopy(compact, 0, padded, 0, (int) rodataOffset);
        System.arraycopy(compact, (int) rodataOffset, padded, (int) rodataOffset + gap,
                compact.length - (int) rodataOffset);
        put32(padded, 0x20, rodataOffset + gap);
        put32(padded, 0x30, dataOffset + gap);

        String report = NsoInspector.inspect(
                new ByteArrayInputStream(padded), "exefs/main", padded.length);
        assertTrue(report, report.contains("1 imports, 1 exports, 1 relocations"));
        assertTrue(report, report.contains("nn::init::Start export: PRESENT"));
        assertTrue(report, report.contains("Needed libraries: libnn_sdk.so"));
    }

    private static long u32(byte[] data, int offset) {
        return (data[offset] & 0xffL) | ((data[offset + 1] & 0xffL) << 8)
                | ((data[offset + 2] & 0xffL) << 16) | ((data[offset + 3] & 0xffL) << 24);
    }

    private static byte[] makeNso() throws Exception { return makeNso(false, false); }

    private static byte[] makeNso(boolean useGnuHash, boolean useRelr) throws Exception {
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
        if (useGnuHash) {
            put32(rodata, 0x10, 1);          // GNU hash: nbuckets
            put32(rodata, 0x14, 1);          // GNU hash: symoffset
            put32(rodata, 0x18, 1);          // GNU hash: bloom_size
            put32(rodata, 0x1c, 0);          // GNU hash: bloom_shift
            put64(rodata, 0x20, 0);          // bloom word
            put32(rodata, 0x28, 1);          // bucket -> symbol 1
            put32(rodata, 0x2c, 0);          // symbol 1 chain continues
            put32(rodata, 0x30, 1);          // symbol 2 chain terminates
        } else {
            put32(rodata, 0x10, 1);          // DT_HASH: nbucket
            put32(rodata, 0x14, 3);          // DT_HASH: nchain
        }
        putString(rodata, 0x40, strings);

        // Three ELF64 dynamic symbols: null, one undefined import, one defined startup export.
        put32(rodata, 0x80 + 24, importName);
        rodata[0x80 + 24 + 4] = 0x12;
        put16(rodata, 0x80 + 24 + 6, 0);
        put32(rodata, 0x80 + 48, startupName);
        rodata[0x80 + 48 + 4] = 0x12;
        put16(rodata, 0x80 + 48 + 6, 1);

        // A single R_AARCH64_JUMP_SLOT relocation (type 1026 / 0x402).
        put64(data, 0x30, 0x50);
        put64(data, 0x38, 0x402);
        put64(data, 0x40, 0);

        long hashTag = useGnuHash ? 0x6ffffef5L : 4;
        long[][] dynamic = useRelr ? new long[][] {
                {1, needed},          // DT_NEEDED
                {hashTag, 0x110},     // DT_HASH or DT_GNU_HASH
                {5, 0x140},           // DT_STRTAB
                {6, 0x180},           // DT_SYMTAB
                {7, 0x230},           // DT_RELA
                {8, 24},              // DT_RELASZ
                {10, strings.length}, // DT_STRSZ
                {36, 0x280},          // DT_RELR
                {35, 8},              // DT_RELRSZ
                {0, 0}                // DT_NULL
        } : new long[][] {
                {1, needed},          // DT_NEEDED
                {hashTag, 0x110},     // DT_HASH or DT_GNU_HASH
                {5, 0x140},           // DT_STRTAB
                {6, 0x180},           // DT_SYMTAB
                {7, 0x230},           // DT_RELA
                {8, 24},              // DT_RELASZ
                {10, strings.length}, // DT_STRSZ
                {0, 0}                // DT_NULL
        };
        if (useRelr) put64(data, 0x80, 0x300);  // RELR target in module image
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
