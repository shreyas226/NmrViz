import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.prefs.Preferences;

/**
 * Finding spectra on disk, so nobody has to walk down to a `2rr` by hand.
 *
 * A vendor dataset is a directory tree, not a file, and the part that actually
 * holds the numbers sits three or four levels below the name a person thinks
 * of: `METABOLITE/158/pdata/1/2rr`. Three things follow from that, and all
 * three live here:
 *
 *   resolve()  takes anything inside such a tree and works out the directory
 *              the backend wants, whether it was handed the top of the dataset,
 *              the `pdata` folder, or a stray `title` file.
 *   scan()     walks a directory and reports every dataset underneath it, so a
 *              browser can list them instead of making someone navigate.
 *   Recents    remembers what was opened, across restarts.
 *
 * Format detection is deliberately by layout rather than by extension: vendors
 * name the data files, not the folders, and `procs` next to `2rr` identifies
 * Bruker processed data far more reliably than any suffix.
 */
public final class DatasetLocator {

    private DatasetLocator() {}

    /** How deep a scan will go below the folder it is pointed at. */
    private static final int MAX_SCAN_DEPTH = 6;
    /** Stops a mis-aimed scan of a home directory from running for minutes. */
    private static final int MAX_RESULTS = 500;

    private static final int MAX_RECENTS = 10;
    private static final String PREF_RECENTS = "recentDatasets";
    private static final String PREF_LAST_DIR = "lastBrowseDirectory";

    // ── What a dataset looks like ───────────────────────────────────────────

    /** Vendor formats recognised by the shape of the directory on disk. */
    public enum Format {
        BRUKER("Bruker"),
        JCAMP("JCAMP-DX"),
        VARIAN("Varian/Agilent"),
        JEOL("JEOL"),
        UNKNOWN("-");

        public final String label;
        Format(String label) { this.label = label; }
    }

    /** One spectrum found on disk, as a browser row. */
    public static final class Entry {
        public final File path;        // what to hand the backend
        public final String name;      // dataset name, e.g. METABOLITE
        public final String expNo;     // experiment number, e.g. 158
        public final String procNo;    // processing number, e.g. 1
        public final int dimension;    // 1, 2, 3, or 0 when not yet known
        public final Format format;

        Entry(File path, String name, String expNo, String procNo, int dimension, Format format) {
            this.path = path;
            this.name = name;
            this.expNo = expNo;
            this.procNo = procNo;
            this.dimension = dimension;
            this.format = format;
        }

        public String dimensionLabel() {
            return dimension > 0 ? dimension + "D" : "?";
        }

        @Override public String toString() {
            return name + (expNo.isEmpty() ? "" : "/" + expNo) + "  (" + dimensionLabel() + ")";
        }
    }

    // ── Recognising one directory ───────────────────────────────────────────

    /** Processed Bruker data: the numbers, plus the parameters to read them. */
    public static boolean isBrukerProcessed(File dir) {
        return dir != null && dir.isDirectory()
                && new File(dir, "procs").isFile()
                && (new File(dir, "1r").isFile()
                 || new File(dir, "2rr").isFile()
                 || new File(dir, "3rrr").isFile());
    }

    /** Raw Bruker data: an acquisition that has not been processed yet. */
    public static boolean isBrukerRaw(File dir) {
        return dir != null && dir.isDirectory()
                && (new File(dir, "acqus").isFile() || new File(dir, "acqu").isFile())
                && (new File(dir, "fid").isFile() || new File(dir, "ser").isFile());
    }

    /** Varian/Agilent: a .fid directory holding fid + procpar. */
    public static boolean isVarian(File dir) {
        return dir != null && dir.isDirectory()
                && new File(dir, "procpar").isFile()
                && new File(dir, "fid").isFile();
    }

    public static boolean isDataset(File dir) {
        return isBrukerProcessed(dir) || isBrukerRaw(dir) || isVarian(dir);
    }

    public static Format formatOf(File dir) {
        if (isBrukerProcessed(dir) || isBrukerRaw(dir)) return Format.BRUKER;
        if (isVarian(dir)) return Format.VARIAN;
        return Format.UNKNOWN;
    }

    /**
     * Dimensionality from the filenames, without opening anything.
     *
     * Bruker encodes it in the name of the processed file - 1r, 2rr, 3rrr - so
     * a scan of a few hundred datasets stays instant. Raw data has no such
     * marker, hence the 0: the backend works it out properly on load.
     */
    public static int dimensionOf(File dir) {
        if (dir == null) return 0;
        if (new File(dir, "3rrr").isFile()) return 3;
        if (new File(dir, "2rr").isFile()) return 2;
        if (new File(dir, "1r").isFile()) return 1;
        if (new File(dir, "ser").isFile()) return 2;   // ser implies >1D
        return 0;
    }

    // ── resolve: accept anything inside a dataset ───────────────────────────

    /**
     * Turn whatever was selected into the directory the backend can load.
     *
     * Walks up first, because selecting a file inside a dataset is the common
     * case - clicking `2rr`, or `title`, or `procs`. Only if nothing above is
     * a dataset does it look downward, which covers pointing at the dataset
     * name and letting the first experiment inside it be opened.
     *
     * Returns null when nothing nearby looks like NMR data at all, so the
     * caller can fall back to treating the selection as a .str or .csv file.
     */
    public static File resolve(File selected) {
        if (selected == null || !selected.exists()) return null;

        File dir = selected.isDirectory() ? selected : selected.getParentFile();
        for (int up = 0; dir != null && up <= 4; up++, dir = dir.getParentFile()) {
            if (isDataset(dir)) return dir;
        }

        if (selected.isDirectory()) {
            List<Entry> found = scan(selected, MAX_SCAN_DEPTH);
            if (!found.isEmpty()) return found.get(0).path;
        }
        return null;
    }

