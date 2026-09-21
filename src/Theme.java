import javax.swing.*;
import javax.swing.border.*;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.event.*;

/**
 * The stylesheet for APSY: every colour, font, spacing value and shared widget
 * style lives here and nowhere else.
 *
 * Swing has no CSS, so the equivalent discipline has to be imposed by hand:
 * the rest of the app asks this class for a border or a button instead of
 * spelling out its own insets. That is what keeps two panels built months apart
 * from disagreeing by three pixels, and it means a change of padding is one
 * edit here rather than a search across four thousand lines.
 *
 * The spacing values are a scale, not arbitrary numbers. Everything is a
 * multiple of 4, so gaps line up wherever they meet and "slightly bigger" is a
 * decision between two defined steps rather than a fresh guess each time.
 */
public final class Theme {

    private Theme() {}

    // ── Palette ─────────────────────────────────────────────────────────────
    //
    // Not final: a theme switch rewrites these in place, and everything else
    // reads them at the moment it paints or is refreshed. That is why the app
    // asks Theme for a colour instead of caching one - a cached colour is a
    // pixel that never changes theme.

    public static Color BG;             // panel background
    public static Color SURFACE;        // text fields, plot canvas, log
    public static Color TEXT;           // body text
    public static Color TEXT_MUTED;     // secondary labels
    public static Color BORDER;         // outlines
    public static Color BORDER_SUBTLE;  // inner dividers
    public static Color ACCENT;         // hover / selection wash
    public static Color CONTROL;        // button face

    public static Color PLOT_LINE;      // the trace
    public static Color PLOT_HOVER;     // highlight
    public static Color GRID_LINE;
    public static Color TOOLTIP_BG;
    public static Color PLOT_BASELINE;  // the zero line under a 1D trace

    // Ribbon: one shade per tab, shared by the toolbar body and its tab button.
    // These stay dark in both themes - the ribbon is the app's dark chrome, and
    // inverting it in dark mode would leave nothing to anchor the window.
    public static Color RIBBON_PROCESS;
    public static Color RIBBON_ANALYZE;
    public static Color RIBBON_APPS;
    public static Color RIBBON_MANAGE;
    public static Color RIBBON_IDLE;
    public static Color RIBBON_HEADER;
    public static Color RIBBON_EDGE;

    // Secondary toolbar: the strip of icon tools under the ribbon.
    public static Color TOOLBAR_BG;
    public static Color TOOLBAR_EDGE;
    public static Color TOOLBAR_SEPARATOR;
    // The icon buttons on that strip draw themselves in their own foreground,
    // so this colour is the icons as much as the labels.
    public static Color TOOLBAR_TEXT;
    public static Color TOOLBAR_HOVER;

    /** Which palette is loaded. */
    public enum Mode { LIGHT, DARK }

    private static Mode mode = Mode.LIGHT;
    private static final String PREF_MODE = "themeMode";

    public static Mode mode() { return mode; }

    public static boolean isDark() { return mode == Mode.DARK; }

    /** Load the palette the user last chose. Call before building any UI. */
    public static void loadSavedMode() {
        String saved = java.util.prefs.Preferences
                .userNodeForPackage(Theme.class).get(PREF_MODE, Mode.LIGHT.name());
        try {
            setMode(Mode.valueOf(saved), false);
        } catch (IllegalArgumentException ex) {
            setMode(Mode.LIGHT, false);
        }
    }

    public static void setMode(Mode next) {
        setMode(next, true);
    }

    private static void setMode(Mode next, boolean persist) {
        mode = next == null ? Mode.LIGHT : next;
        if (mode == Mode.DARK) applyDark(); else applyLight();
        if (persist) {
            java.util.prefs.Preferences.userNodeForPackage(Theme.class)
                    .put(PREF_MODE, mode.name());
        }
    }

