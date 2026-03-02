package com.nexusui.overlay;

import com.nexusui.api.NexusPage;
import com.nexusui.api.NexusPageFactory;
import org.apache.log4j.Logger;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * NexusFrame - Generic overlay window for the NexusUI framework.
 *
 * Other mods register pages via registerPageFactory(NexusPageFactory) or
 * the legacy registerPage(NexusPage).
 * The frame displays a tab bar when multiple pages are registered.
 * Data is auto-refreshed every 5 seconds.
 *
 * Each window gets its own page instances from the factories, so multiple
 * simultaneous windows each have independent state and refresh cycles.
 */
public class NexusFrame extends JFrame {

    private static final Logger log = Logger.getLogger(NexusFrame.class);

    // Theme colors (public so page implementations can reuse them)
    public static final Color BG_PRIMARY = new Color(10, 14, 23);
    public static final Color BG_SECONDARY = new Color(17, 24, 39);
    public static final Color BG_CARD = new Color(21, 29, 46);
    public static final Color BORDER = new Color(60, 90, 140, 77);
    public static final Color BORDER_BRIGHT = new Color(80, 180, 255, 102);
    public static final Color CYAN = new Color(100, 220, 255);
    public static final Color GREEN = new Color(100, 255, 100);
    public static final Color ORANGE = new Color(255, 180, 50);
    public static final Color RED = new Color(255, 80, 80);
    public static final Color YELLOW = new Color(255, 255, 100);
    public static final Color PURPLE = new Color(180, 100, 255);
    public static final Color TEXT_PRIMARY = new Color(220, 225, 232);
    public static final Color TEXT_SECONDARY = new Color(138, 149, 168);
    public static final Color TEXT_MUTED = new Color(80, 88, 104);

    public static final Font FONT_TITLE = new Font("Consolas", Font.BOLD, 14);
    public static final Font FONT_HEADER = new Font("Consolas", Font.BOLD, 12);
    public static final Font FONT_BODY = new Font("Segoe UI", Font.PLAIN, 12);
    public static final Font FONT_MONO = new Font("Consolas", Font.PLAIN, 11);
    public static final Font FONT_SMALL = new Font("Consolas", Font.PLAIN, 10);

    // Last created instance (for backward compat with requestTemporaryFocus etc.)
    private static NexusFrame instance;

    // Registered page factories (thread-safe for concurrent read from Swing EDT + write from game thread)
    private static final List<NexusPageFactory> pageFactories = new CopyOnWriteArrayList<NexusPageFactory>();

    // All currently open frames
    private static final List<NexusFrame> openFrames = new CopyOnWriteArrayList<NexusFrame>();

    // Per-frame page instances (each frame gets its own set from the factories)
    private final List<NexusPage> framePages = new ArrayList<NexusPage>();

    private int port;
    private Thread refreshThread;
    private Point dragOffset;

    // Tab state
    private int selectedTabIndex = 0;
    private final Map<String, JComponent> panelCache = new LinkedHashMap<String, JComponent>();
    private JPanel contentArea;
    private TabBar tabBar;
    private CardLayout contentLayout;
    private JPanel footerPanel;

    // Resize support
    private static final int RESIZE_BORDER = 6;
    private static final int MIN_WIDTH = 600;
    private static final int MIN_HEIGHT = 400;
    private static final int DIR_N = 1;
    private static final int DIR_S = 2;
    private static final int DIR_W = 4;
    private static final int DIR_E = 8;
    private int resizeDir = 0;
    private Point resizeStart;
    private Rectangle startBounds;

    /**
     * Register a page factory with NexusUI. Call from your mod's onGameLoad().
     * Each window will create its own page instance via the factory,
     * enabling independent multi-window support.
     */
    public static void registerPageFactory(NexusPageFactory factory) {
        if (factory == null || factory.getId() == null) {
            log.warn("NexusUI: Attempted to register null factory or factory with null ID");
            return;
        }

        // Remove existing factory with same ID
        for (NexusPageFactory f : pageFactories) {
            if (f.getId().equals(factory.getId())) {
                pageFactories.remove(f);
                break;
            }
        }
        pageFactories.add(factory);
        log.info("NexusUI: Registered page factory '" + factory.getTitle() + "' (id=" + factory.getId() + ")");

        // Add page to all open frames
        for (NexusFrame frame : openFrames) {
            if (frame.isDisplayable()) {
                final NexusFrame f = frame;
                final NexusPageFactory fac = factory;
                // Remove old page with same ID if present
                Iterator<NexusPage> it = f.framePages.iterator();
                while (it.hasNext()) {
                    if (it.next().getId().equals(fac.getId())) {
                        it.remove();
                        break;
                    }
                }
                f.framePages.add(fac.create());
                SwingUtilities.invokeLater(new Runnable() {
                    public void run() {
                        // Remove cached panel for this ID so rebuildTabs creates a new one
                        JComponent old = f.panelCache.remove(fac.getId());
                        if (old != null) {
                            f.contentArea.remove(old);
                        }
                        f.rebuildTabs();
                    }
                });
            }
        }
    }

