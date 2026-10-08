import com.lonx.audiotag.internal.AudioTagNative;
import com.lonx.audiotag.model.AudioProperties;
import com.lonx.audiotag.model.Metadata;
import com.lonx.audiotag.model.Picture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

/**
 * Native-layer smoke harness for the Windows port (front gate of PLAN.md phase 1).
 *
 * <p>It is deliberately Gradle-free and Kotlin-free: it loads the freshly built DLLs with a plain
 * desktop JVM, then exercises the real JNI surface — read audio properties, read metadata, write
 * tags, write and read back a cover picture — against copies of TagLib's own test fixtures.
 *
 * <p>What it proves that nothing else does:
 * <ul>
 *   <li>the MSVC-built DLLs resolve and load under HotSpot (no missing CRT / arch mismatch);
 *   <li>{@code JNI_OnLoad} finds every class/method it caches — i.e. the Java/Kotlin model contract
 *       that the native code hard-codes actually exists;
 *   <li>the fd→path ABI change works: TagLib opens by UTF-8 path, including paths with non-ASCII
 *       segments, which is the Windows wide-char conversion path;
 *   <li>TagLib can actually <em>write</em> tags through that path, not just read them.
 * </ul>
 *
 * <p>Usage: {@code NativeSmoke <libDir> <fixturesDir> <workDir> [fixture...]}
 * Exits 0 only when every executed check passed.
 */
public final class NativeSmoke {

    private static final List<String> FAILURES = new ArrayList<>();
    private static int checks;
    private static int skipped;

    /** Fixtures where TagLib can store a front cover; other formats are read-only for this check. */
    private static final List<String> PICTURE_CAPABLE = Arrays.asList("flac", "mp3", "m4a");

    /** Smallest valid PNG (1x1, fully transparent) — used as cover art payload. */
    private static final byte[] TINY_PNG = new byte[] {
            (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 'I', 'H', 'D', 'R',
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, (byte) 0xC4,
            (byte) 0x89, 0x00, 0x00, 0x00, 0x0A, 'I', 'D', 'A', 'T',
            0x78, (byte) 0x9C, 0x63, 0x00, 0x01, 0x00, 0x00, 0x05,
            0x00, 0x01, 0x0D, 0x0A, 0x2D, (byte) 0xB4, 0x00, 0x00,
            0x00, 0x00, 'I', 'E', 'N', 'D', (byte) 0xAE, 0x42, 0x60, (byte) 0x82
    };

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: NativeSmoke <libDir> <fixturesDir> <workDir> [fixture...]");
            System.exit(2);
        }

        Path libDir = Paths.get(args[0]).toAbsolutePath();
        Path fixturesDir = Paths.get(args[1]).toAbsolutePath();
        Path workDir = Paths.get(args[2]).toAbsolutePath();

        System.out.println("jvm        : " + System.getProperty("java.version")
                + " (" + System.getProperty("os.arch") + ")");
        System.out.println("libDir     : " + libDir);
        System.out.println("fixturesDir: " + fixturesDir);
        System.out.println("workDir    : " + workDir);
        System.out.println();

        loadLibraries(libDir);

        // A directory with non-ASCII segments exercises the UTF-8 -> wide FileName conversion.
        Path cjkDir = workDir.resolve("中文 目录 ünïcode");
        Files.createDirectories(cjkDir);

        checkNegativePath(libDir);

        List<String> fixtures = new ArrayList<>();
        for (int i = 3; i < args.length; i++) {
            fixtures.add(args[i]);
        }

        for (String fixture : fixtures) {
            Path source = fixturesDir.resolve(fixture);
            if (!Files.isRegularFile(source)) {
                skipped++;
                System.out.println("[SKIP] " + fixture + " — fixture not found at " + source);
                continue;
            }
            // Alternate between a plain and a non-ASCII working directory so both code paths run.
            Path target = (fixtures.size() > 1 && fixture.hashCode() % 2 != 0 ? workDir : cjkDir)
                    .resolve("copy-" + fixture);
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            exercise(fixture, target);
        }