    private static void applyLight() {
        BG            = new Color(240, 243, 246);
        SURFACE       = Color.WHITE;
        TEXT          = new Color(40, 40, 40);
        TEXT_MUTED    = new Color(110, 118, 128);
        BORDER        = new Color(160, 181, 205);
        BORDER_SUBTLE = new Color(206, 216, 228);
        ACCENT        = new Color(211, 226, 242);
        CONTROL       = new Color(235, 238, 242);

        PLOT_LINE     = new Color(30, 96, 145);
        PLOT_HOVER    = new Color(230, 57, 70);
        GRID_LINE     = new Color(225, 225, 225);
        TOOLTIP_BG    = new Color(255, 255, 204);
        PLOT_BASELINE = new Color(200, 200, 200);

        RIBBON_PROCESS = new Color(0, 46, 71);
        RIBBON_ANALYZE = new Color(8, 29, 44);
        RIBBON_APPS    = new Color(10, 51, 45);
        RIBBON_MANAGE  = new Color(43, 27, 36);
        RIBBON_IDLE    = new Color(0, 37, 58);
        RIBBON_HEADER  = new Color(0, 23, 37);
        RIBBON_EDGE    = new Color(0, 36, 56);

        TOOLBAR_BG        = new Color(230, 235, 242);
        TOOLBAR_EDGE      = new Color(195, 205, 218);
        TOOLBAR_SEPARATOR = new Color(170, 180, 195);
        TOOLBAR_TEXT      = new Color(40, 50, 65);
        TOOLBAR_HOVER     = new Color(202, 212, 226);
    }

    /**
     * The dark palette.
     *
     * The plot surface is darker than the panels around it, not lighter: a
     * spectrum is mostly empty space, and a pale canvas in a dark window is a
     * lamp pointed at the reader. The trace lightens to stay legible on it -
     * the light theme's navy is nearly invisible against a dark surface - and
     * the tooltip loses its yellow, which at these luminances reads as a
     * warning rather than a note.
     */
    private static void applyDark() {
        BG            = new Color(30, 34, 39);
        SURFACE       = new Color(20, 23, 26);
        TEXT          = new Color(228, 231, 234);
        TEXT_MUTED    = new Color(150, 160, 170);
        BORDER        = new Color(62, 72, 82);
        BORDER_SUBTLE = new Color(44, 50, 57);
        ACCENT        = new Color(42, 67, 88);
        CONTROL       = new Color(38, 44, 51);

        PLOT_LINE     = new Color(96, 170, 230);
        PLOT_HOVER    = new Color(255, 107, 107);
        GRID_LINE     = new Color(46, 53, 60);
        TOOLTIP_BG    = new Color(56, 62, 70);
        PLOT_BASELINE = new Color(70, 78, 86);

        // Held one step darker than the panels so the chrome still reads as
        // chrome; in light mode it contrasts by being dark, and that has to
        // keep working when everything around it is dark too.
        RIBBON_PROCESS = new Color(0, 34, 53);
        RIBBON_ANALYZE = new Color(8, 22, 33);
        RIBBON_APPS    = new Color(8, 38, 34);
        RIBBON_MANAGE  = new Color(33, 21, 28);
        RIBBON_IDLE    = new Color(0, 27, 43);
        RIBBON_HEADER  = new Color(0, 17, 27);
        RIBBON_EDGE    = new Color(0, 28, 44);

        TOOLBAR_BG        = new Color(35, 40, 46);
        TOOLBAR_EDGE      = new Color(52, 59, 66);
        TOOLBAR_SEPARATOR = new Color(74, 84, 94);
        TOOLBAR_TEXT      = new Color(206, 214, 222);
        TOOLBAR_HOVER     = new Color(52, 62, 74);
    }

    static { applyLight(); }

    // ── Typography ──────────────────────────────────────────────────────────

    private static final String UI_FAMILY   = "Arial";
    private static final String MONO_FAMILY = "Menlo";     // falls back to Courier New

