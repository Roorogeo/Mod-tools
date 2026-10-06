# GriefWatch

A server-side Fabric mod for Minecraft Java Edition **26.2** that logs:

- **Online players** every N minutes (count, names, UUIDs).
- **Grief tools**: TNT placed or ignited, end crystals, TNT minecarts, plus anything else you add to the watch list (respawn anchors, beds in the Nether/End, flint and steel, lava buckets, wither skulls, ...).
- **Large fires**: when fire burns down a lot of blocks, credited to the player who lit a fire nearby.
- **Lava/water casting**: when lava and water make lots of cobblestone, stone, obsidian or basalt, credited to the player who poured the fluid.

Entries go to a dated file in `logs/griefwatch/` and, optionally, to Discord webhooks as embeds. Repeated actions are grouped: the first event of a burst is logged right away, and one summary line follows when the burst ends:

```
[GRIEF] Steve (069a79f4-...) placed TNT [minecraft:tnt] at 120, 64, -310 in overworld
[GRIEF-SUMMARY] Steve placed 47 TNT near 120, 64, -310 in overworld over 28s (069a79f4-..., minecraft:tnt)
```

Fires and casts are reported once they pass a size threshold, then summarised when they stop:

```
[FIRE] Large fire: 48+ blocks burned near 212, 71, -40 in overworld (fire started by Steve)
[FIRE-SUMMARY] Fire started by Steve burned 412 blocks near 230, 72, -35 in overworld over 3m 12s (area 61x9x44) (069a79f4-..., started at 198, 70, -52)
[CAST] Possible lava cast by Alex: 48+ blocks formed by lava and water near 11, 188, 10 in overworld (...)
[CAST-SUMMARY] Lava cast by Alex: 1204 blocks formed near 11, 150, 10 in overworld over 9m 3s (area 14x99x12) (...)
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

The jar is written to `build/libs/griefwatch-1.1.0.jar`. (The `-sources.jar` next to it isn't needed on the server.)

Versions are set in `gradle.properties`. If a newer Loader, Loom or Fabric API is out, take the current values from <https://fabricmc.net/develop>. Minecraft 26.x ships unobfuscated, so the build uses the `net.fabricmc.fabric-loom` plugin and plain `implementation` dependencies, with no mappings line.

## Install

1. Install the Fabric server launcher for 26.2 (<https://fabricmc.net/use/server/>).
2. Copy `griefwatch-1.1.0.jar` and the Fabric API jar into the server's `mods/` folder.
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
  "fireLog": {
    "enabled": true,
    "alertThreshold": 48,        // blocks burned before an alert is sent
    "joinRadius": 12.0,          // burns this close to an active fire count towards it
    "sourceRadius": 32.0,        // a fire is blamed on a player who lit a fire this close (horizontally)...
    "sourceHeight": 32.0,        // ...and this close vertically
    "idleSeconds": 60,           // summary once nothing has burned for this long
    "sourceMaxAgeSeconds": 900,  // how long a player's fire-lighting is remembered
    "maxAreaSeconds": 3600,
    "logUnattributed": true,     // also report fires nobody can be linked to (lightning, lava pools)
    "sourceItems": ["minecraft:flint_and_steel", "minecraft:fire_charge", "minecraft:lava_bucket"]
  },
  "castLog": {
    "castBlocks": ["minecraft:cobblestone", "minecraft:stone", "minecraft:obsidian", "minecraft:basalt"],
    "enabled": true,
    "alertThreshold": 48,        // blocks formed before an alert is sent
    "joinRadius": 8.0,
    "sourceRadius": 24.0,
    "sourceHeight": 128.0,       // casts form far below where the lava was poured
    "idleSeconds": 120,
    "sourceMaxAgeSeconds": 1800,
    "maxAreaSeconds": 3600,
    "logUnattributed": false,    // natural lava/water meeting in caves is not reported by default
    "sourceItems": ["minecraft:lava_bucket", "minecraft:water_bucket"]
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
- **Fires**: a hook on the fire block's burn check notices each flammable block that fire destroys. Burned blocks are grouped into areas. An area keeps growing as long as new burns land within `joinRadius` of it, so a fire spreading across a whole forest stays one area. A new area is blamed on the most recent player who used a `sourceItems` item within `sourceRadius`/`sourceHeight` and within `sourceMaxAgeSeconds`.
- **Casts**: a hook on the fluid-mixing code (where lava meets water and makes a block) counts each `castBlocks` block formed. Casts are grouped and blamed in the same way, using lava and water bucket placements as sources.
- If no webhook is set, or a URL is invalid, Discord is skipped for that log type and file logging carries on.

## Limitations

- Fire and cast blame is a best guess: the area goes to the latest player who lit a fire or poured a fluid nearby. Someone lighting a campfire near another player's forest fire can get the blame. The source coordinates are logged so you can check.
- Only actions a player performs directly are logged. TNT lit by redstone, fire, flaming arrows or dispensers has no player to attribute it to, so it isn't logged.
- The position logged for an air-use (such as a lava bucket) is the block the player was looking at within 5 blocks, or their own position if they weren't looking at one.