    /**
     * Register a page with NexusUI (legacy API).
     * Wraps the page in a singleton factory for backward compatibility.
     * NOTE: For multi-window support, use registerPageFactory() instead.
     */
    public static void registerPage(final NexusPage page) {
        if (page == null || page.getId() == null) {
            log.warn("NexusUI: Attempted to register null page or page with null ID");
            return;
        }

        registerPageFactory(new NexusPageFactory() {
            public String getId() { return page.getId(); }
            public String getTitle() { return page.getTitle(); }
            public NexusPage create() { return page; }
        });
    }

    /** Remove a page by ID from all frames. */
    public static void unregisterPage(String pageId) {
        if (pageId == null) return;
        for (NexusPageFactory f : pageFactories) {
            if (f.getId().equals(pageId)) {
                pageFactories.remove(f);
                log.info("NexusUI: Unregistered page id=" + pageId);
                break;
            }
        }
        // Remove from all open frames
        for (NexusFrame frame : openFrames) {
            Iterator<NexusPage> it = frame.framePages.iterator();
            while (it.hasNext()) {
                if (it.next().getId().equals(pageId)) {
                    it.remove();
                    break;
                }
            }
        }
    }

    /** Get all registered pages (returns pages from the most recent frame, or empty). */
    public static List<NexusPage> getPages() {
        if (instance != null) {
            return new ArrayList<NexusPage>(instance.framePages);
        }
        return new ArrayList<NexusPage>();
    }

    /** Temporarily enable focus for text input (e.g. search fields). */
    public static void requestTemporaryFocus() {
        if (instance != null) {
            instance.setFocusableWindowState(true);
            instance.requestFocus();
        }
    }

    /** Release temporary focus back to non-focusable state. */
    public static void releaseTemporaryFocus() {
        if (instance != null) {
            instance.setFocusableWindowState(false);
        }
    }

    public static void toggle(int port) {
        if (pageFactories.isEmpty()) {
            log.warn("NexusUI: No pages registered. Nothing to display.");
            return;
        }

        // Always create a new instance so the user can open multiple windows
        NexusFrame frame = new NexusFrame(port);

        // Create fresh page instances from factories for this frame
        for (NexusPageFactory factory : pageFactories) {
            frame.framePages.add(factory.create());
        }

        // Offset each new window slightly so they don't stack exactly
        if (!openFrames.isEmpty()) {
            NexusFrame last = openFrames.get(openFrames.size() - 1);
            if (last.isDisplayable() && last.isVisible()) {
                Point loc = last.getLocation();
                frame.setLocation(loc.x + 30, loc.y + 30);
            }
        }

        openFrames.add(frame);
        instance = frame;
        frame.rebuildTabs();
        frame.setVisible(true);
        frame.startRefresh();
    }