    public static final Font SANS      = new Font(UI_FAMILY, Font.PLAIN, 12);
    public static final Font SANS_BOLD = new Font(UI_FAMILY, Font.BOLD, 12);
    public static final Font TITLE     = new Font(UI_FAMILY, Font.BOLD, 14);
    public static final Font MONO      = monoFont(12);

    /** Mono for logs and command entry; Courier New wherever Menlo is absent. */
    private static Font monoFont(int size) {
        Font f = new Font(MONO_FAMILY, Font.PLAIN, size);
        return UI_FAMILY.equals(f.getFamily()) ? new Font("Courier New", Font.PLAIN, size) : f;
    }

    // ── Spacing scale ───────────────────────────────────────────────────────
    //
    // Multiples of 4. Reach for the nearest step rather than inventing a value:
    // that is the whole reason the panels line up.

    public static final int SPACE_XS = 4;    // inside a control
    public static final int SPACE_SM = 8;    // between related controls
    public static final int SPACE_MD = 12;   // between groups in a panel
    public static final int SPACE_LG = 16;   // between panels
    public static final int SPACE_XL = 24;   // major regions

    /** Padding inside a titled section, below its title. */
    public static final Insets SECTION_PADDING = new Insets(SPACE_SM, SPACE_MD, SPACE_MD, SPACE_MD);
    /** Padding inside a push button. */
    public static final Insets BUTTON_PADDING  = new Insets(SPACE_SM, SPACE_MD, SPACE_SM, SPACE_MD);
    /** Gap around the sidebar's contents. */
    public static final Insets SIDEBAR_PADDING = new Insets(SPACE_MD, SPACE_MD, SPACE_MD, SPACE_MD);

    public static final int SIDEBAR_WIDTH = 330;

    // ── Borders ─────────────────────────────────────────────────────────────

