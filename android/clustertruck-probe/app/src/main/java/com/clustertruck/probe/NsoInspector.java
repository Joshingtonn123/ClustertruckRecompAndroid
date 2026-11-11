package com.clustertruck.probe;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Locale;

/**
 * Bounded, read-only metadata inspection of an already-extracted Nintendo Switch NSO module.
 * It decompresses NSO segments in memory to inspect MOD0 dynamic symbols and relocations.
 * It never reads NCA containers, decrypts files, or writes back to the selected folder.
 */
final class NsoInspector {
    private static final int HEADER_SIZE = 0x80;
    // Segments are read one at a time; large alignment gaps are skipped, not buffered.
    private static final long MAX_FILE_RANGE_BYTES = 512L * 1024 * 1024;
    private static final int MAX_SEGMENT_STORED_BYTES = 64 * 1024 * 1024;
    private static final int MAX_IMAGE_BYTES = 128 * 1024 * 1024;
    private static final int MAX_DYNAMIC_ENTRIES = 8192;
    private static final int MAX_SYMBOLS = 200_000;
    private static final int MAX_RELOCATIONS = 1_000_000;
    private static final int MAX_IMPORT_NAMES = 80;

    private NsoInspector() {}

    static final class ModuleReport {
        final String path;
        String text = "";
        boolean metadataParsed;
        final List<String> imports = new ArrayList<>();
        final LinkedHashSet<String> exports = new LinkedHashSet<>();
        final LinkedHashSet<String> neededLibraries = new LinkedHashSet<>();
        final Map<Integer, Integer> relocationTypes = new TreeMap<>();
        String symbolHashStyle = "unavailable";
        long packedRelativeRelocationCount;

        ModuleReport(String path) { this.path = path; }

        String moduleName() {
            int slash = path.lastIndexOf('/');
            return slash >= 0 ? path.substring(slash + 1) : path;
        }
    }

    static String inspect(InputStream input, String path, long reportedSize) throws IOException {
        ModuleReport report = inspectDetailed(input, path, reportedSize);
        return report == null ? null : report.text;
    }

    static ModuleReport inspectDetailed(InputStream input, String path, long reportedSize) throws IOException {
        byte[] header = readAtMost(input, HEADER_SIZE);
        if (header.length < HEADER_SIZE || !asciiAt(header, 0, "NSO0")) return null;
        ModuleReport report = new ModuleReport(path);
        String summary = headerSummary(header, path);

        byte[] image = readModuleImage(input, header, reportedSize);
        if (image == null) {
            report.text = summary + "\n    Dynamic metadata: skipped (NSO segment offsets/sizes exceed the bounded scan limits or the reported file size is inconsistent)";
            return report;
        }
        try {
            report.text = summary + "\n" + dynamicSummary(image, report);
        } catch (Exception error) {
            String message = error.getMessage();
            if (message == null || message.isEmpty()) message = error.getClass().getSimpleName();
            report.text = summary + "\n    Dynamic metadata: unavailable (" + message + ")";
        }
        return report;
    }

    private static String headerSummary(byte[] h, String path) throws IOException {
        long flags = u32(h, 0x0c);
        long bss = u32(h, 0x3c);
        StringBuilder out = new StringBuilder(path).append(" — NSO0, build ID ")
                .append(hex(h, 0x40, 0x20));
        out.append(", flags 0x").append(Long.toHexString(flags));
        out.append(", BSS ").append(bss).append(" bytes");
        String[] names = {"text", "rodata", "data"};
        for (int i = 0; i < 3; i++) {
            int p = 0x10 + i * 0x10;
            long file = u32(h, p);
            long mem = u32(h, p + 4);
            long size = u32(h, p + 8);
            long compressed = u32(h, 0x60 + i * 4);
            out.append(", ").append(names[i]).append(" file@0x").append(Long.toHexString(file))
                    .append(" mem@0x").append(Long.toHexString(mem)).append(" + ").append(size)
                    .append(" bytes (stored ").append(compressed).append(")");
        }
        return out.toString();
    }

