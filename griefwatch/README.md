# GriefWatch

A server-side Fabric mod for Minecraft Java Edition **26.2** that logs:

- **Online players** every N minutes (count, names, UUIDs).
- **Grief tools**: TNT placed or ignited, end crystals, TNT minecarts, plus anything else you add to the watch list (respawn anchors, beds in the Nether/End, flint and steel, lava buckets, wither skulls, ...).

Entries go to a dated file in `logs/griefwatch/` and, optionally, to Discord webhooks as embeds. Repeated actions are grouped: the first event of a burst is logged right away, and one summary line follows when the burst ends:

```
[GRIEF] Steve (069a79f4-...) placed TNT [minecraft:tnt] at 120, 64, -310 in overworld
[GRIEF-SUMMARY] Steve placed 47 TNT near 120, 64, -310 in overworld over 28s (069a79f4-..., minecraft:tnt)
```

Only the server needs the mod. Players join with a vanilla client.

## Requirements

- Minecraft Java Edition 26.2 dedicated server running Fabric Loader 0.19.5 or newer
- [Fabric API](https://modrinth.com/mod/fabric-api) for 26.2
- Java 25

## Build

```sh
cd griefwatch
./gradlew build          # Windows: gradlew.bat build
```

The jar is written to `build/libs/griefwatch-1.0.0.jar`. (The `-sources.jar` next to it isn't needed on the server.)

Versions are set in `gradle.properties`. If a newer Loader, Loom or Fabric API is out, take the current values from <https://fabricmc.net/develop>. Minecraft 26.x ships unobfuscated, so the build uses the `net.fabricmc.fabric-loom` plugin and plain `implementation` dependencies, with no mappings line.

## Install

1. Install the Fabric server launcher for 26.2 (<https://fabricmc.net/use/server/>).
2. Copy `griefwatch-1.0.0.jar` and the Fabric API jar into the server's `mods/` folder.
3. Start the server once. It creates `config/griefwatch.json` with defaults.
4. Put your webhook URL(s) in the config, then run `/griefwatch reload` (or restart).

## Commands

All commands need permission level 3 or higher (admins).

| Command | Effect |
|---|---|
| `/griefwatch reload` | Re-reads `config/griefwatch.json`. The file is read off the server thread. If the file is invalid, the previous settings stay active and the error is shown. |
| `/griefwatch status` | Shows the active settings, open dedup groups and Discord queue size. |

## Configuration (`config/griefwatch.json`)

```jsonc
{
  "playerLog": {
    "enabled": true,
    "intervalSeconds": 300,      // how often to log the online list (minimum 10)
    "logWhenEmpty": true         // false = skip the entry when nobody is online
  },
  "griefLog": {
    "enabled": true,
    "logToConsole": true,        // also echo grief entries into the main server log
    "watched": [
      { "enabled": true, "id": "minecraft:tnt", "trigger": "place", "action": "placed", "label": "TNT", "dimensions": [] },
      { "enabled": true, "id": "minecraft:tnt", "trigger": "interact", "action": "ignited", "label": "TNT", "dimensions": [] },
      { "enabled": false, "id": "#minecraft:beds", "trigger": "place", "action": "placed", "label": "Bed",
        "dimensions": ["minecraft:the_nether", "minecraft:the_end"] }
      // ...
    ]
  },
  "deduplication": {
    "enabled": true,
    "radius": 16.0,              // blocks from the group's centre
    "windowSeconds": 30,         // a group closes after this long with no new matching event
    "maxGroupSeconds": 600       // hard cap, so non-stop griefing still produces regular summaries
  },
  "discord": {
    "enabled": true,
    "webhookUrl": "",            // shared fallback
    "playerListWebhookUrl": "",  // optional: overrides webhookUrl for player lists
    "griefWebhookUrl": "",       // optional: overrides webhookUrl for grief alerts and summaries
    "sendPlayerList": true,
    "sendGriefAlerts": true,
    "sendSummaries": true,
    "username": "GriefWatch",
    "avatarUrl": "",
    "maxQueueSize": 200,         // oldest messages are dropped beyond this
    "playerListColor": 3447003,  // 0x3498DB blue
    "griefAlertColor": 15158332, // 0xE74C3C red
    "griefSummaryColor": 15105570 // 0xE67E22 orange
  },
  "fileLog": {
    "enabled": true,
    "directory": "logs/griefwatch" // relative to the server folder; one file per day
  }
}
```

(The real file is plain JSON. The comments above are only explanation.)

### Watch rules

| Field | Meaning |
|---|---|
| `id` | Item or block id (`minecraft:tnt`), or a tag with a leading `#` (`#minecraft:beds`). |
| `trigger` | `place`: the player successfully used this **item** on a block (placing blocks, end crystals, TNT minecarts, skulls).<br>`use`: the player successfully used this **item**, either on a block or in the air (flint and steel, fire charge, lava bucket).<br>`interact`: the player right-clicked this **block** and it was removed or replaced as a result (TNT being ignited, a bed exploding in the Nether, a charged respawn anchor exploding in the Overworld). |
| `action` | Verb shown in logs (default `placed` / `used` / `activated`). |
| `label` | Display name (defaults to a name made from the id). |
| `dimensions` | Only log in these dimensions, e.g. `["minecraft:the_nether"]`. Empty means everywhere. |
| `enabled` | `false` keeps the rule in the file without using it. |

The default file already contains disabled entries for respawn anchors, beds in the Nether/End, flint and steel, fire charges, lava buckets and wither skeleton skulls. Set `"enabled": true` on the ones you want.

When an `interact` rule fires (for example "ignited TNT"), item rules for the same click are skipped, so lighting TNT logs one line rather than two.

## How it works

- A Mixin wraps `ServerPlayerGameMode.useItemOn` and `useItem`. Every right-click a player makes on the server passes through these two methods. The mod remembers the held item and the clicked block, lets vanilla run, and then checks whether the action succeeded.
- The server thread only does in-memory work: matching rules, updating dedup groups, formatting strings and adding them to queues.
- A background thread appends to `logs/griefwatch/griefwatch-YYYY-MM-DD.log` and switches to a new file at midnight.
- A second background thread posts to Discord with `java.net.http.HttpClient`:
  - Consecutive embeds for the same webhook are batched, up to 10 per message and within Discord's 6000-character limit.
  - On HTTP 429 it waits for `retry_after` and retries.
  - When `X-RateLimit-Remaining` reaches 0 it waits for the reset before sending again.
  - Network errors and 5xx responses are retried 3 times with backoff, then dropped.
  - Other 4xx responses (a bad or deleted webhook) are dropped with a warning that is logged at most once a minute.
  - Player names are escaped for Markdown, and `allowed_mentions` is empty, so a player name can never ping anyone.
- Dedup groups close once their time window passes. They are swept once a second and also closed on shutdown, so memory stays bounded (at most 4096 open groups).
- If no webhook is set, or a URL is invalid, Discord is skipped for that log type and file logging carries on.

## Limitations

- Only actions a player performs directly are logged. TNT lit by redstone, fire, flaming arrows or dispensers has no player to attribute it to, so it isn't logged.
- The position logged for an air-use (such as a lava bucket) is the block the player was looking at within 5 blocks, or their own position if they weren't looking at one.
