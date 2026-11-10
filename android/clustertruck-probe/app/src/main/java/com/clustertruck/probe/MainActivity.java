package com.clustertruck.probe;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.Toast;
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
    private TextView reportView;
    private TextView statusView;
    private Button playButton;
    private Button packageButton;
    private Button settingsButton;
    private Button shareButton;
    private Button copyButton;
    private String lastReport = "";
    private volatile boolean scanning;
    private boolean packageSelected;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
    }

    private void buildUi() {
        getWindow().setStatusBarColor(Color.rgb(10, 13, 19));
        getWindow().setNavigationBarColor(Color.rgb(10, 13, 19));
        getWindow().getDecorView().setSystemUiVisibility(0);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18), dp(12), dp(18), dp(12));
        page.setBackgroundColor(Color.rgb(10, 13, 19));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setOrientation(LinearLayout.HORIZONTAL);
        TextView mark = makeText("CT", 16, Color.rgb(10, 13, 19), true);
        mark.setGravity(Gravity.CENTER);
        mark.setBackground(roundRect(Color.rgb(255, 174, 55), dp(12)));
        header.addView(mark, new LinearLayout.LayoutParams(dp(42), dp(42)));
        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);
        heading.setPadding(dp(11), 0, 0, 0);
        TextView appTitle = makeText("CLUSTERTRUCK", 17, Color.WHITE, true);
        TextView appSub = makeText("ANDROID RECOMP  •  DEVELOPMENT BUILD", 9, Color.rgb(145, 157, 174), true);
        heading.addView(appTitle);
        heading.addView(appSub);
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        TextView version = makeText("DEV 0.1", 10, Color.rgb(255, 190, 100), true);
        version.setPadding(dp(10), dp(7), dp(10), dp(7));
        version.setBackground(roundRect(Color.rgb(42, 34, 25), dp(9)));
        header.addView(version);
        page.addView(header);

        View line = new View(this);
        line.setBackgroundColor(Color.rgb(36, 44, 56));
        LinearLayout.LayoutParams lineParams = new LinearLayout.LayoutParams(-1, dp(1));
        lineParams.topMargin = dp(10);
        lineParams.bottomMargin = dp(12);
        page.addView(line, lineParams);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.HORIZONTAL);
        content.setGravity(Gravity.FILL);
        page.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setPadding(dp(16), dp(15), dp(16), dp(14));
        left.setBackground(roundRect(Color.rgb(22, 28, 38), dp(18)));
        content.addView(left, new LinearLayout.LayoutParams(0, -1, 0.43f));

        TextView eyebrow = makeText("HIGH SPEED  /  PRECISION PLATFORMER", 9, Color.rgb(255, 183, 79), true);
        left.addView(eyebrow);
        TextView heroTitle = makeText("READY TO\nRUN?", 30, Color.WHITE, true);
        heroTitle.setLineSpacing(0, 0.92f);
        LinearLayout.LayoutParams heroParams = new LinearLayout.LayoutParams(-1, -2);
        heroParams.topMargin = dp(9);
        left.addView(heroTitle, heroParams);
        TextView heroDesc = makeText("Your Clustertruck Android project hub. Import a dump to inspect its visible package metadata.", 12, Color.rgb(177, 188, 203), false);
        heroDesc.setLineSpacing(dp(2), 1.0f);
        LinearLayout.LayoutParams descParams = new LinearLayout.LayoutParams(-1, 0, 1);
        descParams.topMargin = dp(8);
        left.addView(heroDesc, descParams);

        playButton = new Button(this);
        playButton.setText("▶   PLAY");
        playButton.setTextSize(15);
        playButton.setTextColor(Color.rgb(20, 22, 25));
        playButton.setAllCaps(false);
        playButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        playButton.setBackground(roundRect(Color.rgb(66, 73, 83), dp(13)));
        playButton.setEnabled(false);
        playButton.setOnClickListener(v -> Toast.makeText(this,
                "Package selected! The Clustertruck game runtime is still being developed, so gameplay is not available yet.",
                Toast.LENGTH_LONG).show());
        LinearLayout.LayoutParams playParams = new LinearLayout.LayoutParams(-1, dp(48));
        playParams.topMargin = dp(10);
        left.addView(playButton, playParams);

        statusView = makeText("●  RUNTIME NOT READY  •  DUMP INSPECTOR ACTIVE", 9, Color.rgb(255, 190, 100), true);
        statusView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
        statusParams.topMargin = dp(8);
        left.addView(statusView, statusParams);

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setPadding(dp(14), dp(12), dp(14), dp(10));
        right.setBackground(roundRect(Color.rgb(16, 21, 29), dp(18)));
        LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(0, -1, 0.57f);
        rightParams.leftMargin = dp(12);
        content.addView(right, rightParams);

        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        tools.setGravity(Gravity.CENTER_VERTICAL);
        TextView reportTitle = makeText("DUMP INSPECTOR", 13, Color.WHITE, true);
        tools.addView(reportTitle, new LinearLayout.LayoutParams(0, -2, 1));
        packageButton = new Button(this);
        styleSmallButton(packageButton, "OPEN FILE");
        packageButton.setOnClickListener(v -> pickPackage());
        tools.addView(packageButton);
        settingsButton = new Button(this);
        styleSmallButton(settingsButton, "⚙ SETTINGS");
        LinearLayout.LayoutParams settingsParams = new LinearLayout.LayoutParams(-2, dp(36));
        settingsParams.leftMargin = dp(6);
        settingsButton.setLayoutParams(settingsParams);
        settingsButton.setOnClickListener(v -> showToolsDialog());
        tools.addView(settingsButton);
        right.addView(tools);

        TextView reportHint = makeText("NSP / XCI DETAILS  •  FOLDER SCAN IN SETTINGS  •  READ-ONLY", 9, Color.rgb(129, 145, 164), true);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(-1, -2);
        hintParams.topMargin = dp(4);
        right.addView(reportHint, hintParams);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        reportView = makeText("NO REPORT YET\n\nChoose OPEN FILE to inspect your .NSP or .XCI package. Or open SETTINGS to scan an already-extracted Switch folder for NSO module headers and main.npdm metadata.\n\nThis is a read-only diagnostic build; the Clustertruck game runtime is not integrated yet.", 11, Color.rgb(185, 196, 210), false);
        reportView.setTypeface(Typeface.MONOSPACE);
        reportView.setTextIsSelectable(true);
        reportView.setPadding(dp(12), dp(12), dp(12), dp(12));
        reportView.setBackground(roundRect(Color.rgb(10, 14, 20), dp(12)));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(-1, 0, 1);
        scrollParams.topMargin = dp(10);
        right.addView(scroll, scrollParams);
        scroll.addView(reportView);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        shareButton = new Button(this);
        styleSmallButton(shareButton, "SHARE REPORT");
        shareButton.setEnabled(false);
        shareButton.setOnClickListener(v -> shareReport());
        actions.addView(shareButton, new LinearLayout.LayoutParams(0, dp(36), 1));
        copyButton = new Button(this);
        styleSmallButton(copyButton, "COPY REPORT");
        copyButton.setEnabled(false);
        copyButton.setOnClickListener(v -> copyReport());
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(0, dp(36), 1);
        copyParams.leftMargin = dp(7);
        actions.addView(copyButton, copyParams);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(-1, -2);
        actionsParams.topMargin = dp(8);
        right.addView(actions, actionsParams);

        TextView footer = makeText("CLUSTERTRUCK RECOMP  •  LANDSCAPE UI  •  ANDROID 11+", 8, Color.rgb(92, 105, 123), true);
        footer.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams footerParams = new LinearLayout.LayoutParams(-1, -2);
        footerParams.topMargin = dp(8);
        page.addView(footer, footerParams);
        setContentView(page);
        updatePlayButton();
    }

    private TextView makeText(String value, int size, int color, boolean bold) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        if (bold) text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return text;
    }

    private GradientDrawable roundRect(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private void updatePlayButton() {
        if (playButton == null) return;
        if (packageSelected) {
            playButton.setEnabled(true);
            playButton.setTextColor(Color.rgb(20, 22, 25));
            playButton.setBackground(roundRect(Color.rgb(255, 190, 55), dp(13)));
            statusView.setText("●  PACKAGE SELECTED  •  RUNTIME IN DEVELOPMENT");
            statusView.setTextColor(Color.rgb(255, 190, 100));
        } else {
            playButton.setEnabled(false);
            playButton.setTextColor(Color.rgb(160, 166, 174));
            playButton.setBackground(roundRect(Color.rgb(66, 73, 83), dp(13)));
            statusView.setText("●  NO PACKAGE SELECTED  •  PLAY LOCKED");
            statusView.setTextColor(Color.rgb(150, 160, 174));
        }
    }

    private void styleSmallButton(Button button, String label) {
        button.setText(label);
        button.setTextSize(9);
        button.setTextColor(Color.rgb(222, 231, 241));
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setMinWidth(0);
        button.setBackground(roundRect(Color.rgb(37, 47, 61), dp(9)));
        button.setLayoutParams(new LinearLayout.LayoutParams(-2, dp(36)));
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

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQUEST_PACKAGE) {
            inspectAsync("Inspecting package metadata…", () -> inspectPackage(uri));
        } else if (requestCode == REQUEST_FOLDER) {
            inspectAsync("Scanning the selected extracted folder…", () -> inspectFolder(uri));
        }
    }

    private void showToolsDialog() {
        if (scanning) return;
        CharSequence[] actions = {
                "Scan an already-extracted game folder",
                "Settings (not available yet)"
        };
        new AlertDialog.Builder(this)
                .setTitle("Development tools")
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) {
                        pickExtractedFolder();
                    } else {
                        Toast.makeText(this, "Settings are not available in this development build yet.",
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    private void pickExtractedFolder() {
        if (scanning) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_FOLDER);
    }

    private interface Inspection {
        String run() throws Exception;
    }

    private void inspectAsync(String status, Inspection inspection) {
        if (scanning) return;
        scanning = true;
        packageSelected = false;
        updatePlayButton();
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
                packageSelected = finalResult.contains("Container: XCI cartridge image") ||
                        finalResult.contains("Container: PFS0 package");
                updatePlayButton();
                setButtonsEnabled(true);
            });
        }, "clustertruck-dump-probe").start();
    }

    private void setButtonsEnabled(boolean enabled) {
        packageButton.setEnabled(enabled);
        settingsButton.setEnabled(enabled);
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
                if (all.length >= 0x104 && asciiAt(all, 0x100, 4).equals("HEAD")) {
                    out.append("Container: XCI cartridge image (HEAD signature found at 0x100)\n");
                    inspectXciPartitions(uri, size, all, out);
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

    private void inspectXciPartitions(Uri uri, long fileSize, byte[] header, StringBuilder out) throws IOException {
        if (header.length < 0x140) {
            out.append("XCI header is truncated; partition metadata unavailable.\n");
            return;
        }
        long hfsOffset = littleLong(header, 0x130);
        long hfsSize = littleLong(header, 0x138);
        out.append("Root HFS0 offset: 0x").append(Long.toHexString(hfsOffset)).append('\n');
        out.append("Root HFS0 header size: ").append(hfsSize).append(" bytes\n");
        if (hfsOffset < 0 || hfsSize < 0x10 || hfsOffset > fileSize ||
                hfsSize > fileSize - hfsOffset) {
            out.append("Root HFS0 range is outside the selected file; no partition listing possible.\n");
            return;
        }
        List<HfsEntry> partitions = readHfs0(uri, hfsOffset, fileSize, "root", out);
        if (partitions == null) return;
        for (HfsEntry partition : partitions) {
            String label = partition.name;
            if (!("secure".equals(label) || "normal".equals(label) ||
                    "update".equals(label) || "logo".equals(label))) continue;
            long partitionOffset = partition.absoluteOffset;
            out.append("\nPartition: ").append(label).append(" (")
                    .append(partition.size).append(" bytes)\n");
            if (partition.size < 0x10 || partitionOffset < 0 ||
                    partitionOffset > fileSize || partition.size > fileSize - partitionOffset) {
                out.append("  Invalid partition range; skipped.\n");
                continue;
            }
            List<HfsEntry> files = readHfs0(uri, partitionOffset, fileSize, label, out);
            if (files == null) continue;
            int shown = 0;
            for (HfsEntry entry : files) {
                if (shown++ >= 120) {
                    out.append("  … additional entries omitted\n");
                    break;
                }
                out.append("  ").append(entry.name).append(" — ")
                        .append(entry.size).append(" bytes");
                if (entry.signature != null) out.append(" [").append(entry.signature).append("]");
                out.append('\n');
            }
        }
        out.append("\nOnly XCI/HFS0 directory metadata and visible file signatures were read; NCA payloads were not decrypted or extracted.\n");
    }

    private static final class HfsEntry {
        final String name;
        final long size;
        final long absoluteOffset;
        final String signature;
        HfsEntry(String name, long size, long absoluteOffset, String signature) {
            this.name = name;
            this.size = size;
            this.absoluteOffset = absoluteOffset;
            this.signature = signature;
        }
    }

    private List<HfsEntry> readHfs0(Uri uri, long offset, long fileSize,
                                    String label, StringBuilder out) throws IOException {
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("Android could not reopen XCI stream");
            skipFully(input, offset);
            byte[] magic = readExactly(input, 4);
            if (!"HFS0".equals(ascii(magic))) {
                out.append("  ").append(label).append(": expected HFS0, found ")
                        .append(ascii(magic)).append('\n');
                return null;
            }
            byte[] rest = readExactly(input, 12);
            int count = littleInt(rest, 0);
            long stringSizeLong = littleUnsignedInt(rest, 4);
            if (count < 0 || count > MAX_PFS0_ENTRIES || stringSizeLong > MAX_STRING_TABLE) {
                out.append("  ").append(label).append(": HFS0 directory exceeds safe parser limits.\n");
                return null;
            }
            int stringSize = (int) stringSizeLong;
            long tableSize = (long) count * 0x40L;
            long dataStart = offset + 0x10L + tableSize + stringSizeLong;
            if (dataStart < offset || dataStart > fileSize ||
                    tableSize + stringSizeLong > fileSize - offset - 0x10L) {
                out.append("  ").append(label).append(": HFS0 directory extends beyond the XCI.\n");
                return null;
            }
            byte[] table = readExactly(input, (int) tableSize);
            byte[] strings = readExactly(input, stringSize);
            ByteBuffer entries = ByteBuffer.wrap(table).order(ByteOrder.LITTLE_ENDIAN);
            List<HfsEntry> result = new ArrayList<>();
            out.append("  ").append(label).append(" HFS0 entries: ").append(count).append('\n');
            for (int i = 0; i < count; i++) {
                long relativeOffset = entries.getLong();
                long length = entries.getLong();
                int nameOffset = entries.getInt();
                entries.getInt();
                entries.position(entries.position() + 40);
                if (nameOffset < 0 || nameOffset >= strings.length) continue;
                int end = nameOffset;
                while (end < strings.length && strings[end] != 0) end++;
                if (end == strings.length || relativeOffset < 0 || length < 0 ||
                        relativeOffset > fileSize - dataStart ||
                        length > fileSize - dataStart - relativeOffset) continue;
                String name = new String(strings, nameOffset, end - nameOffset, StandardCharsets.UTF_8);
                long absolute = dataStart + relativeOffset;
                String signature = null;
                if (length >= 4) {
                    try (InputStream item = getContentResolver().openInputStream(uri)) {
                        if (item != null) {
                            skipFully(item, absolute);
                            signature = ascii(readExactly(item, 4));
                        }
                    }
                }
                result.add(new HfsEntry(name, length, absolute, signature));
            }
            return result;
        }
    }

    private String inspectFolder(Uri treeUri) throws IOException {
        String treeId = DocumentsContract.getTreeDocumentId(treeUri);
        if (treeId == null) throw new IOException("Android did not return a folder document ID");
        FolderScan scan = new FolderScan();
        scanChildren(treeUri, treeId, "", 0, scan);

        StringBuilder out = new StringBuilder();
        out.append("CLUSTERTRUCK SWITCH DUMP — EXTRACTED FOLDER REPORT\n");
        out.append("Created: ").append(timestamp()).append('\n');
        out.append("Files seen: ").append(scan.filesSeen)
                .append(scan.truncated ? " (scan limit reached)" : "").append('\n');
        out.append("NSO executables found: ").append(scan.nsoCount).append('\n');

        if (scan.nsoReports.isEmpty()) {
            out.append("\nNo NSO0 executable header was found in the scanned files.\n");
        } else {
            out.append("\nSwitch executable module headers:\n");
            for (String item : scan.nsoReports) out.append("  ").append(item).append('\n');
        }
        if (scan.npdmReport != null) out.append("\nmain.npdm metadata:\n  ").append(scan.npdmReport).append('\n');

        out.append("\nNotes:\n");
        out.append("- The scan only reads Switch module headers and hashes main.npdm; it does not unpack or modify game files.\n");
        out.append("- NSO headers and build IDs identify executable inputs but do not prove the generic lifter supports every instruction.\n");
        out.append("- No executable payload was uploaded or included in this report.\n");
        return out.toString();
    }

    private static final class FolderScan {
        int filesSeen;
        int nsoCount;
        boolean truncated;
        String npdmReport;
        final List<String> nsoReports = new ArrayList<>();
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
        String basename = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        boolean moduleCandidate = "main".equals(basename) || "sdk".equals(basename) ||
                "rtld".equals(basename) || basename.startsWith("subsdk") || basename.endsWith(".nso");

        if (moduleCandidate) {
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input != null) {
                    String info = NsoInspector.inspect(input, path, size);
                    if (info != null) {
                        scan.nsoCount++;
                        if (scan.nsoReports.size() < 64) scan.nsoReports.add(info);
                    } else if ("main".equals(basename) || "sdk".equals(basename)) {
                        if (scan.nsoReports.size() < 64)
                            scan.nsoReports.add(path + " — expected Switch NSO0 header was not found");
                    }
                }
            } catch (Exception error) {
                if (scan.nsoReports.size() < 64)
                    scan.nsoReports.add(path + " — read error: " + error.getMessage());
            }
        }

        if ("main.npdm".equals(basename) && scan.npdmReport == null) {
            try {
                scan.npdmReport = path + " — " + size + " bytes, SHA-256 " + sha256(uri);
            } catch (Exception error) {
                scan.npdmReport = path + " — SHA-256 unavailable: " + error.getMessage();
            }
        }
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

    private static long littleLong(byte[] bytes, int offset) {
        return ByteBuffer.wrap(bytes, offset, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
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