    private static String dynamicSummary(byte[] image, ModuleReport report) throws IOException {
        long mod0 = u32(image, 4);
        requireRange(image.length, mod0, 8, "MOD0 header");
        if (!asciiAt(image, (int) mod0, "MOD0")) throw new IOException("MOD0 signature not found");
        long dynamic = mod0 + s32(image, (int) mod0 + 4);
        requireRange(image.length, dynamic, 16, "dynamic table");

        long strtab = -1, symtab = -1, hash = -1, gnuHash = -1, strsz = -1;
        long rela = -1, relasz = 0, jmprel = -1, pltrelsz = 0, relr = -1, relrsz = 0;
        List<Long> neededOffsets = new ArrayList<>();
        boolean terminated = false;
        for (int i = 0; i < MAX_DYNAMIC_ENTRIES; i++) {
            long p = dynamic + (long) i * 16;
            requireRange(image.length, p, 16, "dynamic entry");
            long tag = i64(image, (int) p);
            long value = i64(image, (int) p + 8);
            if (tag == 0) { terminated = true; break; }
            switch ((int) tag) {
                case 1: neededOffsets.add(value); break;       // DT_NEEDED
                case 2: pltrelsz = value; break;              // DT_PLTRELSZ
                case 4: hash = value; break;                  // DT_HASH
                case 5: strtab = value; break;                // DT_STRTAB
                case 6: symtab = value; break;                // DT_SYMTAB
                case 7: rela = value; break;                  // DT_RELA
                case 8: relasz = value; break;                // DT_RELASZ
                case 10: strsz = value; break;                // DT_STRSZ
                case 23: jmprel = value; break;               // DT_JMPREL
                case 35: relrsz = value; break;               // DT_RELRSZ
                case 36: relr = value; break;                 // DT_RELR
                case 0x6ffffef5: gnuHash = value; break;       // DT_GNU_HASH
                default: break;
            }
        }
        if (!terminated) throw new IOException("dynamic table has no terminator");
        if (strtab < 0) throw new IOException("dynamic string table missing");
        long stringEnd = strsz >= 0 && strtab + strsz <= image.length ? strtab + strsz : image.length;
        requireRange(image.length, strtab, 1, "dynamic string table");
        if (stringEnd <= strtab) throw new IOException("dynamic string table is empty");

        LinkedHashSet<String> libraries = new LinkedHashSet<>();
        for (long offset : neededOffsets) {
            String name = cString(image, strtab + offset, stringEnd);
            if (!name.isEmpty()) {
                libraries.add(name);
                report.neededLibraries.add(name);
            }
        }

        int imports = 0, exports = 0;
        boolean startupExportFound = false;
        LinkedHashSet<String> importNames = new LinkedHashSet<>();
        long symbolCount = -1;
        if (symtab >= 0 && hash >= 0) {
            requireRange(image.length, hash, 8, "ELF hash table");
            symbolCount = u32(image, (int) hash + 4);
            report.symbolHashStyle = "SysV";
        } else if (symtab >= 0 && gnuHash >= 0) {
            symbolCount = gnuHashSymbolCount(image, gnuHash);
            report.symbolHashStyle = "GNU";
        }
        if (symbolCount >= 0) {
            if (symbolCount > MAX_SYMBOLS) throw new IOException("symbol table exceeds the scan limit");
            requireRange(image.length, symtab, symbolCount * 24L, "dynamic symbol table");
            for (int i = 0; i < (int) symbolCount; i++) {
                int p = (int) symtab + i * 24;
                long nameOffset = u32(image, p);
                int section = u16(image, p + 6);
                if (nameOffset == 0) continue;
                String name = cString(image, strtab + nameOffset, stringEnd);
                if (name.isEmpty()) continue;
                if (section == 0) {
                    imports++;
                    report.imports.add(name);
                    if (importNames.size() < MAX_IMPORT_NAMES) importNames.add(name);
                } else {
                    exports++;
                    report.exports.add(name);
                    if ("_ZN2nn4init5StartEmmPFvvES2_".equals(name)) startupExportFound = true;
                }
            }
        }

        Map<Integer, Integer> relocationTypes = new TreeMap<>();
        long relocationCount = 0;
        if (rela >= 0) {
            long count = relasz / 24;
            if (count > MAX_RELOCATIONS) throw new IOException("REL relocation table exceeds the scan limit");
            requireRange(image.length, rela, count * 24, "REL relocation table");
            for (int i = 0; i < (int) count; i++) {
                int type = (int) i64(image, (int) rela + i * 24 + 8);
                relocationTypes.put(type, relocationTypes.getOrDefault(type, 0) + 1);
                report.relocationTypes.put(type, report.relocationTypes.getOrDefault(type, 0) + 1);
                relocationCount++;
            }
        }
        if (jmprel >= 0) {
            long count = pltrelsz / 24;
            if (count > MAX_RELOCATIONS || relocationCount + count > MAX_RELOCATIONS)
                throw new IOException("PLT relocation table exceeds the scan limit");
            requireRange(image.length, jmprel, count * 24, "PLT relocation table");
            for (int i = 0; i < (int) count; i++) {
                int type = (int) i64(image, (int) jmprel + i * 24 + 8);
                relocationTypes.put(type, relocationTypes.getOrDefault(type, 0) + 1);
                report.relocationTypes.put(type, report.relocationTypes.getOrDefault(type, 0) + 1);
                relocationCount++;
            }
        }

        if (relr >= 0) report.packedRelativeRelocationCount = countRelrTargets(image, relr, relrsz);

        report.metadataParsed = true;
        StringBuilder out = new StringBuilder("    Dynamic metadata: ")
                .append(imports).append(" imports, ").append(exports).append(" exports, ")
                .append(relocationCount).append(" RELA relocations; nn::init::Start export: ")
                .append(startupExportFound ? "PRESENT" : "not found in this module")
                .append("\n    Symbol hash table: ").append(report.symbolHashStyle);
        if (relr >= 0) {
            out.append("\n    Packed relative relocations (DT_RELR): ")
                    .append(report.packedRelativeRelocationCount)
                    .append(" targets; Android loader support: ")
                    .append(supportsPackedRelativeRelocations() ? "implemented" : "not implemented");
        }
        if (!libraries.isEmpty()) {
            out.append("\n    Needed libraries: ");
            appendJoined(out, libraries);
        } else {
            out.append("\n    Needed libraries: none listed");
        }
        if (!relocationTypes.isEmpty()) {
            out.append("\n    Relocation types: ");
            boolean first = true;
            boolean allSupported = true;
            for (Map.Entry<Integer, Integer> entry : relocationTypes.entrySet()) {
                if (!first) out.append(", ");
                out.append("0x").append(Integer.toHexString(entry.getKey())).append(" × ").append(entry.getValue());
                first = false;
                if (!supportsRuntimeRelocation(entry.getKey())) allSupported = false;
            }
            out.append("\n    Current loader relocation coverage: ")
                    .append(allSupported ? "all observed types are implemented" : "UNSUPPORTED types observed");
        }
        if (!importNames.isEmpty()) {
            out.append("\n    Imported symbol sample (up to ").append(MAX_IMPORT_NAMES).append("): ");
            appendJoined(out, importNames);
        }
        return out.toString();
    }