        System.out.println();
        System.out.println("checks=" + checks + " skipped=" + skipped + " failures=" + FAILURES.size());
        if (FAILURES.isEmpty()) {
            System.out.println("RESULT: PASS");
        } else {
            System.out.println("RESULT: FAIL");
            for (String failure : FAILURES) {
                System.out.println("  - " + failure);
            }
            System.exit(1);
        }
    }

    private static void loadLibraries(Path libDir) {
        // taglib.dll is the interesting one: its JNI_OnLoad resolves every cached class and method,
        // so a successful load already proves the Java/Kotlin model contract is satisfied.
        for (String name : new String[] {"taglib", "ebur128", "quickjs-ng"}) {
            Path dll = libDir.resolve(name + ".dll");
            try {
                System.load(dll.toString());
                System.out.println("[ OK ] System.load " + dll.getFileName());
                checks++;
            } catch (UnsatisfiedLinkError e) {
                fail("System.load " + dll.getFileName() + " -> " + e.getMessage());
            }
        }
        System.out.println();
    }

    /** A missing file must be reported, never crash the JVM: that is the ABI's error contract. */
    private static void checkNegativePath(Path libDir) {
        Path missing = libDir.resolve("definitely-missing-" + 12345 + ".flac");
        try {
            AudioProperties props = AudioTagNative.getAudioProperties(missing.toString(), 1);
            checks++;
            System.out.println("[info] missing-file getAudioProperties -> " + props);
            Metadata md = AudioTagNative.getMetadata(missing.toString(), true);
            checks++;
            System.out.println("[info] missing-file getMetadata -> " + md);
        } catch (Throwable t) {
            fail("missing file crashed the native layer: " + t);
        }
        System.out.println();
    }

    private static void exercise(String fixture, Path file) throws IOException {
        System.out.println("=== " + fixture + " ===");
        System.out.println("    path: " + file);

        String path = file.toString();
        String ext = extension(fixture);

        AudioProperties before = AudioTagNative.getAudioProperties(path, 1);
        check(fixture + ": getAudioProperties non-null", before != null, "got null");
        System.out.println("    properties: " + before);

        Metadata read = AudioTagNative.getMetadata(path, true);
        check(fixture + ": getMetadata non-null", read != null, "got null");
        if (read != null) {
            System.out.println("    metadata:" + read.dump());
            System.out.println("    supportsTypedPictures=" + read.supportsTypedPictures);
        }

        // ---- write round-trip -------------------------------------------------
        HashMap<String, String[]> updates = new HashMap<>();
        String title = "Lyrico 冒烟测试 ✓ " + ext;
        String artist = "Lonx";
        String lyrics = "第一行\n第二行\nline three";
        updates.put("TITLE", new String[] {title});
        updates.put("ARTIST", new String[] {artist});
        updates.put("ALBUM", new String[] {"Windows Port"});
        updates.put("LYRICS", new String[] {lyrics});

        boolean saved = AudioTagNative.savePropertyMap(path, updates);
        check(fixture + ": savePropertyMap returned true", saved, "returned false");

        Metadata after = AudioTagNative.getMetadata(path, false);
        check(fixture + ": getMetadata after save non-null", after != null, "got null");
        if (after != null) {
            checkString(fixture + ": TITLE round-trip", title, first(after.propertyMap.get("TITLE")));
            checkString(fixture + ": ARTIST round-trip", artist, first(after.propertyMap.get("ARTIST")));
            checkString(fixture + ": ALBUM round-trip", "Windows Port", first(after.propertyMap.get("ALBUM")));
            check(fixture + ": LYRICS present", after.propertyMap.containsKey("LYRICS"),
                    "missing; keys=" + after.propertyMap.keySet());
        }

        String[] single = AudioTagNative.getMetadataPropertyValues(path, "TITLE");
        check(fixture + ": getMetadataPropertyValues(TITLE)",
                single != null && single.length == 1 && title.equals(single[0]),
                "got " + Arrays.toString(single));

        // ---- picture round-trip ----------------------------------------------
        if (PICTURE_CAPABLE.contains(ext)) {
            Picture cover = new Picture(TINY_PNG, "", "Front Cover", "image/png");
            boolean picsSaved = AudioTagNative.savePictures(path, new Picture[] {cover});
            check(fixture + ": savePictures returned true", picsSaved, "returned false");

            Picture[] pictures = AudioTagNative.getPictures(path);
            check(fixture + ": getPictures returned 1 picture",
                    pictures != null && pictures.length == 1,
                    "got " + (pictures == null ? "null" : String.valueOf(pictures.length)));
            if (pictures != null && pictures.length == 1) {
                check(fixture + ": picture bytes round-trip",
                        Arrays.equals(TINY_PNG, pictures[0].getData()),
                        "len=" + (pictures[0].getData() == null ? -1 : pictures[0].getData().length));
                System.out.println("    picture: " + pictures[0]);
            }
        } else {
            skipped++;
            System.out.println("    [SKIP] picture round-trip not applicable for ." + ext);
        }

        // ---- the file must still be a valid audio file after rewriting --------
        AudioProperties rewritten = AudioTagNative.getAudioProperties(path, 1);
        check(fixture + ": file still readable after write", rewritten != null, "got null");
        if (rewritten != null && before != null) {
            check(fixture + ": audio properties unchanged by tag write",
                    rewritten.sampleRate == before.sampleRate && rewritten.channels == before.channels,
                    "before=" + before + " after=" + rewritten);
        }

        System.out.println();
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    private static String first(String[] values) {
        return values == null || values.length == 0 ? null : values[0];
    }

    private static void checkString(String label, String expected, String actual) {
        check(label, expected.equals(actual), "expected '" + expected + "' got '" + actual + "'");
    }

    private static void check(String label, boolean ok, String detail) {
        checks++;
        if (ok) {
            System.out.println("    [ OK ] " + label);
        } else {
            fail(label + " — " + detail);
        }
    }

    private static void fail(String message) {
        FAILURES.add(message);
        System.out.println("    [FAIL] " + message);
    }
}
