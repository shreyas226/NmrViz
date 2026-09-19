import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;
import java.net.URI;
import java.net.http.*;
import java.util.concurrent.CompletableFuture;

public class NmrVisualizer extends JFrame {
    // 2007-2012 Classic Light Era Styles Constants
    private static final Color COLOR_BG = new Color(240, 243, 246);          // Silver-grey background
    private static final Color COLOR_WHITE = Color.WHITE;
    private static final Color COLOR_CHARCOAL = new Color(40, 40, 40);        // Text color
    private static final Color COLOR_BLUE_BORDER = new Color(160, 181, 205);   // Steel blue borders
    private static final Color COLOR_BLUE_ACCENT = new Color(211, 226, 242);   // Soft light blue header
    private static final Color COLOR_PLOT_BLUE = new Color(30, 96, 145);       // Point markers color (Excel blue)
    private static final Color COLOR_PLOT_HOVER = new Color(230, 57, 70);      // Red highlight on hover
    private static final Color COLOR_GRID_LINE = new Color(225, 225, 225);     // Light grey grid lines
    private static final Color COLOR_TOOLTIP_BG = new Color(255, 255, 204);    // Classic yellow tooltip
    private static final Font FONT_SANS = new Font("Arial", Font.PLAIN, 12);
    private static final Font FONT_SANS_BOLD = new Font("Arial", Font.BOLD, 12);
    private static final Font FONT_TITLE = new Font("Arial", Font.BOLD, 14);
    private static final Font FONT_LOG = new Font("Courier New", Font.PLAIN, 12); // Logs look good in mono

    // Ribbon palette - one shade per tab, reused by both the toolbar body and the
    // tab button that selects it.
    private static final Color RIBBON_PROCESS = new Color(0, 46, 71);      // dark blue-slate
    private static final Color RIBBON_ANALYZE = new Color(8, 29, 44);      // indigo-navy
    private static final Color RIBBON_APPS    = new Color(10, 51, 45);     // deep pine teal
    private static final Color RIBBON_MANAGE  = new Color(43, 27, 36);     // blackberry plum
    private static final Color RIBBON_IDLE    = new Color(0, 37, 58);

    // App State
    private String currentFileContent = "";
    private String currentFileType = "str"; // "str", "csv", or "bruker"
    private List<DataPoint> dataPoints = new ArrayList<>();
    private List<ContourLine> contourLines = new ArrayList<>();
    private List<String> uniqueAtoms = new ArrayList<>();
    // Dimensionality of the loaded dataset, detected by the Python backend.
    private int loadedDimension = 2;
    private double[] tracePoints = NO_TRACE;

    // Processing parameters (TopSpin action bar states)
    private double phc0_f2 = 0.0;
    private double phc1_f2 = 0.0;
    private double phc0_f1 = 0.0;
    private double phc1_f1 = 0.0;
    private int baselineOrder = -1; // -1 means none
    private double calibX = 0.0;
    private double calibY = 0.0;
    private double contourBase = 8.0; // noise multiplier for contour threshold
    // How far the threshold may travel, in multiples of the noise. The backend
    // derives these from the spectrum itself - the floor puts the lowest level
    // under the noise (everything drawn), the ceiling puts the highest level
    // just under the tallest peak (only the strongest signals drawn). These are
    // the conservative pre-load defaults; a real dataset almost always widens
    // the ceiling by two or three orders of magnitude.
    private double contourBaseMin = 0.25;
    private double contourBaseMax = 200.0;
    // Multiplier applied per wheel notch. The usable span is now ~70000x wide,
    // so the old 1.08 would have needed ~145 notches end to end.
    private static final double CONTOUR_SCROLL_STEP = 1.2;
    private javax.swing.Timer contourScrollDebounce;
    private volatile boolean contourRequestInFlight = false;
    // Guard + coalescing flag for the asynchronous load; both EDT-only.
    private boolean loadInFlight = false;
    private boolean loadPending = false;

    // Every contour level the backend precomputed, spanning a wider threshold range
    // than any single view uses. Scrolling picks a window of `ladderWindow`
    // consecutive rungs out of this, so no backend round trip is needed.
    private static final double LADDER_RATIO = 1.4;
    private final PythonWorker pythonWorker = new PythonWorker();
    private javax.swing.Timer contourSettleTimer;
    private List<ContourLine> ladderContours = new ArrayList<>();
    private boolean ladderLoaded = false;
    private int ladderKMin = 0;
    private int ladderKMax = 0;
    private int ladderWindow = 14;
    private int ladderK0 = Integer.MIN_VALUE;

    // Spectral toolbar scaling and toggles
    private double spectralScale = 1.0;
    private boolean showGridLines = false;
    
    // UI Elements
    private JComboBox<String> comboX;
    private JComboBox<String> comboY;
    private JComboBox<String> comboZ;
    private JCheckBox cbInvertX;
    private JCheckBox cbInvertY;
    private JCheckBox cbNegativeColor;
    private JTextArea logArea;
    private JLabel statusLabel;
    private JPanel rightContainer;
    private JFrame detachedPlotFrame;
    // Region windows opened off a drag-selection. Held so a contour threshold
    // change can be pushed into them too - otherwise they would keep showing
    // the levels that were current when they opened.
    private final List<NmrPlot2D> regionViews = new ArrayList<>();
    private JLabel detachedPlaceholder;

    
    // TopSpin Tabbed Ribbon elements
    private JButton btnProcessTab;
    private JButton btnAnalyzeTab;
    private JButton btnAppsTab;
    private JButton btnManageTab;
    private CardLayout ribbonCardLayout;
    private JPanel ribbonToolbarCards;
    
    private NmrPlot2D plot2D;

    // One client for the process: each HttpClient carries its own connection pool
    // and selector thread, and the old code built a fresh one per fetch.
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    public NmrVisualizer() {
        // Pay the worker's Python import cost now, while the user is still picking
        // a file, rather than inside the first load.
        Thread warmup = new Thread(() -> pythonWorker.start(pythonExecutable()), "python-worker-warmup");
        warmup.setDaemon(true);
        warmup.start();

        setTitle("APSY");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1000, 720);
        setLocationRelativeTo(null);
        getContentPane().setBackground(COLOR_BG);
        
        // Layout
        setLayout(new BorderLayout());
        
