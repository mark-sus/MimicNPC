# MimicNPC


MimicNPC periodically spawns a Citizens-powered NPC near a player that looks **exactly like another online player** (same skin, nameplate hidden from the tab list). The NPC behaves naturally — wandering, chopping trees, fighting passive mobs — and vanishes in a puff of smoke the moment someone gets too close.
 
If **Simple Voice Chat** is installed, the mechanic goes a step further: while an NPC is "wearing" someone's skin, it quietly listens in on that player's voice chat, memorizes snippets of their voice, and occasionally plays them back nearby — distorted into something unsettling. Some of these lines are saved to disk permanently, until an admin clears them.

<iframe width="560" height="315" src="https://www.youtube-nocookie.com/embed/HW_mSJCP8kE" title="YouTube video player" frameborder="0" allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share" allowfullscreen></iframe>

## ✨ Features
 
- **Dynamic spawning** — an NPC appears near a random online player, wearing the skin of another random online player, with a configurable interval and cap on simultaneously active NPCs.
- **Natural behavior** — chops nearby trees, attacks passive mobs, sprints while moving and walks while acting, naturally looks at nearby players.
- **Disappears on approach** — the moment a player gets close, the NPC vanishes with smoke and sound before it can be examined too closely.
- **Lifespan limit** — an NPC despawns on its own if it's been idle for too long.
- 🎙️ **Voice mechanic (optional, requires Simple Voice Chat)**
  - Captures voice **only** from the player whose skin is currently worn by an active NPC — no background recording of the rest of the server.
  - Automatically finds the parts of the recording where the player was actually talking (not silence or mic noise) and stores them as separate "lines."
  - Distorts the voice (pitch shift, bitcrush, a bit of "tape stutter") so it sounds unsettling rather than like a normal voice chat clip.
  - While a "talkative" NPC is alive, it periodically plays back one of its memorized lines at a random interval — not every NPC talks, that's decided randomly at spawn.
  - Some of the played-back lines are saved **permanently** as `.wav` files — listen to them separately or reuse them elsewhere. Cleared with a single command.
- **Flexible config** — spawn/search radii, despawn distance, action durations, NPC lifespan, and a dedicated block of voice-mechanic settings.
---
 
## 📋 Commands
 
| Command | Description |
|---|---|
| `/randomnpc spawn` | Force-spawn an NPC immediately |
| `/randomnpc clear` | Remove all NPCs created by the plugin |
| `/randomnpc reload` | Reload config.yml |
| `/randomnpc voicestatus` | Voice mechanic diagnostics (SVC status, who's being tracked, how many lines are archived) |
| `/randomnpc clearvoicelines` | Delete all permanently saved voiceline files from disk |
 
All commands require the `randomnpc.admin` permission (defaults to `op`).
 
---
 
## ⚙️ Dependencies
 
| Plugin | Requirement |
|---|---|
| [Citizens](https://www.spigotmc.org/resources/citizens.13811/) | **Required** — the plugin disables itself on startup without it |
| [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat) | Optional — everything except the voice mechanic works fine without it |
 
---
 
## 🔧 Example configuration
 
```yaml
spawn-interval-seconds: 300
max-active-npcs: 2
spawn-radius-min: 15
spawn-radius-max: 20
despawn-distance: 8.0
max-lifetime-seconds: 900
 
voice:
  enabled: true
  scare-chance: 0.35        # chance a given NPC turns out to be "talkative"
  memorize-interval-seconds: 6
  memorize-max-lines: 6
  playback-gap-min-seconds: 2
  playback-gap-max-seconds: 3
  archive-enabled: true
  archive-chance: 0.2
```
 
The full list of options — with a comment on every line — is generated in `config.yml` after the first run.
 
---
 
## 🖥️ Compatibility
 
Tested on Paper/Spigot **1.21.x**.
 
---
 
*Found a bug or have a feature idea? Open an issue.*