    private NexusFrame(int port) {
        this.port = port;

        setUndecorated(true);
        setAlwaysOnTop(true);
        setFocusableWindowState(false);
        setSize(960, 700);
        setLocationRelativeTo(null);
        setBackground(new Color(0, 0, 0, 0));

        // Style tooltips to match NexusUI theme
        UIManager.put("ToolTip.background", BG_CARD);
        UIManager.put("ToolTip.foreground", TEXT_PRIMARY);
        UIManager.put("ToolTip.border", BorderFactory.createLineBorder(BORDER_BRIGHT, 1));
        UIManager.put("ToolTip.font", FONT_SMALL);

        setLayout(new BorderLayout(0, 0));

        // Title bar (draggable, with close button)
        TitleBar titleBar = new TitleBar();
        add(titleBar, BorderLayout.NORTH);

        // Center: tab bar + content
        JPanel center = new JPanel(new BorderLayout(0, 0));
        center.setBackground(BG_PRIMARY);

        tabBar = new TabBar();
        center.add(tabBar, BorderLayout.NORTH);

        contentLayout = new CardLayout();
        contentArea = new JPanel(contentLayout);
        contentArea.setBackground(BG_PRIMARY);

        JScrollPane scroll = new JScrollPane(contentArea);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(BG_PRIMARY);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        // The CardLayout panels handle their own scrolling if needed
        // Actually, let's not wrap in scroll - let each page handle its own scrolling
        center.add(contentArea, BorderLayout.CENTER);
        add(center, BorderLayout.CENTER);

        // Footer
        footerPanel = new JPanel() {
            protected void paintComponent(Graphics g) {
                g.setColor(BG_SECONDARY);
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setColor(BORDER);
                g.drawLine(0, 0, getWidth(), 0);
                g.setFont(FONT_SMALL);
                g.setColor(TEXT_MUTED);
                g.drawString("NexusUI Framework | " + framePages.size() + " page(s) loaded", 12, 16);
                g.drawString("Auto-refresh: 5s", getWidth() - 130, 16);
            }
        };
        footerPanel.setPreferredSize(new Dimension(0, 24));
        add(footerPanel, BorderLayout.SOUTH);

        // Outer border
        getRootPane().setBorder(BorderFactory.createLineBorder(BORDER_BRIGHT, 1));

        // Auto-toggle focusable when any text component gets/loses focus
        installTextFieldFocusSupport();

        // Enable resize via edge/corner dragging
        installResizeSupport();
    }

    // ========================================================================
    // Auto-focus support for text fields
    // ========================================================================

    private void installTextFieldFocusSupport() {
        // Intercept mouse clicks: when user clicks a text component inside this frame,
        // temporarily make the window focusable so the field can receive keyboard input.
        Toolkit.getDefaultToolkit().addAWTEventListener(new AWTEventListener() {
            public void eventDispatched(AWTEvent event) {
                if (event.getID() == MouseEvent.MOUSE_PRESSED) {
                    MouseEvent me = (MouseEvent) event;
                    Component src = me.getComponent();
                    if (src instanceof javax.swing.text.JTextComponent
                            && SwingUtilities.getWindowAncestor(src) == NexusFrame.this) {
                        // Click on a text field inside our frame → enable focus
                        setFocusableWindowState(true);
                        src.requestFocusInWindow();
                    } else if (SwingUtilities.getWindowAncestor(src) == NexusFrame.this) {
                        // Click on non-text component in our frame → release focus
                        setFocusableWindowState(false);
                    }
                }
            }
        }, AWTEvent.MOUSE_EVENT_MASK);

        // Also listen for focus changes to release focusable when text field loses focus
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addPropertyChangeListener(
                "focusOwner", new java.beans.PropertyChangeListener() {
            public void propertyChange(java.beans.PropertyChangeEvent evt) {
                Object oldFocus = evt.getOldValue();
                Object newFocus = evt.getNewValue();

                // A text component in our frame lost focus
                if (oldFocus instanceof javax.swing.text.JTextComponent
                        && SwingUtilities.getWindowAncestor((Component) oldFocus) == NexusFrame.this) {
                    // Only release if new focus is NOT another text component in this frame
                    if (!(newFocus instanceof javax.swing.text.JTextComponent)
                            || SwingUtilities.getWindowAncestor((Component) newFocus) != NexusFrame.this) {
                        setFocusableWindowState(false);
                    }
                }
            }
        });
    }

    // ========================================================================
    // Resize support (glass pane approach)
    // ========================================================================