    /**
     * A titled section frame.
     *
     * The title sits in the top border, so the content padding needs extra room
     * at the top or the first control crowds the text.
     */
    public static Border section(String title) {
        Border titled = BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(BORDER), " " + title + " ",
                TitledBorder.LEFT, TitledBorder.TOP, SANS_BOLD, TEXT);
        return BorderFactory.createCompoundBorder(titled, padding(SECTION_PADDING));
    }

    /** Thin outline for text fields, the log and the plot. */
    public static Border field() {
        return BorderFactory.createLineBorder(BORDER);
    }

    /** Outline plus breathing room, for anything holding text directly. */
    public static Border inputField() {
        return BorderFactory.createCompoundBorder(field(), padding(SPACE_XS, SPACE_SM));
    }

    public static Border padding(Insets i) {
        return BorderFactory.createEmptyBorder(i.top, i.left, i.bottom, i.right);
    }

    public static Border padding(int vertical, int horizontal) {
        return BorderFactory.createEmptyBorder(vertical, horizontal, vertical, horizontal);
    }

    public static Border padding(int all) {
        return BorderFactory.createEmptyBorder(all, all, all, all);
    }

    /** A single hairline on one edge, for separating regions without a full box. */
    public static Border edge(int top, int left, int bottom, int right) {
        return BorderFactory.createMatteBorder(top, left, bottom, right, BORDER);
    }

    // ── Widgets ─────────────────────────────────────────────────────────────

    /** The standard push button: flat, outlined, washes to the accent on hover. */
    public static JButton button(String label) {
        JButton btn = new JButton(label);
        btn.setFont(SANS_BOLD);
        btn.setBackground(CONTROL);
        btn.setForeground(TEXT);
        btn.setBorder(BorderFactory.createCompoundBorder(field(), padding(BUTTON_PADDING)));
        btn.setFocusPainted(false);
        btn.setContentAreaFilled(false);
        btn.setOpaque(true);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.addMouseListener(new MouseAdapter() {
            public void mouseEntered(MouseEvent e) {
                if (btn.isEnabled()) btn.setBackground(ACCENT);
            }
            public void mouseExited(MouseEvent e) {
                btn.setBackground(CONTROL);
            }
        });
        return btn;
    }

    /** A checkbox that matches the panel it sits on. */
    public static JCheckBox checkBox(String label, boolean selected) {
        JCheckBox box = new JCheckBox(label, selected);
        box.setFont(SANS_BOLD);
        box.setForeground(TEXT);
        box.setBackground(BG);
        box.setFocusPainted(false);
        // Swing leaves no room between the tick and its text at this font size.
        box.setIconTextGap(SPACE_SM);
        return box;
    }

    /** A section label in the sidebar's forms. */
    public static JLabel label(String text) {
        JLabel l = new JLabel(text);
        l.setFont(SANS_BOLD);
        l.setForeground(TEXT);
        return l;
    }

    /** A panel that paints the app background and nothing else. */
    public static JPanel panel(LayoutManager layout) {
        JPanel p = new JPanel(layout);
        p.setBackground(BG);
        return p;
    }

    /** A titled section panel, the sidebar's building block. */
    public static JPanel sectionPanel(String title, LayoutManager layout) {
        JPanel p = panel(layout);
        p.setBorder(section(title));
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        return p;
    }

    // ── Re-theming a live window ────────────────────────────────────────────
    //
    // Swing paints from colours already handed to each component, so changing
    // the palette does nothing to a window that is already on screen. Two
    // things bridge that: the look and feel's own defaults, which cover menus,
    // dialogs and scrollbars, and a walk over the tree for every colour this
    // app set itself.

    private static final String ROLE_KEY = "apsy.themeRole";

    /** Remember what a component is, so a later theme switch can recolour it. */
    public static <T extends JComponent> T role(T component, String role) {
        component.putClientProperty(ROLE_KEY, role);
        applyRole(component, role);
        return component;
    }

    private static void applyRole(JComponent c, String role) {
        switch (role) {
            case "panel":          c.setBackground(BG); break;
            case "surface":        c.setBackground(SURFACE); c.setForeground(TEXT); break;
            case "toolbar":        c.setBackground(TOOLBAR_BG); break;
            case "footer":         c.setBackground(ACCENT); c.setForeground(TEXT); break;
            case "separator":      c.setForeground(TOOLBAR_SEPARATOR); break;
            case "muted":          c.setForeground(TEXT_MUTED); break;
            case "ribbon-process": c.setBackground(RIBBON_PROCESS); break;
            case "ribbon-analyze": c.setBackground(RIBBON_ANALYZE); break;
            case "ribbon-apps":    c.setBackground(RIBBON_APPS); break;
            case "ribbon-manage":  c.setBackground(RIBBON_MANAGE); break;
            case "ribbon-header":  c.setBackground(RIBBON_HEADER); break;
            case "control":        c.setBackground(CONTROL); c.setForeground(TEXT); break;
            case "toolbar-button": c.setForeground(TOOLBAR_TEXT); break;
            default: break;
        }
    }

    /**
     * Push the palette into the look and feel.
     *
     * Menus, popups, dialogs and scrollbars are built by the LAF from these
     * keys rather than by this app, so without them a switched theme leaves
     * every dropdown stubbornly light.
     */
    public static void installUiDefaults() {
        UIManager.put("Panel.background", BG);
        UIManager.put("OptionPane.background", BG);
        UIManager.put("OptionPane.messageForeground", TEXT);
        UIManager.put("Label.foreground", TEXT);
        UIManager.put("MenuItem.background", BG);
        UIManager.put("MenuItem.foreground", TEXT);
        UIManager.put("MenuItem.selectionBackground", ACCENT);
        UIManager.put("MenuItem.selectionForeground", TEXT);
        UIManager.put("MenuItem.disabledForeground", TEXT_MUTED);
        UIManager.put("Menu.background", BG);
        UIManager.put("Menu.foreground", TEXT);
        UIManager.put("Menu.selectionBackground", ACCENT);
        UIManager.put("Menu.selectionForeground", TEXT);
        UIManager.put("Menu.disabledForeground", TEXT_MUTED);
        UIManager.put("CheckBoxMenuItem.background", BG);
        UIManager.put("CheckBoxMenuItem.foreground", TEXT);
        UIManager.put("PopupMenu.background", BG);
        UIManager.put("PopupMenu.foreground", TEXT);
        UIManager.put("Separator.foreground", BORDER_SUBTLE);
        UIManager.put("ToolTip.background", TOOLTIP_BG);
        UIManager.put("ToolTip.foreground", TEXT);
        UIManager.put("TextField.background", SURFACE);
        UIManager.put("TextField.foreground", TEXT);
        UIManager.put("TextField.caretForeground", TEXT);
        UIManager.put("TextArea.background", SURFACE);
        UIManager.put("TextArea.foreground", TEXT);
        UIManager.put("Table.background", SURFACE);
        UIManager.put("Table.foreground", TEXT);
        UIManager.put("Table.gridColor", BORDER_SUBTLE);
        UIManager.put("Table.selectionBackground", ACCENT);
        UIManager.put("Table.selectionForeground", TEXT);
        UIManager.put("TableHeader.background", CONTROL);
        UIManager.put("TableHeader.foreground", TEXT);
        UIManager.put("ScrollPane.background", BG);
        UIManager.put("Viewport.background", SURFACE);
        UIManager.put("FileChooser.background", BG);
    }

    /**
     * Recolour a window that is already on screen.
     *
     * Tagged components get their role's colour back; everything else is
     * matched by type. Buttons are only touched when they are still wearing
     * the palette's own control colour, so a button deliberately coloured
     * something else is left alone.
     */
    public static void refresh(Component c) {
        if (c instanceof JComponent) {
            JComponent jc = (JComponent) c;
            Object role = jc.getClientProperty(ROLE_KEY);
            if (role instanceof String) {
                applyRole(jc, (String) role);
                refreshChildren(c);
                return;
            }
        }
        if (c instanceof JTextComponent) {
            c.setBackground(SURFACE);
            c.setForeground(TEXT);
            ((JTextComponent) c).setCaretColor(TEXT);
        } else if (c instanceof JTable) {
            c.setBackground(SURFACE);
            c.setForeground(TEXT);
            ((JTable) c).setGridColor(BORDER_SUBTLE);
        } else if (c instanceof AbstractButton) {
            AbstractButton b = (AbstractButton) c;
            if (b instanceof JCheckBox || b instanceof JRadioButton) {
                b.setBackground(BG);
                b.setForeground(TEXT);
            } else if (isThemedControl(b)) {
                b.setBackground(CONTROL);
                b.setForeground(TEXT);
            }
        } else if (c instanceof JLabel) {
            // Foreground only: a label on the ribbon keeps its parent's colour.
            c.setForeground(TEXT);
        } else if (c instanceof JPanel || c instanceof JScrollPane) {
            c.setBackground(BG);
        }
        refreshChildren(c);
    }

    private static void refreshChildren(Component c) {
        if (c instanceof JScrollPane) {
            ((JScrollPane) c).getViewport().setBackground(SURFACE);
        }
        if (c instanceof Container) {
            for (Component child : ((Container) c).getComponents()) refresh(child);
        }
    }

    /**
     * Whether a button is wearing one of the palette's control colours.
     *
     * Both palettes are checked, because by the time this runs the new one is
     * already loaded and the button still carries the old.
     */
    private static boolean isThemedControl(AbstractButton b) {
        Color bg = b.getBackground();
        if (bg == null) return false;
        return bg.equals(CONTROL)
            || bg.equals(new Color(235, 238, 242))     // light CONTROL
            || bg.equals(new Color(38, 44, 51));       // dark CONTROL
    }

    /**
     * Pin a component to its natural height inside a vertical BoxLayout.
     *
     * BoxLayout stretches a child to its maximum height, which is unbounded by
     * default: without this a form floats in the middle of a box far taller
     * than its contents, which is exactly how dead space creeps in.
     */
    public static void pinHeight(JComponent c) {
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height));
    }
}