    private static long gnuHashSymbolCount(byte[] image, long hash) throws IOException {
        requireRange(image.length, hash, 16, "GNU hash header");
        long bucketCount = u32(image, (int) hash);
        long symbolOffset = u32(image, (int) hash + 4);
        long bloomCount = u32(image, (int) hash + 8);
        if (bucketCount == 0 || bucketCount > MAX_SYMBOLS ||
                symbolOffset > MAX_SYMBOLS || bloomCount > MAX_SYMBOLS)
            throw new IOException("GNU hash dimensions exceed the scan limit");
        long buckets = hash + 16 + bloomCount * 8;
        requireRange(image.length, buckets, bucketCount * 4, "GNU hash buckets");
        long chain = buckets + bucketCount * 4;
        long last = 0;
        for (int i = 0; i < (int) bucketCount; i++) {
            long value = u32(image, (int) buckets + i * 4);
            if (value != 0 && value < symbolOffset)
                throw new IOException("GNU hash bucket points before the symbol offset");
            if (value > last) last = value;
        }
        if (last < symbolOffset) return symbolOffset;
        for (long symbol = last; symbol < MAX_SYMBOLS; symbol++) {
            long chainEntry = chain + (symbol - symbolOffset) * 4;
            requireRange(image.length, chainEntry, 4, "GNU hash chain");
            if ((u32(image, (int) chainEntry) & 1) != 0) return symbol + 1;
        }
        throw new IOException("GNU hash chain exceeds the scan limit");
    }

