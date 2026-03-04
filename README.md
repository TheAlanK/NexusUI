# NexusUI

An **external dashboard and data bridge** for [Starsector](https://fractalsoftworks.com/). NexusUI opens interactive panels **outside** the game window — perfect for multi-monitor setups where you want fleet stats, colony income, faction relations, or cheat tools on a second screen while playing.

It also exposes a localhost REST API, letting external tools (browsers, scripts, companion apps) read live game data and execute commands.

![Starsector 0.98a-RC7](https://img.shields.io/badge/Starsector-0.98a--RC7-blue)
![Version 0.9.2-beta](https://img.shields.io/badge/Version-0.9.2--beta-orange)
![License: MIT](https://img.shields.io/badge/License-MIT-green)

## Why NexusUI?

Starsector runs fullscreen on your primary monitor. If you have a second screen, it sits there doing nothing. NexusUI changes that:

- **Drag dashboard windows to your second monitor** — fleet overview, cargo, colonies, faction relations — all live-updating while you play
- **Open multiple windows** — fleet stats on one screen, cheats panel on another, profiler in a corner
- **Build companion web apps** — the REST API serves live game data as JSON on `localhost:5959`
- **Execute game commands from outside** — thread-safe command queue lets external tools add credits, spawn ships, or modify game state

The panels are **independent OS windows** (Swing), not in-game UI. They float freely across all your monitors, resize, and stay open while you play.

## Features

- **External Dashboard Windows** — Themed Swing windows with tabbed pages, draggable and resizable, that live outside the game window on any monitor
- **Multi-Window Support** — Each click opens a new independent window with its own page state; arrange them across monitors however you like
- **REST API Bridge** — Embedded HTTP server (`127.0.0.1:5959`) exposing fleet, cargo, colonies, and factions as JSON endpoints with CORS
- **Thread-safe Command Queue** — Safely execute game-state modifications from Swing panels or HTTP requests via `GameDataBridge`
- **Floating Campaign Button** — Draggable "N" button on the campaign map to quickly open a dashboard window
- **Page Registration API** — `NexusPage` / `NexusPageFactory` interfaces for mods to add their own dashboard panels
- **Themed Drawing Utilities** — Color palette, fonts, and helpers (`drawCardBg`, `drawCardHeader`, `drawLabeledBar`, `drawRelationBar`) for consistent panel styling
- **Tripad Extension Integration** — When [Tripad Extension](https://github.com/TheAlanK/TripadExtension) is installed, the campaign button is managed by Tripad's unified button bar

## Installation

1. Install [LazyLib](https://fractalsoftworks.com/forum/index.php?topic=5444.0) if you haven't already
2. Download the [latest release](https://github.com/TheAlanK/NexusUI/releases)
3. Extract to your `Starsector/mods/` directory
4. Enable **NexusUI** in the Starsector launcher

## Multi-Monitor Usage

1. Load a save in Starsector
2. Click the floating **N** button on the campaign map (or use a [Tripad Extension](https://github.com/TheAlanK/TripadExtension) button)
3. A dashboard window appears — **drag it to your second monitor**
4. Click the N button again to open more windows
5. Each window updates live every ~3 seconds while you play

## Architecture

```
com.nexusui
├── api/
│   ├── NexusPage           # Interface: dashboard panels
│   └── NexusPageFactory    # Interface: per-window page instances
├── bridge/
│   └── GameDataBridge      # Game thread data cache + command queue
├── core/
│   └── NexusModPlugin      # Mod entry point, server lifecycle
├── overlay/
│   ├── NexusFrame          # External Swing window with tabs + drawing utils
│   └── NexusOverlay        # Campaign map floating button (OpenGL)
└── server/
    └── NexusHttpServer     # Embedded localhost HTTP server
```

## For Modders

### Quick Start — Register a Dashboard Page

**1. Add NexusUI as a dependency** in your `mod_info.json`:

```json
{
  "dependencies": [
    {"id": "nexus_ui", "name": "NexusUI"}
  ]
}
```

**2. Implement `NexusPage`:**

```java
import com.nexusui.api.NexusPage;
import javax.swing.JPanel;

public class MyPage implements NexusPage {
    public String getId()    { return "my_mod_page"; }
    public String getTitle() { return "My Mod"; }

    public JPanel createPanel(int port) {
        JPanel panel = new JPanel();
        // Build your Swing UI here
        // 'port' is the HTTP server port (5959) for REST calls
        return panel;
    }

    public void refresh() {
        // Called every ~3 seconds to update data
    }
}
```

**3. Register in your ModPlugin:**

```java
import com.nexusui.overlay.NexusFrame;

@Override
public void onGameLoad(boolean newGame) {
    NexusFrame.registerPage(new MyPage());
}
```

### Multi-Window Support with `NexusPageFactory`

Each `toggle()` call creates a new independent window. Use `NexusPageFactory` so each window gets its own page state — essential for multi-monitor setups where users may open several windows:

```java
import com.nexusui.api.NexusPageFactory;
import com.nexusui.api.NexusPage;
import com.nexusui.overlay.NexusFrame;

NexusFrame.registerPageFactory(new NexusPageFactory() {
    public String getId()    { return "my_page"; }
    public String getTitle() { return "My Mod"; }
    public NexusPage create() { return new MyPage(); }
});
```

### Optional Integration (NexusUI Not Required)

If your mod should work **with or without** NexusUI installed, do NOT add it to `dependencies`. Use lazy class loading instead:

> **Starsector's security sandbox** blocks reflection (`Class.forName`, `getMethod`, `invoke` all throw `SecurityException`). Use the `isModEnabled()` guard below — Java loads classes lazily, so `MyNexusIntegration` won't resolve unless the `if` block executes.

**1. Create a helper class** that imports NexusUI types:

```java
import com.nexusui.api.NexusPage;
import com.nexusui.api.NexusPageFactory;
import com.nexusui.overlay.NexusFrame;

public class MyNexusIntegration {
    public static void register() {
        NexusFrame.registerPageFactory(new NexusPageFactory() {
            public String getId()    { return "my_page"; }
            public String getTitle() { return "My Mod"; }
            public NexusPage create() { return new MyPage(); }
        });
    }
}
```

**2. Guard the call** in your ModPlugin:

```java
if (Global.getSettings().getModManager().isModEnabled("nexus_ui")) {
    try {
        MyNexusIntegration.register();
    } catch (Throwable e) {
        log.warn("NexusUI integration failed: " + e.getMessage());
    }
}
```

### Thread-safe Game Commands

Execute game-state changes safely from Swing panels or HTTP handlers:

```java
import com.nexusui.bridge.GameDataBridge;

String cmdId = GameDataBridge.getInstance().enqueueCommand(new GameDataBridge.GameCommand() {
    public String execute() {
        // Runs on the game thread — safe to use all game APIs
        Global.getSector().getPlayerFleet().getCargo().getCredits().add(1000);
        return "{\"success\":true}";
    }
});

// Poll for result (non-blocking, returns null if not ready)
String result = GameDataBridge.getInstance().pollCommandResult(cmdId);
```

### Custom Data Providers

Expose mod-specific data via the REST API for external tools:

```java
GameDataBridge.getInstance().registerProvider("mymod", new GameDataBridge.DataProvider() {
    public String getData() {
        return "{\"status\":\"active\",\"count\":42}";
    }
});
// Accessible at: GET http://127.0.0.1:5959/api/v1/custom/mymod
```

### Drawing Utilities

`NexusFrame` provides static helpers for building themed Swing panels:

| Method | Description |
|--------|-------------|
| `drawCardBg(g2, x, y, w, h)` | Rounded card background with border (8px radius) |
| `drawCardHeader(g2, x, y, w, title, badge)` | Gradient header with title and optional badge |
| `drawLabeledBar(g2, x, y, w, h, label, value, pct, color)` | Progress bar (0.0–1.0 scale) |
| `drawRelationBar(g2, x, y, w, h, name, relation, color)` | Centered relation bar (-100 to +100) |
| `formatNumber(long)` | Format with K/M suffixes: `1234` → `"1.2K"` |
| `prettifyId(String)` | `"heavy_machinery"` → `"Heavy machinery"` |
| `truncate(String, int)` | Truncate with ellipsis |

### Theme Colors

| Constant | RGB | Usage |
|----------|-----|-------|
| `BG_PRIMARY` | `(10, 14, 23)` | Main background |
| `BG_SECONDARY` | `(17, 24, 39)` | Title bar, secondary panels |
| `BG_CARD` | `(21, 29, 46)` | Card backgrounds |
| `CYAN` | `(100, 220, 255)` | Primary accent |
| `GREEN` | `(100, 255, 100)` | Positive values |
| `ORANGE` | `(255, 180, 50)` | Warnings |
| `RED` | `(255, 80, 80)` | Negative values |
| `TEXT_PRIMARY` | `(220, 225, 232)` | Main text |
| `TEXT_SECONDARY` | `(138, 149, 168)` | Secondary text |

### Fonts

| Constant | Font | Usage |
|----------|------|-------|
| `FONT_TITLE` | Consolas Bold 14 | Page titles |
| `FONT_HEADER` | Consolas Bold 12 | Section headers |
| `FONT_BODY` | Segoe UI 12 | Body text |
| `FONT_MONO` | Consolas 11 | Monospace data |
| `FONT_SMALL` | Consolas 10 | Small labels |

## REST API

All endpoints return JSON. CORS is enabled. Server binds to `127.0.0.1:5959` (localhost only).

| Endpoint | Description |
|----------|-------------|
| `GET /api/v1/game` | Player name, faction, credits, date, total ships |
| `GET /api/v1/fleet` | Fleet composition, ship details, CR, strength |
| `GET /api/v1/cargo` | Credits, cargo space, fuel, supplies, crew, commodities |
| `GET /api/v1/colonies` | Player colonies, industries, income, stability |
| `GET /api/v1/factions` | All factions with relation values and rep levels |
| `GET /api/v1/custom/{key}` | Custom data from registered `DataProvider`s |

Data is cached on the game thread and updated every 5 seconds (only when the overlay is visible).

### Example: Fetch Fleet Data from a Browser

```javascript
fetch('http://127.0.0.1:5959/api/v1/fleet')
  .then(r => r.json())
  .then(fleet => {
    console.log(fleet.totalShips + ' ships, ' + fleet.capitals + ' capitals');
    fleet.members.forEach(ship => {
      console.log(ship.shipName + ' — CR: ' + ship.cr + '%');
    });
  });
```

### Example: Build a Companion Web Dashboard

Since the API serves JSON with CORS enabled, you can build a standalone web page that reads live game data:

```html
<script>
  setInterval(async () => {
    const game = await (await fetch('http://127.0.0.1:5959/api/v1/game')).json();
    document.getElementById('credits').textContent = game.credits.toLocaleString();
    document.getElementById('date').textContent = game.dateString;
  }, 5000);
</script>
```

## Constants

| Constant | Value | Location |
|----------|-------|----------|
| `NexusModPlugin.MOD_ID` | `"nexus_ui"` | Mod identifier |
| `NexusModPlugin.DEFAULT_PORT` | `5959` | HTTP server port |
| `NexusModPlugin.VERSION` | `"0.9.0-beta"` | Current version |

## Mods Built on NexusUI

- [NexusDashboard](https://github.com/TheAlanK/NexusDashboard) — Fleet composition, combat readiness, faction relations, cargo, and colonies overview
- [NexusCheats](https://github.com/TheAlanK/NexusCheats) — Add credits, resources, weapons, ships, XP, and story points
- [NexusProfiler](https://github.com/TheAlanK/NexusProfiler) — Real-time FPS, memory, GC pause tracking, and performance diagnostics
- [NexusTactical](https://github.com/TheAlanK/NexusTactical) — Real-time combat fleet status visualization
- [TripadExtension](https://github.com/TheAlanK/TripadExtension) — Modular floating button framework for the campaign map

## Dependencies

- [LazyLib](https://fractalsoftworks.com/forum/index.php?topic=5444.0)

## Community

I won't be posting this on the Fractal Softworks forums myself for personal reasons, but if you'd like to share it there, you're absolutely free to do so.

If you have suggestions, bug reports, or feature requests, feel free to [open an issue](https://github.com/TheAlanK/NexusUI/issues).

## License

[MIT](LICENSE)
