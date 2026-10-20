# The Local Chainsaws

Forge 1.20.1 mod (47.x) for The Local's server: iron, diamond and netherite chainsaws that fell whole trees.
Our own clean-room code, art and sound, MIT licensed.

## Gameplay

- **Break any log of a natural tree with a chainsaw (standing, not crouching)**: the whole tree comes down. Every log
  drops as an item through its normal loot table with the chainsaw as the tool (Fortune, Silk Touch and loot modifiers
  apply); the leaves that would now decay are broken with their decay loot (saplings, sticks, apples); everything lands
  at the stump; a sapling is replanted (four for a 2x2 trunk when four dropped), and for 3 seconds a still-swinging
  chainsaw can't knock the new sapling out again.
- Caps per felling: iron 64 logs, diamond 128, netherite 256 (nearest first, so a bigger tree is cut from the bottom up;
  changeable in the server config, see [Tuning](#tuning)).
- 1 durability per log; it stops with 1 durability left instead of breaking.
- **Wear warning:** when the chainsaw in your hand drops to 10% of its durability, an action-bar line says how many uses
  are left and the engine sputters (only you hear it); at its last point it says it's worn out (felling and revving
  stop there, but a plain block break would still break it for good). Repair on an anvil.
- Fortune and Silk Touch change nothing for logs (vanilla logs drop themselves either way): one item per log. Leaves
  drop their decay loot, not leaf blocks. Upgrading to netherite keeps enchantments, name and wear.
- **Advancements** (in the Minecraft tab, after Acquire Hardware): *Power Tool* (get a chainsaw) and *Timber!* (fell a
  whole tree with one cut).
- Only natural trees: the logs must touch at least 4 natural (non-persistent) leaves, and only logs of the same kind
  above the cut, within 12 blocks sideways, are taken. Player-placed leaves are persistent, so builds are safe.
- Chainsaws are real `AxeItem`s (vanilla axe damage/speed for the tier): they mine wood fast, strip logs and take axe
  enchantments. Netherite is fire resistant.
- **Crouching (sneaking)** is left to [Tree Harvester](https://modrinth.com/mod/tree-harvester) when it is installed (The Local
  had it server-side until 2026-10-19; without it crouching chops a single log): it fells
  trees for any axe while crouching, and chainsaws count as axes, so its `axeWhitelist` does not need them. Both mods test
  the same thing (`Player.isCrouching()`), so exactly one of them acts on a break; the self-test checks this with Tree
  Harvester installed. Without Tree Harvester, crouching chops a single log. (If Tree Harvester's `treeHarvestWithoutSneak`
  were ever turned on, whichever runs first takes the tree and the other finds nothing left to fell.)
- Nether stems (no leaves) are not felled by the chainsaw itself; sneak for Tree Harvester's nether-tree support.

## Fighting

- **Swing** (left-click): a normal axe hit (same damage, cooldown, shield-disabling and 2 durability as the vanilla axe
  of the tier) plus a rev burst sound and sparks and blood where the chain meets the mob. Skeletons, golems, slimes,
  blazes and the like get extra sparks instead of blood (`data/localchainsaws/tags/entity_types/bloodless.json`).
- **Hold right-click** (anywhere: in the air, at a mob, at the ground) to start the engine: a rev-up sound and a puff
  of smoke, and 12 ticks (0.6 s) of wind-up while the saw is brought up. Once it's revved up, as long as right-click is
  held, the chain saws **what's right in front of you** every 5 ticks (4 times a second): up to 3 living things
  (the one under the crosshair first, then the nearest) whose hitbox is within 3 blocks (a normal hit's reach)
  inside a narrow 45° cone around where you look, not behind a block. These numbers (wind-up, interval, damage factor,
  reach, cone, max targets, cut time) are in the server config, see [Tuning](#tuning).
  Letting go winds the engine down (rev-down sound, a little smoke); press again for a new wind-up.
  - Each thing sawn takes 1.1x the damage per second of perfectly timed axe swings (axe damage incl.
    Sharpness/Smite/Strength x attack speed), so iron ~8.9, diamond ~9.9, netherite ~11 per second against no armour,
    and costs 1 durability. The saw stops by itself with 1 durability left, even in the middle of a sweep (it never
    breaks from revving), and won't start again until it's repaired.
  - Never sawn: you, what you're riding (and anyone riding with you), teammates, your own pets, armor stands.
    Someone else's tamed pet only when it's under your crosshair. Players are sawn like mobs where PvP is on.
  - Bites don't knock the target back (it stays in the chain), use up the swing cooldown (rev-then-swing is no faster),
    respect the usual attack checks (PvP setting, claim/protection mods via `AttackEntityEvent`, shields block them),
    and kills read "X was shredded by Y" (damage type `localchainsaws:chainsaw`).
  - **Revved into a tree:** with the crosshair on a log of a natural tree within the same 3 blocks, the chain saws
    into it (cracks and sawdust everyone sees). After 15 ticks (0.75 s) of continuous aim at that log it is broken
    exactly as a left-click breaks it, so the tree fells just like left-click felling: same caps per tier, drops,
    replanting and durability, claims apply, and crouching leaves it to Tree Harvester. Looking away or letting go
    starts the cut over. Mobs in the cone keep getting sawn meanwhile; a mob in front of the log takes the chain
    instead. Logs of builds (player-placed leaves, or no leaves) are never cut by the rev.
  - Right-click on a log of a natural tree revs into it instead of stripping it (otherwise the first click would strip
    the trunk); logs in builds still strip. Otherwise right-click does what the block does first (a chest opens);
    anything without a right-click use of its own (stone, grass, air) revs. Apart from tree felling, revving never
    breaks blocks. With a **shield in the off hand** right-click keeps blocking. Left-click felling and the crouch /
    Tree Harvester split are unchanged. You walk slowly while revving, like drawing a bow.
  - Sound: the rev-up and rev-down are sent to everyone nearby (and played at once on your own client); every client
    plays a seamless engine loop for each player it sees revved up (the "using an item" flag is synced vanilla entity
    data), fading in as the rev-up tops out, and the first-person chainsaw rises during the wind-up and shakes once
    revved. Smoke, sparks/blood and the swing per sweep are sent by the server to everyone nearby. Particles follow
    the client's particle setting.

## Tuning

All the numbers live in the world's **`serverconfig/localchainsaws-server.toml`** (Forge writes it with the defaults on
the first start; put a copy in the server's `defaultconfigs/` to seed new worlds). Edits apply without a restart: Forge
normally re-reads the file within seconds of a save, but with ModernFix installed (as on The Local) its file watcher is
off, so run **`/mfsrc`** (op or server console; on The Local `~/bin/mc cmd mfsrc`) after saving. Either way the server
log then shows `[localchainsaws] server config changed: reach 3.0, coneDegrees 45.0, ...`. Players pick up a new wind-up length (only
used on their side for the saw rising and the loop fading in) when they next join.

| key | default | what |
|---|---|---|
| `rev.reach` | 3.0 | blocks from the eyes to the nearest point of a hitbox or log |
| `rev.coneDegrees` | 45 | width of the sawn area, in total |
| `rev.maxTargets` | 3 | most things sawn per sweep |
| `rev.windupTicks` | 12 | ticks from pressing right-click to the first bite |
| `rev.revIntervalTicks` | 5 | ticks between sweeps |
| `rev.revDpsFactor` | 1.1 | rev damage per second vs. perfectly timed axe swings |
| `rev.cutTicks` | 15 | ticks of revved aim at a natural tree's log before it is cut |
| `felling.ironMaxLogs` / `diamondMaxLogs` / `netheriteMaxLogs` | 64 / 128 / 256 | most logs per felling |
| `wear.lowDurabilityPercent` | 10 | when the "wearing out" warning comes, 0 = off |

Each player's own `config/localchainsaws-client.toml` has `sound.revLoopVolume` and `sound.soundVolume` (0 to 1) for
how loud the engine loop and the other chainsaw sounds are on their computer.

## Robustness

A rev bite can kill a mob and a felling breaks many blocks, and other mods react to both (conversions, drops,
backpacks, claims). If one of them throws, the chainsaw stops what it was doing (the rev winds down; a felling drops what
it had cut so far) and logs the error once with its stack trace, instead of crashing the server (the 2026-10-20 crash
was Hordes + Sophisticated Backpacks failing on a zombie's kill). Fake players (other mods' machines holding a chainsaw)
never get advancements.

## Recipes

- Iron Chainsaw: `" I "`, `" RS"`, `" S "` (I iron ingot, R redstone, S string)
- Diamond Chainsaw: `" DR"`, `"RSR"`, `" S "` (D diamond)
- Netherite Chainsaw: smithing table, netherite upgrade template + diamond chainsaw + netherite ingot (like vanilla
  netherite tools; keeps enchantments).

## Build

```sh
export JAVA_HOME=~/opt/jdk-17.0.20.1+1     # any JDK 17
./gradlew build                            # -> build/libs/localchainsaws-1.20.1-<version>.jar
./gradlew runGameTestServer                # 33 server-side tests: grow trees, cut them with a fake player, count drops;
                                           # swing / rev damage, wind-up, cone sweep and its 3-block / 45° /
                                           # 3-target limits, durability floor, rev vs. felling, log stripping and blocks,
                                           # revving into trees (cut time, caps, worn saw, builds, sneaking); wear
                                           # warnings, advancements, live config change, Fortune / Silk Touch, smithing,
                                           # another mod throwing on a rev kill or mid-felling
```

![The three chainsaws](docs/preview.png)

Art and sound are generated, not drawn by hand in an editor: `tools/make_textures.py [preview.png]` (16x16 sprites,
8x8 spark/blood particles), `tools/make_sound.py` (synthesized two-stroke engine + chain noise, Ogg Vorbis via ffmpeg:
the felling roar, the hit burst, the rev-up, the rev-down and the seamless 2 s rev loop), `tools/make_arena.py` (the empty gametest structure).

`-Dlocalchainsaws.clientcheck=true` on a client logs `[localchainsaws-clientcheck]` lines for every chainsaw sound it
plays, each rev loop it starts (this player / another player) and when it becomes audible, and the spark/blood
particles it spawns per second; the
headless two-client test uses it.

The tests can also run inside a full modpack server: start it with
`-Dlocalchainsaws.selftest=true` (Forge itself only registers gametests in a dev workspace, so this runs the same scenarios through `SelfTest`: trees in the sky around x=30000 z=30000, fake players, a `[localchainsaws-selftest] SUMMARY n/n passed` log line, then it stops the server; throwaway copies only)
With Tree Harvester present the sneaking test expects Tree
Harvester to fell the tree exactly once.