    private static long countRelrTargets(byte[] image, long relr, long relrSize) throws IOException {
        if ((relrSize & 7) != 0) throw new IOException("DT_RELR size is not pointer-aligned");
        requireRange(image.length, relr, relrSize, "DT_RELR table");
        long cursor = -1, count = 0;
        for (long p = relr; p < relr + relrSize; p += 8) {
            long entry = i64(image, (int) p);
            if ((entry & 1) == 0) {
                if ((entry & 7) != 0) throw new IOException("DT_RELR target is unaligned");
                requireRange(image.length, entry, 8, "DT_RELR target");
                cursor = entry + 8;
                count++;
            } else {
                if (cursor < 0) throw new IOException("DT_RELR bitmap precedes an address");
                for (int bit = 0; bit < 63; bit++) {
                    if (((entry >>> (bit + 1)) & 1) == 0) continue;
                    long target = cursor + (long) bit * 8;
                    requireRange(image.length, target, 8, "DT_RELR bitmap target");
                    count++;
                    if (count > MAX_RELOCATIONS) throw new IOException("DT_RELR targets exceed the scan limit");
                }
                cursor += 63L * 8;
            }
            if (count > MAX_RELOCATIONS) throw new IOException("DT_RELR targets exceed the scan limit");
        }
        return count;
    }

    static boolean supportsPackedRelativeRelocations() { return true; }

    static boolean supportsRuntimeRelocation(int type) {
        return type == 257 || type == 1025 || type == 1026 || type == 1027;
    }

    static String linkAudit(List<ModuleReport> modules) {
        Map<String, LinkedHashSet<String>> providers = new TreeMap<>();
        for (ModuleReport module : modules) {
            String name = module.moduleName();
            if (!isLoadedByCurrentRuntime(name) || !module.metadataParsed) continue;
            for (String symbol : module.exports)
                providers.computeIfAbsent(symbol, ignored -> new LinkedHashSet<>()).add(name);
        }

        StringBuilder out = new StringBuilder("Cross-module import/export audit:\n");
        out.append("  Loader comparison includes main, sdk and subsdk0 only; rtld and additional subsdk modules are not mapped by the current generic loader.\n");
        for (ModuleReport module : modules) {
            String name = module.moduleName();
            if (!isLoadedByCurrentRuntime(name)) continue;
            if (!module.metadataParsed) {
                out.append("  ").append(name).append(": dynamic symbols unavailable; no link audit possible.\n");
                continue;
            }
            int matched = 0;
            List<String> unmatched = new ArrayList<>();
            Map<String, Integer> groups = new TreeMap<>();
            for (String symbol : module.imports) {
                if (providers.containsKey(symbol)) {
                    matched++;
                } else {
                    unmatched.add(symbol);
                    String group = classifyImport(symbol);
                    groups.put(group, groups.getOrDefault(group, 0) + 1);
                }
            }
            out.append("  ").append(name).append(": ").append(module.imports.size())
                    .append(" imports; ").append(matched).append(" found in mapped module exports; ")
                    .append(unmatched.size()).append(" not found in those exports.\n");
            if (!groups.isEmpty()) {
                out.append("    Unmatched import groups (heuristic): ");
                appendJoined(out, formatCounts(groups));
                out.append('\n');
                out.append("    Unmatched symbol examples: ");
                int limit = Math.min(18, unmatched.size());
                appendJoined(out, unmatched.subList(0, limit));
                if (unmatched.size() > limit) out.append(", ...");
                out.append('\n');
            }
        }
        out.append("  Important: an import not found in another mapped module may still be supplied by an Android system library or an implemented HLE function; this audit does not label it missing.");
        return out.toString();
    }

    private static boolean isLoadedByCurrentRuntime(String name) {
        return "main".equals(name) || "sdk".equals(name) || "subsdk0".equals(name);
    }

    private static String classifyImport(String symbol) {
        if (symbol.startsWith("_ZN7android") || symbol.startsWith("ANative") ||
                symbol.startsWith("android")) return "Android framework/native";
        if (symbol.startsWith("Nv") || symbol.startsWith("nvgr_") ||
                symbol.startsWith("multimedia_")) return "NVIDIA/Tegra multimedia";
        if (symbol.startsWith("_ZN2nn") || symbol.startsWith("nn")) return "Nintendo SDK/services";
        if (symbol.startsWith("_Z") || symbol.startsWith("__")) return "C/C++ ABI or runtime";
        return "Other/system";
    }

