package com.clustertruck.probe;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * A small Android 11+ metadata probe for a user's own Clustertruck Switch dump.
 * It does not decrypt, extract, patch or run game content.
 */
public final class MainActivity extends Activity {
    private static final int REQUEST_PACKAGE = 1001;
    private static final int REQUEST_FOLDER = 1002;
    private static final int MAX_PFS0_ENTRIES = 8192;
    private static final int MAX_STRING_TABLE = 4 * 1024 * 1024;
    private static final int MAX_FILES = 100_000;
    private static final long MAX_TEXT_SCAN_BYTES = 128L * 1024L * 1024L;
    private static final int MAX_TEXT_SAMPLE = 2 * 1024 * 1024;

    private TextView reportView;
    private Button packageButton;
    private Button folderButton;
    private Button shareButton;
    private Button copyButton;
    private String lastReport = "";
    private volatile boolean scanning;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
    }

    private void buildUi() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(16), dp(16), dp(12));

        TextView title = new TextView(this);
        title.setText("Clustertruck Switch Dump Probe");
        title.setTextSize(21);
        title.setPadding(0, 0, 0, dp(8));
        page.addView(title);

        TextView explanation = new TextView(this);
        explanation.setText(
                "Inspect your own .NSP / .XCI file, or an already-extracted Switch game folder. " +
                "This tool only reads metadata; it does not decrypt or extract game contents."
        );
        explanation.setTextSize(14);
        explanation.setPadding(0, 0, 0, dp(12));
        page.addView(explanation);

        packageButton = new Button(this);
        packageButton.setText("Inspect .NSP / .XCI file");
        packageButton.setOnClickListener(v -> pickPackage());
        page.addView(packageButton);

        folderButton = new Button(this);
        folderButton.setText("Inspect extracted game folder");
        folderButton.setOnClickListener(v -> pickFolder());
        page.addView(folderButton);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        shareButton = new Button(this);
        shareButton.setText("Share report");
        shareButton.setEnabled(false);
        shareButton.setOnClickListener(v -> shareReport());
        actions.addView(shareButton, new LinearLayout.LayoutParams(0, -2, 1));

        copyButton = new Button(this);
        copyButton.setText("Copy report");
        copyButton.setEnabled(false);
        copyButton.setOnClickListener(v -> copyReport());
        actions.addView(copyButton, new LinearLayout.LayoutParams(0, -2, 1));
        page.addView(actions);

        ScrollView scroll = new ScrollView(this);
        reportView = new TextView(this);
        reportView.setTextSize(12);
        reportView.setTypeface(android.graphics.Typeface.MONOSPACE);
        reportView.setTextIsSelectable(true);
        reportView.setPadding(0, dp(12), 0, dp(12));
        reportView.setText("No report yet.\n\nChoose a package file or an extracted game folder.");
        scroll.addView(reportView);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(page);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void pickPackage() {
        if (scanning) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_PACKAGE);
    }

    private void pickFolder() {
        if (scanning) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        startActivityForResult(intent, REQUEST_FOLDER);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQUEST_PACKAGE) {
            inspectAsync("Inspecting package metadata…", () -> inspectPackage(uri));
        } else if (requestCode == REQUEST_FOLDER) {
            inspectAsync("Scanning extracted game folder…", () -> inspectFolder(uri));
        }
    }

    private interface Inspection {
        String run() throws Exception;
    }

    private void inspectAsync(String status, Inspection inspection) {
        if (scanning) return;
        scanning = true;
        setButtonsEnabled(false);
        shareButton.setEnabled(false);
        copyButton.setEnabled(false);
        lastReport = "";
        reportView.setText(status);
        new Thread(() -> {
            String result;
            try {
                result = inspection.run();
            } catch (Exception error) {
                result = "Inspection failed\n" + error.getClass().getSimpleName() + ": " + error.getMessage();
            }
            final String finalResult = result;
            runOnUiThread(() -> {
                scanning = false;
                lastReport = finalResult;
                reportView.setText(finalResult);
                shareButton.setEnabled(!lastReport.isEmpty());
                copyButton.setEnabled(!lastReport.isEmpty());
                setButtonsEnabled(true);
            });
        }, "clustertruck-dump-probe").start();
    }

    private void setButtonsEnabled(boolean enabled) {
        packageButton.setEnabled(enabled);
        folderButton.setEnabled(enabled);
    }

    private String inspectPackage(Uri uri) throws IOException {
        String name = displayName(uri);
        long size = fileSize(uri);
        StringBuilder out = new StringBuilder();
        out.append("CLUSTERTRUCK SWITCH DUMP — PACKAGE REPORT\n");
        out.append("Created: ").append(timestamp()).append('\n');
        out.append("Name: ").append(name).append('\n');
        out.append("Size: ").append(size >= 0 ? size + " bytes" : "unknown").append('\n');

        ContentResolver resolver = getContentResolver();
        try (InputStream raw = resolver.openInputStream(uri)) {
            if (raw == null) throw new IOException("Android could not open this file provider stream");
            byte[] magic = readExactly(raw, 4);
            String firstFour = ascii(magic);
            if ("PFS0".equals(firstFour)) {
                out.append("Container: PFS0 package (commonly used by NSP files)\n");
                appendPfs0Report(out, raw, size);
            } else if ("NSO0".equals(firstFour)) {
                out.append("Container: standalone Nintendo Switch NSO executable\n");
                byte[] header = new byte[0x80];
                System.arraycopy(magic, 0, header, 0, 4);
                readFully(raw, header, 4, header.length - 4);
                String nsoInfo = describeNsoHeader(header, name);
                if (nsoInfo != null) out.append(nsoInfo).append('\n');
            } else {
                byte[] tail = readAtMost(raw, 0x200);
                byte[] all = new byte[4 + tail.length];
                System.arraycopy(magic, 0, all, 0, 4);
                System.arraycopy(tail, 0, all, 4, tail.length);
                if (all.length >= 7 && asciiAt(all, 0, 7).equals("UnityFS")) {
                    out.append("Container: UnityFS asset bundle\n");
                } else if (all.length >= 0x104 && asciiAt(all, 0x100, 4).equals("HEAD")) {
                    out.append("Container: XCI cartridge image (HEAD signature found at 0x100)\n");
                    out.append("XCI partition contents were not unpacked or decrypted.\n");
                } else if (all.length >= 0x204 &&
                        (asciiAt(all, 0x200, 4).equals("NCA2") || asciiAt(all, 0x200, 4).equals("NCA3"))) {
                    out.append("Container: NCA content archive (header signature visible at 0x200)\n");
                    out.append("NCA contents were not decrypted or extracted.\n");
                } else {
                    out.append("Container: unknown (first bytes: ").append(hex(all, Math.min(all.length, 32))).append(")\n");
                    out.append("No package contents were extracted.\n");
                }
            }
        }
        out.append("\nImportant: this app does not decrypt or extract NSP/XCI/NCA files. It only reports visible container metadata.\n");
        return out.toString();
    }

    private void appendPfs0Report(StringBuilder out, InputStream input, long packageSize) throws IOException {
        byte[] rest = readExactly(input, 12);
        int fileCount = littleInt(rest, 0);
        long stringSizeLong = littleUnsignedInt(rest, 4);
        if (fileCount < 0 || fileCount > MAX_PFS0_ENTRIES) {
            throw new IOException("PFS0 entry count is outside the safe limit: " + fileCount);
        }
        if (stringSizeLong > MAX_STRING_TABLE) {
            throw new IOException("PFS0 string table is too large: " + stringSizeLong + " bytes");
        }
        int stringSize = (int) stringSizeLong;
        long tableSizeLong = (long) fileCount * 0x18L;
        long dataStart = 0x10L + tableSizeLong + stringSizeLong;
        if (packageSize >= 0 && dataStart > packageSize) {
            throw new IOException("PFS0 directory table exceeds the selected file size");
        }
        byte[] table = readExactly(input, (int) tableSizeLong);
        byte[] strings = readExactly(input, stringSize);
        out.append("PFS0 entries: ").append(fileCount).append('\n');
        out.append("PFS0 data section offset: 0x").append(Long.toHexString(dataStart)).append('\n');

        ByteBuffer entries = ByteBuffer.wrap(table).order(ByteOrder.LITTLE_ENDIAN);
        int displayed = Math.min(fileCount, 200);
        for (int i = 0; i < displayed; i++) {
            long offset = entries.getLong();
            long length = entries.getLong();
            int nameOffset = entries.getInt();
            entries.getInt(); // reserved
            if (nameOffset < 0 || nameOffset >= strings.length) {
                out.append("  [").append(i).append("] invalid name offset\n");
                continue;
            }
            int end = nameOffset;
            while (end < strings.length && strings[end] != 0) end++;
            if (end == strings.length) {
                out.append("  [").append(i).append("] unterminated name\n");
                continue;
            }
            String entryName = new String(strings, nameOffset, end - nameOffset, StandardCharsets.UTF_8);
            if (offset < 0 || length < 0 || dataStart + offset < 0 ||
                    (packageSize >= 0 && (dataStart + offset > packageSize || length > packageSize - (dataStart + offset)))) {
                out.append("  ").append(entryName).append(" — invalid range\n");
                continue;
            }
            out.append("  ").append(entryName).append(" — ").append(length).append(" bytes\n");
        }
        if (fileCount > displayed) out.append("  … ").append(fileCount - displayed).append(" more entries omitted\n");
        out.append("Only the PFS0 directory and filenames were read; payload files were not extracted.\n");
    }

    private String inspectFolder(Uri treeUri) throws IOException {
        String treeId = DocumentsContract.getTreeDocumentId(treeUri);
        if (treeId == null) throw new IOException("Android did not return a folder document ID");
        FolderScan scan = new FolderScan();
        scanChildren(treeUri, treeId, "", 0, scan);

        StringBuilder out = new StringBuilder();
        out.append("CLUSTERTRUCK SWITCH DUMP — EXTRACTED FOLDER REPORT\n");
        out.append("Created: ").append(timestamp()).append('\n');
        out.append("Files seen: ").append(scan.filesSeen).append(scan.truncated ? " (scan limit reached)" : "").append('\n');
        out.append("NSO executables found: ").append(scan.nsoCount).append('\n');
        out.append("Text bytes scanned: ").append(scan.textBytes).append('\n');
        out.append("Files skipped by text-scan size/budget limits: ").append(scan.skippedTextFiles).append('\n');

        if (scan.nsoReports.isEmpty()) {
            out.append("\nNo NSO0 executable header was found in the scanned files.\n");
        } else {
            out.append("\nNSO module headers:\n");
            for (String item : scan.nsoReports) out.append("  ").append(item).append('\n');
        }
        if (scan.npdmReport != null) out.append("\n").append(scan.npdmReport).append('\n');

        out.append("\nUnity filename evidence:\n");
        if (scan.pathEvidence.isEmpty()) out.append("  none found\n");
        else for (String item : scan.pathEvidence) out.append("  ").append(item).append('\n');

        out.append("\nUnity / runtime strings in sampled files:\n");
        if (scan.textEvidence.isEmpty()) out.append("  none found\n");
        else for (String item : scan.textEvidence) out.append("  ").append(item).append('\n');

        boolean il2cpp = scan.hasIl2cpp;
        boolean mono = scan.hasMono;
        out.append("\nScripting backend assessment: ");
        if (il2cpp && mono) out.append("ambiguous; both IL2CPP and Mono-related markers found\n");
        else if (il2cpp) out.append("IL2CPP-related evidence found; confirm against the Switch executable\n");
        else if (mono) out.append("Mono/.NET-related evidence found; confirm against the Switch executable\n");
        else if (!scan.pathEvidence.isEmpty() || !scan.textEvidence.isEmpty())
            out.append("Unity-related evidence found, but backend is undetermined\n");
        else out.append("undetermined; no reliable Unity backend marker found\n");

        out.append("\nNotes:\n");
        out.append("- NSO build IDs and Unity markers are diagnostic evidence, not proof of compatibility with LASRecomp.\n");
        out.append("- The earlier Unity WebGL scan describes a different build target; this report inspects the selected Switch files.\n");
        out.append("- No game file was modified, unpacked, patched or decrypted by this scan.\n");
        return out.toString();
    }

    private static final class FolderScan {
        int filesSeen;
        int nsoCount;
        boolean truncated;
        long textBytes;
        int skippedTextFiles;
        boolean hasIl2cpp;
        boolean hasMono;
        String npdmReport;
        final List<String> nsoReports = new ArrayList<>();
        final List<String> pathEvidence = new ArrayList<>();
        final List<String> textEvidence = new ArrayList<>();
    }

    private void scanChildren(Uri treeUri, String parentId, String prefix, int depth, FolderScan scan) throws IOException {
        if (scan.truncated || depth > 8) return;
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId);
        String[] projection = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
        };
        try (Cursor cursor = getContentResolver().query(children, projection, null, null, null)) {
            if (cursor == null) throw new IOException("Android could not list this folder");
            int idColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int typeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
            int sizeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE);
            if (idColumn < 0 || nameColumn < 0 || typeColumn < 0) {
                throw new IOException("Folder provider returned incomplete document metadata");
            }
            while (cursor.moveToNext()) {
                if (scan.filesSeen >= MAX_FILES) {
                    scan.truncated = true;
                    break;
                }
                String id = cursor.getString(idColumn);
                String name = cursor.getString(nameColumn);
                String mime = cursor.getString(typeColumn);
                long size = sizeColumn >= 0 && !cursor.isNull(sizeColumn) ? cursor.getLong(sizeColumn) : -1;
                if (name == null) name = "(unnamed)";
                String path = prefix.isEmpty() ? name : prefix + "/" + name;
                Uri doc = DocumentsContract.buildDocumentUriUsingTree(treeUri, id);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    scanChildren(treeUri, id, path, depth + 1, scan);
                    if (scan.truncated) break;
                } else {
                    scan.filesSeen++;
                    inspectFolderFile(doc, path, size, scan);
                }
            }
        } catch (SecurityException error) {
            throw new IOException("Android denied access to part of the selected folder", error);
        }
    }

    private void inspectFolderFile(Uri uri, String path, long size, FolderScan scan) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.contains("globalgamemanagers")) addPathEvidence(scan, path, "Unity global game manager");
        if (lower.contains("boot.config")) addPathEvidence(scan, path, "Unity boot configuration");
        if (lower.contains("resources.assets")) addPathEvidence(scan, path, "Unity resources asset");
        if (lower.contains("sharedassets")) addPathEvidence(scan, path, "Unity shared assets");
        if (lower.contains("global-metadata.dat") || lower.contains("il2cpp_data") || lower.contains("libil2cpp") ||
                lower.contains("gameassembly")) {
            addPathEvidence(scan, path, "IL2CPP-related path marker");
            scan.hasIl2cpp = true;
        }
        if (lower.contains("assembly-csharp") || lower.contains("mscorlib") || lower.contains("mono-2.0")) {
            addPathEvidence(scan, path, "Mono/.NET-related path marker");
            scan.hasMono = true;
        }
        if (lower.contains("unityplayer") || lower.contains("unityfs") || lower.contains("data.unity3d")) {
            addPathEvidence(scan, path, "Unity runtime/archive path marker");
        }

        String basename = lower.substring(lower.lastIndexOf('/') + 1);
        if ("main".equals(basename) || basename.endsWith(".nso") || basename.startsWith("subsdk")) {
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input != null) {
                    byte[] header = readAtMost(input, 0x80);
                    String info = describeNsoHeader(header, path);
                    if (info != null) {
                        scan.nsoCount++;
                        if (scan.nsoReports.size() < 64) scan.nsoReports.add(info);
                    } else if ("main".equals(basename) && scan.nsoReports.size() < 64) {
                        scan.nsoReports.add(path + " — file named main, but no NSO0 header");
                    }
                }
            } catch (Exception error) {
                if (scan.nsoReports.size() < 64) scan.nsoReports.add(path + " — read error: " + error.getMessage());
            }
        }

        if ("main.npdm".equals(basename) && scan.npdmReport == null) {
            try {
                scan.npdmReport = path + " — " + size + " bytes, SHA-256 " + sha256(uri);
            } catch (Exception error) {
                scan.npdmReport = path + " — SHA-256 unavailable: " + error.getMessage();
            }
        }

        boolean textCandidate = basename.endsWith(".assets") || basename.endsWith(".resource") ||
                basename.endsWith(".bundle") || basename.endsWith(".dat") || basename.endsWith(".dll") ||
                basename.endsWith(".config") || basename.equals("main") || basename.equals("boot.config");
        if (textCandidate && size >= 0 && size <= MAX_TEXT_SAMPLE &&
                scan.textBytes + size <= MAX_TEXT_SCAN_BYTES) {
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input != null) {
                    byte[] sample = readAtMost(input, (int) Math.min(size, MAX_TEXT_SAMPLE));
                    scan.textBytes += sample.length;
                    collectTextMarkers(scan, path, sample);
                }
            } catch (Exception ignored) {
                // A provider can reject a secondary read; the path/header evidence is still retained.
            }
        } else if (textCandidate) {
            scan.skippedTextFiles++;
        }
    }

    private static void addPathEvidence(FolderScan scan, String path, String marker) {
        if (scan.pathEvidence.size() < 100) scan.pathEvidence.add(path + " — " + marker);
    }

    private static void collectTextMarkers(FolderScan scan, String path, byte[] data) {
        String sample = new String(data, StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
        StringBuilder markers = new StringBuilder();
        String[] tokens = {"unityplayer", "unityengine", "unityfs", "globalgamemanagers",
                "il2cpp", "assembly-csharp", "mono-2.0", "libnn_gfx", "nvn"};
        for (String token : tokens) {
            if (sample.contains(token)) {
                if (markers.length() > 0) markers.append(", ");
                markers.append(token);
                if (token.contains("il2cpp")) scan.hasIl2cpp = true;
                if (token.contains("assembly-csharp") || token.contains("mono-2.0")) scan.hasMono = true;
            }
        }
        if (markers.length() > 0 && scan.textEvidence.size() < 100)
            scan.textEvidence.add(path + " — " + markers);
    }

    private static String describeNsoHeader(byte[] header, String path) {
        if (header.length < 0x80 || !asciiAt(header, 0, 4).equals("NSO0")) return null;
        ByteBuffer data = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        int flags = data.getInt(0x0c);
        int bss = data.getInt(0x3c);
        String buildId = hexRange(header, 0x40, 0x20);
        StringBuilder out = new StringBuilder(path).append(" — NSO0, build ID ").append(buildId);
        out.append(", flags 0x").append(Integer.toHexString(flags));
        out.append(", BSS ").append(Integer.toUnsignedLong(bss)).append(" bytes");
        String[] names = {"text", "rodata", "data"};
        for (int i = 0; i < 3; i++) {
            int position = 0x10 + i * 0x10;
            long fileOffset = Integer.toUnsignedLong(data.getInt(position));
            long memoryOffset = Integer.toUnsignedLong(data.getInt(position + 4));
            long decompressedSize = Integer.toUnsignedLong(data.getInt(position + 8));
            out.append(", ").append(names[i]).append(" @0x").append(Long.toHexString(memoryOffset))
                    .append(" + ").append(decompressedSize).append(" bytes");
            if (fileOffset < 0) return path + " — invalid NSO segment header";
        }
        return out.toString();
    }

    private String sha256(Uri uri) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("file could not be opened");
            byte[] buffer = new byte[64 * 1024];
            for (int count; (count = input.read(buffer)) >= 0; ) {
                if (count > 0) digest.update(buffer, 0, count);
            }
        }
        return hex(digest.digest(), 32);
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) return cursor.getString(index);
            }
        } catch (Exception ignored) { }
        return uri.getLastPathSegment() != null ? uri.getLastPathSegment() : "selected file";
    }

    private long fileSize(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (index >= 0 && !cursor.isNull(index)) return cursor.getLong(index);
            }
        } catch (Exception ignored) { }
        return -1;
    }

    private void shareReport() {
        if (lastReport.isEmpty()) return;
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, lastReport);
        startActivity(Intent.createChooser(send, "Share dump report"));
    }

    private void copyReport() {
        if (lastReport.isEmpty()) return;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("Clustertruck Switch dump report", lastReport));
            reportView.setText(lastReport + "\n\nReport copied to clipboard.");
        }
    }

    private static String timestamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date());
    }

    private static String ascii(byte[] bytes) {
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    private static String asciiAt(byte[] bytes, int offset, int length) {
        if (offset < 0 || offset + length > bytes.length) return "";
        return new String(bytes, offset, length, StandardCharsets.US_ASCII);
    }

    private static byte[] readExactly(InputStream input, int length) throws IOException {
        byte[] out = new byte[length];
        readFully(input, out, 0, length);
        return out;
    }

    private static void readFully(InputStream input, byte[] out, int offset, int length) throws IOException {
        int done = 0;
        while (done < length) {
            int count = input.read(out, offset + done, length - done);
            if (count < 0) throw new IOException("unexpected end of file");
            if (count == 0) continue;
            done += count;
        }
    }

    private static byte[] readAtMost(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(limit, 8192));
        byte[] buffer = new byte[8192];
        while (out.size() < limit) {
            int count = input.read(buffer, 0, Math.min(buffer.length, limit - out.size()));
            if (count < 0) break;
            if (count == 0) continue;
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }

    private static void skipFully(InputStream input, long count) throws IOException {
        long remaining = count;
        while (remaining > 0) {
            long skipped = input.skip(remaining);
            if (skipped > 0) remaining -= skipped;
            else {
                if (input.read() < 0) throw new IOException("unexpected end of file");
                remaining--;
            }
        }
    }

    private static int littleInt(byte[] bytes, int offset) {
        return ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private static long littleUnsignedInt(byte[] bytes, int offset) {
        return Integer.toUnsignedLong(littleInt(bytes, offset));
    }

    private static String hexRange(byte[] bytes, int offset, int length) {
        byte[] part = new byte[length];
        System.arraycopy(bytes, offset, part, 0, length);
        return hex(part, length);
    }

    private static String hex(byte[] bytes, int length) {
        StringBuilder result = new StringBuilder(length * 2);
        for (int i = 0; i < length && i < bytes.length; i++) {
            result.append(String.format(Locale.ROOT, "%02x", bytes[i] & 0xff));
        }
        return result.toString();
    }
}
