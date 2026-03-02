package com.nexusui.bridge;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.*;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.nexusui.server.NexusHttpServer;

import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * GameDataBridge - Extracts game state and caches it as JSON for the HTTP server.
 *
 * Runs as an EveryFrameScript on the game thread, ensuring thread-safe access
 * to game APIs. The cached JSON strings are read by HTTP server threads.
 *
 * Modders can register additional data providers:
 * <pre>
 *   GameDataBridge.getInstance().registerProvider("mymod", new DataProvider() {
 *       public String getData() {
 *           return new JSONObject().put("key", "value").toString();
 *       }
 *   });
 * </pre>
 */
public class GameDataBridge implements EveryFrameScript {

    private static final Logger log = Logger.getLogger(GameDataBridge.class);

    public interface DataProvider {
        String getData();
    }

    /**
     * Command interface for thread-safe game state modification.
     * Commands are enqueued from any thread and executed on the game thread.
     *
     * Usage from Swing EDT:
     * <pre>
     *   String cmdId = GameDataBridge.getInstance().enqueueCommand(new GameCommand() {
     *       public String execute() {
     *           Global.getSector().getPlayerFleet().getCargo().getCredits().add(10000);
     *           return "{\"success\":true}";
     *       }
     *   });
     * </pre>
     */
    public interface GameCommand {
        /** Execute on the game thread. Return a JSON result string. */
        String execute();
    }

    private static GameDataBridge instance;

    // Cached snapshots as JSONObjects (volatile for cross-thread visibility).
    // Game thread builds a new JSONObject each cycle, publishes via volatile write.
    // In-process consumers (DashboardPage) read the reference directly — zero serialization.
    // HTTP handlers call toString() lazily only when a request comes in.
    private volatile JSONObject gameInfoObj = new JSONObject();
    private volatile JSONObject fleetObj = new JSONObject();
    private volatile JSONObject coloniesObj = new JSONObject();
    private volatile JSONObject cargoObj = new JSONObject();
    private volatile JSONObject factionsObj = new JSONObject();

    private final Map<String, DataProvider> customProviders = new HashMap<String, DataProvider>();

    // Command queue: producer = any thread (Swing EDT, HTTP), consumer = game thread
    private final ConcurrentLinkedQueue<GameCommand> commandQueue = new ConcurrentLinkedQueue<GameCommand>();
    private final ConcurrentHashMap<String, String> commandResults = new ConcurrentHashMap<String, String>();
    private final AtomicLong commandIdCounter = new AtomicLong(0);

    /** Max uncollected command results before cleanup (prevents unbounded growth). */
    private static final int MAX_PENDING_RESULTS = 100;

    private volatile boolean overlayVisible = false;
    private float updateTimer = 0f;
    private static final float UPDATE_INTERVAL = 5.0f; // Update every 5 seconds

    public static GameDataBridge getInstance() {
        return instance;
    }

    public GameDataBridge() {
        instance = this;
    }

    public void registerProvider(String key, DataProvider provider) {
        customProviders.put(key, provider);
    }

    public void setOverlayVisible(boolean visible) { this.overlayVisible = visible; }

    // Direct JSONObject access for in-process consumers — zero serialization overhead
    public JSONObject getGameInfoObj() { return gameInfoObj; }
    public JSONObject getFleetObj()    { return fleetObj; }
    public JSONObject getColoniesObj() { return coloniesObj; }
    public JSONObject getCargoObj()    { return cargoObj; }
    public JSONObject getFactionsObj() { return factionsObj; }

    // String access for HTTP server — serializes on demand (only when a request arrives)
    public String getGameInfoJson() { return gameInfoObj.toString(); }
    public String getFleetJson()    { return fleetObj.toString(); }
    public String getColoniesJson() { return coloniesObj.toString(); }
    public String getCargoJson()    { return cargoObj.toString(); }
    public String getFactionsJson() { return factionsObj.toString(); }

    /**
     * Enqueue a command for execution on the game thread.
     * Returns a command ID to poll for the result.
     * Thread-safe: can be called from Swing EDT, HTTP threads, etc.
     */
    public String enqueueCommand(final GameCommand command) {
        final String id = "cmd_" + commandIdCounter.incrementAndGet();
        commandQueue.add(new GameCommand() {
            public String execute() {
                try {
                    String result = command.execute();
                    commandResults.put(id, result != null ? result : "{\"success\":true}");
                } catch (Exception e) {
                    String msg = e.getMessage() != null ? e.getMessage().replace("\"", "'") : "Unknown error";
                    commandResults.put(id, "{\"success\":false,\"error\":\"" + msg + "\"}");
                }
                return null;
            }
        });
        return id;
    }

    /**
     * Poll for a command result. Returns null if not yet complete.
     * Result is removed after retrieval.
     */
    public String pollCommandResult(String commandId) {
        return commandResults.remove(commandId);
    }

