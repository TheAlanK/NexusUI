# NexusUI

A **UI framework** for [Starsector](https://fractalsoftworks.com/) mods. NexusUI provides an in-game overlay system where mods can register interactive panels accessible via a floating button on the campaign screen.

![Starsector 0.98a-RC7](https://img.shields.io/badge/Starsector-0.98a--RC7-blue)
![Version 0.9.0-beta](https://img.shields.io/badge/Version-0.9.0--beta-orange)
![License: MIT](https://img.shields.io/badge/License-MIT-green)

## Features

- **Floating Overlay Button** — Draggable "N" button rendered above all campaign UI
- **Tabbed Panel System** — Browser-style tab bar with colored mod indicators, hover effects, and rounded tabs
- **Page Registration API** — Simple `NexusPage` interface for mods to add their own panels
- **Thread-safe Command Queue** — Safely modify game state from Swing EDT via `GameDataBridge`
- **REST API Bridge** — Embedded HTTP server (port 5959) exposing game data as JSON endpoints
- **Themed UI Utilities** — Pre-built color palette, fonts, and drawing helpers for consistent Swing panel styling

## Installation

1. Install [LazyLib](https://fractalsoftworks.com/forum/index.php?topic=5444.0) if you haven't already
2. Download the latest release or clone this repository
3. Copy the `NexusUI` folder into your `Starsector/mods/` directory
4. Enable **NexusUI** in the Starsector launcher

## For Modders — Creating a NexusUI Page

### 1. Add NexusUI as a dependency

In your `mod_info.json`:
```json
{
  "dependencies": [
    {"id": "lw_lazylib", "name": "LazyLib"},
    {"id": "nexus_ui", "name": "NexusUI"}
  ]
}
```

### 2. Implement `NexusPage`

```java
import com.nexusui.api.NexusPage;
import javax.swing.JPanel;

public class MyPage implements NexusPage {
    public String getId()    { return "my_mod_page"; }
    public String getTitle() { return "My Mod"; }

    public JPanel createPanel(int port) {
        JPanel panel = new JPanel();
        // Build your UI here
        // Use NexusFrame utility methods for consistent theming
        return panel;
    }

    public void refresh() {
        // Called periodically to update data (optional)
    }
}
```

### 3. Register in your ModPlugin

```java
import com.fs.starfarer.api.BaseModPlugin;
import com.nexusui.overlay.NexusFrame;

public class MyModPlugin extends BaseModPlugin {
    @Override
    public void onGameLoad(boolean newGame) {
        NexusFrame.registerPage(new MyPage());
    }
}
```

### 4. Execute Game Commands (Thread-safe)

```java
import com.nexusui.bridge.GameDataBridge;

// From Swing EDT, enqueue a command that runs on the game thread
String cmdId = GameDataBridge.getInstance().enqueueCommand(() -> {
    // This runs on the game thread — safe to call game APIs
    Global.getSector().getPlayerFleet().getCargo().getCredits().add(1000);
    return "{\"success\":true,\"message\":\"Added 1000 credits\"}";
});

// Poll for result (e.g., on a Swing Timer)
String result = GameDataBridge.getInstance().pollCommandResult(cmdId);
```

## REST API Endpoints

| Endpoint | Description |
|----------|-------------|
| `GET /api/v1/game` | Game metadata (date, player name, level) |
| `GET /api/v1/fleet` | Player fleet composition and CR |
| `GET /api/v1/cargo` | Cargo manifest |
| `GET /api/v1/colonies` | Player colony data |
| `GET /api/v1/factions` | Faction relations |
| `GET /api/v1/custom/{key}` | Custom data from registered providers |

## Utility Methods

`NexusFrame` provides static helpers for building themed panels:

- `drawCardBg(Graphics2D, x, y, w, h)` — Draw a styled card background
- `drawCardHeader(Graphics2D, title, x, y, w)` — Draw a card header with title
- `drawLabeledBar(Graphics2D, label, value, max, x, y, w, color)` — Draw a labeled progress bar
- `drawRelationBar(Graphics2D, label, value, x, y, w)` — Draw a relation bar (-100 to +100)
- `formatNumber(long)` — Format numbers with K/M suffixes
- `prettifyId(String)` — Convert snake_case IDs to Title Case
## Theme Colors

| Constant | Hex | Usage |
|----------|-----|-------|
| `BG_PRIMARY` | `#0D1117` | Main background |
| `BG_SECONDARY` | `#161B22` | Secondary panels |
| `BG_CARD` | `#1C2333` | Card backgrounds |
| `CYAN` | `#58A6FF` | Accent, links |
| `GREEN` | `#3FB950` | Positive values |
| `ORANGE` | `#D29922` | Warnings |
| `RED` | `#F85149` | Negative values |

## Mods Built on NexusUI

- [NexusDashboard](https://github.com/TheAlanK/NexusDashboard) — Fleet composition, combat readiness, faction relations, cargo, and colonies overview
- [NexusCheats](https://github.com/TheAlanK/NexusCheats) — Add credits, resources, weapons, ships, XP, and story points
- [NexusProfiler](https://github.com/TheAlanK/NexusProfiler) — Real-time FPS, memory, GC pause tracking, and performance diagnostics
- [NexusTactical](https://github.com/TheAlanK/NexusTactical) — Real-time combat fleet status visualization

## Dependencies

- [LazyLib](https://fractalsoftworks.com/forum/index.php?topic=5444.0)

## Community

I won't be posting this on the Fractal Softworks forums myself for personal reasons, but if you'd like to share it there, you're absolutely free to do so.

If you have suggestions, bug reports, or feature requests, feel free to [open an issue](https://github.com/TheAlanK/NexusUI/issues).

## License

[MIT](LICENSE)
