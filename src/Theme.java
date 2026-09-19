import javax.swing.*;
import javax.swing.border.*;
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

    public static final Color BG            = new Color(240, 243, 246);  // silver-grey panel background
    public static final Color SURFACE       = Color.WHITE;               // text fields, plot, log
    public static final Color TEXT          = new Color(40, 40, 40);     // charcoal body text
    public static final Color TEXT_MUTED    = new Color(110, 118, 128);  // secondary labels
    public static final Color BORDER        = new Color(160, 181, 205);  // steel blue
    public static final Color BORDER_SUBTLE = new Color(206, 216, 228);  // inner dividers
    public static final Color ACCENT        = new Color(211, 226, 242);  // hover / selection wash
    public static final Color CONTROL       = new Color(235, 238, 242);  // button face

    public static final Color PLOT_LINE  = new Color(30, 96, 145);       // Excel blue
    public static final Color PLOT_HOVER = new Color(230, 57, 70);       // red highlight
    public static final Color GRID_LINE  = new Color(225, 225, 225);
    public static final Color TOOLTIP_BG = new Color(255, 255, 204);     // classic yellow

    // Ribbon: one shade per tab, shared by the toolbar body and its tab button.
    public static final Color RIBBON_PROCESS = new Color(0, 46, 71);
    public static final Color RIBBON_ANALYZE = new Color(8, 29, 44);
    public static final Color RIBBON_APPS    = new Color(10, 51, 45);
    public static final Color RIBBON_MANAGE  = new Color(43, 27, 36);
    public static final Color RIBBON_IDLE    = new Color(0, 37, 58);
    public static final Color RIBBON_HEADER  = new Color(0, 23, 37);     // tab strip, darkest
    public static final Color RIBBON_EDGE    = new Color(0, 36, 56);     // hairline under a ribbon

    // Secondary toolbar: the light strip of icon tools under the ribbon.
    public static final Color TOOLBAR_BG        = new Color(230, 235, 242);
    public static final Color TOOLBAR_EDGE      = new Color(195, 205, 218);
    public static final Color TOOLBAR_SEPARATOR = new Color(170, 180, 195);

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