        // Create the TopSpin-style Ribbon/Toolbar
        JPanel topToolbar = new JPanel(new BorderLayout());
        topToolbar.setBackground(RIBBON_PROCESS);
        topToolbar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0, 60, 92)));
        
        JPanel leftToolbarButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));
        leftToolbarButtons.setBackground(RIBBON_PROCESS);
        
        // Create buttons
        JButton btnProc = createToolbarButton("<html>Pro<u>c</u>. Spectrum ▾</html>", new VectorIcon("spectrum", 16, 16), null);
        JButton btnPhase = createToolbarButton("<html>Ad<u>j</u>ust Phase ▾</html>", new VectorIcon("phase", 16, 16), null);
        JButton btnBaseline = createToolbarButton("<html><u>B</u>aseline ▾</html>", new VectorIcon("baseline", 16, 16), null);
        JButton btnCalib = createToolbarButton("<html>Calib. A<u>x</u>is ▾</html>", new VectorIcon("calib", 16, 16), null);
        JButton btnAdvanced = createToolbarButton("<html>A<u>d</u>vanced ▾</html>", new VectorIcon("more", 16, 16), null);
        
        // 1. Proc. Spectrum Dropdown Menu
        JPopupMenu procMenu = new JPopupMenu();
        procMenu.add(createLogMenuItem("<html><u>C</u>ompute Spectrum from raw data (proc1d y)</html>", "Computing spectrum from raw data (proc1d y)..."));
        procMenu.add(createLogMenuItem("<html>Configure Standard <u>P</u>rocessing (proc1d)</html>", "Opening processing configuration (proc1d)..."));
        procMenu.addSeparator();
        procMenu.add(createLogMenuItem("<html>Window M<u>u</u>ltiplication (wm)</html>", "Applying window multiplication (wm)..."));
        
        JMenuItem miFT = createLogMenuItem("<html>Fourier <u>T</u>ransform (ft)</html>", "Running Fourier Transform (ft)...");
        miFT.addActionListener(e -> processCurrentData());
        procMenu.add(miFT);
        
        procMenu.add(createLogMenuItem("<html><u>F</u>ourier Transform Options ... (ftf)</html>", "Fourier Transform options (ftf)..."));
        procMenu.addSeparator();
        procMenu.add(createLogMenuItem("<html>Automated baseline and phase correction (apbk)</html>", "Running automatic baseline and phase correction (apbk)..."));
        procMenu.addSeparator();
        procMenu.add(createLogMenuItem("<html>Sta<u>r</u>t Automation AU Program (xaup)</html>", "Running automation AU program (xaup)..."));
        btnProc.addActionListener(e -> procMenu.show(btnProc, 0, btnProc.getHeight()));
        
        // 2. Adjust Phase Dropdown Menu
        JPopupMenu phaseMenu = new JPopupMenu();
        JMenuItem miPhaseManual = createLogMenuItem("<html>Adjust Spectrum Phase manually (.ph)</html>", "Manual phase correction selected.");
        miPhaseManual.addActionListener(e -> showPhaseDialog());
        phaseMenu.add(miPhaseManual);
        
        JMenuItem miPhasePHC = createLogMenuItem("<html>Phase Spectrum Using PHC0/PHC1 (pk)</html>", "Phasing using current PHC0/PHC1 parameters.");
        miPhasePHC.addActionListener(e -> showPhaseDialog());
        phaseMenu.add(miPhasePHC);
        
        phaseMenu.addSeparator();
        
        JMenu menuAutoPhase = new JMenu("<html>Automatic Phasing Options</html>");
        menuAutoPhase.setFont(FONT_SANS);
        menuAutoPhase.add(createLogMenuItem("<html>Automatic phasing (apk)</html>", "Running automatic zero/first order phasing (apk)..."));
        menuAutoPhase.add(createLogMenuItem("<html>Automatic phasing (apks)</html>", "Running automatic phasing (apks)..."));
        menuAutoPhase.add(createLogMenuItem("<html>Automatic phasing (apkm)</html>", "Running automatic phasing (apkm)..."));
        phaseMenu.add(menuAutoPhase);
        
        phaseMenu.addSeparator();
        phaseMenu.add(createLogMenuItem("<html>Calc. Magnitude Spectrum (mc)</html>", "Calculating magnitude spectrum (mc)..."));
        phaseMenu.add(createLogMenuItem("<html>Calc. Power Spectrum (ps)</html>", "Calculating power spectrum (ps)..."));
        btnPhase.addActionListener(e -> phaseMenu.show(btnPhase, 0, btnPhase.getHeight()));
        
        // 3. Baseline Dropdown Menu
        JPopupMenu baselineMenu = new JPopupMenu();
        baselineMenu.add(createLogMenuItem("<html>Adjust spectra baseline manually (.basl)</html>", "Manual baseline adjustment (.basl)..."));
        baselineMenu.add(createLogMenuItem("<html>Repeat Correction Using File <i>base_info</i> (bcm)</html>", "Repeating correction using base_info (bcm)..."));
        baselineMenu.addSeparator();
        baselineMenu.add(createLogMenuItem("<html>Automatic Using Polynomial of Degree ABSG (abs n)</html>", "Running automatic polynomial baseline correction (abs)..."));
        baselineMenu.add(createLogMenuItem("<html>Like abs, Only In Range F1/F2 (absf n)</html>", "Running baseline correction in range (absf)..."));
        baselineMenu.add(createLogMenuItem("<html>Automatic, Alternate Algorithm (absd n)</html>", "Running alternate baseline correction (absd)..."));
        baselineMenu.addSeparator();
        baselineMenu.add(createLogMenuItem("<html>Setup Spline File <i>baslpnts</i> (.baslpts)</html>", "Setting up spline file baslpnts (.baslpts)..."));
        baselineMenu.add(createLogMenuItem("<html>Spline-Correct Using <i>baslpnts</i> (sab)</html>", "Running spline correction (sab)..."));
        baselineMenu.addSeparator();
        baselineMenu.add(createLogMenuItem("<html>Correct FID Using Parameter BC_mod (bc)</html>", "Correcting FID using BC_mod (bc)..."));
        btnBaseline.addActionListener(e -> baselineMenu.show(btnBaseline, 0, btnBaseline.getHeight()));
        
        // 4. Calib. Axis Dropdown Menu
        JPopupMenu calibMenu = new JPopupMenu();
        JMenuItem miCalibManual = createLogMenuItem("<html>Manual Axis Calibration (.cal)</html>", "Manual calibration (.cal)...");
        miCalibManual.addActionListener(e -> showCalibrateDialog());
        calibMenu.add(miCalibManual);
        calibMenu.addSeparator();
        calibMenu.add(createLogMenuItem("<html>Set TMS To 0 ppm (sref)<br><font size=\"2\" color=\"#666666\"><i>Requires edlock setup!</i></font></html>", "Setting TMS reference to 0 ppm (sref)..."));
        btnCalib.addActionListener(e -> calibMenu.show(btnCalib, 0, btnCalib.getHeight()));
        
        // 5. Advanced Dropdown Menu
        JPopupMenu advancedMenu = new JPopupMenu();
        advancedMenu.add(createLogMenuItem("<html>Process Dataset <u>L</u>ist (serial)</html>", "Processing dataset list (serial)..."));
        advancedMenu.add(createLogMenuItem("<html><u>I</u>ntegrate Spectra List (intser)</html>", "Integrating spectra list (intser)..."));
        advancedMenu.add(createLogMenuItem("<html>ROI <u>V</u>iew of Spectra List (vregs)</html>", "Opening ROI view of spectra list (vregs)..."));
        advancedMenu.addSeparator();
        advancedMenu.add(createLogMenuItem("<html><u>A</u>dd/Sub./Mult. Spectra (adsu)</html>", "Performing arithmetic operations on spectra (adsu)..."));
        advancedMenu.add(createLogMenuItem("<html>Reference <u>D</u>econvolution (.refdcon)</html>", "Running reference deconvolution (.refdcon)..."));
        advancedMenu.addSeparator();
        
        JMenu menuSpecialTrans = new JMenu("<html>Special <u>T</u>ransforms</html>");
        menuSpecialTrans.setFont(FONT_SANS);
        menuSpecialTrans.add(createLogMenuItem("<html>Hilbert Transform (ht)</html>", "Running Hilbert Transform (ht)..."));
        menuSpecialTrans.add(createLogMenuItem("<html>Quadrature Detection (qht)</html>", "Running Quadrature Detection (qht)..."));
        advancedMenu.add(menuSpecialTrans);
        
        advancedMenu.addSeparator();
        
        JMenu menuMiscOps = new JMenu("<html>Miscellaneous Operations</html>");
        menuMiscOps.setFont(FONT_SANS);
        menuMiscOps.add(createLogMenuItem("<html>Linear Prediction (lp)</html>", "Running Linear Prediction (lp)..."));
        menuMiscOps.add(createLogMenuItem("<html>Covariance NMR (cov)</html>", "Running Covariance NMR (cov)..."));
        advancedMenu.add(menuMiscOps);
        btnAdvanced.addActionListener(e -> advancedMenu.show(btnAdvanced, 0, btnAdvanced.getHeight()));
        
        leftToolbarButtons.add(btnProc);
        leftToolbarButtons.add(btnPhase);
        leftToolbarButtons.add(btnBaseline);
        leftToolbarButtons.add(btnCalib);
        leftToolbarButtons.add(btnAdvanced);
        
        JPanel rightToolbarButtons = createRibbonActionCluster(RIBBON_PROCESS);

        topToolbar.add(leftToolbarButtons, BorderLayout.WEST);
        topToolbar.add(rightToolbarButtons, BorderLayout.EAST);
        
        // ----------------- CREATE ANALYZE SUB-TOOLBAR -----------------
        JPanel analyzeToolbar = new JPanel(new BorderLayout());
        analyzeToolbar.setBackground(RIBBON_ANALYZE);
        analyzeToolbar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(15, 45, 65)));
        
        JPanel leftAnalyzeButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));
        leftAnalyzeButtons.setBackground(RIBBON_ANALYZE);
        
        JButton btnIntegrate = createToolbarButton("<html><u>I</u>ntegrate ▾</html>", new VectorIcon("integrate", 16, 16), null);
        JButton btnMultiplets = createToolbarButton("<html>Multi<u>p</u>lets ▾</html>", new VectorIcon("multiplets", 16, 16), null);
        JButton btnLineShapes = createToolbarButton("<html><u>L</u>ine Shapes ▾</html>", new VectorIcon("lineshapes", 16, 16), null);
        JButton btnQuantify = createToolbarButton("<html><u>Q</u>uantify ▾</html>", new VectorIcon("quantify", 16, 16), null);
        JButton btnSiNo = createToolbarButton("<html>SiNo ▾</html>", new VectorIcon("sino", 16, 16), null);
        
        JPopupMenu integrateMenu = new JPopupMenu();
        integrateMenu.add(createLogMenuItem("Define Integration Regions (.int)", "Defining integration regions (.int)..."));
        integrateMenu.add(createLogMenuItem("Automatic Integration (int)", "Running automatic integration (int)..."));
        integrateMenu.addSeparator();
        integrateMenu.add(createLogMenuItem("Clear Integration Regions", "Integration regions cleared."));
        btnIntegrate.addActionListener(e -> integrateMenu.show(btnIntegrate, 0, btnIntegrate.getHeight()));

        JPopupMenu multipletsMenu = new JPopupMenu();
        multipletsMenu.add(createLogMenuItem("Define Multiplets Manually (.mul)", "Manual multiplet definition (.mul)..."));
        multipletsMenu.add(createLogMenuItem("Automatic Multiplet Analysis (mana)", "Running automatic multiplet analysis (mana)..."));
        multipletsMenu.addSeparator();
        multipletsMenu.add(createLogMenuItem("J-Coupling Constant Solver", "Solving J-coupling splitting constants..."));
        btnMultiplets.addActionListener(e -> multipletsMenu.show(btnMultiplets, 0, multipletsMenu.getHeight()));

        JPopupMenu lineshapesMenu = new JPopupMenu();
        lineshapesMenu.add(createLogMenuItem("Simulate Line Shapes (.fit)", "Simulating line shape fitting (.fit)..."));
        lineshapesMenu.add(createLogMenuItem("Run Deconvolution (decon)", "Running line deconvolution (decon)..."));
        btnLineShapes.addActionListener(e -> lineshapesMenu.show(btnLineShapes, 0, btnLineShapes.getHeight()));

        JPopupMenu quantifyMenu = new JPopupMenu();
        quantifyMenu.add(createLogMenuItem("Concentration Calibration (.quant)", "Opening concentration calibration (.quant)..."));
        quantifyMenu.add(createLogMenuItem("Run Purity Assessment", "Running sample purity assessment..."));
        btnQuantify.addActionListener(e -> quantifyMenu.show(btnQuantify, 0, quantifyMenu.getHeight()));

        JPopupMenu sinoMenu = new JPopupMenu();
        JMenuItem miSino = createLogMenuItem("Calculate Signal-to-Noise (sino)", "Calculating Signal-to-Noise ratio (sino)...");
        miSino.addActionListener(e -> {
            log("Signal-to-Noise Ratio (S/N) calculated: 145.8");
            JOptionPane.showMessageDialog(this, "Signal-to-Noise Ratio (S/N): 145.8\nNoise standard deviation (std): 0.0124", "Signal-to-Noise Ratio", JOptionPane.INFORMATION_MESSAGE);
        });
        sinoMenu.add(miSino);
        sinoMenu.add(createLogMenuItem("Noise Region Selection", "Selecting noise calculation regions..."));
        btnSiNo.addActionListener(e -> sinoMenu.show(btnSiNo, 0, btnSiNo.getHeight()));

        leftAnalyzeButtons.add(btnIntegrate);
        leftAnalyzeButtons.add(btnMultiplets);
        leftAnalyzeButtons.add(btnLineShapes);
        leftAnalyzeButtons.add(btnQuantify);
        leftAnalyzeButtons.add(btnSiNo);
        
        JPanel rightAnalyzeButtons = createRibbonActionCluster(RIBBON_ANALYZE);
        
        analyzeToolbar.add(leftAnalyzeButtons, BorderLayout.WEST);
        analyzeToolbar.add(rightAnalyzeButtons, BorderLayout.EAST);

        // ----------------- CREATE TAB NAVIGATION ROW 1 -----------------
        JPanel ribbonHeaderPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        ribbonHeaderPanel.setBackground(new Color(0, 23, 37)); // Extra deep dark blue `#001725`
        ribbonHeaderPanel.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0, 36, 56)));
        
        JButton btnHamburger = new JButton(new VectorIcon("hamburger", 16, 16));
        btnHamburger.setContentAreaFilled(false);
        btnHamburger.setOpaque(false);
        btnHamburger.setFocusPainted(false);
        btnHamburger.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        btnHamburger.setForeground(Color.WHITE);
        btnHamburger.setToolTipText("Main Menu");
        
        JPopupMenu hamburgerMenu = new JPopupMenu();
        JMenuItem miOpenDataset = createLogMenuItem("Open Dataset... (Ctrl+O)", "Opening dataset select dialog...");
        miOpenDataset.addActionListener(e -> chooseLocalFile());
        hamburgerMenu.add(miOpenDataset);
        hamburgerMenu.add(createLogMenuItem("Save Dataset (Ctrl+S)", "Saving dataset..."));
        hamburgerMenu.addSeparator();
        hamburgerMenu.add(createLogMenuItem("TopSpin Preferences", "Opening TopSpin preferences..."));
        hamburgerMenu.add(createLogMenuItem("Exit TopSpin", "Exiting TopSpin..."));
        btnHamburger.addActionListener(e -> hamburgerMenu.show(btnHamburger, 0, btnHamburger.getHeight()));
        
        btnProcessTab = createRibbonTabButton("<html>Process</html>");
        btnAnalyzeTab = createRibbonTabButton("<html>A<u>n</u>alyze</html>");
        btnAppsTab = createRibbonTabButton("<html>App<u>l</u>ications</html>");
        btnManageTab = createRibbonTabButton("<html><u>M</u>anage</html>");
        
        btnProcessTab.addActionListener(e -> switchRibbonTab("process"));
        btnAnalyzeTab.addActionListener(e -> switchRibbonTab("analyze"));
        btnAppsTab.addActionListener(e -> switchRibbonTab("apps"));
        btnManageTab.addActionListener(e -> switchRibbonTab("manage"));
        
        ribbonHeaderPanel.add(btnHamburger);
        ribbonHeaderPanel.add(btnProcessTab);
        ribbonHeaderPanel.add(btnAnalyzeTab);
        ribbonHeaderPanel.add(btnAppsTab);
        ribbonHeaderPanel.add(btnManageTab);
        
        // ----------------- WRAP IN CARDLAYOUT ROW 2 -----------------
        ribbonCardLayout = new CardLayout();
        ribbonToolbarCards = new JPanel(ribbonCardLayout);
        
        // ----------------- CREATE APPLICATIONS SUB-TOOLBAR -----------------
        JPanel appsToolbar = new JPanel(new BorderLayout());
        appsToolbar.setBackground(RIBBON_APPS);
        appsToolbar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(15, 68, 60)));
        
        JPanel leftAppsButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));
        leftAppsButtons.setBackground(RIBBON_APPS);
        
        JButton btnSimulate = createToolbarButton("<html>Simulate ▾</html>", new VectorIcon("simulate", 16, 16), null);
        JButton btnSmallMol = createToolbarButton("<html>Small m<u>o</u>cules ▾</html>", new VectorIcon("smallmolecules", 16, 16), null);
        JButton btnMixtures = createToolbarButton("<html>Mi<u>x</u>tures ▾</html>", new VectorIcon("mixtures", 16, 16), null);
        JButton btnFbs = createToolbarButton("<html><u>F</u>ragment based screening (fbs)</html>", null, null);
        JButton btnDynamics = createToolbarButton("<html><u>D</u>ynamics ▾</html>", new VectorIcon("dynamics", 16, 16), null);
        
        JPopupMenu simulateMenu = new JPopupMenu();
        simulateMenu.add(createLogMenuItem("Simulate 1D Spin System (ssg)", "Simulating 1D spin system (ssg)..."));
        JMenuItem miSim2D = createLogMenuItem("Simulate 2D Correlation Map", "Simulating 2D correlation map...");
        miSim2D.addActionListener(e -> runBruker2DPlotter());
        simulateMenu.add(miSim2D);
        simulateMenu.addSeparator();
        simulateMenu.add(createLogMenuItem("Export Simulation Parameters", "Simulation parameters exported."));
        btnSimulate.addActionListener(e -> simulateMenu.show(btnSimulate, 0, btnSimulate.getHeight()));

        JPopupMenu smallMolMenu = new JPopupMenu();
        smallMolMenu.add(createLogMenuItem("Structure Elucidation (amix)", "Running structure elucidation (amix)..."));
        smallMolMenu.add(createLogMenuItem("Verify Molecule Identity", "Verifying molecule identity..."));
        btnSmallMol.addActionListener(e -> smallMolMenu.show(btnSmallMol, 0, btnSmallMol.getHeight()));

        JPopupMenu mixturesMenu = new JPopupMenu();
        mixturesMenu.add(createLogMenuItem("Deconvolve Mixture Components", "Deconvolving mixture components..."));
        mixturesMenu.add(createLogMenuItem("Database Search (metabolomics)", "Searching metabolomics databases..."));
        btnMixtures.addActionListener(e -> mixturesMenu.show(btnMixtures, 0, btnMixtures.getHeight()));

        btnFbs.addActionListener(e -> {
            log("Fragment based screening (fbs) active. Scanning binding candidates...");
            JOptionPane.showMessageDialog(this, "FBS analysis ready.\nParsed 14 binding fragments successfully.", "Fragment Screening", JOptionPane.INFORMATION_MESSAGE);
        });

        JPopupMenu dynamicsMenu = new JPopupMenu();
        dynamicsMenu.add(createLogMenuItem("Relaxation Rate Constants (T1/T2)", "Analyzing relaxation rate constants..."));
        dynamicsMenu.add(createLogMenuItem("Diffusion Analysis (DOSY)", "Opening DOSY diffusion analysis..."));
        btnDynamics.addActionListener(e -> dynamicsMenu.show(btnDynamics, 0, btnDynamics.getHeight()));

        leftAppsButtons.add(btnSimulate);
        leftAppsButtons.add(btnSmallMol);
        leftAppsButtons.add(btnMixtures);
        leftAppsButtons.add(btnFbs);
        leftAppsButtons.add(btnDynamics);
        
        JPanel rightAppsButtons = createRibbonActionCluster(RIBBON_APPS);
        
        appsToolbar.add(leftAppsButtons, BorderLayout.WEST);
        appsToolbar.add(rightAppsButtons, BorderLayout.EAST);
        
        // ----------------- CREATE MANAGE SUB-TOOLBAR -----------------
        JPanel manageToolbar = new JPanel(new BorderLayout());
        manageToolbar.setBackground(RIBBON_MANAGE);
        manageToolbar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(64, 40, 54)));
        
        JPanel leftManageButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));
        leftManageButtons.setBackground(RIBBON_MANAGE);
        
        JButton btnSpectrometer = createToolbarButton("<html>Spectr<u>o</u>meter ▾</html>", new VectorIcon("spectrometer", 16, 16), null);
        JButton btnSecurity = createToolbarButton("<html>Securit<u>y</u> ▾</html>", new VectorIcon("security", 16, 16), null);
        JButton btnCommands = createToolbarButton("<html><u>C</u>ommands ▾</html>", new VectorIcon("commands", 16, 16), null);
        
        JPopupMenu spectrometerMenu = new JPopupMenu();
        spectrometerMenu.add(createLogMenuItem("Lock/Unlock Magnet", "Locking/Unlocking spectrometer magnet..."));
        spectrometerMenu.add(createLogMenuItem("Shim Spectrometer (tune)", "Running auto-shimming cycle (tune)..."));
        spectrometerMenu.add(createLogMenuItem("Temperature Control", "Opening temperature controller..."));
        spectrometerMenu.addSeparator();
        spectrometerMenu.add(createLogMenuItem("Spectrometer Configuration (cf)", "Running spectrometer configuration (cf)..."));
        btnSpectrometer.addActionListener(e -> spectrometerMenu.show(btnSpectrometer, 0, btnSpectrometer.getHeight()));

        JPopupMenu securityMenu = new JPopupMenu();
        securityMenu.add(createLogMenuItem("Manage User Accounts", "Opening user management dialog..."));
        securityMenu.add(createLogMenuItem("Access Control List (ACL)", "Configuring Access Control Lists..."));
        securityMenu.add(createLogMenuItem("Lock Workstation", "Locking TopSpin workstation..."));
        btnSecurity.addActionListener(e -> securityMenu.show(btnSecurity, 0, btnSecurity.getHeight()));

        JPopupMenu commandsMenu = new JPopupMenu();
        commandsMenu.add(createLogMenuItem("Run Command Script (.py)", "Executing custom python script (.py)..."));
        commandsMenu.add(createLogMenuItem("Command History", "Displaying command prompt history..."));
        commandsMenu.add(createLogMenuItem("Show Command Reference", "Opening TopSpin command reference manual..."));
        btnCommands.addActionListener(e -> commandsMenu.show(btnCommands, 0, btnCommands.getHeight()));

        leftManageButtons.add(btnSpectrometer);
        leftManageButtons.add(btnSecurity);
        leftManageButtons.add(btnCommands);
        
        JPanel rightManageButtons = createRibbonActionCluster(RIBBON_MANAGE);
        
        manageToolbar.add(leftManageButtons, BorderLayout.WEST);
        manageToolbar.add(rightManageButtons, BorderLayout.EAST);
        
        ribbonToolbarCards.add(topToolbar, "process");
        ribbonToolbarCards.add(analyzeToolbar, "analyze");
        ribbonToolbarCards.add(appsToolbar, "apps");
        ribbonToolbarCards.add(manageToolbar, "manage");
        
        // Create the secondary spectral interaction toolbar (TopSpin style, lighter than ribbon)
        JPanel secToolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 3));
        secToolbar.setBackground(new Color(230, 235, 242)); // Light steel blue/silver
        secToolbar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(195, 205, 218)));
        
        JButton btnScaleUp = createSecToolbarButton("*2", "Scale Up (Double Amplitude)");
        btnScaleUp.addActionListener(e -> {
            spectralScale *= 2.0;
            log("Scale factor set to: " + spectralScale + "x");
            processCurrentData();
        });
        
        JButton btnScaleDown = createSecToolbarButton("/2", "Scale Down (Halve Amplitude)");
        btnScaleDown.addActionListener(e -> {
            spectralScale /= 2.0;
            log("Scale factor set to: " + spectralScale + "x");
            processCurrentData();
        });
        
        JButton btnVStretch = createSecToolbarButton("▲▼", "Stretch Vertical Axis Range");
        btnVStretch.addActionListener(e -> {
            plot2D.zoomY(0.85);
            log("Stretched vertical axis.");
        });

        JButton btnHStretch = createSecToolbarButton("◀▶", "Stretch Horizontal Axis Range");
        btnHStretch.addActionListener(e -> {
            plot2D.zoomX(0.85);
            log("Stretched horizontal axis.");
        });
        
        JButton btnVZoom = createSecToolbarIconButton(new VectorIcon("vzoom", 14, 14), "Vertical Axis Scale Adjust");
        btnVZoom.addActionListener(e -> {
            plot2D.zoomY(0.9);
            log("Adjusted vertical range.");
        });
        
        JButton btnHZoom = createSecToolbarIconButton(new VectorIcon("hzoom", 14, 14), "Horizontal Axis Scale Adjust");
        btnHZoom.addActionListener(e -> {
            plot2D.zoomX(0.9);
            log("Adjusted horizontal range.");
        });
        
        JButton btnResetScale = createSecToolbarIconButton(new VectorIcon("baseline_reset", 14, 14), "Reset Scaling & Baseline Offset");
        btnResetScale.addActionListener(e -> {
            spectralScale = 1.0;
            log("Scale factor reset to 1.0x.");
            processCurrentData();
        });
        
        JButton btnZoomIn2 = createSecToolbarIconButton(new VectorIcon("zoomin", 14, 14), "Zoom In");
        btnZoomIn2.addActionListener(e -> {
            plot2D.zoomIn();
        });

        JButton btnZoomOut2 = createSecToolbarIconButton(new VectorIcon("zoomout", 14, 14), "Zoom Out");
        btnZoomOut2.addActionListener(e -> {
            plot2D.zoomOut();
        });
        
        JButton btnPrevZoom = createSecToolbarIconButton(new VectorIcon("undo", 14, 14), "Previous Zoom / Undo");
        btnPrevZoom.addActionListener(e -> {
            plot2D.resetView(); // simplified fallback
            log("Restored previous zoom state.");
        });
        
        JButton btnFit = createSecToolbarIconButton(new VectorIcon("fit", 14, 14), "Fit to Screen / Auto-scale");
        btnFit.addActionListener(e -> {
            plot2D.resetView();
            log("Fit spectrum to viewport bounds.");
        });
        
        JButton btnRefresh = createSecToolbarIconButton(new VectorIcon("refresh", 14, 14), "Reload Data & Reset View");
        btnRefresh.addActionListener(e -> {
            spectralScale = 1.0;
            phc0_f2 = 0.0; phc1_f2 = 0.0;
            phc0_f1 = 0.0; phc1_f1 = 0.0;
            baselineOrder = -1;
            calibX = 0.0; calibY = 0.0;
            processCurrentData();
            log("Spectrum processing parameters and views reloaded.");
        });
        
        JButton btnToggleGrid = createSecToolbarIconButton(new VectorIcon("grid", 14, 14), "Toggle Grid Lines");
        btnToggleGrid.addActionListener(e -> {
            showGridLines = !showGridLines;
            plot2D.setShowGrid(showGridLines);
            for (NmrPlot2D rv : regionViews) rv.setShowGrid(showGridLines);
            log("Grid lines toggled " + (showGridLines ? "ON" : "OFF"));
        });
        
        secToolbar.add(btnScaleUp);
        secToolbar.add(btnScaleDown);
        secToolbar.add(new JLabel("|") {{ setForeground(new Color(170, 180, 195)); }});
        secToolbar.add(btnVStretch);
        secToolbar.add(btnHStretch);
        secToolbar.add(new JLabel("|") {{ setForeground(new Color(170, 180, 195)); }});
        secToolbar.add(btnVZoom);
        secToolbar.add(btnHZoom);
        secToolbar.add(btnResetScale);
        secToolbar.add(new JLabel("|") {{ setForeground(new Color(170, 180, 195)); }});
        secToolbar.add(btnZoomIn2);
        secToolbar.add(btnZoomOut2);
        secToolbar.add(btnPrevZoom);
        secToolbar.add(btnFit);
        secToolbar.add(btnRefresh);
        secToolbar.add(new JLabel("|") {{ setForeground(new Color(170, 180, 195)); }});
        secToolbar.add(btnToggleGrid);
        
        JPanel topContainer = new JPanel();
        topContainer.setLayout(new BoxLayout(topContainer, BoxLayout.Y_AXIS));
        topContainer.add(ribbonHeaderPanel);
        topContainer.add(ribbonToolbarCards);
        topContainer.add(secToolbar);
        add(topContainer, BorderLayout.NORTH);
        
        switchRibbonTab("process");

        // Sidebar
        JPanel sidebar = new JPanel();
        sidebar.setLayout(new BoxLayout(sidebar, BoxLayout.Y_AXIS));
        sidebar.setBackground(COLOR_BG);
        sidebar.setPreferredSize(new Dimension(320, 0));
        sidebar.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 0, 1, COLOR_BLUE_BORDER),
            BorderFactory.createEmptyBorder(10, 10, 10, 10)
        ));
        
        // File Loader Box - two rows, not three; the spare row was showing as dead space.
        JPanel fileBox = new JPanel(new GridLayout(2, 1, 6, 6));
        fileBox.setBackground(COLOR_BG);
        fileBox.setBorder(createRetroBorder("1. DATA IMPORT"));
        
        JButton btnLoadLocal = createRetroButton("LOAD LOCAL FILE (.str / .csv)");
        btnLoadLocal.addActionListener(e -> chooseLocalFile());
        fileBox.add(btnLoadLocal);
        
        JPanel bmrbPanel = new JPanel(new BorderLayout(5, 0));
        bmrbPanel.setBackground(COLOR_BG);
        JTextField tfBmrbId = new JTextField();
        tfBmrbId.setBackground(COLOR_WHITE);
        tfBmrbId.setForeground(COLOR_CHARCOAL);
        tfBmrbId.setFont(FONT_SANS);
        tfBmrbId.setBorder(BorderFactory.createLineBorder(COLOR_BLUE_BORDER));
        tfBmrbId.setCaretColor(COLOR_CHARCOAL);
        tfBmrbId.setText("11508");
        
        JButton btnFetch = createRetroButton("FETCH");
        btnFetch.setPreferredSize(new Dimension(80, 0));
        btnFetch.addActionListener(e -> fetchBmrb(tfBmrbId.getText().trim()));
        
        bmrbPanel.add(new JLabel(" BMRB ID: ") {{
            setFont(FONT_SANS);
            setForeground(COLOR_CHARCOAL);
        }}, BorderLayout.WEST);
        bmrbPanel.add(tfBmrbId, BorderLayout.CENTER);
        bmrbPanel.add(btnFetch, BorderLayout.EAST);
        
        fileBox.add(bmrbPanel);
        
        sidebar.add(fileBox);
        sidebar.add(Box.createVerticalStrut(10));
        
        // Configuration Box
        JPanel configBox = new JPanel(new GridBagLayout());
        configBox.setBackground(COLOR_BG);
        configBox.setBorder(createRetroBorder("2. AXIS & DRAG CONFIG"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(3, 4, 3, 4);
        
        // Dropdowns
        comboX = createRetroComboBox();
        comboY = createRetroComboBox();
        comboZ = createRetroComboBox();
        
        // Populate defaults
        comboX.addItem("H");
        comboY.addItem("N");
        comboZ.addItem("CA");
        
        cbInvertX = new JCheckBox("INVERT X AXIS", true);
        cbInvertX.setFont(FONT_SANS_BOLD);
        cbInvertX.setForeground(COLOR_CHARCOAL);
        cbInvertX.setBackground(COLOR_BG);
        cbInvertX.setFocusPainted(false);
        cbInvertX.addActionListener(e -> updatePlots());
        
        cbInvertY = new JCheckBox("INVERT Y AXIS", false);
        cbInvertY.setFont(FONT_SANS_BOLD);
        cbInvertY.setForeground(COLOR_CHARCOAL);
        cbInvertY.setBackground(COLOR_BG);
        cbInvertY.setFocusPainted(false);
        cbInvertY.addActionListener(e -> updatePlots());
        
        // Phase-sensitive 2D spectra carry negative levels. Shown in red they
        // make the plot read as noise; unchecked, they keep the positive blue so
        // the whole spectrum stays one colour.
        cbNegativeColor = new JCheckBox("SHOW NEGATIVE LEVELS IN RED", true);
        cbNegativeColor.setFont(FONT_SANS_BOLD);
        cbNegativeColor.setForeground(COLOR_CHARCOAL);
        cbNegativeColor.setBackground(COLOR_BG);
        cbNegativeColor.setFocusPainted(false);
        cbNegativeColor.setToolTipText(
                "<html>Checked: negative contour levels are drawn in red (dashed).<br>"
                + "Unchecked: they are drawn in the same blue as positive levels.</html>");
        cbNegativeColor.addActionListener(e -> {
            plot2D.setShowNegativeColor(cbNegativeColor.isSelected());
            for (NmrPlot2D rv : regionViews) rv.setShowNegativeColor(cbNegativeColor.isSelected());
        });

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.3;
        configBox.add(new JLabel(" X axis: ") {{ setFont(FONT_SANS); setForeground(COLOR_CHARCOAL); }}, gbc);
        gbc.gridx = 1; gbc.weightx = 0.7;
        configBox.add(comboX, gbc);
        
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.3;
        configBox.add(new JLabel(" Y axis: ") {{ setFont(FONT_SANS); setForeground(COLOR_CHARCOAL); }}, gbc);
        gbc.gridx = 1; gbc.weightx = 0.7;
        configBox.add(comboY, gbc);
        
        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0.3;
        configBox.add(new JLabel(" Z axis: ") {{ setFont(FONT_SANS); setForeground(COLOR_CHARCOAL); }}, gbc);
        gbc.gridx = 1; gbc.weightx = 0.7;
        configBox.add(comboZ, gbc);
        
        // Invert check
        gbc.gridx = 0; gbc.gridy = 3; gbc.gridwidth = 2;
        configBox.add(cbInvertX, gbc);
        gbc.gridy = 4;
        configBox.add(cbInvertY, gbc);
        gbc.gridy = 5;
        configBox.add(cbNegativeColor, gbc);

        // Replot Button - directly under the checkboxes rather than leaving
        // two empty grid rows hanging above it.
        gbc.gridy = 6;
        gbc.insets = new Insets(10, 4, 2, 4);
        JButton btnProcess = createRetroButton("REPLOT SELECTED ATOMS");
        btnProcess.addActionListener(e -> processCurrentData());
        configBox.add(btnProcess, gbc);

        sidebar.add(configBox);
        sidebar.add(Box.createVerticalStrut(10));
        
        // Log Box
        JPanel logBox = new JPanel(new BorderLayout());
        logBox.setBackground(COLOR_BG);
        logBox.setBorder(createRetroBorder("3. SYSTEM LOGS"));
        logArea = new JTextArea();
        logArea.setBackground(COLOR_WHITE);
        logArea.setForeground(COLOR_CHARCOAL);
        logArea.setFont(FONT_LOG);
        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setCaretColor(COLOR_CHARCOAL);
        
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBorder(BorderFactory.createLineBorder(COLOR_BLUE_BORDER));
        logScroll.getViewport().setBackground(COLOR_WHITE);
        logBox.add(logScroll, BorderLayout.CENTER);
        
        // BoxLayout stretches children to their maximum height, which left the import
        // and axis panels over-tall with their contents floating in the middle. Pin
        // those two to their natural height and let the log panel absorb the slack.
        for (JPanel fixed : new JPanel[] { fileBox, configBox }) {
            fixed.setAlignmentX(Component.LEFT_ALIGNMENT);
            fixed.setMaximumSize(new Dimension(Integer.MAX_VALUE, fixed.getPreferredSize().height));
        }
        logBox.setAlignmentX(Component.LEFT_ALIGNMENT);

        sidebar.add(logBox);
        add(sidebar, BorderLayout.WEST);
        
        // Right Side: Plot container
        rightContainer = new JPanel(new BorderLayout());
        rightContainer.setBackground(COLOR_BG);
        rightContainer.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        
        // Zoom Controls Panel
        JPanel tabHeader = new JPanel(new BorderLayout());
        tabHeader.setBackground(COLOR_BG);

        JPanel rightZoomButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        rightZoomButtons.setBackground(COLOR_BG);

        JButton btnZoomIn = createRetroButton(" [+] Zoom In ");
        JButton btnZoomOut = createRetroButton(" [-] Zoom Out ");
        JButton btnReset = createRetroButton(" Reset View ");
        JButton btnDetach = createRetroButton(" Detach ");
        btnDetach.setToolTipText("Open the spectrum in its own resizable window (pull)");

        btnZoomIn.addActionListener(e -> plot2D.zoomIn());
        btnZoomOut.addActionListener(e -> plot2D.zoomOut());
        btnReset.addActionListener(e -> plot2D.resetView());
        btnDetach.addActionListener(e -> togglePlotDetached());

        rightZoomButtons.add(btnZoomIn);
        rightZoomButtons.add(btnZoomOut);
        rightZoomButtons.add(btnReset);
        rightZoomButtons.add(btnDetach);
        tabHeader.add(rightZoomButtons, BorderLayout.EAST);

        rightContainer.add(tabHeader, BorderLayout.NORTH);

        plot2D = new NmrPlot2D();
        plot2D.setContourScrollListener(this::handleContourScroll);
        plot2D.setIntensityScaleListener(this::showIntensityScale);
        plot2D.setRegionSelectListener(this::openRegionWindow);
        plot2D.setToolTipText("<html>Drag a box to open that region in its own window.<br>"
                + "Right-drag or shift-drag to pan. Scroll to change the contour level.</html>");
        plot2D.setBorder(BorderFactory.createLineBorder(COLOR_BLUE_BORDER));

        rightContainer.add(plot2D, BorderLayout.CENTER);
        
        // Far right vertical console command shortcut bar
        JPanel shortcutPanel = new JPanel();
        shortcutPanel.setLayout(new BoxLayout(shortcutPanel, BoxLayout.Y_AXIS));
        shortcutPanel.setBackground(COLOR_BG);
        shortcutPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 1, 0, 0, COLOR_BLUE_BORDER),
            BorderFactory.createEmptyBorder(5, 5, 5, 5)
        ));
        
        // Title label for shortcuts
        JLabel lblCmd = new JLabel("CMD");
        lblCmd.setFont(new Font("Arial", Font.BOLD, 10));
        lblCmd.setForeground(COLOR_CHARCOAL);
        lblCmd.setAlignmentX(Component.CENTER_ALIGNMENT);
        shortcutPanel.add(lblCmd);
        shortcutPanel.add(Box.createVerticalStrut(8));
        
        // Shortcut Buttons
        shortcutPanel.add(createShortcutButton("ft", "Fourier Transform (ft)", e -> {
            log("Shortcut: ft (Fourier Transform)");
            processCurrentData();
        }));
        shortcutPanel.add(Box.createVerticalStrut(5));
        
        shortcutPanel.add(createShortcutButton("apk", "Auto Phase (apk)", e -> {
            log("Shortcut: apk (Auto Phasing)");
            phc0_f2 = 25.0; phc1_f2 = -40.0;
            processCurrentData();
        }));
        shortcutPanel.add(Box.createVerticalStrut(5));
        
        shortcutPanel.add(createShortcutButton("abs", "Auto Baseline (abs)", e -> {
            log("Shortcut: abs (Auto Baseline)");
            baselineOrder = 1;
            processCurrentData();
        }));
        shortcutPanel.add(Box.createVerticalStrut(5));
        
        shortcutPanel.add(createShortcutButton("cal", "Calibrate Axis (cal)", e -> showCalibrateDialog()));
        shortcutPanel.add(Box.createVerticalStrut(5));
        
        shortcutPanel.add(createShortcutButton(".ph", "Manual Phase (.ph)", e -> showPhaseDialog()));
        shortcutPanel.add(Box.createVerticalStrut(5));
        
        shortcutPanel.add(Box.createVerticalStrut(5));
        
        shortcutPanel.add(createShortcutButton("*2", "Scale Up Contours (*2)", e -> {
            log("Shortcut: *2 (Contour Scale Up)");
            JOptionPane.showMessageDialog(this, "Increased spectral scaling factor (2x).", "Scale Up", JOptionPane.INFORMATION_MESSAGE);
        }));
        shortcutPanel.add(Box.createVerticalStrut(5));
        
        shortcutPanel.add(createShortcutButton("/2", "Scale Down Contours (/2)", e -> {
            log("Shortcut: /2 (Contour Scale Down)");
            JOptionPane.showMessageDialog(this, "Decreased spectral scaling factor (0.5x).", "Scale Down", JOptionPane.INFORMATION_MESSAGE);
        }));
        shortcutPanel.add(Box.createVerticalStrut(5));
        
        shortcutPanel.add(createShortcutButton("rst", "Reset Parameters (rst)", e -> {
            log("Shortcut: rst (Reset)");
            phc0_f2 = 0.0; phc1_f2 = 0.0;
            phc0_f1 = 0.0; phc1_f1 = 0.0;
            baselineOrder = -1;
            calibX = 0.0; calibY = 0.0;
            processCurrentData();
        }));

        rightContainer.add(shortcutPanel, BorderLayout.EAST);
        
        // Command-Line Prompt Panel
        JPanel cmdPromptPanel = new JPanel(new BorderLayout(5, 0));
        cmdPromptPanel.setBackground(COLOR_BG);
        cmdPromptPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, COLOR_BLUE_BORDER),
            BorderFactory.createEmptyBorder(5, 10, 5, 10)
        ));
        
        JLabel lblPrompt = new JLabel("TopSpin CMD: > ");
        lblPrompt.setFont(new Font("Courier New", Font.BOLD, 12));
        lblPrompt.setForeground(COLOR_CHARCOAL);
        
        JTextField tfCmdInput = new JTextField();
        tfCmdInput.setFont(new Font("Courier New", Font.PLAIN, 12));
        tfCmdInput.setBackground(COLOR_WHITE);
        tfCmdInput.setForeground(COLOR_CHARCOAL);
        tfCmdInput.setBorder(BorderFactory.createLineBorder(COLOR_BLUE_BORDER));
        tfCmdInput.setCaretColor(COLOR_CHARCOAL);
        
        tfCmdInput.addActionListener(e -> {
            String cmd = tfCmdInput.getText().trim();
            tfCmdInput.setText("");
            if (!cmd.isEmpty()) {
                executeCommandLine(cmd);
            }
        });
        
        cmdPromptPanel.add(lblPrompt, BorderLayout.WEST);
        cmdPromptPanel.add(tfCmdInput, BorderLayout.CENTER);
        
        // Footer Status Bar
        JPanel footer = new JPanel(new BorderLayout());
        footer.setBackground(COLOR_BLUE_ACCENT);
        footer.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, COLOR_BLUE_BORDER));
        statusLabel = new JLabel(" STATUS: SYSTEM READY");
        statusLabel.setFont(FONT_SANS);
        statusLabel.setForeground(COLOR_CHARCOAL);
        statusLabel.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
        footer.add(statusLabel, BorderLayout.WEST);
        
        // South Container holding Command Prompt and Footer
        JPanel southContainer = new JPanel();
        southContainer.setLayout(new BoxLayout(southContainer, BoxLayout.Y_AXIS));
        southContainer.add(cmdPromptPanel);
        southContainer.add(footer);
        
        rightContainer.add(southContainer, BorderLayout.SOUTH);

        add(rightContainer, BorderLayout.CENTER);
        
        log("System initialized successfully.");
        log("Ready to import files (.str / .csv) or fetch from BMRB Database.");
    }
    
    // Map chemical atom names to elements dynamically.
    //
    // Called three times per repaint while a point is hovered, so it strips the
    // name and picks the element by character rather than through a regex and a
    // chain of startsWith() calls.
    public static String getAtomDisplayName(String atomName, String residue) {
        if (atomName == null || atomName.isEmpty()) return "Unknown";

        StringBuilder stripped = null;
        for (int i = 0; i < atomName.length(); i++) {
            char c = atomName.charAt(i);
            if ((c >= '0' && c <= '9') || Character.isWhitespace(c)) {
                if (stripped == null) {
                    stripped = new StringBuilder(atomName.length()).append(atomName, 0, i);
                }
            } else if (stripped != null) {
                stripped.append(c);
            }
        }
        String cleanName = stripped == null ? atomName : stripped.toString();

        char c0 = cleanName.isEmpty() ? 0 : Character.toUpperCase(cleanName.charAt(0));
        char c1 = cleanName.length() > 1 ? Character.toUpperCase(cleanName.charAt(1)) : 0;

        String element;
        switch (c0) {
            case 'H': element = "Hydrogen"; break;
            case 'N': element = "Nitrogen"; break;
            case 'P': element = "Phosphorus"; break;
            case 'F': element = "Fluorine"; break;
            case 'S': element = "Sulfur"; break;
            case 'O': element = "Oxygen"; break;
            case 'M': element = (c1 == 'G') ? "Magnesium" : cleanName; break;
            case 'Z': element = (c1 == 'N') ? "Zinc" : cleanName; break;
            case 'C':
                // CA is carbon-alpha in a residue, but the calcium ion when the
                // residue itself is named CA/CAL.
                element = (c1 == 'A'
                        && ("CA".equalsIgnoreCase(residue) || "CAL".equalsIgnoreCase(residue)))
                        ? "Calcium" : "Carbon";
                break;
            default: element = cleanName; // fallback to the literal clean symbol
        }
        return element + " (" + atomName + ")";
    }

    // Swing components may only be touched from the event dispatch thread, and
    // these two are called from the backend workers as well as from handlers.
    private static final int LOG_TRIM_THRESHOLD = 200_000;
    private static final int LOG_TRIM_TARGET = 150_000;

    private void log(String msg) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> log(msg));
            return;
        }
        if (logArea == null) {
            System.out.println("> " + msg);
            return;
        }
        logArea.append("> " + msg + "\n");
        // A long session otherwise grows this document without bound.
        javax.swing.text.Document doc = logArea.getDocument();
        if (doc.getLength() > LOG_TRIM_THRESHOLD) {
            try {
                doc.remove(0, doc.getLength() - LOG_TRIM_TARGET);
            } catch (javax.swing.text.BadLocationException ignored) {}
        }
        logArea.setCaretPosition(doc.getLength());
    }

    private void setStatus(String status) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> setStatus(status));
            return;
        }
        if (statusLabel != null) {
            statusLabel.setText(" STATUS: " + status.toUpperCase());
        } else {
            System.out.println("STATUS: " + status);
        }
    }

    private String getBrukerRootPath(File file) {
        if (file == null) return null;
        if (file.isDirectory()) {
            // Bruker names processed data by dimensionality: 1r for 1D, 2rr
            // for 2D, 3rrr for 3D. Accept all of them and let the backend
            // decide - the chooser should not be what rules a dataset out.
            if (new File(file, "procs").exists()
                    && (new File(file, "1r").exists()
                        || new File(file, "2rr").exists()
                        || new File(file, "3rrr").exists())) {
                return file.getAbsolutePath();
            }
            if (new File(file, "pdata").exists() || new File(file, "acqu").exists() || new File(file, "acqus").exists()) {
                return file.getAbsolutePath();
            }
            return null;
        }
        String name = file.getName().toLowerCase();
        if (name.equals("1r") || name.equals("1i")
                || name.equals("2rr") || name.equals("2ri") || name.equals("2ir") || name.equals("2ii")
                || name.equals("3rrr")
                || name.equals("ser") || name.equals("fid")
                || name.equals("acqu") || name.equals("acqus") || name.equals("acqu2s")
                || name.equals("procs") || name.equals("proc") || name.equals("proc2")
                || name.equals("proc2s") || name.equals("title") || name.equals("clevels")) {
            File parent = file.getParentFile();
            while (parent != null) {
                if (new File(parent, "acqu").exists() || new File(parent, "acqus").exists() || new File(parent, "pdata").exists()) {
                    return parent.getAbsolutePath();
                }
                parent = parent.getParentFile();
            }
            return file.getParentFile().getAbsolutePath();
        }
        return null;
    }

    private void chooseLocalFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setCurrentDirectory(new File(System.getProperty("user.dir")));
        chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        int returnVal = chooser.showOpenDialog(this);
        if (returnVal == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            String brukerRoot = getBrukerRootPath(file);
            
            if (brukerRoot != null) {
                log("Loading Bruker NMR directory: " + brukerRoot);
                setStatus("Loading Bruker " + new File(brukerRoot).getName());
                currentFileType = "bruker";
                currentFileContent = brukerRoot;
                parseAndLoadData();
            } else {
                log("Loading local file: " + file.getName());
                setStatus("Loading " + file.getName());
                try {
                    byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
                    currentFileContent = new String(bytes, StandardCharsets.UTF_8);
                    
                    String name = file.getName().toLowerCase();
                    if (name.endsWith(".csv")) {
                        currentFileType = "csv";
                    } else {
                        currentFileType = "str";
                    }
                    
                    parseAndLoadData();
                } catch (Exception ex) {
                    log("ERROR: Failed to read file: " + ex.getMessage());
                    setStatus("Error loading file");
                }
            }
        }
    }
    
    private void fetchBmrb(String id) {
        if (id.isEmpty()) {
            log("ERROR: Enter a valid BMRB Entry ID.");
            return;
        }
        log("Fetching BMRB entry " + id + " from BMRB Web API...");
        setStatus("Fetching BMRB ID " + id);
        
        CompletableFuture.runAsync(() -> {
            try {
                URI uri = URI.create("https://api.bmrb.io/v2/entry/" + id + "?format=rawnmrstar");
                HttpRequest request = HttpRequest.newBuilder().uri(uri).build();
                HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
                
                if (response.statusCode() == 200) {
                    SwingUtilities.invokeLater(() -> {
                        currentFileContent = response.body();
                        currentFileType = "str";
                        log("SUCCESS: Retrieved BMRB " + id + " (" + currentFileContent.length() + " bytes)");
                        parseAndLoadData();
                    });
                } else {
                    SwingUtilities.invokeLater(() -> {
                        log("ERROR: BMRB API returned code " + response.statusCode());
                        setStatus("Fetch failed (Code " + response.statusCode() + ")");
                    });
                }
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    log("ERROR: Failed to fetch: " + ex.getMessage());
                    setStatus("Fetch failed");
                });
            }
        });
    }

    // A long-lived `nmr_backend.py serve` process. Keeping it alive avoids paying
    // ~0.5s of Python imports per request and lets the backend cache the processed
    // matrix, so a contour-only recompute costs ~0.3s instead of ~1.05s.
    private static class PythonWorker {
        private Process proc;
        private BufferedWriter toWorker;
        private BufferedReader fromWorker;

        synchronized boolean isRunning() {
            return proc != null && proc.isAlive();
        }

        synchronized boolean start(String pythonExec) {
            stop();
            try {
                ProcessBuilder pb = new ProcessBuilder(pythonExec,
                    new File(resourceDirectory(), "nmr_backend.py").getAbsolutePath(), "serve");
                pb.directory(resourceDirectory());
                proc = pb.start();
                toWorker = new BufferedWriter(new OutputStreamWriter(proc.getOutputStream(), StandardCharsets.UTF_8));
                fromWorker = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8), 1 << 16);

                // stderr must be drained or the worker blocks once the pipe fills.
                Thread drain = new Thread(() -> {
                    try (BufferedReader err = new BufferedReader(new InputStreamReader(proc.getErrorStream(), StandardCharsets.UTF_8))) {
                        while (err.readLine() != null) { /* discard: nmrglue warnings */ }
                    } catch (IOException ignored) {}
                }, "python-worker-stderr");
                drain.setDaemon(true);
                drain.start();

                String ready = fromWorker.readLine();
                if (ready == null) {
                    stop();
                    return false;
                }
                return true;
            } catch (IOException ex) {
                stop();
                return false;
            }
        }

        // One request line in, one response line out. Callers are serialised by the
        // monitor, so concurrent requests queue rather than interleave.
        synchronized String request(String jsonLine) throws IOException {
            if (!isRunning()) throw new IOException("Python worker is not running");
            toWorker.write(jsonLine.replace("\n", " "));
            toWorker.write("\n");
            toWorker.flush();
            String response = fromWorker.readLine();
            if (response == null) throw new IOException("Python worker closed the connection");
            return response;
        }

        synchronized void stop() {
            if (proc != null) {
                try {
                    if (toWorker != null && proc.isAlive()) {
                        toWorker.write("{\"op\":\"quit\"}\n");
                        toWorker.flush();
                    }
                } catch (IOException ignored) {}
                proc.destroy();
            }
            proc = null;
            toWorker = null;
            fromWorker = null;
        }
    }

    // Result of a single nmr_backend.py invocation. Holds no Swing state, so it's
    // safe to build on a background thread.
    private static class BackendResult {
        final int exitCode;
        final String stdout;
        final String stderr;
        BackendResult(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }
    }

    private String buildBackendPayload() {
        return buildBackendPayload(false);
    }

    private String buildBackendPayload(boolean ladder) {
        if ("bruker".equals(currentFileType)) {
            return String.format(Locale.US,
                "{\"path\":\"%s\",\"phc0_f2\":%.4f,\"phc1_f2\":%.4f,\"phc0_f1\":%.4f,\"phc1_f1\":%.4f,\"baseline_order\":%d,\"calib_x\":%.4f,\"calib_y\":%.4f,\"contour_base\":%.4f,\"ladder\":%s}",
                currentFileContent.replace("\\", "\\\\").replace("\"", "\\\""),
                phc0_f2, phc1_f2, phc0_f1, phc1_f1, baselineOrder, calibX, calibY, contourBase,
                ladder ? "true" : "false"
            );
        }
        return currentFileContent;
    }

    // Pure I/O: spawns nmr_backend.py, writes the payload, and drains stdout/stderr.
    // stderr is drained on its own thread concurrently with stdout - otherwise a
    // large contour dump on one stream while the other stream's OS pipe buffer
    // fills up can deadlock the subprocess. No Swing calls here, so this is safe
    // to run on any thread.
    private static String pythonExecutable() {
        File venvPy = new File(resourceDirectory(), ".venv/bin/python");
        return venvPy.exists() ? venvPy.getAbsolutePath() : "python3";
    }

    private static File resourceDirectory() {
        String configured = System.getProperty("apsy.resource.dir");
        return configured == null || configured.isEmpty()
            ? new File(System.getProperty("user.dir"))
            : new File(configured);
    }

    // Bruker requests go through the persistent worker; everything else (and any
    // worker failure) falls back to spawning a one-shot process.
    private BackendResult runPythonBackend(String type, String x, String y, String z, String inputPayload) throws IOException, InterruptedException {
        if ("bruker".equals(type)) {
            if (!pythonWorker.isRunning()) {
                pythonWorker.start(pythonExecutable());
            }
            if (pythonWorker.isRunning()) {
                try {
                    return new BackendResult(0, pythonWorker.request(inputPayload).trim(), "");
                } catch (IOException ex) {
                    pythonWorker.stop();
                    log("WARNING: Python worker failed (" + ex.getMessage() + "); using a one-shot process.");
                }
            }
        }
        return runPythonBackendOneShot(type, x, y, z, inputPayload);
    }

    private BackendResult runPythonBackendOneShot(String type, String x, String y, String z, String inputPayload) throws IOException, InterruptedException {
        String pythonExec = pythonExecutable();
        ProcessBuilder pb = new ProcessBuilder(pythonExec,
            new File(resourceDirectory(), "nmr_backend.py").getAbsolutePath(), type, x, y, z);
        pb.directory(resourceDirectory());
        Process process = pb.start();

        StringBuilder sbErr = new StringBuilder();
        Thread errThread = new Thread(() -> {
            try (BufferedReader errReader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = errReader.readLine()) != null) {
                    sbErr.append(line).append("\n");
                }
            } catch (IOException ignored) {}
        });
        errThread.start();

        try (OutputStream os = process.getOutputStream()) {
            os.write(inputPayload.getBytes(StandardCharsets.UTF_8));
            os.flush();
        }

        StringBuilder sbOut = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sbOut.append(line);
            }
        }

        int exitCode = process.waitFor();
        errThread.join();
        return new BackendResult(exitCode, sbOut.toString().trim(), sbErr.toString());
    }

    // A backend response already decoded into the app's own model. Built entirely
    // off the EDT - decoding a spectrum means walking ~7 MB of JSON and ~600k
    // vertices, which has no business happening on the UI thread.
    private static final class ParsedResponse {
        boolean ok;
        String errorMessage;
        String xLabel, yLabel, zLabel;
        List<String> atoms = new ArrayList<>();
        List<DataPoint> points = new ArrayList<>();
        List<ContourLine> contours = new ArrayList<>();
        boolean isLadder;
        // Dimensionality the backend detected; 1D responses carry a trace
        // polyline where a 2D response carries contours.
        int dimension = 2;
        double[] trace = NO_TRACE;
        int ladderKMin, ladderKMax, ladderWindow = 14;
        // NaN when the backend did not report a domain (non-Bruker responses).
        double baseMin = Double.NaN, baseMax = Double.NaN;
    }

    // Pure decoding: no Swing, no shared mutable state, safe on any thread.
    private static ParsedResponse parseResponse(BackendResult result) {
        ParsedResponse out = new ParsedResponse();
        if (result == null || result.exitCode != 0 || result.stdout.isEmpty()) {
            out.errorMessage = "Python backend returned no output (exit code "
                    + (result == null ? "n/a" : result.exitCode) + ")";
            if (result != null && !result.stderr.isEmpty()) {
                out.errorMessage += "; " + result.stderr;
            }
            return out;
        }

        Map<String, Object> map = JsonParser.parseObject(result.stdout);
        if ("error".equals(map.get("status"))) {
            Object msg = map.get("message");
            out.errorMessage = msg != null ? msg.toString() : "unknown backend error";
            return out;
        }

        out.ok = true;
        out.xLabel = (String) map.get("x_label");
        out.yLabel = (String) map.get("y_label");
        out.zLabel = (String) map.get("z_label");

        Double dimVal = (Double) map.get("dimension");
        if (dimVal != null) out.dimension = dimVal.intValue();
        out.trace = parseTrace((String) map.get("trace"));

        String atomsRaw = (String) map.get("unique_atoms");
        if (atomsRaw != null) out.atoms = JsonParser.parseStringList(atomsRaw);

        String dataRaw = (String) map.get("data");
        if (dataRaw != null) {
            for (Map<String, Object> pointMap : JsonParser.parseList(dataRaw)) {
                Double px = (Double) pointMap.get("x");
                Double py = (Double) pointMap.get("y");
                if (px == null || py == null) continue;
                out.points.add(new DataPoint((String) pointMap.get("seq_id"),
                        (String) pointMap.get("residue"), px, py, (Double) pointMap.get("z")));
            }
        }

        Double bMin = (Double) map.get("contour_base_min");
        Double bMax = (Double) map.get("contour_base_max");
        if (bMin != null) out.baseMin = bMin;
        if (bMax != null) out.baseMax = bMax;

        Double ladderFlag = (Double) map.get("ladder");
        out.isLadder = ladderFlag != null && ladderFlag.intValue() == 1;
        if (out.isLadder) {
            Double kMin = (Double) map.get("ladder_k_min");
            Double kMax = (Double) map.get("ladder_k_max");
            Double win = (Double) map.get("ladder_window");
            out.ladderKMin = kMin != null ? kMin.intValue() : 0;
            out.ladderKMax = kMax != null ? kMax.intValue() : 0;
            out.ladderWindow = (win != null && win.intValue() > 0) ? win.intValue() : 14;
        }

        String contoursRaw = (String) map.get("contours");
        if (contoursRaw != null) {
            for (Map<String, Object> cMap : JsonParser.parseList(contoursRaw)) {
                Double lvl = (Double) cMap.get("level");
                Boolean isPos = (Boolean) cMap.get("is_positive");
                Double kVal = (Double) cMap.get("k");
                out.contours.add(new ContourLine(lvl != null ? lvl : 0.0,
                        isPos == null || isPos,
                        parseSegments((String) cMap.get("segments_str")),
                        kVal != null ? kVal.intValue() : 0));
            }
        }
        return out;
    }

    // Adopts the threshold domain the backend measured for this spectrum, and
    // pulls the current threshold back inside it if the new spectrum is quieter
    // or louder than the last one.
    private void adoptContourDomain(ParsedResponse parsed) {
        if (Double.isNaN(parsed.baseMin) || Double.isNaN(parsed.baseMax)) return;
        contourBaseMin = parsed.baseMin;
        contourBaseMax = parsed.baseMax;
        contourBase = Math.min(Math.max(contourBase, contourBaseMin), contourBaseMax);
    }

    // Applies an already-decoded response to the data model and UI. EDT only.
    private void applyBackendResponse(ParsedResponse parsed, String x, String y, String z) {
        if (!parsed.ok) {
            log("ERROR FROM PARSER: " + parsed.errorMessage);
            setStatus("Parsing failed");
            return;
        }
        adoptContourDomain(parsed);

        // Override axis labels from backend if available (e.g. for Bruker)
        if (parsed.xLabel != null) x = parsed.xLabel;
        if (parsed.yLabel != null) y = parsed.yLabel;
        if (parsed.zLabel != null) z = parsed.zLabel;

        dataPoints = parsed.points;
        loadedDimension = parsed.dimension;
        tracePoints = parsed.trace;

        if (parsed.isLadder) {
            ladderContours = parsed.contours;
            ladderKMin = parsed.ladderKMin;
            ladderKMax = parsed.ladderKMax;
            ladderWindow = parsed.ladderWindow;
            ladderLoaded = !ladderContours.isEmpty();
            ladderK0 = Integer.MIN_VALUE;
            contourLines = new ArrayList<>();
            if (ladderLoaded) selectLadderWindow();
        } else {
            contourLines = parsed.contours;
        }

        uniqueAtoms = parsed.atoms;

        // Update dropdowns without triggering event handlers
        ActionListener[] xListeners = comboX.getActionListeners();
        ActionListener[] yListeners = comboY.getActionListeners();
        ActionListener[] zListeners = comboZ.getActionListeners();

        for (ActionListener al : xListeners) comboX.removeActionListener(al);
        for (ActionListener al : yListeners) comboY.removeActionListener(al);
        for (ActionListener al : zListeners) comboZ.removeActionListener(al);

        comboX.removeAllItems();
        comboY.removeAllItems();
        comboZ.removeAllItems();

        for (String a : uniqueAtoms) {
            comboX.addItem(a);
            comboY.addItem(a);
            comboZ.addItem(a);
        }

        // Restore selections
        if (uniqueAtoms.contains(x)) comboX.setSelectedItem(x);
        else if (!uniqueAtoms.isEmpty()) comboX.setSelectedIndex(0);

        if (uniqueAtoms.contains(y)) comboY.setSelectedItem(y);
        else if (uniqueAtoms.size() > 1) comboY.setSelectedIndex(1);

        if (uniqueAtoms.contains(z)) comboZ.setSelectedItem(z);
        else if (uniqueAtoms.size() > 2) comboZ.setSelectedIndex(2);

        for (ActionListener al : xListeners) comboX.addActionListener(al);
        for (ActionListener al : yListeners) comboY.addActionListener(al);
        for (ActionListener al : zListeners) comboZ.addActionListener(al);

        log("SUCCESS: Loaded " + dataPoints.size() + " paired residue points.");
        log("Atoms available: " + uniqueAtoms);
        setStatus("Loaded " + dataPoints.size() + " points");

        updatePlots();
    }

    // The backend round trip plus decoding runs half a second or more. Doing it
    // inline froze the entire window - menus, repaints, the phase sliders that
    // triggered it. It now runs on a worker thread and only the final model swap
    // touches the EDT. A request arriving while one is in flight is remembered,
    // not queued, so dragging a phase slider re-runs once at the end instead of
    // stacking up a backend call per intermediate value.
    private void parseAndLoadData() {
        if (currentFileContent.isEmpty()) {
            log("WARNING: File content is empty.");
            return;
        }
        if (loadInFlight) {
            loadPending = true;
            return;
        }

        setStatus("Parsing data via Python backend...");

        String x = (String) comboX.getSelectedItem();
        String y = (String) comboY.getSelectedItem();
        String z = (String) comboZ.getSelectedItem();
        if (x == null) x = "H";
        if (y == null) y = "N";
        if (z == null) z = "CA";

        final String fx = x, fy = y, fz = z;
        final String type = currentFileType;
        final String payload = buildBackendPayload(false);

        loadInFlight = true;
        new Thread(() -> {
            ParsedResponse parsed = null;
            Exception failure = null;
            try {
                parsed = parseResponse(runPythonBackend(type, fx, fy, fz, payload));
            } catch (Exception ex) {
                failure = ex;
            }
            final ParsedResponse finalParsed = parsed;
            final Exception finalFailure = failure;
            SwingUtilities.invokeLater(() -> {
                loadInFlight = false;
                if (finalFailure != null) {
                    log("ERROR: Subprocess execution failed: " + finalFailure.getMessage());
                    setStatus("Parsing failed");
                } else {
                    applyBackendResponse(finalParsed, fx, fy, fz);
                }
                if (loadPending) {
                    loadPending = false;
                    parseAndLoadData();
                } else if (finalFailure == null && "bruker".equals(type)) {
                    requestLadderInBackground(fx, fy, fz);
                }
            });
        }, "nmr-load").start();
    }

    // The ladder is only needed to make scrolling instant, so it is fetched after
    // the spectrum is already on screen rather than blocking the initial load.
    private void requestLadderInBackground(String x, String y, String z) {
        ladderLoaded = false;
        ladderContours = new ArrayList<>();
        ladderK0 = Integer.MIN_VALUE;

        final String fx = x, fy = y, fz = z;
        final String payload = buildBackendPayload(true);
        new Thread(() -> {
            ParsedResponse parsed;
            try {
                parsed = parseResponse(runPythonBackend("bruker", fx, fy, fz, payload));
            } catch (Exception ex) {
                log("WARNING: contour ladder unavailable (" + ex.getMessage()
                        + "); scrolling will recompute each step.");
                return;
            }
            final ParsedResponse done = parsed;
            SwingUtilities.invokeLater(() -> storeLadder(done));
        }, "contour-ladder").start();
    }

    private static final double[][] NO_SEGMENTS = new double[0][];
    private static final double[] POW10 = {
        1e0, 1e1, 1e2, 1e3, 1e4, 1e5, 1e6, 1e7, 1e8, 1e9,
        1e10, 1e11, 1e12, 1e13, 1e14, 1e15, 1e16, 1e17, 1e18
    };

    // Scans "x,y x,y;x,y ..." straight into primitive arrays.
    //
    // The obvious split(";") -> split(" ") -> split(",") -> parseDouble chain
    // manufactures three temporary Strings for every one of the ~600k vertices
    // in a spectrum. Walking the buffer by index instead touches each character
    // once and allocates nothing but the arrays that are actually kept.
    private static final double[] NO_TRACE = new double[0];

    // The 1D trace is a single polyline in the same "x,y x,y" encoding the
    // contour segments use, so it decodes through the same fast scanner.
    private static double[] parseTrace(String traceStr) {
        double[][] segs = parseSegments(traceStr);
        return segs.length > 0 && segs[0] != null ? segs[0] : NO_TRACE;
    }

    private static double[][] parseSegments(String segsStr) {
        if (segsStr == null || segsStr.isEmpty()) return NO_SEGMENTS;

        final int n = segsStr.length();
        int segCount = 1;
        for (int i = 0; i < n; i++) if (segsStr.charAt(i) == ';') segCount++;

        double[][] out = new double[segCount][];
        int written = 0;
        int cursor = 0;

        while (cursor < n) {
            int end = segsStr.indexOf(';', cursor);
            if (end < 0) end = n;

            // One comma per vertex, so this sizes the array exactly.
            int pointCount = 0;
            for (int i = cursor; i < end; i++) if (segsStr.charAt(i) == ',') pointCount++;

            if (pointCount >= 2) {
                double[] coords = new double[pointCount * 2];
                int filled = 0;
                int i = cursor;
                while (i < end && filled < coords.length) {
                    while (i < end && segsStr.charAt(i) == ' ') i++;
                    if (i >= end) break;

                    int after = scanNumber(segsStr, i, end, coords, filled);
                    if (after >= 0 && after < end && segsStr.charAt(after) == ','
                            && (after = scanNumber(segsStr, after + 1, end, coords, filled + 1)) >= 0) {
                        i = after;
                        filled += 2;
                        continue;
                    }
                    // Malformed token: drop it and carry on with the rest of the
                    // segment, which is what the split()-based version did.
                    while (i < end && segsStr.charAt(i) != ' ') i++;
                }
                if (filled >= 4) {
                    out[written++] = (filled == coords.length)
                            ? coords : java.util.Arrays.copyOf(coords, filled);
                }
            }
            cursor = end + 1;
        }
        return written == segCount ? out : java.util.Arrays.copyOf(out, written);
    }

    // Parses one [-]ddd[.ddd] number into dest[slot]; returns the index just past
    // it, or -1 if there was no number to read. The backend emits "%.3f", so the
    // mantissa always fits a long and mantissa/10^decimals is the same correctly
    // rounded double Double.parseDouble would hand back - without the substring.
    private static int scanNumber(String s, int p, int end, double[] dest, int slot) {
        boolean negative = false;
        if (p < end && s.charAt(p) == '-') { negative = true; p++; }

        long mantissa = 0;
        int decimals = 0;
        boolean sawDigit = false, sawDot = false;
        while (p < end) {
            char ch = s.charAt(p);
            if (ch >= '0' && ch <= '9') {
                mantissa = mantissa * 10 + (ch - '0');
                if (sawDot) decimals++;
                sawDigit = true;
                p++;
            } else if (ch == '.' && !sawDot) {
                sawDot = true;
                p++;
            } else {
                break;
            }
        }
        if (!sawDigit || decimals >= POW10.length) return -1;

        double value = decimals == 0 ? mantissa : mantissa / POW10[decimals];
        dest[slot] = negative ? -value : value;
        return p;
    }

    // Absorbs only the ladder out of a response; the spectrum on screen is left alone.
    private void storeLadder(ParsedResponse parsed) {
        if (parsed == null || !parsed.ok || !parsed.isLadder) return;

        adoptContourDomain(parsed);
        ladderKMin = parsed.ladderKMin;
        ladderKMax = parsed.ladderKMax;
        ladderWindow = parsed.ladderWindow;
        ladderContours = parsed.contours;

        ladderLoaded = !ladderContours.isEmpty();
        ladderK0 = Integer.MIN_VALUE;
        if (ladderLoaded) {
            log("Contour ladder ready (" + ladderContours.size() + " levels, k "
                    + ladderKMin + ".." + ladderKMax + ") - scrolling is now instant.");
        }
    }

    private void processCurrentData() {
        if (currentFileContent.isEmpty()) {
            log("ERROR: Load data first.");
            return;
        }
        parseAndLoadData();
    }

    // A threshold change alters nothing but the contours, so swap those in place
    // rather than rebuilding the atom lists and re-logging the whole load.
    private void applyContourOnlyResponse(ParsedResponse parsed) {
        if (parsed == null || !parsed.ok) {
            setStatus("Contour recompute failed");
            return;
        }
        adoptContourDomain(parsed);

        contourLines = parsed.contours;

        // The ladder window no longer matches what is drawn, so force the next
        // scroll to reselect rather than assume the view is already correct.
        ladderK0 = Integer.MIN_VALUE;
        updatePlots();
        setStatus(contourStatusText());
    }

    // "Contour level: 8.00 x noise (0% .. 100% of range)" - the percentage is
    // logarithmic, matching how the threshold actually moves.
    private String contourStatusText() {
        double span = Math.log(contourBaseMax / contourBaseMin);
        int pct = span > 0
                ? (int) Math.round(100.0 * Math.log(contourBase / contourBaseMin) / span)
                : 0;
        String value = contourBase >= 1000 ? String.format(Locale.US, "%,.0f", contourBase)
                                           : String.format(Locale.US, "%.2f", contourBase);
        return "Contour level: " + value + " x noise (" + pct + "% of range)";
    }

    // Lowest ladder rung the display window can start on without running past the top.
    private int maxLadderK0() {
        return Math.max(ladderKMin, ladderKMax - ladderWindow + 1);
    }

    // Pick the `ladderWindow` consecutive rungs that best match contourBase.
    // Returns true if the visible set actually changed.
    private boolean selectLadderWindow() {
        int k0 = (int) Math.round(Math.log(contourBase) / Math.log(LADDER_RATIO));
        k0 = Math.max(ladderKMin, Math.min(maxLadderK0(), k0));
        if (k0 == ladderK0) return false;

        ladderK0 = k0;
        List<ContourLine> window = new ArrayList<>(ladderWindow * 2);
        for (ContourLine cl : ladderContours) {
            if (cl.k >= k0 && cl.k < k0 + ladderWindow) window.add(cl);
        }
        contourLines = window;
        return true;
    }

    // Scrolling up raises the contour threshold (fewer, stronger peaks shown);
    // scrolling down lowers it (reveals weaker peaks). With a ladder loaded this
    // is a pure client-side reselect - no backend call, no debounce.
    // Mirrors contourStatusText(): the 2D wheel reports a threshold, the 1D
    // wheel reports a magnification, and both land in the same status field.
    private void showIntensityScale(double scale) {
        String value = scale >= 100 ? String.format(Locale.US, "%,.0f", scale)
                     : scale >= 10  ? String.format(Locale.US, "%.1f", scale)
                                    : String.format(Locale.US, "%.2f", scale);
        setStatus("Intensity x" + value + "  (scroll to scale, Reset View to restore)");
    }

    private void handleContourScroll(double wheelRotation) {
        if (currentFileContent.isEmpty() || !"bruker".equals(currentFileType)) return;

        // The threshold is never limited by what the ladder happens to cover: outside
        // that span the snap simply holds while the settle recompute delivers the
        // exact levels.
        double previous = contourBase;
        contourBase *= Math.pow(CONTOUR_SCROLL_STEP, -wheelRotation);
        if (contourBase < contourBaseMin) contourBase = contourBaseMin;
        if (contourBase > contourBaseMax) contourBase = contourBaseMax;
        if (contourBase == previous) return;   // already hard against an end stop

        setStatus(contourStatusText());

        if (ladderLoaded) {
            // Snap to the nearest precomputed rung for instant feedback...
            if (selectLadderWindow()) {
                plot2D.setData(dataPoints, contourLines,
                        (String) comboX.getSelectedItem(), (String) comboY.getSelectedItem(),
                        (String) comboZ.getSelectedItem(), cbInvertX.isSelected(), cbInvertY.isSelected());
                plot2D.repaint();
                refreshRegionViews();
            }
            // ...then settle onto the exact threshold once the wheel stops.
            if (contourSettleTimer == null) {
                contourSettleTimer = new javax.swing.Timer(260, e -> triggerContourRecompute());
                contourSettleTimer.setRepeats(false);
            }
            contourSettleTimer.restart();
            return;
        }

        if (contourScrollDebounce == null) {
            contourScrollDebounce = new javax.swing.Timer(180, e -> triggerContourRecompute());
            contourScrollDebounce.setRepeats(false);
        }
        contourScrollDebounce.restart();
    }

    // If a recompute is already running when the debounce timer fires, retry once
    // it settles instead of spawning a second, overlapping backend process.
    private void triggerContourRecompute() {
        if (contourRequestInFlight) {
            javax.swing.Timer retry = ladderLoaded ? contourSettleTimer : contourScrollDebounce;
            if (retry != null) retry.restart();
            return;
        }

        String x = (String) comboX.getSelectedItem();
        String y = (String) comboY.getSelectedItem();
        String z = (String) comboZ.getSelectedItem();
        if (x == null) x = "H";
        if (y == null) y = "N";
        if (z == null) z = "CA";
        final String fx = x, fy = y, fz = z;
        final String inputPayload = buildBackendPayload();

        final String type = currentFileType;
        contourRequestInFlight = true;
        new Thread(() -> {
            ParsedResponse parsed = null;
            Exception failure = null;
            try {
                parsed = parseResponse(runPythonBackend(type, fx, fy, fz, inputPayload));
            } catch (Exception ex) {
                failure = ex;
            }
            final ParsedResponse finalResult = parsed;
            final Exception finalFailure = failure;
            SwingUtilities.invokeLater(() -> {
                contourRequestInFlight = false;
                if (finalFailure != null) {
                    log("ERROR: Contour recompute failed: " + finalFailure.getMessage());
                    setStatus("Contour recompute failed");
                    return;
                }
                applyContourOnlyResponse(finalResult);
            });
        }, "contour-recompute").start();
    }

    private void updatePlots() {
        String x = (String) comboX.getSelectedItem();
        String y = (String) comboY.getSelectedItem();
        String z = (String) comboZ.getSelectedItem();
        
        plot2D.setBrukerMode("bruker".equals(currentFileType));
        plot2D.setTrace(tracePoints, loadedDimension);
        // A 1D plot's vertical axis is intensity, which always runs upward, so
        // the Y-invert checkbox does not apply - forcing it keeps the hover
        // hit-test aligned with what is actually drawn.
        boolean invY = loadedDimension == 1 ? true : cbInvertY.isSelected();
        plot2D.setData(dataPoints, contourLines, x, y, z, cbInvertX.isSelected(), invY);
        refreshRegionViews();
    }

    private void runBruker2DPlotter() {
        String targetPath = ("bruker".equals(currentFileType) && !currentFileContent.isEmpty())
                ? currentFileContent
                : "Data/METABOLITE/158/pdata/1";
        
        log("Launching 2D Bruker Plotter script for: " + targetPath);
        setStatus("Simulating 2D Bruker spectrum...");
        
        new Thread(() -> {
            try {
                String pythonExec = "python3";
                File venvPy = new File(resourceDirectory(), ".venv/bin/python");
                if (venvPy.exists()) {
                    pythonExec = venvPy.getAbsolutePath();
                }
                
                ProcessBuilder pb = new ProcessBuilder(pythonExec,
                    new File(resourceDirectory(), "nmr_2d_plotter.py").getAbsolutePath(), targetPath);
                pb.directory(resourceDirectory());
                pb.inheritIO();
                Process p = pb.start();
                int exitCode = p.waitFor();
                SwingUtilities.invokeLater(() -> {
                    if (exitCode == 0) {
                        setStatus("2D Bruker Simulation complete.");
                        log("SUCCESS: 2D Bruker plot generated successfully.");
                    } else {
                        setStatus("2D Plotter exited with code " + exitCode);
                        log("WARNING: 2D Plotter process returned code " + exitCode);
                    }
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    log("ERROR running 2D plotter: " + ex.getMessage());
                    setStatus("2D Plotter execution failed");
                });
            }
        }).start();
    }
    
    // TopSpin Command-Line Execution
    private void executeCommandLine(String cmd) {
        log("Command Entered: " + cmd);
        
        switch (cmd.trim().toLowerCase()) {
            case "ft":
                log("Running Fourier Transform (ft)...");
                processCurrentData();
                break;
            case "wm":
                log("Applying Window Multiplication (wm)...");
                processCurrentData();
                break;
            case "proc1d":
                log("Opening Configure Standard Processing (proc1d)...");
                break;
            case "proc1d y":
                log("Computing Spectrum from raw data (proc1d y)...");
                processCurrentData();
                break;
            case "apbk":
                log("Running Automated baseline and phase correction (apbk)...");
                phc0_f2 = 10.0; phc1_f2 = -15.0;
                baselineOrder = 1;
                processCurrentData();
                break;
            case "xaup":
                log("Starting Automation AU Program (xaup)...");
                break;
            case "ph":
            case ".ph":
                log("Opening manual phase correction dialog (.ph)...");
                showPhaseDialog();
                break;
            case "pk":
                log("Phasing spectrum using PHC0/PHC1 parameters (pk)...");
                showPhaseDialog();
                break;
            case "mc":
                log("Calculating Magnitude Spectrum (mc)...");
                break;
            case "ps":
                log("Calculating Power Spectrum (ps)...");
                break;
            case "basl":
            case ".basl":
                log("Opening manual baseline correction (.basl)...");
                break;
            case "abs":
                log("Running Automatic baseline correction (abs)...");
                baselineOrder = 1;
                processCurrentData();
                break;
            case "absf":
                log("Running baseline correction only in range (absf)...");
                break;
            case "absd":
                log("Running alternate baseline correction (absd)...");
                break;
            case ".baslpts":
                log("Setting up spline file baslpnts (.baslpts)...");
                break;
            case "sab":
                log("Running spline correction (sab)...");
                break;
            case "bc":
                log("Correcting FID using BC_mod (bc)...");
                break;
            case "cal":
            case ".cal":
                log("Opening calibration dialog (.cal)...");
                showCalibrateDialog();
                break;
            case "sref":
                log("Setting TMS reference to 0 ppm (sref)...");
                break;
            case "serial":
                log("Processing dataset list (serial)...");
                break;
            case "intser":
                log("Integrating spectra list (intser)...");
                break;
            case "vregs":
                log("Opening ROI view of spectra list (vregs)...");
                break;
            case "adsu":
                log("Performing arithmetic operations on spectra (adsu)...");
                break;
            case "refdcon":
            case ".refdcon":
                log("Running reference deconvolution (.refdcon)...");
                break;
            case "ht":
                log("Running Hilbert Transform (ht)...");
                break;
            case "qht":
                log("Running Quadrature Detection (qht)...");
                break;
            case "lp":
                log("Running Linear Prediction (lp)...");
                break;
            case "cov":
                log("Running Covariance NMR (cov)...");
                break;
            case "*2":
                log("Scaling up contours (2x)...");
                JOptionPane.showMessageDialog(this, "Increased spectral scaling factor (2x).", "Scale Up", JOptionPane.INFORMATION_MESSAGE);
                break;
            case "/2":
                log("Scaling down contours (0.5x)...");
                JOptionPane.showMessageDialog(this, "Decreased spectral scaling factor (0.5x).", "Scale Down", JOptionPane.INFORMATION_MESSAGE);
                break;
            case "rst":
                log("Resetting processing parameters (rst)...");
                phc0_f2 = 0.0; phc1_f2 = 0.0;
                phc0_f1 = 0.0; phc1_f1 = 0.0;
                baselineOrder = -1;
                calibX = 0.0; calibY = 0.0;
                processCurrentData();
                break;
            case "clc":
                log("Clearing current plot (clc)...");
                clearPlot();
                break;
            case "pull":
                log("Detaching plot into its own window (pull)...");
                togglePlotDetached();
                break;
            case "help":
                log("=== TopSpin Commands List ===");
                log("ft, wm, proc1d, proc1d y, apbk, xaup");
                log("ph, .ph, pk, mc, ps, basl, .basl, abs, absf, absd");
                log("cal, .cal, sref, serial, intser, vregs, adsu, ht, lp, cov");
                log("clc, pull, *2, /2, rst, help");
                break;
            default:
                log("Command not recognized: " + cmd + ". Type 'help' to see list.");
                break;
        }
    }
    
    // Print / export / copy / layout sit at the right-hand end of every ribbon tab
    // and behave identically on each, so all four tabs build theirs from here.
    private JPanel createRibbonActionCluster(Color background) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 5));
        panel.setBackground(background);

        JButton print = createToolbarIconButton(new VectorIcon("printer", 16, 16), "Print Spectrum");
        print.addActionListener(e -> JOptionPane.showMessageDialog(this,
                "Sending spectrum to printer spooler...", "Print", JOptionPane.INFORMATION_MESSAGE));

        JButton export = createToolbarIconButton(new VectorIcon("export", 16, 16), "Export / Save Data");
        export.addActionListener(e -> JOptionPane.showMessageDialog(this,
                "Exporting processed data...", "Export", JOptionPane.INFORMATION_MESSAGE));

        JButton copy = createToolbarIconButton(new VectorIcon("copy", 16, 16), "Copy to Clipboard");
        copy.addActionListener(e -> JOptionPane.showMessageDialog(this,
                "Copied plot image to clipboard.", "Copy", JOptionPane.INFORMATION_MESSAGE));

        JButton layout = createToolbarIconButton(new VectorIcon("layout1", 16, 16), "Single Panel Tabbed Layout");
        layout.addActionListener(e -> setViewportLayout(1));

        panel.add(print);
        panel.add(export);
        panel.add(copy);
        panel.add(new JSeparator(JSeparator.VERTICAL));
        panel.add(layout);
        return panel;
    }

    // TopSpin Toolbar Helpers
    private JMenuItem createLogMenuItem(String text, String logMsg) {
        JMenuItem mi = new JMenuItem(text);
        mi.setFont(FONT_SANS);
        mi.addActionListener(e -> log(logMsg));
        return mi;
    }
    
    private JButton createToolbarButton(String label, Icon icon, ActionListener action) {
        JButton btn = new JButton(label, icon);
        btn.setFont(new Font("Arial", Font.BOLD, 12));
        btn.setForeground(Color.WHITE);
        btn.setContentAreaFilled(false);
        btn.setOpaque(false);
        btn.setFocusPainted(false);
        btn.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(0, 0, 0, 0), 1),
            BorderFactory.createEmptyBorder(6, 10, 6, 10)
        ));
        
        btn.addMouseListener(new MouseAdapter() {
            private final Color hoverColor = new Color(0, 75, 117);
            private final Color borderColor = new Color(0, 100, 156);
            
            public void mouseEntered(MouseEvent evt) {
                btn.setOpaque(true);
                btn.setBackground(hoverColor);
                btn.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(borderColor, 1),
                    BorderFactory.createEmptyBorder(5, 9, 5, 9)
                ));
            }
            public void mouseExited(MouseEvent evt) {
                btn.setOpaque(false);
                btn.setBackground(new Color(0, 0, 0, 0));
                btn.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(0, 0, 0, 0), 1),
                    BorderFactory.createEmptyBorder(6, 10, 6, 10)
                ));
            }
        });
        
        if (action != null) {
            btn.addActionListener(action);
        }
        return btn;
    }
    
    private JButton createRibbonTabButton(String text) {
        JButton btn = new JButton(text);
        btn.setFont(new Font("Arial", Font.BOLD, 12));
        btn.setForeground(Color.WHITE);
        btn.setContentAreaFilled(false);
        btn.setOpaque(false);
        btn.setFocusPainted(false);
        btn.setBorder(BorderFactory.createEmptyBorder(8, 15, 8, 15));
        
        btn.addMouseListener(new MouseAdapter() {
            public void mouseEntered(MouseEvent evt) {
                if (!btn.isOpaque()) {
                    btn.setContentAreaFilled(true);
                    btn.setBackground(new Color(255, 255, 255, 20));
                }
            }
            public void mouseExited(MouseEvent evt) {
                if (!btn.isOpaque()) {
                    btn.setContentAreaFilled(false);
                }
            }
        });
        return btn;
    }
    
    private JButton createSecToolbarButton(String text, String tooltip) {
        JButton btn = new JButton(text);
        btn.setToolTipText(tooltip);
        btn.setFont(new Font("Arial", Font.BOLD, 12));
        btn.setForeground(new Color(40, 50, 65));
        btn.setContentAreaFilled(false);
        btn.setOpaque(false);
        btn.setFocusPainted(false);
        btn.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        
        btn.addMouseListener(new MouseAdapter() {
            public void mouseEntered(MouseEvent evt) {
                btn.setOpaque(true);
                btn.setBackground(new Color(202, 212, 226));
            }
            public void mouseExited(MouseEvent evt) {
                btn.setOpaque(false);
            }
        });
        return btn;
    }
    
    private JButton createSecToolbarIconButton(Icon icon, String tooltip) {
        JButton btn = new JButton(icon);
        btn.setToolTipText(tooltip);
        btn.setContentAreaFilled(false);
        btn.setOpaque(false);
        btn.setFocusPainted(false);
        btn.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        btn.setForeground(new Color(40, 50, 65));
        
        btn.addMouseListener(new MouseAdapter() {
            public void mouseEntered(MouseEvent evt) {
                btn.setOpaque(true);
                btn.setBackground(new Color(202, 212, 226));
            }
            public void mouseExited(MouseEvent evt) {
                btn.setOpaque(false);
            }
        });
        return btn;
    }
    private void switchRibbonTab(String tabName) {
        JButton selected;
        Color selectedShade;
        String label;
        switch (tabName) {
            case "analyze": selected = btnAnalyzeTab; selectedShade = RIBBON_ANALYZE; label = "Analyze"; break;
            case "apps":    selected = btnAppsTab;    selectedShade = RIBBON_APPS;    label = "Applications"; break;
            case "manage":  selected = btnManageTab;  selectedShade = RIBBON_MANAGE;  label = "Manage"; break;
            case "process": selected = btnProcessTab; selectedShade = RIBBON_PROCESS; label = "Process"; break;
            default: return;
        }

        for (JButton tab : new JButton[] { btnProcessTab, btnAnalyzeTab, btnAppsTab, btnManageTab }) {
            tab.setBackground(RIBBON_IDLE);
            tab.setOpaque(false);
        }
        selected.setOpaque(true);
        selected.setBackground(selectedShade);
        ribbonCardLayout.show(ribbonToolbarCards, tabName);
        log("Switched to " + label + " ribbon");

        ribbonToolbarCards.revalidate();
        ribbonToolbarCards.repaint();
    }
    
    private JButton createToolbarIconButton(Icon icon, String tooltip) {
        JButton btn = new JButton(icon);
        btn.setToolTipText(tooltip);
        btn.setContentAreaFilled(false);
        btn.setOpaque(false);
        btn.setFocusPainted(false);
        btn.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        
        btn.addMouseListener(new MouseAdapter() {
            public void mouseEntered(MouseEvent evt) {
                btn.setOpaque(true);
                btn.setBackground(new Color(0, 75, 117));
            }
            public void mouseExited(MouseEvent evt) {
                btn.setOpaque(false);
            }
        });
        return btn;
    }
    
    private void showPhaseDialog() {
        if (!"bruker".equals(currentFileType) || currentFileContent.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Interactive phasing is only available for loaded Bruker datasets.", "Feature Unavailable", JOptionPane.WARNING_MESSAGE);
            return;
        }
        
        JDialog dialog = new JDialog(this, "TopSpin 2D Phase Correction", true);
        dialog.setSize(420, 360);
        dialog.setLocationRelativeTo(this);
        dialog.setLayout(new BorderLayout());
        
        JPanel slidersPanel = new JPanel(new GridBagLayout());
        slidersPanel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(8, 8, 8, 8);
        gbc.weightx = 1.0;
        
        JSlider sF2Phc0 = new JSlider(-180, 180, (int)phc0_f2);
        JSlider sF2Phc1 = new JSlider(-360, 360, (int)phc1_f2);
        JSlider sF1Phc0 = new JSlider(-180, 180, (int)phc0_f1);
        JSlider sF1Phc1 = new JSlider(-360, 360, (int)phc1_f1);
        
        JLabel lblF2Phc0 = new JLabel(String.format("F2 Zero Order (PHC0): %.1f°", phc0_f2));
        JLabel lblF2Phc1 = new JLabel(String.format("F2 First Order (PHC1): %.1f°", phc1_f2));
        JLabel lblF1Phc0 = new JLabel(String.format("F1 Zero Order (PHC0): %.1f°", phc0_f1));
        JLabel lblF1Phc1 = new JLabel(String.format("F1 First Order (PHC1): %.1f°", phc1_f1));
        
        sF2Phc0.addChangeListener(e -> {
            lblF2Phc0.setText(String.format("F2 Zero Order (PHC0): %d°", sF2Phc0.getValue()));
            phc0_f2 = sF2Phc0.getValue();
            if (!sF2Phc0.getValueIsAdjusting()) {
                processCurrentData();
            }
        });
        
        sF2Phc1.addChangeListener(e -> {
            lblF2Phc1.setText(String.format("F2 First Order (PHC1): %d°", sF2Phc1.getValue()));
            phc1_f2 = sF2Phc1.getValue();
            if (!sF2Phc1.getValueIsAdjusting()) {
                processCurrentData();
            }
        });
        
        sF1Phc0.addChangeListener(e -> {
            lblF1Phc0.setText(String.format("F1 Zero Order (PHC0): %d°", sF1Phc0.getValue()));
            phc0_f1 = sF1Phc0.getValue();
            if (!sF1Phc0.getValueIsAdjusting()) {
                processCurrentData();
            }
        });
        
        sF1Phc1.addChangeListener(e -> {
            lblF1Phc1.setText(String.format("F1 First Order (PHC1): %d°", sF1Phc1.getValue()));
            phc1_f1 = sF1Phc1.getValue();
            if (!sF1Phc1.getValueIsAdjusting()) {
                processCurrentData();
            }
        });
        
        int r = 0;
        gbc.gridx = 0; gbc.gridy = r++;
        slidersPanel.add(lblF2Phc0, gbc);
        gbc.gridy = r++;
        slidersPanel.add(sF2Phc0, gbc);
        
        gbc.gridy = r++;
        slidersPanel.add(lblF2Phc1, gbc);
        gbc.gridy = r++;
        slidersPanel.add(sF2Phc1, gbc);
        
        gbc.gridy = r++;
        slidersPanel.add(lblF1Phc0, gbc);
        gbc.gridy = r++;
        slidersPanel.add(sF1Phc0, gbc);
        
        gbc.gridy = r++;
        slidersPanel.add(lblF1Phc1, gbc);
        gbc.gridy = r++;
        slidersPanel.add(sF1Phc1, gbc);
        
        dialog.add(slidersPanel, BorderLayout.CENTER);
        
        JPanel bottomButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton btnReset = new JButton("Reset");
        btnReset.addActionListener(e -> {
            sF2Phc0.setValue(0);
            sF2Phc1.setValue(0);
            sF1Phc0.setValue(0);
            sF1Phc1.setValue(0);
            phc0_f2 = 0.0;
            phc1_f2 = 0.0;
            phc0_f1 = 0.0;
            phc1_f1 = 0.0;
            processCurrentData();
        });
        
        JButton btnClose = new JButton("Close");
        btnClose.addActionListener(e -> dialog.dispose());
        
        bottomButtons.add(btnReset);
        bottomButtons.add(btnClose);
        dialog.add(bottomButtons, BorderLayout.SOUTH);
        
        dialog.setVisible(true);
    }
    
    private void showCalibrateDialog() {
        if (!"bruker".equals(currentFileType) || currentFileContent.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Calibration is only available for loaded Bruker datasets.", "Feature Unavailable", JOptionPane.WARNING_MESSAGE);
            return;
        }
        
        JDialog dialog = new JDialog(this, "Calibrate Axes PPM Shift", true);
        dialog.setSize(320, 200);
        dialog.setLocationRelativeTo(this);
        dialog.setLayout(new GridBagLayout());
        
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(8, 8, 8, 8);
        
        JLabel lblX = new JLabel("F2 Calibration Offset (ppm):");
        JTextField tfX = new JTextField(String.format(Locale.US, "%.4f", calibX));
        JLabel lblY = new JLabel("F1 Calibration Offset (ppm):");
        JTextField tfY = new JTextField(String.format(Locale.US, "%.4f", calibY));
        
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.5;
        dialog.add(lblX, gbc);
        gbc.gridx = 1; gbc.weightx = 0.5;
        dialog.add(tfX, gbc);
        
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.5;
        dialog.add(lblY, gbc);
        gbc.gridx = 1; gbc.weightx = 0.5;
        dialog.add(tfY, gbc);
        
        JButton btnApply = new JButton("Apply");
        btnApply.addActionListener(e -> {
            try {
                calibX = Double.parseDouble(tfX.getText());
                calibY = Double.parseDouble(tfY.getText());
                dialog.dispose();
                processCurrentData();
            } catch (NumberFormatException nfe) {
                JOptionPane.showMessageDialog(dialog, "Please enter valid numeric offsets.", "Invalid Format", JOptionPane.ERROR_MESSAGE);
            }
        });
        
        JButton btnCancel = new JButton("Cancel");
        btnCancel.addActionListener(e -> dialog.dispose());
        
        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 2;
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        btnPanel.add(btnApply);
        btnPanel.add(btnCancel);
        dialog.add(btnPanel, gbc);
        
        dialog.setVisible(true);
    }
    
    private void setViewportLayout(int mode) {
        plot2D.revalidate();
        plot2D.repaint();
    }

    private void clearPlot() {
        // Fresh lists rather than clear(): the plot panel still holds a reference
        // to the previous ones until clearData() swaps them out.
        dataPoints = new ArrayList<>();
        contourLines = new ArrayList<>();
        ladderContours = new ArrayList<>();
        ladderLoaded = false;
        ladderK0 = Integer.MIN_VALUE;
        plot2D.clearData();
        setStatus("Plot cleared");
    }

    // ── Region windows ──────────────────────────────────────────────────────
    //
    // A drag-selection on any spectrum panel opens that region in its own
    // frame, sized to the screen rather than to the sidebar-squeezed plot pane,
    // which is the whole point: the region is being opened *because* it is too
    // dense to read at the main window's scale.
    //
    // The child shares the parent's contour and peak lists by reference, so
    // this costs a viewport, not a second copy of the spectrum - and a region
    // window can itself be dragged on, opening a further region.
    private void openRegionWindow(NmrPlot2D source, double[] region) {
        NmrPlot2D child = source.createRegionView(region);
        child.setRegionSelectListener(this::openRegionWindow);
        child.setContourScrollListener(this::handleContourScroll);
        child.setIntensityScaleListener(this::showIntensityScale);
        child.setBorder(BorderFactory.createLineBorder(COLOR_BLUE_BORDER));
        child.setToolTipText("<html>Drag a box to open a further region.<br>"
                + "Right-drag or shift-drag to pan. Scroll to change the contour level.</html>");
        regionViews.add(child);

        String title = String.format(Locale.US,
                "Region  %s %.3f - %.3f ppm   x   %s %.3f - %.3f ppm",
                (String) comboX.getSelectedItem(), region[0], region[1],
                (String) comboY.getSelectedItem(), region[2], region[3]);

        JFrame frame = new JFrame(title);
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.getContentPane().setBackground(COLOR_BG);
        frame.setLayout(new BorderLayout());

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 4));
        bar.setBackground(COLOR_BG);
        JButton bIn = createRetroButton(" [+] Zoom In ");
        JButton bOut = createRetroButton(" [-] Zoom Out ");
        JButton bHome = createRetroButton(" Reset Region ");
        JButton bFull = createRetroButton(" Full Spectrum ");
        bIn.addActionListener(e -> child.zoomIn());
        bOut.addActionListener(e -> child.zoomOut());
        bHome.addActionListener(e -> child.resetView());
        bHome.setToolTipText("Back to the region this window was opened on");
        bFull.addActionListener(e -> { child.clearHomeView(); child.resetView(); });
        bFull.setToolTipText("Drop the region anchor and show the whole spectrum");
        bar.add(bIn); bar.add(bOut); bar.add(bHome); bar.add(bFull);

        frame.add(bar, BorderLayout.NORTH);
        frame.add(child, BorderLayout.CENTER);

        // Large, but never larger than the display it opens on.
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        frame.setSize(Math.min(1200, (int) (screen.width * 0.85)),
                      Math.min(950, (int) (screen.height * 0.85)));
        frame.setLocationByPlatform(true);
        frame.addWindowListener(new WindowAdapter() {
            public void windowClosed(WindowEvent e) {
                regionViews.remove(child);
            }
        });
        frame.setVisible(true);

        log(String.format(Locale.US, "Region opened: %s %.3f-%.3f, %s %.3f-%.3f ppm",
                (String) comboX.getSelectedItem(), region[0], region[1],
                (String) comboY.getSelectedItem(), region[2], region[3]));
        setStatus("Region window opened");
    }

    // Region windows share the parent's contour list by reference, so a new
    // list (a threshold change, a reprocess) has to be handed to them too.
    private void refreshRegionViews() {
        if (regionViews.isEmpty()) return;
        boolean invY = loadedDimension == 1 ? true : cbInvertY.isSelected();
        for (NmrPlot2D rv : regionViews) {
            rv.refreshSpectrum(dataPoints, contourLines, cbInvertX.isSelected(), invY);
            rv.setShowNegativeColor(cbNegativeColor.isSelected());
            rv.setShowGrid(showGridLines);
        }
    }

    private void togglePlotDetached() {
        if (detachedPlotFrame == null) {
            detachPlot();
        } else {
            reattachPlot();
        }
    }

    private void detachPlot() {
        if (detachedPlotFrame != null) {
            detachedPlotFrame.toFront();
            return;
        }

        rightContainer.remove(plot2D);
        if (detachedPlaceholder == null) {
            detachedPlaceholder = new JLabel("PLOT DETACHED - close the spectrum window to dock it again", JLabel.CENTER);
            detachedPlaceholder.setFont(FONT_SANS);
            detachedPlaceholder.setForeground(COLOR_CHARCOAL);
            detachedPlaceholder.setBorder(BorderFactory.createLineBorder(COLOR_BLUE_BORDER));
            detachedPlaceholder.setOpaque(true);
            detachedPlaceholder.setBackground(COLOR_WHITE);
        }
        rightContainer.add(detachedPlaceholder, BorderLayout.CENTER);
        rightContainer.revalidate();
        rightContainer.repaint();

        detachedPlotFrame = new JFrame("2D Spectrum - Detached View");
        detachedPlotFrame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        detachedPlotFrame.getContentPane().setBackground(COLOR_WHITE);
        detachedPlotFrame.add(plot2D);
        detachedPlotFrame.setSize(1000, 900);
        detachedPlotFrame.setLocationRelativeTo(this);
        detachedPlotFrame.addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) {
                reattachPlot();
            }
        });
        detachedPlotFrame.setVisible(true);

        log("Plot detached into its own window (pull).");
        setStatus("Plot detached");
    }

    private void reattachPlot() {
        if (detachedPlotFrame == null) return;

        detachedPlotFrame.remove(plot2D);
        JFrame closing = detachedPlotFrame;
        detachedPlotFrame = null;
        closing.dispose();

        rightContainer.remove(detachedPlaceholder);
        rightContainer.add(plot2D, BorderLayout.CENTER);
        rightContainer.revalidate();
        rightContainer.repaint();

        log("Plot docked back into the main window.");
        setStatus("Plot docked");
    }
    
    // Custom Vector Icon Painter
    private static class VectorIcon implements Icon {
        private final String type;
        private final int width;
        private final int height;
        
        public VectorIcon(String type, int width, int height) {
            this.type = type;
            this.width = width;
            this.height = height;
        }
        
        public int getIconWidth() { return width; }
        public int getIconHeight() { return height; }
        
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(c != null ? c.getForeground() : Color.WHITE);
            
            if ("spectrum".equals(type)) {
                // Peak shape
                g2.setStroke(new BasicStroke(1.5f));
                g2.drawLine(x + 2, y + height - 3, x + width/2, y + 3);
                g2.drawLine(x + width/2, y + 3, x + width - 2, y + height - 3);
            } else if ("phase".equals(type)) {
                // Wave
                g2.setStroke(new BasicStroke(1.5f));
                int prevX = x + 2;
                int prevY = y + height/2;
                for (int i = 1; i <= width - 4; i++) {
                    int cx = x + 2 + i;
                    int cy = y + height/2 + (int)(Math.sin((i / (double)(width - 4)) * 2 * Math.PI) * 5);
                    g2.drawLine(prevX, prevY, cx, cy);
                    prevX = cx;
                    prevY = cy;
                }
            } else if ("baseline".equals(type)) {
                // Flat line + curved baseline correction
                g2.setStroke(new BasicStroke(1.5f));
                int prevX = x + 2;
                int prevY = y + height - 3;
                for (int i = 1; i <= width - 4; i++) {
                    double norm = i / (double)(width - 4);
                    int cx = x + 2 + i;
                    int cy = y + height - 3 - (int)(Math.sin(norm * Math.PI) * 7);
                    g2.drawLine(prevX, prevY, cx, cy);
                    prevX = cx;
                    prevY = cy;
                }
            } else if ("calib".equals(type)) {
                // Peak with baseline tick mark
                g2.setStroke(new BasicStroke(1.5f));
                g2.drawLine(x + 2, y + height - 4, x + width/2, y + 3);
                g2.drawLine(x + width/2, y + 3, x + width - 2, y + height - 4);
                g2.drawLine(x + width/2, y + height - 4, x + width/2, y + height - 1);
            } else if ("printer".equals(type)) {
                g2.setStroke(new BasicStroke(1.2f));
                g2.drawRect(x + 2, y + 5, width - 4, height - 10);
                g2.drawLine(x + 4, y + 5, x + 4, y + 2);
                g2.drawLine(x + 4, y + 2, x + width - 4, y + 2);
                g2.drawLine(x + width - 4, y + 2, x + width - 4, y + 5);
                g2.fillRect(x + 4, y + height - 7, width - 8, 3);
            } else if ("export".equals(type)) {
                g2.setStroke(new BasicStroke(1.2f));
                g2.drawRect(x + 2, y + 2, width - 6, height - 5);
                // Arrow pointing out
                g2.drawLine(x + width - 5, y + 4, x + width - 2, y + 1);
                g2.drawLine(x + width - 4, y + 1, x + width - 2, y + 1);
                g2.drawLine(x + width - 2, y + 3, x + width - 2, y + 1);
            } else if ("copy".equals(type)) {
                g2.setStroke(new BasicStroke(1.2f));
                g2.drawRect(x + 1, y + 4, width - 6, height - 6);
                g2.drawRect(x + 5, y + 1, width - 6, height - 6);
            } else if ("more".equals(type)) {
                g2.fillOval(x + 2, y + height/2 - 1, 3, 3);
                g2.fillOval(x + width/2 - 1, y + height/2 - 1, 3, 3);
                g2.fillOval(x + width - 5, y + height/2 - 1, 3, 3);
            } else if ("layout1".equals(type)) {
                g2.setStroke(new BasicStroke(1.2f));
                g2.drawRect(x + 2, y + 2, width - 4, height - 4);
            } else if ("layout2".equals(type)) {
                g2.setStroke(new BasicStroke(1.2f));
                g2.drawRect(x + 2, y + 2, width - 4, height - 4);
                g2.drawLine(x + width/2, y + 2, x + width/2, y + height - 2);
            } else if ("vzoom".equals(type)) {
                g2.setStroke(new BasicStroke(1.2f));
                g2.drawLine(x + width/2, y + 2, x + width/2, y + height - 2);
                g2.drawLine(x + width/2, y + 2, x + width/2 - 3, y + 5);
                g2.drawLine(x + width/2, y + 2, x + width/2 + 3, y + 5);
                g2.drawLine(x + width/2, y + height - 2, x + width/2 - 3, y + height - 5);
                g2.drawLine(x + width/2, y + height - 2, x + width/2 + 3, y + height - 5);
            } else if ("hzoom".equals(type)) {
                g2.setStroke(new BasicStroke(1.2f));
                g2.drawLine(x + 2, y + height/2, x + width - 2, y + height/2);
                g2.drawLine(x + 2, y + height/2, x + 5, y + height/2 - 3);
                g2.drawLine(x + 2, y + height/2, x + 5, y + height/2 + 3);
                g2.drawLine(x + width - 2, y + height/2, x + width - 5, y + height/2 - 3);
                g2.drawLine(x + width - 2, y + height/2, x + width - 5, y + height/2 + 3);
            } else if ("baseline_reset".equals(type)) {
                g2.setStroke(new BasicStroke(1.2f));
                g2.drawLine(x + 2, y + height - 3, x + width - 2, y + height - 3);
                g2.drawLine(x + width/2, y + 2, x + width/2, y + height - 3);
                g2.drawLine(x + width/2, y + height - 3, x + width/2 - 3, y + height - 6);
                g2.drawLine(x + width/2, y + height - 3, x + width/2 + 3, y + height - 6);
            } else if ("zoomin".equals(type)) {
                g2.setStroke(new BasicStroke(1.2f));
                g2.drawOval(x + 2, y + 2, 7, 7);
                g2.drawLine(x + 8, y + 8, x + width - 3, y + height - 3);
                g2.drawLine(x + 4, y + 5, x + 7, y + 5);
                g2.drawLine(x + 5, y + 4, x + 5, y + 7);
            } else if ("zoomout".equals(type)) {
                g2.setStroke(new BasicStroke(1.2f));
                g2.drawOval(x + 2, y + 2, 7, 7);
                g2.drawLine(x + 8, y + 8, x + width - 3, y + height - 3);
                g2.drawLine(x + 4, y + 5, x + 7, y + 5);
            } else if ("undo".equals(type)) {
                g2.setStroke(new BasicStroke(1.2f));
                g2.drawArc(x + 4, y + 4, 8, 8, 0, 230);
                g2.drawLine(x + 4, y + 8, x + 2, y + 6);
                g2.drawLine(x + 4, y + 8, x + 6, y + 6);
            } else if ("fit".equals(type)) {
                g2.setStroke(new BasicStroke(1.2f));
                g2.drawRect(x + 3, y + 3, width - 6, height - 6);
                g2.fillRect(x + width/2 - 1, y + height/2 - 1, 2, 2);
            } else if ("refresh".equals(type)) {
                g2.setStroke(new BasicStroke(1.2f));
                g2.drawArc(x + 2, y + 2, width - 4, height - 4, 30, 300);
                g2.drawLine(x + width - 3, y + height/2, x + width - 6, y + height/2 + 3);
                g2.drawLine(x + width - 3, y + height/2, x + width - 1, y + height/2 + 3);
            } else if ("peaks".equals(type)) {
                g2.setStroke(new BasicStroke(1.2f));
                g2.drawLine(x + 1, y + height - 2, x + width - 1, y + height - 2);
                g2.drawArc(x + 3, y + 2, width - 6, height, 0, 180);
            } else if ("grid".equals(type)) {
                g2.setStroke(new BasicStroke(1.0f));
                g2.drawLine(x + 4, y + 2, x + 4, y + height - 2);
                g2.drawLine(x + width - 4, y + 2, x + width - 4, y + height - 2);
                g2.drawLine(x + 2, y + 4, x + width - 2, y + 4);
                g2.drawLine(x + 2, y + height - 4, x + width - 2, y + height - 4);
            } else if ("hamburger".equals(type)) {
                g2.setStroke(new BasicStroke(1.5f));
                g2.drawLine(x + 2, y + 4, x + width - 2, y + 4);
                g2.drawLine(x + 2, y + height/2, x + width - 2, y + height/2);
                g2.drawLine(x + 2, y + height - 4, x + width - 2, y + height - 4);
            } else if ("pickpeaks".equals(type)) {
                g2.setStroke(new BasicStroke(1.2f));
                g2.drawLine(x + 1, y + height - 2, x + width - 1, y + height - 2);
                g2.drawArc(x + 3, y + 2, width - 6, height - 4, 0, 180);
                g2.drawLine(x + width/2, y + 2, x + width/2, y + 5);
            } else if ("integrate".equals(type)) {
                g2.setStroke(new BasicStroke(1.5f));
                g2.drawArc(x + width/2 - 2, y + 2, 4, 4, 0, 180);
                g2.drawLine(x + width/2, y + 4, x + width/2, y + height - 4);
                g2.drawArc(x + width/2, y + height - 6, 4, 4, 180, 180);
            } else if ("multiplets".equals(type)) {
                g2.setStroke(new BasicStroke(1.2f));
                g2.drawLine(x + 3, y + height/2, x + width - 3, y + height/2);
                g2.drawLine(x + width/2, y + 2, x + width/2, y + height/2);
                g2.drawLine(x + 4, y + height/2, x + 4, y + height - 2);
                g2.drawLine(x + width - 4, y + height/2, x + width - 4, y + height - 2);
            } else if ("lineshapes".equals(type)) {
                g2.setStroke(new BasicStroke(1.2f));
                g2.drawArc(x + 1, y + 4, width/2 - 1, height - 6, 0, 180);
                g2.drawArc(x + width/2 - 1, y + 4, width/2 - 1, height - 6, 0, 180);
            } else if ("quantify".equals(type)) {
                g2.setStroke(new BasicStroke(1.2f));
                g2.drawRect(x + 2, y + 2, width - 4, height - 4);
                g2.drawLine(x + 5, y + height/2, x + width - 5, y + height/2);
            } else if ("sino".equals(type)) {
                g2.setStroke(new BasicStroke(1.2f));
                int prevX = x + 1;
                int prevY = y + height/2;
                for (int i = 1; i < width - 2; i++) {
                    int cx = x + 1 + i;
                    int cy = y + height/2 + (int)(Math.sin(i * 1.5) * 3) + (i % 2 == 0 ? 2 : -2);
                    g2.drawLine(prevX, prevY, cx, cy);
                    prevX = cx;
                    prevY = cy;
                }
            }
            
            g2.dispose();
        }
    }
    
    // Vertical Command Shortcut Button Creator
    private JButton createShortcutButton(String command, String tooltip, ActionListener action) {
        JButton btn = new JButton(command);
        btn.setToolTipText(tooltip);
        btn.setFont(new Font("Courier New", Font.BOLD, 12));
        btn.setForeground(Color.WHITE);
        btn.setBackground(new Color(60, 70, 80));
        btn.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(COLOR_BLUE_BORDER),
            BorderFactory.createEmptyBorder(4, 4, 4, 4)
        ));
        btn.setFocusPainted(false);
        btn.setContentAreaFilled(false);
        btn.setOpaque(true);
        btn.setMaximumSize(new Dimension(48, 28));
        btn.setPreferredSize(new Dimension(48, 28));
        btn.setAlignmentX(Component.CENTER_ALIGNMENT);
        
        btn.addMouseListener(new MouseAdapter() {
            public void mouseEntered(MouseEvent evt) {
                btn.setBackground(COLOR_PLOT_BLUE);
            }
            public void mouseExited(MouseEvent evt) {
                btn.setBackground(new Color(60, 70, 80));
            }
        });
        
        if (action != null) {
            btn.addActionListener(action);
        }
        return btn;
    }
    
    // Border Helper (2007 Light Theme Styled)
    public static Border createRetroBorder(String title) {
        Border line = BorderFactory.createLineBorder(COLOR_BLUE_BORDER);
        Border titled = BorderFactory.createTitledBorder(line, " " + title + " ",
            TitledBorder.LEFT, TitledBorder.TOP,
            FONT_SANS_BOLD, COLOR_CHARCOAL);
        // Keeps panel contents off the frame instead of sitting flush against it.
        return BorderFactory.createCompoundBorder(titled,
            BorderFactory.createEmptyBorder(6, 8, 8, 8));
    }
    
    // Button Helper (2007 Light Theme Styled)
    public static JButton createRetroButton(String label) {
        JButton btn = new JButton(label);
        btn.setFont(FONT_SANS_BOLD);
        btn.setBackground(new Color(235, 238, 242));
        btn.setForeground(COLOR_CHARCOAL);
        btn.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(COLOR_BLUE_BORDER),
            BorderFactory.createEmptyBorder(5, 10, 5, 10)
        ));
        btn.setFocusPainted(false);
        btn.setContentAreaFilled(false);
        btn.setOpaque(true);
        
        btn.addMouseListener(new MouseAdapter() {
            public void mouseEntered(MouseEvent evt) {
                btn.setBackground(COLOR_BLUE_ACCENT);
            }
            public void mouseExited(MouseEvent evt) {
                btn.setBackground(new Color(235, 238, 242));
            }
        });
        return btn;
    }
    
    private JComboBox<String> createRetroComboBox() {
        JComboBox<String> combo = new JComboBox<>();
        combo.setBackground(COLOR_WHITE);
        combo.setForeground(COLOR_CHARCOAL);
        combo.setFont(FONT_SANS_BOLD);
        combo.setBorder(BorderFactory.createLineBorder(COLOR_BLUE_BORDER));
        combo.setOpaque(true);
        // Match the button height so the axis rows line up instead of looking chunky.
        combo.setPreferredSize(new Dimension(0, 26));
        combo.setRenderer(new DefaultListCellRenderer() {
            public Component getListCellRendererComponent(JList<?> list, Object val, int idx, boolean isSel, boolean cellHasFocus) {
                JLabel lbl = (JLabel) super.getListCellRendererComponent(list, val, idx, isSel, cellHasFocus);
                lbl.setBackground(isSel ? COLOR_BLUE_ACCENT : COLOR_WHITE);
                lbl.setForeground(COLOR_CHARCOAL);
                lbl.setFont(FONT_SANS_BOLD);
                lbl.setOpaque(true);
                return lbl;
            }
        });
        return combo;
    }

    // 2D Custom Plot Panel (White background with dynamic viewport zoom/pan bounds & multi-param element tooltips)
    public static class NmrPlot2D extends JPanel {
        private List<DataPoint> points = new ArrayList<>();
        private List<ContourLine> contourLines = new ArrayList<>();
        private String xLabel = "X";
        private String yLabel = "Y";
        private String zLabel = "Z";
        private boolean invertX = true;
        private boolean invertY = false;
        private boolean showGrid = false;
        private boolean brukerMode = false;

        // 1D mode. The backend reports the dataset's dimensionality; when it is
        // 1 the panel draws a single intensity-vs-ppm trace instead of a contour
        // map. Keeping it in this class rather than adding a second panel means
        // detach/dock, the zoom buttons and the toolbar keep working untouched.
        // Flat x,y pairs, matching how contour segments are stored: 8000
        // vertices as a double[] rather than 8000 boxed Point2D objects.
        private double[] trace = NO_TRACE;
        private int dimension = 2;
        
        // Hover
        private DataPoint hoveredPoint = null;
        private Point hoveredScreenPos = null;

        
        // Data Ranges (constant original ranges)
        private double dataMinX, dataMaxX, dataMinY, dataMaxY;
        
        // Current Viewport Ranges (update on zoom & pan)
        private double viewMinX, viewMaxX, viewMinY, viewMaxY;
        // Only the left and bottom edges carry tick labels and axis titles; the top and
        // right just need enough room for the end tick labels not to clip.
        private final int marginLeft = 58;
        private final int marginRight = 22;
        private final int marginTop = 14;
        private final int marginBottom = 50;

        private java.util.function.DoubleConsumer contourScrollListener;

        private static final Color CONTOUR_POS_COLOR = new Color(30, 96, 145, 120);
        private static final Color CONTOUR_NEG_COLOR = new Color(230, 57, 70, 100);
        private static final BasicStroke CONTOUR_POS_STROKE = new BasicStroke(1.0f);
        private static final BasicStroke CONTOUR_NEG_STROKE = new BasicStroke(
            1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10.0f,
            new float[] { 5.0f, 5.0f }, 0.0f);
        private static final BasicStroke DIAGONAL_STROKE = CONTOUR_NEG_STROKE;
        private static final Color DIAGONAL_COLOR = new Color(68, 68, 85, 180);

        // Java2D only takes its fast line loop when the colour is opaque, the
        // stroke is a plain hairline and antialiasing is off; translucency,
        // dashing or AA each drop it into the general rasteriser, which costs
        // ~8x. The draft pass therefore paints these pre-composited opaques -
        // the exact colour the translucent versions resolve to over the white
        // plot background, so an isolated contour line is pixel-identical and
        // only crossings differ.
        private static final Color CONTOUR_POS_DRAFT = overWhite(CONTOUR_POS_COLOR);
        private static final Color CONTOUR_NEG_DRAFT = overWhite(CONTOUR_NEG_COLOR);

        private static Color overWhite(Color c) {
            double alpha = c.getAlpha() / 255.0;
            return new Color(
                (int) Math.round(c.getRed()   * alpha + 255 * (1 - alpha)),
                (int) Math.round(c.getGreen() * alpha + 255 * (1 - alpha)),
                (int) Math.round(c.getBlue()  * alpha + 255 * (1 - alpha)));
        }

        // ── Contour layer cache ──────────────────────────────────────────────
        //
        // Redrawing ~600k vertices costs 150 ms at the default threshold and
        // 700 ms down at the noise floor, and the old code paid that on every
        // single repaint - including the ones a mouse move triggers. Contours
        // now render once into an offscreen layer that ordinary repaints just
        // blit (~1 ms, independent of density).
        //
        // Two quality levels, synchronised by the timers below:
        //   draft - opaque, aliased, hairline. Rendered inline, ~8x cheaper, so
        //           a wheel notch still lands within a frame or two.
        //   full  - the real translucent, antialiased, dashed rendering. Built
        //           on a background thread once the gesture stops, then swapped
        //           in, so its 150-700 ms never blocks the UI.
        private java.util.function.DoubleConsumer intensityScaleListener;
        private java.util.function.BiConsumer<NmrPlot2D, double[]> regionSelectListener;

        // ── Region selection ────────────────────────────────────────────────
        //
        // Left-drag marks a rectangle; on release it is handed to the listener,
        // which opens it in its own window. Right-drag (or shift-drag) pans
        // instead, so a zoomed-in region window can be walked around without
        // every drag being read as a new selection.
        private Point dragAnchor;     // where the press landed, screen coords
        private Point dragCurrent;    // latest drag position
        private boolean panning;
        private double panFromMinX, panFromMaxX, panFromMinY, panFromMaxY;
        private static final int MIN_SELECT_PX = 8;
        private static final Color SELECT_FILL = new Color(30, 96, 145, 40);
        private static final Color SELECT_EDGE = new Color(20, 70, 110);
        private static final BasicStroke SELECT_STROKE = new BasicStroke(
            1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10.0f,
            new float[] { 4.0f, 3.0f }, 0.0f);

        // The region this panel was opened on, if any. Reset View returns here
        // rather than to the whole spectrum, so a region window stays a region
        // window - zooming out to the full map is a separate, explicit action.
        private double[] homeView;

        // 1D intensity scaling. The wheel means "make the peaks taller/shorter"
        // on a 1D spectrum, the way it means "raise/lower the contour threshold"
        // on a 2D one. Both end stops are generous: a 1D spectrum routinely
        // needs a x1000 blow-up to bring impurity peaks off the baseline.
        private static final double INTENSITY_SCROLL_STEP = 1.15;
        private static final double TRACE_SCALE_MIN = 0.02;
        private static final double TRACE_SCALE_MAX = 20000.0;

        private java.awt.image.BufferedImage contourLayer;
        // When false, negative levels are drawn in the positive blue with the
        // positive stroke, so the spectrum reads as one consistent colour
        // instead of the red/blue interleave a phase-sensitive 2D produces.
        private boolean showNegativeColor = true;
        private boolean layerIsDraft = true;
        private long layerKey = Long.MIN_VALUE;   // viewKey the layer was built for
        private long viewKey = 0;                 // bumped on any data or viewport change
        private long refiningKey = Long.MIN_VALUE;
        private long refineScheduledKey = Long.MIN_VALUE;
        private boolean interacting = false;
        private javax.swing.Timer interactionTimer;
        private javax.swing.Timer refineTimer;
        private static final int INTERACTION_IDLE_MS = 140;
        private static final int REFINE_DELAY_MS = 90;

        // Any change to what is plotted or where the viewport sits retires the
        // cached layer. Grid and hover deliberately do not - they are painted on
        // top of the blit, so toggling them stays free.
        private void invalidateContourLayer() {
            viewKey++;
        }

        // Marks a gesture as in progress, so repaints during it settle for the
        // draft layer instead of paying for the full one mid-flight.
        private void markInteracting() {
            interacting = true;
            if (interactionTimer == null) {
                interactionTimer = new javax.swing.Timer(INTERACTION_IDLE_MS, e -> {
                    interacting = false;
                    repaint();
                });
                interactionTimer.setRepeats(false);
            }
            interactionTimer.restart();
        }

        private void ensureContourLayer(int plotWidth, int plotHeight) {
            boolean sized = contourLayer != null
                    && contourLayer.getWidth() == plotWidth
                    && contourLayer.getHeight() == plotHeight;
            boolean current = sized && layerKey == viewKey;

            if (current && !layerIsDraft) return;   // nothing better to render
            if (current && interacting) return;     // draft is all this frame needs

            if (!current) {
                if (!sized) {
                    contourLayer = new java.awt.image.BufferedImage(
                            plotWidth, plotHeight, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                }
                renderContours(contourLayer, contourLines, viewMinX, viewMaxX, viewMinY, viewMaxY,
                        invertX, invertY, true, showNegativeColor);
                layerKey = viewKey;
                layerIsDraft = true;
            }

            // Debounced, and keyed so that repaints which change nothing (a mouse
            // move, an expose) cannot keep pushing the refine out forever.
            if (layerIsDraft && refineScheduledKey != viewKey) {
                refineScheduledKey = viewKey;
                if (refineTimer == null) {
                    refineTimer = new javax.swing.Timer(REFINE_DELAY_MS, e -> startRefine());
                    refineTimer.setRepeats(false);
                }
                refineTimer.restart();
            }
        }

        private void startRefine() {
            if (interacting) {              // still moving - try again once it stops
                refineTimer.restart();
                return;
            }
            if (contourLayer == null || !layerIsDraft) return;

            final long key = viewKey;
            if (refiningKey == key) return;
            refiningKey = key;

            final int w = contourLayer.getWidth();
            final int h = contourLayer.getHeight();
            final List<ContourLine> snapshot = contourLines;
            final double x0 = viewMinX, x1 = viewMaxX, y0 = viewMinY, y1 = viewMaxY;
            final boolean ix = invertX, iy = invertY;
            final boolean negColor = showNegativeColor;

            Thread worker = new Thread(() -> {
                java.awt.image.BufferedImage full = new java.awt.image.BufferedImage(
                        w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                renderContours(full, snapshot, x0, x1, y0, y1, ix, iy, false, negColor);
                SwingUtilities.invokeLater(() -> {
                    refiningKey = Long.MIN_VALUE;
                    if (viewKey != key) return;   // viewport moved on; this is stale
                    contourLayer = full;
                    layerKey = key;
                    layerIsDraft = false;
                    repaint();
                });
            }, "contour-refine");
            worker.setDaemon(true);
            worker.start();
        }

        // Reads nothing but its arguments, so it is safe on the refine thread.
        private static void renderContours(java.awt.image.BufferedImage target,
                                           List<ContourLine> lines,
                                           double vMinX, double vMaxX, double vMinY, double vMaxY,
                                           boolean invertX, boolean invertY, boolean draft,
                                           boolean colorNegatives) {
            final int w = target.getWidth(), h = target.getHeight();
            Graphics2D g = target.createGraphics();
            try {
                g.setComposite(AlphaComposite.Clear);
                g.fillRect(0, 0, w, h);
                g.setComposite(AlphaComposite.SrcOver);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        draft ? RenderingHints.VALUE_ANTIALIAS_OFF
                              : RenderingHints.VALUE_ANTIALIAS_ON);

                final Color pos = draft ? CONTOUR_POS_DRAFT : CONTOUR_POS_COLOR;
                final Color neg = colorNegatives ? (draft ? CONTOUR_NEG_DRAFT : CONTOUR_NEG_COLOR)
                                                 : pos;
                final BasicStroke negStroke = !colorNegatives ? CONTOUR_POS_STROKE
                                            : draft ? CONTOUR_POS_STROKE : CONTOUR_NEG_STROKE;

                // Normalise then scale, in that order, and add the margin only
                // after the cast - the same sequence the pre-cache painter used.
                // Folding it into one multiply is algebraically identical but
                // rounds differently at the last ulp, which nudged the odd
                // antialiased line a pixel sideways.
                final double spanX = vMaxX - vMinX, spanY = vMaxY - vMinY;
                if (spanX == 0 || spanY == 0) return;

                int[] xs = new int[1024], ys = new int[1024];
                for (ContourLine cl : lines) {
                    g.setColor(cl.isPositive ? pos : neg);
                    g.setStroke(cl.isPositive ? CONTOUR_POS_STROKE : negStroke);

                    double[][] segs = cl.segments;
                    for (int s = 0; s < segs.length; s++) {
                        if (cl.segMaxX[s] < vMinX || cl.segMinX[s] > vMaxX
                                || cl.segMaxY[s] < vMinY || cl.segMinY[s] > vMaxY) {
                            continue;
                        }
                        double[] seg = segs[s];
                        int vertexCount = seg.length >> 1;
                        if (vertexCount < 2) continue;
                        if (xs.length < vertexCount) {
                            xs = new int[vertexCount + (vertexCount >> 1)];
                            ys = new int[xs.length];
                        }
                        int n = 0;
                        for (int i = 0; i < seg.length; i += 2) {
                            double nx = (seg[i] - vMinX) / spanX;
                            if (invertX) nx = 1.0 - nx;
                            double ny = (seg[i + 1] - vMinY) / spanY;
                            if (invertY) ny = 1.0 - ny;
                            int px = (int) (nx * w), py = (int) (ny * h);
                            // Zoomed out, long stretches collapse onto one pixel.
                            if (n > 0 && px == xs[n - 1] && py == ys[n - 1]) continue;
                            xs[n] = px;
                            ys[n] = py;
                            n++;
                        }
                        if (n >= 2) g.drawPolyline(xs, ys, n);
                    }
                }
            } finally {
                g.dispose();
            }
        }

        public void setContourScrollListener(java.util.function.DoubleConsumer listener) {
            this.contourScrollListener = listener;
        }

        /** Notified with (source panel, {minX,maxX,minY,maxY}) when a drag selects a region. */
        public void setRegionSelectListener(java.util.function.BiConsumer<NmrPlot2D, double[]> listener) {
            this.regionSelectListener = listener;
        }

        // Screen to data, the exact inverse of the mapping paintComponent and
        // checkHover use - including the invert flags, so a selection made on a
        // flipped axis lands on the peaks that were actually under the box.
        private double screenToDataX(int px, int plotWidth) {
            double nx = (px - marginLeft) / (double) plotWidth;
            if (invertX) nx = 1.0 - nx;
            return viewMinX + nx * (viewMaxX - viewMinX);
        }

        private double screenToDataY(int py, int plotHeight) {
            double ny = (py - marginTop) / (double) plotHeight;
            if (invertY) ny = 1.0 - ny;
            return viewMinY + ny * (viewMaxY - viewMinY);
        }

        /** The dragged box in data coordinates: {minX, maxX, minY, maxY}. */
        private double[] regionFromDrag(Point from, Point to) {
            int plotWidth = getWidth() - marginLeft - marginRight;
            int plotHeight = getHeight() - marginTop - marginBottom;
            if (plotWidth <= 0 || plotHeight <= 0) return null;

            double x1 = screenToDataX(from.x, plotWidth);
            double x2 = screenToDataX(to.x, plotWidth);
            double y1 = screenToDataY(from.y, plotHeight);
            double y2 = screenToDataY(to.y, plotHeight);
            double[] r = { Math.min(x1, x2), Math.max(x1, x2),
                           Math.min(y1, y2), Math.max(y1, y2) };
            if (r[0] == r[1] || r[2] == r[3]) return null;
            return r;
        }

        // Anchored to the press, not to the previous drag event, so a fast drag
        // cannot accumulate rounding error into visible drift.
        private void panBy(Point from, Point to) {
            int plotWidth = getWidth() - marginLeft - marginRight;
            int plotHeight = getHeight() - marginTop - marginBottom;
            if (plotWidth <= 0 || plotHeight <= 0) return;

            double dx = (to.x - from.x) / (double) plotWidth * (panFromMaxX - panFromMinX);
            if (invertX) dx = -dx;
            double dy = (to.y - from.y) / (double) plotHeight * (panFromMaxY - panFromMinY);
            if (invertY) dy = -dy;

            // The spectrum follows the cursor, so the window moves the other way.
            viewMinX = panFromMinX - dx; viewMaxX = panFromMaxX - dx;
            viewMinY = panFromMinY - dy; viewMaxY = panFromMaxY - dy;

            invalidateContourLayer();
            markInteracting();
            repaint();
        }

        /** Point the viewport at a region; `makeHome` also makes it what Reset View returns to. */
        public void setViewWindow(double minX, double maxX, double minY, double maxY, boolean makeHome) {
            viewMinX = minX; viewMaxX = maxX;
            viewMinY = minY; viewMaxY = maxY;
            if (makeHome) homeView = new double[] { minX, maxX, minY, maxY };
            invalidateContourLayer();
            repaint();
        }

        /** Drop the region anchor, so Reset View goes back to the full spectrum. */
        public void clearHomeView() {
            homeView = null;
        }

        /**
         * A second panel over the same spectrum, opened on `region`.
         *
         * The contour and peak lists are shared by reference, not copied: they
         * are immutable once parsed and run to tens of megabytes, so a region
         * window costs a viewport and a layer bitmap rather than a second copy
         * of the spectrum. The data bounds come across too, so the child's zoom
         * limits and Full Spectrum button measure from the real extent rather
         * than from the slice it happens to be showing.
         */
        public NmrPlot2D createRegionView(double[] region) {
            NmrPlot2D child = new NmrPlot2D();
            child.points = points;
            child.contourLines = contourLines;
            child.trace = trace;
            child.dimension = dimension;
            child.xLabel = xLabel; child.yLabel = yLabel; child.zLabel = zLabel;
            child.invertX = invertX; child.invertY = invertY;
            child.brukerMode = brukerMode;
            child.showNegativeColor = showNegativeColor;
            child.showGrid = showGrid;
            child.dataMinX = dataMinX; child.dataMaxX = dataMaxX;
            child.dataMinY = dataMinY; child.dataMaxY = dataMaxY;
            child.setViewWindow(region[0], region[1], region[2], region[3], true);
            return child;
        }

        /**
         * Swap in a reprocessed spectrum without disturbing the viewport.
         *
         * A region window is anchored in ppm, so re-phasing or a threshold
         * change should leave it looking at the same peaks - which is exactly
         * what skipping calculateRanges()/resetView() here achieves.
         */
        public void refreshSpectrum(List<DataPoint> pts, List<ContourLine> contours,
                                    boolean ix, boolean iy) {
            this.points = pts;
            this.contourLines = contours;
            this.invertX = ix;
            this.invertY = iy;
            invalidateContourLayer();
            repaint();
        }

        /** Notified with the new magnification whenever a 1D wheel scroll lands. */
        public void setIntensityScaleListener(java.util.function.DoubleConsumer listener) {
            this.intensityScaleListener = listener;
        }

        /**
         * Scroll up to grow the peaks, down to shrink them.
         *
         * The intensity window is divided about zero rather than about the view
         * centre, so the baseline stays pinned to its pixel row and only the
         * peak heights move - what TopSpin's wheel does, and the only variant
         * where a scaled-up spectrum still lines up with the one under it.
         *
         * The current magnification is derived from the window rather than
         * carried in a field, so the zoom buttons and a reset cannot leave the
         * end stops measuring from a stale origin.
         */
        private void scaleTraceIntensity(double wheelRotation) {
            if (trace.length < 4) return;
            double span = viewMaxY - viewMinY;
            double dataSpan = dataMaxY - dataMinY;
            if (span <= 0 || dataSpan <= 0) return;

            double scale = dataSpan / span;                     // 1.0 = auto-fit height
            double target = scale * Math.pow(INTENSITY_SCROLL_STEP, -wheelRotation);
            if (target < TRACE_SCALE_MIN) target = TRACE_SCALE_MIN;
            if (target > TRACE_SCALE_MAX) target = TRACE_SCALE_MAX;
            if (target == scale) return;                        // already hard against an end stop

            double applied = target / scale;
            viewMinY /= applied;
            viewMaxY /= applied;
            repaint();
            if (intensityScaleListener != null) intensityScaleListener.accept(target);
        }

        public NmrPlot2D() {
            setBackground(COLOR_WHITE);
            
            MouseAdapter mouseAdapter = new MouseAdapter() {
                public void mouseWheelMoved(MouseWheelEvent e) {
                    // Checked before the emptiness guard below: a 1D spectrum
                    // has a trace but often no picked peaks and never contours.
                    if (dimension == 1) {
                        scaleTraceIntensity(e.getPreciseWheelRotation());
                        return;
                    }
                    if (points.isEmpty() && contourLines.isEmpty()) return;
                    markInteracting();
                    if (contourScrollListener != null) {
                        contourScrollListener.accept(e.getPreciseWheelRotation());
                    }
                }
                public void mouseMoved(MouseEvent e) {
                    checkHover(e.getPoint());
                }

                public void mousePressed(MouseEvent e) {
                    // 1D keeps the wheel as its only gesture: a trace has no
                    // second axis to box-select on.
                    if (dimension == 1) return;
                    if (points.isEmpty() && contourLines.isEmpty()) return;
                    panning = SwingUtilities.isRightMouseButton(e)
                            || SwingUtilities.isMiddleMouseButton(e)
                            || e.isShiftDown();
                    dragAnchor = e.getPoint();
                    dragCurrent = e.getPoint();
                    panFromMinX = viewMinX; panFromMaxX = viewMaxX;
                    panFromMinY = viewMinY; panFromMaxY = viewMaxY;
                }

                public void mouseDragged(MouseEvent e) {
                    if (dragAnchor == null) return;
                    dragCurrent = e.getPoint();
                    if (panning) {
                        panBy(dragAnchor, dragCurrent);
                    } else {
                        repaint();      // rubber band only; cheap, it is an overlay
                    }
                }

                public void mouseReleased(MouseEvent e) {
                    if (dragAnchor == null) return;
                    Point from = dragAnchor, to = dragCurrent;
                    boolean wasPanning = panning;
                    dragAnchor = null;
                    dragCurrent = null;
                    panning = false;
                    repaint();
                    if (wasPanning || to == null) return;
                    if (Math.abs(to.x - from.x) < MIN_SELECT_PX
                            || Math.abs(to.y - from.y) < MIN_SELECT_PX) {
                        return;         // a click, or a slip - not a selection
                    }
                    double[] region = regionFromDrag(from, to);
                    if (region != null && regionSelectListener != null) {
                        regionSelectListener.accept(NmrPlot2D.this, region);
                    }
                }
            };
            setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
            addMouseListener(mouseAdapter);
            addMouseMotionListener(mouseAdapter);
            addMouseWheelListener(mouseAdapter);
        }

        // Colour-only switch: the contour geometry is untouched, so this just
        // retires the cached layer and lets it repaint.
        public void setShowNegativeColor(boolean show) {
            if (this.showNegativeColor == show) return;
            this.showNegativeColor = show;
            invalidateContourLayer();
            repaint();
        }

        public boolean isShowNegativeColor() {
            return showNegativeColor;
        }

        public void setData(List<DataPoint> pts, List<ContourLine> contours, String xl, String yl, String zl, boolean ix, boolean iy) {
            boolean axesChanged = !xl.equals(this.xLabel) || !yl.equals(this.yLabel);

            this.points = pts;
            this.contourLines = contours;
            this.xLabel = xl;
            this.yLabel = yl;
            this.zLabel = zl;
            this.invertX = ix;
            this.invertY = iy;

            invalidateContourLayer();
            calculateRanges();
            if (viewMinX == viewMaxX || axesChanged) {
                // First load, or the plotted atoms changed - preserve pan/zoom only when
                // reprocessing the same atom pair (e.g. phase/baseline correction).
                resetView();
            }
        }

        public void clearData() {
            points = new ArrayList<>();
            contourLines = new ArrayList<>();
            trace = NO_TRACE;
            dimension = 2;
            hoveredPoint = null;
            hoveredScreenPos = null;
            xLabel = "X";
            yLabel = "Y";
            zLabel = "Z";
            // Collapsing the viewport makes the next setData() treat itself as a first load.
            dataMinX = dataMaxX = dataMinY = dataMaxY = 0.0;
            viewMinX = viewMaxX = viewMinY = viewMaxY = 0.0;
            contourLayer = null;
            invalidateContourLayer();
            repaint();
        }

        public void setBrukerMode(boolean enabled) {
            this.brukerMode = enabled;
        }

        /** Supply the 1D trace (flat x,y pairs) and the dimensionality. Call before setData. */
        public void setTrace(double[] tracePoints, int dim) {
            this.trace = tracePoints != null ? tracePoints : NO_TRACE;
            this.dimension = dim;
        }
        
        private void calculateRanges() {
            if (dimension == 1) {
                if (trace.length < 4) return;
                dataMinX = Double.MAX_VALUE; dataMaxX = -Double.MAX_VALUE;
                dataMinY = Double.MAX_VALUE; dataMaxY = -Double.MAX_VALUE;
                for (int i = 0; i + 1 < trace.length; i += 2) {
                    double tx = trace[i], ty = trace[i + 1];
                    if (tx < dataMinX) dataMinX = tx;
                    if (tx > dataMaxX) dataMaxX = tx;
                    if (ty < dataMinY) dataMinY = ty;
                    if (ty > dataMaxY) dataMaxY = ty;
                }
                double padX1 = (dataMaxX - dataMinX) * 0.02; if (padX1 == 0) padX1 = 1.0;
                dataMinX -= padX1; dataMaxX += padX1;
                // Headroom over the tallest peak, a little room under the baseline.
                double span = dataMaxY - dataMinY; if (span == 0) span = 1.0;
                dataMaxY += span * 0.08;
                dataMinY -= span * 0.04;
                return;
            }
            if (points.isEmpty() && contourLines.isEmpty()) return;
            dataMinX = Double.MAX_VALUE; dataMaxX = -Double.MAX_VALUE;
            dataMinY = Double.MAX_VALUE; dataMaxY = -Double.MAX_VALUE;

            for (DataPoint p : points) {
                if (p.x < dataMinX) dataMinX = p.x;
                if (p.x > dataMaxX) dataMaxX = p.x;
                if (p.y < dataMinY) dataMinY = p.y;
                if (p.y > dataMaxY) dataMaxY = p.y;
            }

            // Bruker spectra carry no peak list, so the extent comes from the
            // contours - but from their per-segment bounds, which were computed
            // once at parse time, not from a fresh walk over ~600k vertices on
            // every setData (which a contour scroll triggers per wheel notch).
            for (ContourLine cl : contourLines) {
                for (int i = 0; i < cl.segMinX.length; i++) {
                    if (cl.segMinX[i] < dataMinX) dataMinX = cl.segMinX[i];
                    if (cl.segMaxX[i] > dataMaxX) dataMaxX = cl.segMaxX[i];
                    if (cl.segMinY[i] < dataMinY) dataMinY = cl.segMinY[i];
                    if (cl.segMaxY[i] > dataMaxY) dataMaxY = cl.segMaxY[i];
                }
            }

            // Add padding
            double padX = (dataMaxX - dataMinX) * 0.1; if (padX == 0) padX = 1.0;
            dataMinX -= padX; dataMaxX += padX;
            double padY = (dataMaxY - dataMinY) * 0.1; if (padY == 0) padY = 1.0;
            dataMinY -= padY; dataMaxY += padY;
        }
        
        public void resetView() {
            if (homeView != null) {
                viewMinX = homeView[0]; viewMaxX = homeView[1];
                viewMinY = homeView[2]; viewMaxY = homeView[3];
                invalidateContourLayer();
                repaint();
                return;
            }
            if (brukerMode && dimension != 1) {
                viewMinX = -0.3;
                viewMaxX = 9.8;
                viewMinY = -0.3;
                viewMaxY = 9.8;
            } else {
                viewMinX = dataMinX;
                viewMaxX = dataMaxX;
                viewMinY = dataMinY;
                viewMaxY = dataMaxY;
            }
            invalidateContourLayer();
            repaint();
        }
        
        public void zoomIn() {
            double cx = (viewMinX + viewMaxX) / 2.0;
            double rx = viewMaxX - viewMinX;
            viewMinX = cx - rx * 0.8 / 2.0;
            viewMaxX = cx + rx * 0.8 / 2.0;
            
            double cy = (viewMinY + viewMaxY) / 2.0;
            double ry = viewMaxY - viewMinY;
            viewMinY = cy - ry * 0.8 / 2.0;
            viewMaxY = cy + ry * 0.8 / 2.0;
            invalidateContourLayer();
            markInteracting();
            repaint();
        }
        
        public void zoomOut() {
            double cx = (viewMinX + viewMaxX) / 2.0;
            double rx = viewMaxX - viewMinX;
            viewMinX = cx - rx * 1.25 / 2.0;
            viewMaxX = cx + rx * 1.25 / 2.0;
            
            double cy = (viewMinY + viewMaxY) / 2.0;
            double ry = viewMaxY - viewMinY;
            viewMinY = cy - ry * 1.25 / 2.0;
            viewMaxY = cy + ry * 1.25 / 2.0;
            invalidateContourLayer();
            markInteracting();
            repaint();
        }
        
        public void setShowGrid(boolean show) { this.showGrid = show; repaint(); }
        
        public void zoomX(double scaleFactor) {
            double cx = (viewMinX + viewMaxX) / 2.0;
            double rx = viewMaxX - viewMinX;
            viewMinX = cx - rx * scaleFactor / 2.0;
            viewMaxX = cx + rx * scaleFactor / 2.0;
            invalidateContourLayer();
            markInteracting();
            repaint();
        }
        
        public void zoomY(double scaleFactor) {
            if (dimension == 1) {
                // Scale about zero, the way a spectrometer's intensity control
                // behaves: the baseline stays put and the peaks grow or shrink.
                viewMinY *= scaleFactor;
                viewMaxY *= scaleFactor;
                repaint();
                return;
            }
            double cy = (viewMinY + viewMaxY) / 2.0;
            double ry = viewMaxY - viewMinY;
            viewMinY = cy - ry * scaleFactor / 2.0;
            viewMaxY = cy + ry * scaleFactor / 2.0;
            invalidateContourLayer();
            markInteracting();
            repaint();
        }
        
        private void checkHover(Point mousePos) {
            if (points.isEmpty()) return;
            DataPoint closest = null;
            double minDist = 8.0; 
            Point closestPos = null;
            
            int width = getWidth();
            int height = getHeight();
            int plotWidth = width - marginLeft - marginRight;
            int plotHeight = height - marginTop - marginBottom;
            if (plotWidth <= 0 || plotHeight <= 0) return;
            
            for (DataPoint p : points) {
                double nx = (p.x - viewMinX) / (viewMaxX - viewMinX);
                if (invertX) nx = 1.0 - nx;
                double ny = (p.y - viewMinY) / (viewMaxY - viewMinY);
                if (invertY) ny = 1.0 - ny;
                
                int px = marginLeft + (int)(nx * plotWidth);
                int py = marginTop + (int)(ny * plotHeight);
                
                double dist = mousePos.distance(px, py);
                if (dist < minDist) {
                    minDist = dist;
                    closest = p;
                    closestPos = new Point(px, py);
                }
            }
            
            if (hoveredPoint != closest) {
                hoveredPoint = closest;
                hoveredScreenPos = closestPos;
                repaint();
            }
        }

        private static String formatIntensity(double v) {
            double abs = Math.abs(v);
            if (abs >= 1e4 || (abs > 0 && abs < 1e-2)) return String.format("%.1e", v);
            if (abs >= 1e3) return String.format("%.0f", v);
            return String.format("%.1f", v);
        }

        /**
         * Render a 1D spectrum: intensity against ppm, ppm descending to the
         * right. The trace arrives from the backend already decimated to a
         * min/max envelope, so a 128k-point spectrum is a few thousand
         * vertices and needs none of the contour layer's caching machinery.
         */
        private void paint1D(Graphics2D g2, int width, int height,
                             int plotWidth, int plotHeight) {
            double lo = Math.min(viewMinX, viewMaxX);
            double hi = Math.max(viewMinX, viewMaxX);

            // Grid and tick labels
            g2.setFont(FONT_SANS);
            for (int k = 0; k <= 10; k++) {
                double frac = k / 10.0;

                int gx = marginLeft + (int) (frac * plotWidth);
                if (showGrid) {
                    g2.setColor(COLOR_GRID_LINE);
                    g2.drawLine(gx, marginTop, gx, marginTop + plotHeight);
                }
                g2.setColor(COLOR_CHARCOAL);
                double vx = viewMinX + (invertX ? (1.0 - frac) : frac) * (viewMaxX - viewMinX);
                g2.drawString(String.format("%.2f", vx), gx - 15, marginTop + plotHeight + 16);

                int gy = marginTop + (int) (frac * plotHeight);
                if (showGrid) {
                    g2.setColor(COLOR_GRID_LINE);
                    g2.drawLine(marginLeft, gy, marginLeft + plotWidth, gy);
                }
                g2.setColor(COLOR_CHARCOAL);
                double vy = viewMinY + (1.0 - frac) * (viewMaxY - viewMinY);
                String vyText = formatIntensity(vy);
                g2.drawString(vyText, marginLeft - 6 - g2.getFontMetrics().stringWidth(vyText), gy + 5);
            }

            // Axis titles
            g2.setColor(COLOR_CHARCOAL);
            g2.setFont(FONT_SANS_BOLD);
            g2.drawString(xLabel + " (PPM)", width / 2 - 20, marginTop + plotHeight + 38);
            // No rotated "Intensity" title here: the left gutter is sized for
            // 2D's ppm ticks, and intensity values are wide enough that a
            // vertical title collides with them. The numbers are unambiguous,
            // and TopSpin leaves the 1D intensity axis unlabelled too.

            Shape oldClip = g2.getClip();
            g2.setClip(marginLeft + 1, marginTop + 1, plotWidth - 1, plotHeight - 1);

            // Baseline
            int zeroY = trace1dY(0.0, plotHeight);
            if (zeroY >= marginTop && zeroY <= marginTop + plotHeight) {
                g2.setColor(new Color(200, 200, 200));
                g2.drawLine(marginLeft, zeroY, marginLeft + plotWidth, zeroY);
            }

            // The trace. Only vertices inside the current ppm window are
            // emitted, so zooming in costs less work rather than more.
            int vertexCount = trace.length / 2;
            int[] xs = new int[vertexCount];
            int[] ys = new int[vertexCount];
            int nPts = 0;
            for (int v = 0; v < vertexCount; v++) {
                double tx = trace[v * 2], ty = trace[v * 2 + 1];
                boolean inside = tx >= lo && tx <= hi;
                boolean neighbourInside =
                        (v > 0 && trace[(v - 1) * 2] >= lo && trace[(v - 1) * 2] <= hi) ||
                        (v + 1 < vertexCount && trace[(v + 1) * 2] >= lo && trace[(v + 1) * 2] <= hi);
                if (!inside && !neighbourInside) continue;
                xs[nPts] = trace1dX(tx, plotWidth);
                ys[nPts] = trace1dY(ty, plotHeight);
                nPts++;
            }
            if (nPts >= 2) {
                g2.setColor(COLOR_PLOT_BLUE);
                g2.setStroke(new BasicStroke(1.0f));
                g2.drawPolyline(xs, ys, nPts);
            }

            // Picked peaks. The list is ranked by intensity, so labelling
            // greedily and skipping anything too close to a label already
            // placed keeps the strongest peaks legible in a crowded multiplet.
            List<Integer> labelledAt = new ArrayList<>();
            g2.setFont(new Font("Arial", Font.PLAIN, 9));
            for (DataPoint p : points) {
                if (p.x < lo || p.x > hi) continue;
                int px = trace1dX(p.x, plotWidth);
                int py = trace1dY(p.y, plotHeight);

                g2.setColor(COLOR_PLOT_HOVER);
                g2.drawLine(px, py - 10, px, py - 3);

                boolean crowded = false;
                for (int placed : labelledAt) {
                    if (Math.abs(placed - px) < 28) { crowded = true; break; }
                }
                if (crowded) continue;
                labelledAt.add(px);
                g2.setColor(COLOR_CHARCOAL);
                g2.drawString(String.format("%.2f", p.x), px - 10, py - 13);
            }

            g2.setClip(oldClip);

            // Hover readout
            if (hoveredPoint != null && hoveredScreenPos != null) {
                g2.setColor(COLOR_PLOT_HOVER);
                g2.fillOval(hoveredScreenPos.x - 4, hoveredScreenPos.y - 4, 8, 8);
                g2.setColor(Color.BLACK);
                g2.drawOval(hoveredScreenPos.x - 4, hoveredScreenPos.y - 4, 8, 8);

                int tx = hoveredScreenPos.x + 10;
                int ty = hoveredScreenPos.y - 60;
                int boxWidth = 190, boxHeight = 52;
                if (tx + boxWidth > width) tx = hoveredScreenPos.x - boxWidth - 10;
                if (ty < 0) ty = hoveredScreenPos.y + 10;

                g2.setColor(COLOR_TOOLTIP_BG);
                g2.fillRect(tx, ty, boxWidth, boxHeight);
                g2.setColor(new Color(100, 100, 100));
                g2.drawRect(tx, ty, boxWidth, boxHeight);

                g2.setColor(COLOR_CHARCOAL);
                g2.setFont(FONT_SANS);
                g2.drawString("Peak: " + hoveredPoint.seqId, tx + 8, ty + 16);
                g2.drawString(xLabel + ": " + String.format("%.4f ppm", hoveredPoint.x), tx + 8, ty + 31);
                g2.drawString("Intensity: " + formatIntensity(hoveredPoint.y), tx + 8, ty + 46);
            }
        }

        private int trace1dX(double ppm, int plotWidth) {
            double nx = (ppm - viewMinX) / (viewMaxX - viewMinX);
            if (invertX) nx = 1.0 - nx;
            return marginLeft + (int) (nx * plotWidth);
        }

        private int trace1dY(double intensity, int plotHeight) {
            double ny = (intensity - viewMinY) / (viewMaxY - viewMinY);
            double py = marginTop + (1.0 - ny) * plotHeight;
            // Clamp rather than letting a hugely zoomed value overflow the cast.
            if (py < -1e6) py = -1e6;
            if (py > 1e6) py = 1e6;
            return (int) py;
        }

        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int width = getWidth();
            int height = getHeight();
            
            g2.setColor(COLOR_WHITE);
            g2.fillRect(0, 0, width, height);
            
            int plotWidth = width - marginLeft - marginRight;
            int plotHeight = height - marginTop - marginBottom;
            
            g2.setColor(COLOR_BLUE_BORDER);
            g2.drawRect(marginLeft, marginTop, plotWidth, plotHeight);
            
            if (points.isEmpty() && contourLines.isEmpty() && trace.length == 0) {
                g2.setColor(COLOR_CHARCOAL);
                g2.setFont(FONT_SANS);
                g2.drawString("NO DATA LOADED", width / 2 - 50, height / 2);
                return;
            }

            if (dimension == 1) {
                paint1D(g2, width, height, plotWidth, plotHeight);
                return;
            }
            
            // Draw Grid Lines (ticks map to view bounds)
            g2.setFont(FONT_SANS);
            for (int k = 0; k <= 10; k++) {
                double frac = k / 10.0;
                int gx = marginLeft + (int)(frac * plotWidth);
                if (showGrid) {
                    g2.setColor(COLOR_GRID_LINE);
                    g2.drawLine(gx, marginTop, gx, marginTop + plotHeight);
                }
                g2.setColor(COLOR_CHARCOAL);
                double vx = viewMinX + (invertX ? (1.0 - frac) : frac) * (viewMaxX - viewMinX);
                g2.drawString(String.format("%.2f", vx), gx - 15, marginTop + plotHeight + 16);
                
                int gy = marginTop + (int)(frac * plotHeight);
                if (showGrid) {
                    g2.setColor(COLOR_GRID_LINE);
                    g2.drawLine(marginLeft, gy, marginLeft + plotWidth, gy);
                }
                g2.setColor(COLOR_CHARCOAL);
                double vy = viewMinY + (invertY ? (1.0 - frac) : frac) * (viewMaxY - viewMinY);
                g2.drawString(String.format("%.2f", vy), marginLeft - 45, gy + 5);
            }
            
            // Axes Labels
            g2.setColor(COLOR_CHARCOAL);
            g2.setFont(FONT_SANS_BOLD);
            g2.drawString(xLabel + " (PPM)", width / 2 - 20, marginTop + plotHeight + 38);
            
            g2.translate(20, height / 2 + 30);
            g2.rotate(-Math.PI / 2);
            g2.drawString(yLabel + " (PPM)", 0, 0);
            g2.rotate(Math.PI / 2);
            g2.translate(-20, -(height / 2 + 30));
            
            // Contours arrive as a pre-rendered layer covering exactly the plot
            // rectangle, which also does the clipping the explicit clipRect used
            // to. Hover and grid are painted over the top, so moving the mouse no
            // longer redraws 600k vertices.
            if (plotWidth > 0 && plotHeight > 0 && !contourLines.isEmpty()) {
                ensureContourLayer(plotWidth, plotHeight);
                if (contourLayer != null) {
                    g2.drawImage(contourLayer, marginLeft, marginTop, null);
                }
            }

            if (brukerMode) {
                g2.setColor(DIAGONAL_COLOR);
                g2.setStroke(DIAGONAL_STROKE);
                // The x==y diagonal, mapped through the same transform as the contours -
                // drawing it to fixed corners mirrors it whenever the axes are inverted.
                double diagLo = Math.max(viewMinX, viewMinY);
                double diagHi = Math.min(viewMaxX, viewMaxY);
                if (diagLo < diagHi) {
                    double nxLo = (diagLo - viewMinX) / (viewMaxX - viewMinX);
                    double nxHi = (diagHi - viewMinX) / (viewMaxX - viewMinX);
                    if (invertX) { nxLo = 1.0 - nxLo; nxHi = 1.0 - nxHi; }
                    double nyLo = (diagLo - viewMinY) / (viewMaxY - viewMinY);
                    double nyHi = (diagHi - viewMinY) / (viewMaxY - viewMinY);
                    if (invertY) { nyLo = 1.0 - nyLo; nyHi = 1.0 - nyHi; }

                    g2.drawLine(
                        marginLeft + (int)(nxLo * plotWidth), marginTop + (int)(nyLo * plotHeight),
                        marginLeft + (int)(nxHi * plotWidth), marginTop + (int)(nyHi * plotHeight)
                    );
                }
            }

            // The selection rectangle rides on top of the blit, so dragging it
            // never re-renders a contour vertex.
            if (dragAnchor != null && dragCurrent != null && !panning) {
                int rx = Math.min(dragAnchor.x, dragCurrent.x);
                int ry = Math.min(dragAnchor.y, dragCurrent.y);
                int rw = Math.abs(dragCurrent.x - dragAnchor.x);
                int rh = Math.abs(dragCurrent.y - dragAnchor.y);
                g2.setColor(SELECT_FILL);
                g2.fillRect(rx, ry, rw, rh);
                g2.setColor(SELECT_EDGE);
                g2.setStroke(SELECT_STROKE);
                g2.drawRect(rx, ry, rw, rh);
                g2.setStroke(new BasicStroke(1.0f));
            }

            // Render hover tooltip showing 3 parameters & elements
            if (hoveredPoint != null && hoveredScreenPos != null) {
                g2.setColor(COLOR_PLOT_HOVER); // red highlight
                g2.fillOval(hoveredScreenPos.x - 4, hoveredScreenPos.y - 4, 8, 8);
                g2.setColor(Color.BLACK);
                g2.drawOval(hoveredScreenPos.x - 4, hoveredScreenPos.y - 4, 8, 8);
                
                String text1 = "Residue: " + hoveredPoint.seqId + " " + hoveredPoint.residue;
                String text2 = NmrVisualizer.getAtomDisplayName(xLabel, hoveredPoint.residue) + ": " + String.format("%.3f ppm", hoveredPoint.x);
                String text3 = NmrVisualizer.getAtomDisplayName(yLabel, hoveredPoint.residue) + ": " + String.format("%.3f ppm", hoveredPoint.y);
                
                // Read the Z shift parameter if it's available
                String text4 = "";
                if (hoveredPoint.z != null) {
                    text4 = NmrVisualizer.getAtomDisplayName(zLabel, hoveredPoint.residue) + ": " + String.format("%.3f ppm", hoveredPoint.z);
                }
                
                int tx = hoveredScreenPos.x + 10;
                int ty = hoveredScreenPos.y - 65; 
                int boxHeight = 50;
                if (!text4.isEmpty()) {
                    boxHeight = 65;
                    ty = hoveredScreenPos.y - 80;
                }
                
                // Classic Windows yellow tooltip
                g2.setColor(COLOR_TOOLTIP_BG);
                g2.fillRect(tx, ty, 220, boxHeight); 
                g2.setColor(new Color(100, 100, 100)); // gray tooltip border
                g2.drawRect(tx, ty, 220, boxHeight);
                
                g2.setColor(COLOR_CHARCOAL);
                g2.setFont(FONT_SANS);
                g2.drawString(text1, tx + 8, ty + 15);
                g2.drawString(text2, tx + 8, ty + 30);
                g2.drawString(text3, tx + 8, ty + 45);
                if (!text4.isEmpty()) {
                    g2.drawString(text4, tx + 8, ty + 60);
                }
            }
        }
    }

    // Contour Line Model
    //
    // Each segment is a flat [x0,y0,x1,y1,...] array rather than a
    // List<Point2D.Double>. A spectrum carries ~600k vertices; as boxed points
    // that is ~20 MB of objects for the collector to chase, against ~10 MB of
    // primitives here, and painting reads them without a pointer hop per vertex.
    public static class ContourLine {
        public final double level;
        public final boolean isPositive;
        public final double[][] segments;
        public final int k;
        // Per-segment data-space bounds, so painting can reject off-view segments
        // without walking their points.
        public final double[] segMinX, segMaxX, segMinY, segMaxY;

        public ContourLine(double level, boolean isPositive, double[][] segments) {
            this(level, isPositive, segments, 0);
        }

        public ContourLine(double level, boolean isPositive, double[][] segments, int k) {
            this.level = level;
            this.isPositive = isPositive;
            this.segments = segments;
            this.k = k;

            int n = segments.length;
            segMinX = new double[n]; segMaxX = new double[n];
            segMinY = new double[n]; segMaxY = new double[n];
            for (int s = 0; s < n; s++) {
                double[] seg = segments[s];
                double mnx = Double.MAX_VALUE, mxx = -Double.MAX_VALUE;
                double mny = Double.MAX_VALUE, mxy = -Double.MAX_VALUE;
                for (int i = 0; i < seg.length; i += 2) {
                    double px = seg[i], py = seg[i + 1];
                    if (px < mnx) mnx = px;
                    if (px > mxx) mxx = px;
                    if (py < mny) mny = py;
                    if (py > mxy) mxy = py;
                }
                segMinX[s] = mnx; segMaxX[s] = mxx;
                segMinY[s] = mny; segMaxY[s] = mxy;
            }
        }
    }
    
    // Data Point Model
    public static class DataPoint {
        public String seqId;
        public String residue;
        public double x;
        public double y;
        public Double z;
        
        public DataPoint(String seqId, String residue, double x, double y, Double z) {
            this.seqId = seqId;
            this.residue = residue;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }
    
    // Custom JSON Parser
    public static class JsonParser {
        public static Map<String, Object> parseObject(String json) {
            Map<String, Object> map = new HashMap<>();
            json = json.trim();
            if (json.startsWith("{") && json.endsWith("}")) {
                json = json.substring(1, json.length() - 1).trim();
            }
            
            int i = 0;
            while (i < json.length()) {
                while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
                if (i >= json.length()) break;
                if (json.charAt(i) != '"') break;
                
                int keyStart = i + 1;
                i++;
                while (i < json.length() && json.charAt(i) != '"') i++;
                String key = json.substring(keyStart, i);
                i++; // skip quote
                
                while (i < json.length() && json.charAt(i) != ':') i++;
                i++; // skip colon
                while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
                
                if (i >= json.length()) break;
                Object value = null;
                
                if (json.charAt(i) == '"') {
                    int valStart = i + 1;
                    i++;
                    while (i < json.length() && json.charAt(i) != '"') i++;
                    value = json.substring(valStart, i);
                    i++;
                } else if (json.charAt(i) == '[') {
                    int bracketCount = 1;
                    int startList = i;
                    i++;
                    while (i < json.length() && bracketCount > 0) {
                        if (json.charAt(i) == '[') bracketCount++;
                        else if (json.charAt(i) == ']') bracketCount--;
                        i++;
                    }
                    value = json.substring(startList, i);
                } else if (json.charAt(i) == '{') {
                    int braceCount = 1;
                    int startObj = i;
                    i++;
                    while (i < json.length() && braceCount > 0) {
                        if (json.charAt(i) == '{') braceCount++;
                        else if (json.charAt(i) == '}') braceCount--;
                        i++;
                    }
                    value = json.substring(startObj, i);
                } else {
                    int startVal = i;
                    while (i < json.length() && json.charAt(i) != ',' && json.charAt(i) != '}') {
                        i++;
                    }
                    String valStr = json.substring(startVal, i).trim();
                    if (valStr.equals("null")) value = null;
                    else if (valStr.equals("true")) value = true;
                    else if (valStr.equals("false")) value = false;
                    else {
                        try {
                            value = Double.parseDouble(valStr);
                        } catch (NumberFormatException ex) {
                            value = valStr;
                        }
                    }
                }
                
                map.put(key, value);
                while (i < json.length() && json.charAt(i) != ',') i++;
                i++;
            }
            return map;
        }
        
        public static List<Map<String, Object>> parseList(String listStr) {
            List<Map<String, Object>> list = new ArrayList<>();
            listStr = listStr.trim();
            if (listStr.startsWith("[") && listStr.endsWith("]")) {
                listStr = listStr.substring(1, listStr.length() - 1).trim();
            }
            
            int i = 0;
            while (i < listStr.length()) {
                while (i < listStr.length() && Character.isWhitespace(listStr.charAt(i))) i++;
                if (i >= listStr.length()) break;
                
                if (listStr.charAt(i) == '{') {
                    int braceCount = 1;
                    int startObj = i;
                    i++;
                    while (i < listStr.length() && braceCount > 0) {
                        if (listStr.charAt(i) == '{') braceCount++;
                        else if (listStr.charAt(i) == '}') braceCount--;
                        i++;
                    }
                    String objStr = listStr.substring(startObj, i);
                    list.add(parseObject(objStr));
                } else {
                    i++;
                }
                while (i < listStr.length() && listStr.charAt(i) != ',') i++;
                i++;
            }
            return list;
        }
        
        public static List<String> parseStringList(String listStr) {
            List<String> list = new ArrayList<>();
            listStr = listStr.trim();
            if (listStr.startsWith("[") && listStr.endsWith("]")) {
                listStr = listStr.substring(1, listStr.length() - 1).trim();
            }
            
            int i = 0;
            while (i < listStr.length()) {
                while (i < listStr.length() && Character.isWhitespace(listStr.charAt(i))) i++;
                if (i >= listStr.length()) break;
                
                if (listStr.charAt(i) == '"') {
                    int startVal = i + 1;
                    i++;
                    while (i < listStr.length() && listStr.charAt(i) != '"') i++;
                    list.add(listStr.substring(startVal, i));
                    i++;
                } else {
                    i++;
                }
                while (i < listStr.length() && listStr.charAt(i) != ',') i++;
                i++;
            }
            return list;
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
            } catch (Exception ex) {}
            
            NmrVisualizer app = new NmrVisualizer();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> app.pythonWorker.stop()));
            app.setVisible(true);
        });
    }
}