    /** Register all API handlers with the HTTP server. */
    public void registerApiHandlers(NexusHttpServer server) {
        server.registerHandler("/api/v1/game", new NexusHttpServer.ApiHandler() {
            public String handle(String method, String path, Map<String, String> headers) {
                return getGameInfoJson();
            }
        });
        server.registerHandler("/api/v1/fleet", new NexusHttpServer.ApiHandler() {
            public String handle(String method, String path, Map<String, String> headers) {
                return getFleetJson();
            }
        });
        server.registerHandler("/api/v1/colonies", new NexusHttpServer.ApiHandler() {
            public String handle(String method, String path, Map<String, String> headers) {
                return getColoniesJson();
            }
        });
        server.registerHandler("/api/v1/cargo", new NexusHttpServer.ApiHandler() {
            public String handle(String method, String path, Map<String, String> headers) {
                return getCargoJson();
            }
        });
        server.registerHandler("/api/v1/factions", new NexusHttpServer.ApiHandler() {
            public String handle(String method, String path, Map<String, String> headers) {
                return getFactionsJson();
            }
        });
        server.registerHandler("/api/v1/custom/", new NexusHttpServer.ApiHandler() {
            public String handle(String method, String path, Map<String, String> headers) {
                String key = path.replace("/api/v1/custom/", "");
                DataProvider provider = customProviders.get(key);
                if (provider != null) {
                    return provider.getData();
                }
                return null;
            }
        });
    }

    // ========================================================================
    // EveryFrameScript implementation
    // ========================================================================

    public boolean isDone() { return false; }
    public boolean runWhilePaused() { return true; }

    public void advance(float amount) {
        // Process command queue every frame (instant feedback)
        GameCommand cmd;
        while ((cmd = commandQueue.poll()) != null) {
            try {
                cmd.execute();
            } catch (Exception e) {
                log.warn("NexusUI: Command execution failed: " + e.getMessage());
            }
        }

        // Evict stale command results to prevent unbounded growth
        if (commandResults.size() > MAX_PENDING_RESULTS) {
            commandResults.clear();
            log.warn("NexusUI: Cleared " + MAX_PENDING_RESULTS + "+ uncollected command results");
        }

        // Snapshot game data (throttled, only when overlay is visible)
        updateTimer += amount;
        if (updateTimer < UPDATE_INTERVAL || !overlayVisible) return;
        updateTimer = 0f;

        try {
            updateAllSnapshots();
        } catch (Exception e) {
            log.warn("NexusUI: Data snapshot failed: " + e.getMessage());
        }
    }

    // ========================================================================
    // Data extraction (runs on game thread)
    // ========================================================================

    private void updateAllSnapshots() {
        SectorAPI sector = Global.getSector();
        if (sector == null) return;

        CampaignFleetAPI playerFleet = sector.getPlayerFleet();
        if (playerFleet == null) return;

        // Build JSONObjects directly — no toString() on the game thread.
        // Published via volatile write; consumers read the reference with zero parsing.
        gameInfoObj = buildGameInfo(sector, playerFleet);
        fleetObj = buildFleetData(playerFleet);
        coloniesObj = buildColoniesData(sector);
        cargoObj = buildCargoData(playerFleet);
        factionsObj = buildFactionsData(sector);
    }

    private JSONObject buildGameInfo(SectorAPI sector, CampaignFleetAPI fleet) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("playerName", Global.getSector().getPlayerPerson().getNameString());
            obj.put("faction", sector.getPlayerFaction().getDisplayName());
            obj.put("factionId", sector.getPlayerFaction().getId());
            obj.put("credits", (long) fleet.getCargo().getCredits().get());
            obj.put("totalShips", fleet.getFleetData().getNumMembers());

            CampaignClockAPI clock = sector.getClock();
            obj.put("day", clock.getDay());
            obj.put("month", clock.getMonth());
            obj.put("cycle", clock.getCycle());
            obj.put("dateString", clock.getDateString());
            obj.put("timestamp", System.currentTimeMillis());

            return obj;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private JSONObject buildFleetData(CampaignFleetAPI fleet) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("totalShips", fleet.getFleetData().getNumMembers());

            int capitals = 0, cruisers = 0, destroyers = 0, frigates = 0, fighters = 0;
            float totalStrength = 0;

            JSONArray members = new JSONArray();
            for (FleetMemberAPI member : fleet.getFleetData().getMembersListCopy()) {
                JSONObject m = new JSONObject();
                m.put("shipName", member.getShipName() != null ? member.getShipName() : "");
                m.put("hullName", member.getHullSpec().getHullName());
                m.put("hullId", member.getHullSpec().getHullId());

                String sizeStr = "FRIGATE";
                if (member.isFighterWing()) {
                    fighters++;
                    sizeStr = "FIGHTER";
                } else {
                    ShipAPI.HullSize size = member.getHullSpec().getHullSize();
                    switch (size) {
                        case CAPITAL_SHIP: capitals++; sizeStr = "CAPITAL"; break;
                        case CRUISER: cruisers++; sizeStr = "CRUISER"; break;
                        case DESTROYER: destroyers++; sizeStr = "DESTROYER"; break;
                        case FRIGATE: frigates++; sizeStr = "FRIGATE"; break;
                        default: break;
                    }
                }
                m.put("hullSize", sizeStr);

                float cr = member.getRepairTracker().getCR();
                float maxCr = member.getRepairTracker().getMaxCR();
                m.put("cr", Math.round(cr * 100));
                m.put("maxCr", Math.round(maxCr * 100));
                m.put("strength", Math.round(member.getMemberStrength() * 10f) / 10f);
                m.put("cargo", (int) member.getCargoCapacity());
                m.put("fuel", (int) member.getFuelCapacity());
                m.put("crew", (int) member.getNeededCrew());
                m.put("isFlagship", member.isFlagship());
                m.put("isMothballed", member.getRepairTracker().isMothballed());

                totalStrength += member.getMemberStrength();
                members.put(m);
            }

