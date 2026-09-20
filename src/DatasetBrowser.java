import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.util.List;
import java.util.function.Consumer;

/**
 * A list of every spectrum under a chosen folder, so opening one is a
 * double-click rather than a walk down to `pdata/1/2rr`.
 *
 * This is the same idea as TopSpin's data browser, and it exists for the same
 * reason: a Bruker dataset's identity is the name and experiment number a
 * person remembers, while the directory that actually gets loaded is three
 * levels below that and is always called "1". Presenting name, experiment,
 * dimension and title in a table means the choice is made in those terms.
 *
 * The scan runs off the event thread. Pointed at a large tree it can take a
 * moment, and a frozen dialog looks like a crash.
 */
public final class DatasetBrowser extends JDialog {

    private final DefaultTableModel model;
    private final JTable table;
    private final JLabel status;
    private final JTextField pathField;
    private List<DatasetLocator.Entry> entries = java.util.Collections.emptyList();
    private final Consumer<File> onOpen;
    private SwingWorker<List<DatasetLocator.Entry>, Void> scanning;

    private static final String[] COLUMNS = { "Dataset", "Exp", "Proc", "Dim", "Format", "Title" };

    public DatasetBrowser(Frame owner, File startDirectory, Consumer<File> onOpen) {
        super(owner, "Open Dataset", true);
        this.onOpen = onOpen;

        model = new DefaultTableModel(COLUMNS, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        table = new JTable(model);
        table.setFont(Theme.SANS);
        table.setRowHeight(22);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoCreateRowSorter(true);
        table.getTableHeader().setFont(Theme.SANS_BOLD);
        table.setGridColor(Theme.BORDER_SUBTLE);
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) openSelected();
            }
        });
        // Enter opens, Escape closes - the two keys anyone tries in a picker.
        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
             .put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "openDataset");
        table.getActionMap().put("openDataset", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { openSelected(); }
        });

        int[] widths = { 190, 55, 55, 45, 110, 260 };
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }

        pathField = new JTextField(startDirectory != null ? startDirectory.getAbsolutePath() : "");
        pathField.setFont(Theme.SANS);
        pathField.setBorder(Theme.inputField());
        pathField.addActionListener(e -> rescan());

        JButton browse = Theme.button("Change Folder...");
        browse.addActionListener(e -> chooseFolder());

        JButton rescan = Theme.button("Rescan");
        rescan.addActionListener(e -> rescan());

        JPanel top = Theme.panel(new BorderLayout(Theme.SPACE_SM, 0));
        top.add(Theme.label("Search in: "), BorderLayout.WEST);
        top.add(pathField, BorderLayout.CENTER);
        JPanel topButtons = Theme.panel(new FlowLayout(FlowLayout.RIGHT, Theme.SPACE_SM, 0));
        topButtons.add(rescan);
        topButtons.add(browse);
        top.add(topButtons, BorderLayout.EAST);
        top.setBorder(BorderFactory.createEmptyBorder(0, 0, Theme.SPACE_SM, 0));

        status = new JLabel(" ");
        status.setFont(Theme.SANS);
        status.setForeground(Theme.TEXT_MUTED);

        JButton open = Theme.button("Open");
        open.addActionListener(e -> openSelected());
        JButton cancel = Theme.button("Cancel");
        cancel.addActionListener(e -> dispose());

        JPanel actions = Theme.panel(new FlowLayout(FlowLayout.RIGHT, Theme.SPACE_SM, 0));
        actions.add(cancel);
        actions.add(open);

        JPanel bottom = Theme.panel(new BorderLayout());
        bottom.add(status, BorderLayout.WEST);
        bottom.add(actions, BorderLayout.EAST);
        bottom.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_SM, 0, 0, 0));

        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(Theme.field());
        scroll.getViewport().setBackground(Theme.SURFACE);

        JPanel content = Theme.panel(new BorderLayout());
        content.setBorder(Theme.padding(Theme.SPACE_MD));
        content.add(top, BorderLayout.NORTH);
        content.add(scroll, BorderLayout.CENTER);
        content.add(bottom, BorderLayout.SOUTH);
        setContentPane(content);

        getRootPane().setDefaultButton(open);
        getRootPane().registerKeyboardAction(e -> dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        setSize(820, 480);
        setLocationRelativeTo(owner);
        rescan();
    }

    private void chooseFolder() {
        JFileChooser chooser = new JFileChooser(new File(pathField.getText()));
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Folder to search for datasets");
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            pathField.setText(chooser.getSelectedFile().getAbsolutePath());
            rescan();
        }
    }

    /** Rescan off the event thread, so a big tree cannot freeze the dialog. */
    private void rescan() {
        File root = new File(pathField.getText().trim());
        if (!root.isDirectory()) {
            status.setText("Not a folder: " + root.getPath());
            model.setRowCount(0);
            return;
        }
        if (scanning != null && !scanning.isDone()) scanning.cancel(true);

        model.setRowCount(0);
        status.setText("Scanning " + root.getName() + "...");
        DatasetLocator.setLastBrowseDirectory(root);

        scanning = new SwingWorker<List<DatasetLocator.Entry>, Void>() {
            @Override protected List<DatasetLocator.Entry> doInBackground() {
                return DatasetLocator.scan(root);
            }
            @Override protected void done() {
                if (isCancelled()) return;
                try {
                    entries = get();
                } catch (Exception ex) {
                    status.setText("Scan failed: " + ex.getMessage());
                    return;
                }
                for (DatasetLocator.Entry e : entries) {
                    model.addRow(new Object[] {
                        e.name, e.expNo, e.procNo, e.dimensionLabel(),
                        e.format.label, DatasetLocator.titleOf(e.path)
                    });
                }
                status.setText(entries.isEmpty()
                        ? "No datasets found under this folder"
                        : entries.size() + " dataset" + (entries.size() == 1 ? "" : "s")
                          + " - double-click to open");
                if (!entries.isEmpty()) table.setRowSelectionInterval(0, 0);
            }
        };
        scanning.execute();
    }

    private void openSelected() {
        int viewRow = table.getSelectedRow();
        if (viewRow < 0) return;
        // The table sorts, so the view row is not the model row.
        int modelRow = table.convertRowIndexToModel(viewRow);
        if (modelRow < 0 || modelRow >= entries.size()) return;
        File path = entries.get(modelRow).path;
        dispose();
        onOpen.accept(path);
    }
}