    private void installResizeSupport() {
        setMinimumSize(new Dimension(MIN_WIDTH, MIN_HEIGHT));

        final JPanel glassPane = new JPanel() {
            public boolean contains(int x, int y) {
                int w = NexusFrame.this.getWidth();
                int h = NexusFrame.this.getHeight();
                return x < RESIZE_BORDER || x >= w - RESIZE_BORDER ||
                       y < RESIZE_BORDER || y >= h - RESIZE_BORDER;
            }
        };
        glassPane.setOpaque(false);
        setGlassPane(glassPane);
        glassPane.setVisible(true);

        glassPane.addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent e) {
                resizeDir = getResizeDirection(e.getX(), e.getY());
                if (resizeDir != 0) {
                    resizeStart = e.getLocationOnScreen();
                    startBounds = getBounds();
                }
            }
            public void mouseReleased(MouseEvent e) {
                resizeDir = 0;
                resizeStart = null;
                startBounds = null;
            }
        });

        glassPane.addMouseMotionListener(new MouseMotionAdapter() {
            public void mouseDragged(MouseEvent e) {
                if (resizeDir == 0 || resizeStart == null || startBounds == null) return;
                Point current = e.getLocationOnScreen();
                int dx = current.x - resizeStart.x;
                int dy = current.y - resizeStart.y;

                Rectangle nb = new Rectangle(startBounds);

                if ((resizeDir & DIR_E) != 0) {
                    nb.width = Math.max(MIN_WIDTH, startBounds.width + dx);
                }
                if ((resizeDir & DIR_W) != 0) {
                    int nw = Math.max(MIN_WIDTH, startBounds.width - dx);
                    nb.x = startBounds.x + startBounds.width - nw;
                    nb.width = nw;
                }
                if ((resizeDir & DIR_S) != 0) {
                    nb.height = Math.max(MIN_HEIGHT, startBounds.height + dy);
                }
                if ((resizeDir & DIR_N) != 0) {
                    int nh = Math.max(MIN_HEIGHT, startBounds.height - dy);
                    nb.y = startBounds.y + startBounds.height - nh;
                    nb.height = nh;
                }

                setBounds(nb);
                validate();
            }

            public void mouseMoved(MouseEvent e) {
                int dir = getResizeDirection(e.getX(), e.getY());
                updateResizeCursor(dir);
            }
        });
    }

    private int getResizeDirection(int x, int y) {
        int w = getWidth();
        int h = getHeight();
        int dir = 0;
        if (y < RESIZE_BORDER) dir |= DIR_N;
        if (y >= h - RESIZE_BORDER) dir |= DIR_S;
        if (x < RESIZE_BORDER) dir |= DIR_W;
        if (x >= w - RESIZE_BORDER) dir |= DIR_E;
        return dir;
    }

    private void updateResizeCursor(int dir) {
        int cursorType;
        switch (dir) {
            case 1:  cursorType = Cursor.N_RESIZE_CURSOR; break;
            case 2:  cursorType = Cursor.S_RESIZE_CURSOR; break;
            case 4:  cursorType = Cursor.W_RESIZE_CURSOR; break;
            case 8:  cursorType = Cursor.E_RESIZE_CURSOR; break;
            case 5:  cursorType = Cursor.NW_RESIZE_CURSOR; break;
            case 9:  cursorType = Cursor.NE_RESIZE_CURSOR; break;
            case 6:  cursorType = Cursor.SW_RESIZE_CURSOR; break;
            case 10: cursorType = Cursor.SE_RESIZE_CURSOR; break;
            default: cursorType = Cursor.DEFAULT_CURSOR;
        }
        getGlassPane().setCursor(Cursor.getPredefinedCursor(cursorType));
    }

    private void rebuildTabs() {
        // Add any new pages to the content area
        for (NexusPage page : framePages) {
            if (!panelCache.containsKey(page.getId())) {
                JPanel panel = page.createPanel(port);
                // Wrap in scroll pane
                JScrollPane scroll = new JScrollPane(panel);
                scroll.setBorder(null);
                scroll.getViewport().setBackground(BG_PRIMARY);
                scroll.getVerticalScrollBar().setUnitIncrement(16);
                scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
                panelCache.put(page.getId(), scroll);
                contentArea.add(scroll, page.getId());
            }
        }

        // Show selected page
        if (!framePages.isEmpty()) {
            if (selectedTabIndex >= framePages.size()) selectedTabIndex = 0;
            contentLayout.show(contentArea, framePages.get(selectedTabIndex).getId());
        }

        tabBar.repaint();
        footerPanel.repaint();
    }

    public void startRefresh() {
        // Signal the game thread to start building snapshots
        com.nexusui.bridge.GameDataBridge bridge = com.nexusui.bridge.GameDataBridge.getInstance();
        if (bridge != null) bridge.setOverlayVisible(true);

        refreshAllPages();
        if (refreshThread == null || !refreshThread.isAlive()) {
            refreshThread = new Thread(new Runnable() {
                public void run() {
                    while (!Thread.currentThread().isInterrupted()) {
                        try {
                            Thread.sleep(3000);
                        } catch (InterruptedException e) {
                            break;
                        }
                        refreshAllPages();
                    }
                }
            });
            refreshThread.setDaemon(true);
            refreshThread.setName("NexusUI-Refresh-" + System.identityHashCode(NexusFrame.this));
            refreshThread.start();
        }
    }

    public void stopRefresh() {
        if (refreshThread != null) {
            refreshThread.interrupt();
            refreshThread = null;
        }

        // Only signal game thread to stop if no other frames are open
        boolean anyVisible = false;
        for (NexusFrame frame : openFrames) {
            if (frame != this && frame.isDisplayable() && frame.isVisible()) {
                anyVisible = true;
                break;
            }
        }
        if (!anyVisible) {
            com.nexusui.bridge.GameDataBridge bridge = com.nexusui.bridge.GameDataBridge.getInstance();
            if (bridge != null) bridge.setOverlayVisible(false);
        }
    }

    private void refreshAllPages() {
        for (NexusPage page : framePages) {
            try {
                page.refresh();
            } catch (Exception e) {
                log.warn("NexusUI: Page '" + page.getId() + "' refresh failed: " + e.getMessage());
            }
        }
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                // Only repaint the visible page, not all of them
                if (selectedTabIndex >= 0 && selectedTabIndex < framePages.size()) {
                    String id = framePages.get(selectedTabIndex).getId();
                    JComponent cached = panelCache.get(id);
                    if (cached != null) cached.repaint();
                }
            }
        });
    }

    // ========================================================================
    // Title Bar
    // ========================================================================
    private class TitleBar extends JPanel {
        TitleBar() {
            setPreferredSize(new Dimension(0, 36));
            setBackground(BG_SECONDARY);

            addMouseListener(new MouseAdapter() {
                public void mousePressed(MouseEvent e) {
                    dragOffset = e.getPoint();
                }
                public void mouseClicked(MouseEvent e) {
                    if (e.getX() > getWidth() - 36) {
                        NexusFrame.this.setVisible(false);
                        stopRefresh();
                        openFrames.remove(NexusFrame.this);
                    }
                }
            });
            addMouseMotionListener(new MouseMotionAdapter() {
                public void mouseDragged(MouseEvent e) {
                    Point loc = NexusFrame.this.getLocation();
                    NexusFrame.this.setLocation(
                        loc.x + e.getX() - dragOffset.x,
                        loc.y + e.getY() - dragOffset.y
                    );
                }
            });
        }

        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            g2.setColor(BG_SECONDARY);
            g2.fillRect(0, 0, getWidth(), getHeight());
            g2.setColor(BORDER_BRIGHT);
            g2.drawLine(0, getHeight() - 1, getWidth(), getHeight() - 1);

            // Logo
            g2.setFont(FONT_TITLE);
            g2.setColor(TEXT_PRIMARY);
            g2.drawString("NEXUS", 14, 23);
            g2.setColor(CYAN);
            g2.drawString("UI", 14 + g2.getFontMetrics().stringWidth("NEXUS"), 23);

            // Close button
            int bx = getWidth() - 32, by = 8, bs = 20;
            g2.setColor(new Color(255, 80, 80, 150));
            g2.fillRoundRect(bx, by, bs, bs, 4, 4);
            g2.setColor(TEXT_PRIMARY);
            g2.setStroke(new BasicStroke(2f));
            g2.drawLine(bx + 5, by + 5, bx + bs - 5, by + bs - 5);
            g2.drawLine(bx + bs - 5, by + 5, bx + 5, by + bs - 5);

            g2.dispose();
        }
    }

    // ========================================================================
    // Tab Bar
    // ========================================================================
    /** Color palette for tab indicator dots - cycles per mod. */
    private static final Color[] TAB_COLORS = {CYAN, ORANGE, PURPLE, GREEN, YELLOW, RED};

    private class TabBar extends JPanel {
        private static final int TAB_HEIGHT = 36;
        private static final int TAB_PAD = 16;
        private static final int DOT_SIZE = 8;
        private static final int DOT_GAP = 8;
        private int hoveredTabIndex = -1;

        TabBar() {
            setPreferredSize(new Dimension(0, TAB_HEIGHT));
            setBackground(BG_PRIMARY);

            addMouseListener(new MouseAdapter() {
                public void mouseClicked(MouseEvent e) {
                    int idx = getTabAt(e.getX());
                    if (idx >= 0) {
                        selectedTabIndex = idx;
                        contentLayout.show(contentArea, framePages.get(idx).getId());
                        repaint();
                    }
                }
                public void mouseExited(MouseEvent e) {
                    if (hoveredTabIndex != -1) {
                        hoveredTabIndex = -1;
                        repaint();
                    }
                }
            });
            addMouseMotionListener(new MouseMotionAdapter() {
                public void mouseMoved(MouseEvent e) {
                    int idx = getTabAt(e.getX());
                    if (idx != hoveredTabIndex) {
                        hoveredTabIndex = idx;
                        repaint();
                    }
                }
            });
        }

        private int getTabAt(int mx) {
            Graphics gg = getGraphics();
            if (gg == null) return -1;
            FontMetrics fm = gg.getFontMetrics(FONT_HEADER);
            int x = 10;
            for (int i = 0; i < framePages.size(); i++) {
                int tw = DOT_SIZE + DOT_GAP + fm.stringWidth(framePages.get(i).getTitle()) + TAB_PAD * 2;
                if (mx >= x && mx <= x + tw) return i;
                x += tw + 2;
            }
            return -1;
        }

        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);

            // Background
            g2.setColor(BG_PRIMARY);
            g2.fillRect(0, 0, getWidth(), getHeight());

            g2.setFont(FONT_HEADER);
            FontMetrics fm = g2.getFontMetrics();
            int x = 10;

            // Compute all tab positions/widths first (for bottom border gap)
            int[] tabX = new int[framePages.size()];
            int[] tabW = new int[framePages.size()];
            for (int i = 0; i < framePages.size(); i++) {
                tabX[i] = x;
                tabW[i] = DOT_SIZE + DOT_GAP + fm.stringWidth(framePages.get(i).getTitle()) + TAB_PAD * 2;
                x += tabW[i] + 2;
            }

            // Draw bottom border, skipping under the selected tab
            g2.setColor(BORDER);
            int selLeft = selectedTabIndex >= 0 && selectedTabIndex < framePages.size() ? tabX[selectedTabIndex] : -1;
            int selRight = selLeft >= 0 ? selLeft + tabW[selectedTabIndex] : -1;
            if (selLeft > 0) {
                g2.drawLine(0, TAB_HEIGHT - 1, selLeft, TAB_HEIGHT - 1);
            }
            if (selRight >= 0 && selRight < getWidth()) {
                g2.drawLine(selRight, TAB_HEIGHT - 1, getWidth(), TAB_HEIGHT - 1);
            }
            if (selLeft < 0) {
                g2.drawLine(0, TAB_HEIGHT - 1, getWidth(), TAB_HEIGHT - 1);
            }

            // Draw tabs
            for (int i = 0; i < framePages.size(); i++) {
                boolean sel = (i == selectedTabIndex);
                boolean hov = (i == hoveredTabIndex) && !sel;
                Color tabColor = TAB_COLORS[i % TAB_COLORS.length];

                int tx = tabX[i];
                int tw = tabW[i];

                // Tab background (rounded top)
                if (sel) {
                    g2.setColor(BG_CARD);
                    g2.fillRoundRect(tx, 0, tw, TAB_HEIGHT + 4, 10, 10);
                    // Overwrite bottom corners to make them square (connect to content)
                    g2.fillRect(tx, TAB_HEIGHT - 6, tw, 6);
                    // Top accent line
                    g2.setColor(tabColor);
                    g2.fillRoundRect(tx, 0, tw, 3, 4, 4);
                } else if (hov) {
                    g2.setColor(new Color(18, 24, 38));
                    g2.fillRoundRect(tx, 4, tw, TAB_HEIGHT - 5, 8, 8);
                }

                // Colored dot
                int dotY = TAB_HEIGHT / 2 - DOT_SIZE / 2;
                g2.setColor(sel ? tabColor : (hov ? tabColor : new Color(tabColor.getRed(), tabColor.getGreen(), tabColor.getBlue(), 100)));
                g2.fillOval(tx + TAB_PAD, dotY, DOT_SIZE, DOT_SIZE);

                // Title text
                g2.setColor(sel ? tabColor : (hov ? TEXT_PRIMARY : TEXT_SECONDARY));
                g2.drawString(framePages.get(i).getTitle(), tx + TAB_PAD + DOT_SIZE + DOT_GAP,
                    TAB_HEIGHT / 2 + fm.getAscent() / 2 - 1);
            }

            g2.dispose();
        }
    }

    // ========================================================================
    // Utility methods for page implementations
    // ========================================================================

    /** Draw a rounded card background. Pages can call this from their paintComponent. */
    public static void drawCardBg(Graphics2D g2, int x, int y, int w, int h) {
        g2.setColor(BG_CARD);
        g2.fillRoundRect(x, y, w, h, 8, 8);
        g2.setColor(BORDER);
        g2.drawRoundRect(x, y, w, h, 8, 8);
    }

    /** Draw a card header with title and optional badge. */
    public static void drawCardHeader(Graphics2D g2, int x, int y, int w, String title, String badge) {
        g2.setColor(new Color(255, 255, 255, 3));
        g2.fillRect(x + 1, y + 1, w - 2, 31);
        g2.setColor(BORDER);
        g2.drawLine(x, y + 32, x + w, y + 32);

        g2.setFont(FONT_HEADER);
        g2.setColor(CYAN);
        g2.drawString(title, x + 12, y + 21);

        if (badge != null && !badge.isEmpty()) {
            g2.setFont(FONT_SMALL);
            g2.setColor(TEXT_SECONDARY);
            int bw = g2.getFontMetrics().stringWidth(badge);
            g2.drawString(badge, x + w - bw - 12, y + 21);
        }
    }

    /** Draw a labeled progress bar. */
    public static void drawLabeledBar(Graphics2D g2, int x, int y, int w, int h,
                                       String label, String value, float pct, Color color) {
        int labelW = 90;
        int valueW = 40;
        int barX = x + labelW + 8;
        int barW = w - labelW - valueW - 16;

        g2.setFont(FONT_SMALL);
        g2.setColor(TEXT_SECONDARY);
        int lw = g2.getFontMetrics().stringWidth(label);
        g2.drawString(label, x + labelW - lw, y + h - 4);

        // Track
        g2.setColor(new Color(255, 255, 255, 8));
        g2.fillRoundRect(barX, y + 2, barW, h - 4, 4, 4);

        // Fill
        int fillW = Math.max(2, (int) (barW * Math.min(pct, 1f)));
        g2.setColor(color);
        g2.fillRoundRect(barX, y + 2, fillW, h - 4, 4, 4);

        // Value
        g2.setColor(color);
        g2.drawString(value, x + w - valueW, y + h - 4);
    }

    /** Draw a relation bar centered at zero. */
    public static void drawRelationBar(Graphics2D g2, int x, int y, int w, int h,
                                        String name, int relation, Color color) {
        int labelW = 100;
        int valueW = 40;
        int barX = x + labelW + 8;
        int barW = w - labelW - valueW - 16;
        int centerX = barX + barW / 2;

        g2.setFont(FONT_SMALL);
        g2.setColor(TEXT_SECONDARY);
        String tName = truncate(name, 14);
        int lw = g2.getFontMetrics().stringWidth(tName);
        g2.drawString(tName, x + labelW - lw, y + h - 4);

        g2.setColor(new Color(255, 255, 255, 8));
        g2.fillRoundRect(barX, y + 2, barW, h - 4, 4, 4);

        g2.setColor(TEXT_MUTED);
        g2.drawLine(centerX, y + 2, centerX, y + h - 2);

        float pct = Math.abs(relation) / 100f;
        int fillW = (int) ((barW / 2) * Math.min(pct, 1f));
        g2.setColor(color);
        if (relation >= 0) {
            g2.fillRect(centerX, y + 3, fillW, h - 6);
        } else {
            g2.fillRect(centerX - fillW, y + 3, fillW, h - 6);
        }

        g2.setColor(color);
        String valStr = (relation >= 0 ? "+" : "") + relation;
        g2.drawString(valStr, x + w - valueW, y + h - 4);
    }

    /** Truncate a string with ellipsis. */
    public static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max - 1) + "\u2026" : s;
    }

    /** Format a number with K/M suffixes. */
    public static String formatNumber(long n) {
        if (Math.abs(n) >= 1000000) return String.format("%.1fM", n / 1000000.0);
        if (Math.abs(n) >= 1000) return String.format("%.1fK", n / 1000.0);
        return String.valueOf(n);
    }

    /** Convert "heavy_machinery" to "Heavy machinery". */
    public static String prettifyId(String id) {
        if (id == null || id.isEmpty()) return "";
        String s = id.replace('_', ' ');
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }

}