    private static List<String> formatCounts(Map<String, Integer> counts) {
        List<String> values = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet())
            values.add(entry.getKey() + "=" + entry.getValue());
        return values;
    }

    private static void appendJoined(StringBuilder out, Iterable<String> values) {
        boolean first = true;
        for (String value : values) {
            if (!first) out.append(", ");
            out.append(value);
            first = false;
        }
    }

    private static void lz4Block(byte[] input, int offset, int length, byte[] output,
                                  int outputOffset, int outputLength) throws IOException {
        requireRange(output.length, outputOffset, outputLength, "LZ4 destination");
        int in = offset, inEnd = offset + length, out = 0;
        while (in < inEnd) {
            int token = input[in++] & 0xff;
            int literals = token >>> 4;
            if (literals == 15) {
                int extension;
                do {
                    if (in >= inEnd) throw new IOException("truncated LZ4 literal length");
                    extension = input[in++] & 0xff;
                    literals += extension;
                } while (extension == 255);
            }
            if (literals > inEnd - in || literals > outputLength - out)
                throw new IOException("invalid LZ4 literal range");
            System.arraycopy(input, in, output, outputOffset + out, literals);
            in += literals;
            out += literals;
            if (in == inEnd) break;
            if (in + 2 > inEnd) throw new IOException("truncated LZ4 match offset");
            int back = (input[in] & 0xff) | ((input[in + 1] & 0xff) << 8);
            in += 2;
            if (back == 0 || back > out) throw new IOException("invalid LZ4 match offset");
            int match = token & 0x0f;
            if (match == 15) {
                int extension;
                do {
                    if (in >= inEnd) throw new IOException("truncated LZ4 match length");
                    extension = input[in++] & 0xff;
                    match += extension;
                } while (extension == 255);
            }
            match += 4;
            if (match > outputLength - out) throw new IOException("LZ4 output exceeds declared segment size");
            for (int i = 0; i < match; i++)
                output[outputOffset + out + i] = output[outputOffset + out + i - back];
            out += match;
        }
        if (out != outputLength)
            throw new IOException("LZ4 segment length mismatch (" + out + " != " + outputLength + ")");
    }

    private static byte[] readModuleImage(InputStream input, byte[] header, long reportedSize) throws IOException {
        long flags = u32(header, 0x0c);
        long[] fileOffsets = new long[3];
        long[] memoryOffsets = new long[3];
        long[] decompressedSizes = new long[3];
        long[] storedSizes = new long[3];
        long imageEnd = 0;

        for (int i = 0; i < 3; i++) {
            int p = 0x10 + i * 0x10;
            fileOffsets[i] = u32(header, p);
            memoryOffsets[i] = u32(header, p + 4);
            decompressedSizes[i] = u32(header, p + 8);
            storedSizes[i] = u32(header, 0x60 + i * 4);
            long fileEnd = fileOffsets[i] + storedSizes[i];
            if (fileOffsets[i] < HEADER_SIZE || fileEnd < fileOffsets[i] ||
                    fileEnd > MAX_FILE_RANGE_BYTES || storedSizes[i] > MAX_SEGMENT_STORED_BYTES ||
                    (reportedSize > 0 && fileEnd > reportedSize)) return null;
            if (memoryOffsets[i] > MAX_IMAGE_BYTES ||
                    decompressedSizes[i] > MAX_IMAGE_BYTES - memoryOffsets[i]) return null;
            imageEnd = Math.max(imageEnd, memoryOffsets[i] + decompressedSizes[i]);
        }

        long bss = u32(header, 0x3c);
        long dataEnd = memoryOffsets[2] + decompressedSizes[2];
        if (bss > MAX_IMAGE_BYTES - dataEnd) return null;
        imageEnd = Math.max(imageEnd, dataEnd + bss);
        imageEnd = (imageEnd + 0xfffL) & ~0xfffL;
        if (imageEnd <= 0 || imageEnd > MAX_IMAGE_BYTES) return null;

        byte[] image = new byte[(int) imageEnd];
        int[] order = {0, 1, 2};
        for (int i = 0; i < order.length; i++) {
            for (int j = i + 1; j < order.length; j++) {
                if (fileOffsets[order[j]] < fileOffsets[order[i]]) {
                    int swap = order[i];
                    order[i] = order[j];
                    order[j] = swap;
                }
            }
        }

        long streamOffset = HEADER_SIZE;
        for (int index : order) {
            long sourceOffset = fileOffsets[index];
            if (sourceOffset < streamOffset) throw new IOException("NSO segment file ranges overlap");
            skipBytes(input, sourceOffset - streamOffset);
            int stored = (int) storedSizes[index];
            int expected = (int) decompressedSizes[index];
            byte[] segment = new byte[stored];
            readInto(input, segment);
            if ((flags & (1L << index)) != 0) {
                lz4Block(segment, 0, segment.length, image, (int) memoryOffsets[index], expected);
            } else {
                if (stored < expected) throw new IOException("uncompressed NSO segment is truncated");
                System.arraycopy(segment, 0, image, (int) memoryOffsets[index], expected);
            }
            streamOffset = sourceOffset + storedSizes[index];
        }
        return image;
    }

    private static void readInto(InputStream input, byte[] output) throws IOException {
        int offset = 0;
        while (offset < output.length) {
            int count = input.read(output, offset, output.length - offset);
            if (count < 0) throw new IOException("NSO file ended before required segment data");
            if (count == 0) continue;
            offset += count;
        }
    }

    private static void skipBytes(InputStream input, long count) throws IOException {
        long remaining = count;
        byte[] discard = new byte[8192];
        while (remaining > 0) {
            long skipped = input.skip(remaining);
            if (skipped > 0) {
                remaining -= skipped;
                continue;
            }
            int wanted = (int) Math.min(discard.length, remaining);
            int read = input.read(discard, 0, wanted);
            if (read < 0) throw new IOException("NSO file ended inside a segment gap");
            if (read == 0) continue;
            remaining -= read;
        }
    }

    private static byte[] readAtMost(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(limit);
        byte[] buffer = new byte[Math.min(8192, limit)];
        while (out.size() < limit) {
            int count = input.read(buffer, 0, Math.min(buffer.length, limit - out.size()));
            if (count < 0) break;
            if (count == 0) continue;
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }

    private static void requireRange(int total, long offset, long length, String what) throws IOException {
        if (offset < 0 || length < 0 || offset > total || length > total - offset)
            throw new IOException(what + " is outside the module image");
    }

    private static long u32(byte[] data, int offset) throws IOException {
        requireRange(data.length, offset, 4, "32-bit field");
        return (data[offset] & 0xffL) | ((data[offset + 1] & 0xffL) << 8)
                | ((data[offset + 2] & 0xffL) << 16) | ((data[offset + 3] & 0xffL) << 24);
    }

    private static int s32(byte[] data, int offset) throws IOException {
        requireRange(data.length, offset, 4, "signed 32-bit field");
        return (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8)
                | ((data[offset + 2] & 0xff) << 16) | (data[offset + 3] << 24);
    }

    private static long i64(byte[] data, int offset) throws IOException {
        requireRange(data.length, offset, 8, "64-bit field");
        return (data[offset] & 0xffL) | ((data[offset + 1] & 0xffL) << 8)
                | ((data[offset + 2] & 0xffL) << 16) | ((data[offset + 3] & 0xffL) << 24)
                | ((data[offset + 4] & 0xffL) << 32) | ((data[offset + 5] & 0xffL) << 40)
                | ((data[offset + 6] & 0xffL) << 48) | ((long) data[offset + 7] << 56);
    }

    private static int u16(byte[] data, int offset) throws IOException {
        requireRange(data.length, offset, 2, "16-bit field");
        return (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8);
    }

    private static String cString(byte[] image, long offset, long end) throws IOException {
        if (offset < 0 || offset >= end || end > image.length) throw new IOException("string offset outside dynamic string table");
        int start = (int) offset, limit = (int) end, p = start;
        while (p < limit && image[p] != 0) p++;
        if (p == limit) throw new IOException("unterminated dynamic string");
        return new String(image, start, p - start, StandardCharsets.UTF_8);
    }

    private static boolean asciiAt(byte[] data, int offset, String value) {
        if (offset < 0 || offset + value.length() > data.length) return false;
        for (int i = 0; i < value.length(); i++) if (data[offset + i] != (byte) value.charAt(i)) return false;
        return true;
    }

    private static String hex(byte[] data, int offset, int count) {
        StringBuilder out = new StringBuilder(count * 2);
        for (int i = 0; i < count; i++) out.append(String.format(Locale.ROOT, "%02x", data[offset + i] & 0xff));
        return out.toString();
    }
}