            obj.put("capitals", capitals);
            obj.put("cruisers", cruisers);
            obj.put("destroyers", destroyers);
            obj.put("frigates", frigates);
            obj.put("fighters", fighters);
            obj.put("totalStrength", Math.round(totalStrength * 10f) / 10f);
            obj.put("members", members);

            return obj;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private JSONObject buildColoniesData(SectorAPI sector) {
        try {
            JSONObject obj = new JSONObject();
            JSONArray colonies = new JSONArray();
            long totalIncome = 0;

            FactionAPI playerFaction = sector.getPlayerFaction();
            for (MarketAPI market : sector.getEconomy().getMarketsCopy()) {
                if (market.getFaction() != playerFaction || market.isHidden()) continue;

                long income = (long) market.getNetIncome();
                totalIncome += income;

                JSONObject c = new JSONObject();
                c.put("name", market.getName());
                c.put("id", market.getId());
                c.put("size", market.getSize());
                c.put("netIncome", income);
                c.put("stability", Math.round(market.getStabilityValue() * 10f) / 10f);

                JSONArray industries = new JSONArray();
                for (Industry ind : market.getIndustries()) {
                    JSONObject indObj = new JSONObject();
                    indObj.put("id", ind.getId());
                    indObj.put("name", ind.getCurrentName());
                    industries.put(indObj);
                }
                c.put("industries", industries);
                colonies.put(c);
            }

            obj.put("colonies", colonies);
            obj.put("totalIncome", totalIncome);
            return obj;
        } catch (Exception e) {
            JSONObject fallback = new JSONObject();
            try { fallback.put("colonies", new JSONArray()); } catch (Exception ignored) {}
            return fallback;
        }
    }

    private JSONObject buildCargoData(CampaignFleetAPI fleet) {
        try {
            CargoAPI cargo = fleet.getCargo();
            JSONObject obj = new JSONObject();
            obj.put("credits", (long) cargo.getCredits().get());
            obj.put("spaceUsed", Math.round(cargo.getSpaceUsed()));
            obj.put("maxSpace", Math.round(cargo.getMaxCapacity()));
            obj.put("fuel", Math.round(cargo.getFuel()));
            obj.put("maxFuel", Math.round(cargo.getMaxFuel()));
            obj.put("supplies", Math.round(cargo.getSupplies()));
            obj.put("crew", cargo.getCrew());
            obj.put("marines", cargo.getMarines());

            JSONArray commodities = new JSONArray();
            String[] commodityIds = {"supplies", "fuel", "crew", "marines", "heavy_machinery",
                                      "metals", "rare_metals", "food", "organics", "volatiles",
                                      "drugs", "organs", "hand_weapons", "luxury_goods"};

            for (String id : commodityIds) {
                float qty = 0;
                if ("crew".equals(id)) qty = cargo.getCrew();
                else if ("marines".equals(id)) qty = cargo.getMarines();
                else qty = cargo.getCommodityQuantity(id);

                if (qty > 0) {
                    JSONObject item = new JSONObject();
                    item.put("id", id);
                    item.put("quantity", Math.round(qty));
                    commodities.put(item);
                }
            }
            obj.put("commodities", commodities);

            return obj;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private JSONObject buildFactionsData(SectorAPI sector) {
        try {
            JSONObject obj = new JSONObject();
            JSONArray factions = new JSONArray();

            FactionAPI playerFaction = sector.getPlayerFaction();
            for (FactionAPI faction : sector.getAllFactions()) {
                if (faction == playerFaction) continue;
                if (faction.isShowInIntelTab()) {
                    JSONObject f = new JSONObject();
                    f.put("id", faction.getId());
                    f.put("name", faction.getDisplayName());
                    float rel = faction.getRelationship(playerFaction.getId());
                    f.put("relation", Math.round(rel * 100));
                    f.put("repLevel", faction.getRelationshipLevel(playerFaction.getId()).name());
                    factions.put(f);
                }
            }

            obj.put("factions", factions);
            return obj;
        } catch (Exception e) {
            JSONObject fallback = new JSONObject();
            try { fallback.put("factions", new JSONArray()); } catch (Exception ignored) {}
            return fallback;
        }
    }
}
