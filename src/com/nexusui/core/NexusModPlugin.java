package com.nexusui.core;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;
import com.nexusui.bridge.GameDataBridge;
import com.nexusui.overlay.NexusOverlay;
import com.nexusui.server.NexusHttpServer;

import org.apache.log4j.Logger;

/**
 * NexusUI Mod Plugin - Initializes the HTTP server, data bridge, and overlay.
 */
public class NexusModPlugin extends BaseModPlugin {

    private static final Logger log = Logger.getLogger(NexusModPlugin.class);

    public static final String MOD_ID = "nexus_ui";
    public static final String VERSION = "0.9.0-beta";
    public static final int DEFAULT_PORT = 5959;

    private static NexusHttpServer server;
    private static GameDataBridge bridge;

    @Override
    public void onApplicationLoad() throws Exception {
        log.info("NexusUI v" + VERSION + " - UI Framework loaded");
    }

    @Override
    public void onGameLoad(boolean newGame) {
        int port = DEFAULT_PORT;

        // Start HTTP server (or reuse if already running)
        if (server == null || !server.isRunning()) {
            server = new NexusHttpServer(port);
            server.start();
        }

        // Create and register data bridge
        bridge = new GameDataBridge();
        bridge.registerApiHandlers(server);
        Global.getSector().addTransientScript(bridge);

        // Register overlay button
        NexusOverlay overlay = new NexusOverlay(port);
        Global.getSector().getListenerManager().addListener(overlay, true);

        log.info("NexusUI: Server on port " + port);
    }

    public static NexusHttpServer getServer() { return server; }
    public static GameDataBridge getBridge() { return bridge; }
}
