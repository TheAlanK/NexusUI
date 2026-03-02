package com.nexusui.api;

import javax.swing.JPanel;

/**
 * NexusPage - Interface for mods to register visual pages in the NexusUI overlay.
 *
 * Implement this interface and register via:
 *   NexusFrame.registerPage(myPage);
 *
 * Example:
 * <pre>
 *   public class MyDashboardPage implements NexusPage {
 *       public String getId() { return "my_dashboard"; }
 *       public String getTitle() { return "My Dashboard"; }
 *       public JPanel createPanel(int port) {
 *           return new MyCustomPanel(port);
 *       }
 *       public void refresh() {
 *           // fetch new data, repaint, etc.
 *       }
 *   }
 * </pre>
 */
public interface NexusPage {

    /** Unique identifier for this page (e.g. "fleet_dashboard"). */
    String getId();

    /** Display title shown in the tab bar. */
    String getTitle();

    /**
     * Create the Swing panel that renders this page's content.
     * Called once when the page is first shown.
     * @param port The HTTP server port (for REST API access).
     */
    JPanel createPanel(int port);

    /**
     * Called periodically (every ~2 seconds) to refresh data.
     * Implementations should fetch new data and call repaint().
     */
    void refresh();
}
