package com.clustertruck.probe;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Read-only reader for the Unity SerializedFile metadata header and type table.
 *
 * It intentionally does not decode object payloads, decompress UnityFS bundles,
 * extract assets, or modify game files. Its purpose is to report the Unity engine
 * version and exact class/type hashes needed to diagnose missing stripped type trees.
 */
final class UnitySerializedInspector {
    private static final int MIN_FORMAT = 17;
    private static final int MAX_FORMAT = 22;
    private static final int MAX_TYPES = 100_000;
    private static final int MAX_NODES = 1_000_000;
    private static final int MAX_STRING_BYTES = 16 * 1024 * 1024;

    private UnitySerializedInspector() { }

    static String inspect(byte[] prefix, long fullSize, String path) {
        if (prefix == null || prefix.length < 20 || path == null) return null;
        if (startsWith(prefix, "UnityFS") || startsWith(prefix, "UnityRaw") ||
                startsWith(prefix, "UnityWeb") || startsWith(prefix, "UnityArchive")) {
            return null;
        }

        long formatLong = Integer.toUnsignedLong(readInt(prefix, 8, ByteOrder.BIG_ENDIAN));
        if (formatLong < 1 || formatLong > 64) return null;
        int format = (int) formatLong;
        int endianMarker = prefix[16] & 0xff;
        if (endianMarker > 1) return null;

        ByteOrder order = endianMarker == 0 ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
        StringBuilder report = new StringBuilder(path).append(" — Unity SerializedFile format ").append(format);

        try {
            ByteBuffer data = ByteBuffer.wrap(prefix).order(order);
            data.position(20);

            if (format >= 22) {
                require(data, 28, "extended format-22 header");
                data.position(data.position() + 28);
            }

            String unityVersion = readCString(data, 128);
            if (unityVersion == null) {
                return report.append("; engine-version string is truncated").toString();
            }
            report.append(", Unity engine ").append(unityVersion.isEmpty() ? "(not recorded)" : unityVersion);

            if (format < MIN_FORMAT || format > MAX_FORMAT) {
                return report.append("; type-hash parser supports formats 17–22 only").toString();
            }

            require(data, 9, "platform and type-table header");
            int platform = data.getInt();
            boolean hasTypeTrees = data.get() != 0;
            int typeCount = data.getInt();
            if (typeCount < 0 || typeCount > MAX_TYPES) {
                throw new ParseFailure("type count outside safety limit: " + typeCount);
            }

            List<String> shaderTypes = new ArrayList<>();
            List<String> computeShaderTypes = new ArrayList<>();
            for (int index = 0; index < typeCount; index++) {
                require(data, 7, "type entry " + index);
                int classId = data.getInt();
                data.get();       // stripped-type flag
                data.getShort();  // script type index

                if (classId == 114) requireAndSkip(data, 16, "MonoBehaviour script ID");

                require(data, 16, "type hash");
                byte[] hash = new byte[16];
                data.get(hash);

                boolean treeAvailable = false;
                if (hasTypeTrees) {
                    require(data, 8, "type-tree counts");
                    int nodeCount = data.getInt();
                    int stringBytes = data.getInt();
                    if (nodeCount < 0 || nodeCount > MAX_NODES) {
                        throw new ParseFailure("type-tree node count outside safety limit: " + nodeCount);
                    }
                    if (stringBytes < 0 || stringBytes > MAX_STRING_BYTES) {
                        throw new ParseFailure("type-tree string buffer outside safety limit: " + stringBytes);
                    }
                    long nodeBytes = (long) nodeCount * (format >= 19 ? 32L : 24L);
                    if (nodeBytes > Integer.MAX_VALUE) {
                        throw new ParseFailure("type-tree node table is too large");
                    }
                    requireAndSkip(data, (int) nodeBytes, "type-tree nodes");
                    requireAndSkip(data, stringBytes, "type-tree string buffer");
                    if (format >= 21) {
                        require(data, 4, "type dependency count");
                        int dependencyCount = data.getInt();
                        if (dependencyCount < 0 || dependencyCount > MAX_NODES) {
                            throw new ParseFailure("type dependency count outside safety limit: " + dependencyCount);
                        }
                        long dependencyBytes = (long) dependencyCount * 4L;
                        if (dependencyBytes > Integer.MAX_VALUE) {
                            throw new ParseFailure("type dependency table is too large");
                        }
                        requireAndSkip(data, (int) dependencyBytes, "type dependencies");
                    }
                    treeAvailable = nodeCount > 0;
                }

                String entry = "class " + classId + " hash=" + toHex(hash) +
                        (treeAvailable ? " (type tree present)" : " (type tree absent)");
                if (classId == 48) shaderTypes.add(entry);
                else if (classId == 72) computeShaderTypes.add(entry);
            }

            report.append(", target platform ").append(platform)
                    .append(", type trees ").append(hasTypeTrees ? "enabled" : "stripped")
                    .append(", type entries ").append(typeCount);
            appendTypeEntries(report, "Shader class 48", shaderTypes);
            appendTypeEntries(report, "ComputeShader class 72", computeShaderTypes);
            if (format >= 22 && fullSize >= 0) {
                report.append(", inspected prefix ").append(prefix.length)
                        .append(" / ").append(fullSize).append(" bytes");
            }
            return report.toString();
        } catch (ParseFailure | java.nio.BufferUnderflowException error) {
            return report.append("; partial type-table read: ").append(error.getMessage()).toString();
        }
    }

    private static void appendTypeEntries(StringBuilder out, String label, List<String> entries) {
        out.append("; ").append(label).append(": ");
        if (entries.isEmpty()) out.append("not listed");
        else {
            for (int i = 0; i < entries.size(); i++) {
                if (i > 0) out.append(" | ");
                out.append(entries.get(i));
            }
        }
    }

    private static String readCString(ByteBuffer data, int maxLength) throws ParseFailure {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < maxLength; i++) {
            require(data, 1, "engine-version string");
            int value = data.get() & 0xff;
            if (value == 0) return out.toString();
            if (value < 0x20 || value > 0x7e) {
                throw new ParseFailure("non-ASCII engine-version string");
            }
            out.append((char) value);
        }
        throw new ParseFailure("engine-version string exceeds safety limit");
    }

    private static void requireAndSkip(ByteBuffer data, int count, String what) throws ParseFailure {
        require(data, count, what);
        data.position(data.position() + count);
    }

    private static void require(ByteBuffer data, int count, String what) throws ParseFailure {
        if (count < 0 || data.remaining() < count) {
            throw new ParseFailure("truncated " + what + " (need " + count +
                    ", have " + data.remaining() + ")");
        }
    }

    private static int readInt(byte[] data, int offset, ByteOrder order) {
        return ByteBuffer.wrap(data).order(order).getInt(offset);
    }

    private static boolean startsWith(byte[] data, String value) {
        byte[] expected = value.getBytes(StandardCharsets.US_ASCII);
        if (data.length < expected.length) return false;
        for (int i = 0; i < expected.length; i++) {
            if (data[i] != expected[i]) return false;
        }
        return true;
    }

    private static String toHex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        return out.toString();
    }

    private static final class ParseFailure extends Exception {
        ParseFailure(String message) { super(message); }
    }
}