    // ── scan: everything underneath a folder ────────────────────────────────

    public static List<Entry> scan(File root) {
        return scan(root, MAX_SCAN_DEPTH);
    }

    public static List<Entry> scan(File root, int maxDepth) {
        List<Entry> out = new ArrayList<>();
        if (root != null && root.isDirectory()) walk(root, root, 0, maxDepth, out);
        // Processed data first, then by name and experiment number read as a
        // number - otherwise expno 10 sorts above expno 2.
        out.sort(Comparator
                .comparing((Entry e) -> e.name.toLowerCase())
                .thenComparingInt(e -> numeric(e.expNo))
                .thenComparingInt(e -> numeric(e.procNo)));
        return out;
    }

    private static void walk(File root, File dir, int depth, int maxDepth, List<Entry> out) {
        if (depth > maxDepth || out.size() >= MAX_RESULTS) return;

        // Processed data is the end of the line: it is what the backend reads,
        // and it holds no further datasets.
        if (isBrukerProcessed(dir) || isVarian(dir)) {
            out.add(describe(root, dir));
            return;
        }

        int before = out.size();
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                if (!child.isDirectory()) continue;
                if (child.getName().startsWith(".")) continue;     // .git, caches
                walk(root, child, depth + 1, maxDepth, out);
            }
        }

        // An acquisition directory is only worth listing on its own if nothing
        // below it has been processed yet. Reporting it as well as its pdata
        // children would list the same spectrum twice, and the raw entry is the
        // less useful of the two: its dimensionality cannot be read from the
        // filenames, so it shows as "?".
        if (out.size() == before && isBrukerRaw(dir)) {
            out.add(describe(root, dir));
        }
    }

    /**
     * Name the dataset the way its owner would.
     *
     * Bruker's layout is `<name>/<expno>/pdata/<procno>`, so the useful label
     * is three or four levels above the directory actually being loaded, not
     * the directory's own name - which is nearly always the unhelpful "1".
     */
    private static Entry describe(File root, File dir) {
        String procNo = "", expNo = "", name = dir.getName();

        File parent = dir.getParentFile();
        if (parent != null && "pdata".equals(parent.getName())) {
            procNo = dir.getName();
            File expDir = parent.getParentFile();
            if (expDir != null) {
                expNo = expDir.getName();
                File nameDir = expDir.getParentFile();
                name = nameDir != null ? nameDir.getName() : expNo;
            }
        } else if (parent != null && isNumeric(dir.getName())) {
            expNo = dir.getName();
            name = parent.getName();
        }

        // Keep the name meaningful when the scan started deeper than the
        // dataset's own folder.
        if (name.isEmpty()) name = dir.getName();
        return new Entry(dir, name, expNo, procNo, dimensionOf(dir), formatOf(dir));
    }

    /** Describe a single directory, for labelling a recents entry. */
    public static Entry describeOne(File dir) {
        if (dir == null || !isDataset(dir)) return null;
        return describe(dir, dir);
    }

    /** The title Bruker stores for a dataset, when there is one. */
    public static String titleOf(File dir) {
        File title = new File(dir, "title");
        if (!title.isFile()) return "";
        try {
            String text = new String(Files.readAllBytes(title.toPath()), StandardCharsets.UTF_8);
            for (String line : text.split("\\R")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) return trimmed;
            }
        } catch (IOException | RuntimeException ignored) {
            // A title is a convenience; an unreadable one is not worth a dialog.
        }
        return "";
    }

    // ── Recently opened ─────────────────────────────────────────────────────

    private static Preferences prefs() {
        return Preferences.userNodeForPackage(DatasetLocator.class);
    }

    /** Most recent first. Paths that have since disappeared are dropped. */
    public static List<String> recents() {
        List<String> out = new ArrayList<>();
        String raw = prefs().get(PREF_RECENTS, "");
        if (raw.isEmpty()) return out;
        for (String path : raw.split("\n")) {
            if (!path.isEmpty() && new File(path).exists()) out.add(path);
        }
        return out;
    }

    public static void addRecent(String path) {
        if (path == null || path.isEmpty()) return;
        List<String> list = recents();
        list.remove(path);                  // re-opening moves it to the front
        list.add(0, path);
        while (list.size() > MAX_RECENTS) list.remove(list.size() - 1);
        prefs().put(PREF_RECENTS, String.join("\n", list));
    }

    public static void clearRecents() {
        prefs().remove(PREF_RECENTS);
    }

    /** Where the browser should open, so it starts where it left off. */
    public static File lastBrowseDirectory(File fallback) {
        String saved = prefs().get(PREF_LAST_DIR, "");
        if (!saved.isEmpty()) {
            File dir = new File(saved);
            if (dir.isDirectory()) return dir;
        }
        return fallback;
    }

    public static void setLastBrowseDirectory(File dir) {
        if (dir != null && dir.isDirectory()) prefs().put(PREF_LAST_DIR, dir.getAbsolutePath());
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static boolean isNumeric(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) if (!Character.isDigit(s.charAt(i))) return false;
        return true;
    }

    private static int numeric(String s) {
        return isNumeric(s) ? Integer.parseInt(s) : Integer.MAX_VALUE;
    }
}
