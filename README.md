# The Local Chainsaws

Forge 1.20.1 mod (47.x) for The Local's server: iron, diamond and netherite chainsaws that fell whole trees.
Our own clean-room code, art and sound, MIT licensed.

## Gameplay

- **Break any log of a natural tree with a chainsaw (standing, not sneaking)**: the whole tree comes down. Every log
  drops as an item through its normal loot table with the chainsaw as the tool (Fortune, Silk Touch and loot modifiers
  apply); the leaves that would now decay are broken with their decay loot (saplings, sticks, apples); everything lands
  at the stump; a sapling is replanted (four for a 2x2 trunk when four dropped).
- Caps per felling: iron 64 logs, diamond 128, netherite 256 (nearest first, so a bigger tree is cut from the bottom up).
- 1 durability per log; it stops with 1 durability left instead of breaking.
- Only natural trees: the logs must touch at least 4 natural (non-persistent) leaves, and only logs of the same kind
  above the cut, within 12 blocks sideways, are taken. Player-placed leaves are persistent, so builds are safe.
- Chainsaws are real `AxeItem`s (vanilla axe damage/speed for the tier): they mine wood fast, strip logs and take axe
  enchantments. Netherite is fire resistant.
- **Sneaking** is left to [Tree Harvester](https://modrinth.com/mod/tree-harvester) (server-only on The Local): it fells
  trees for any axe while sneaking, and chainsaws count as axes, so its `axeWhitelist` does not need them. The two never
  both run on one break. Without Tree Harvester, sneaking chops a single log.
- Nether stems (no leaves) are not felled by the chainsaw itself; sneak for Tree Harvester's nether-tree support.

## Recipes

- Iron Chainsaw: `" I "`, `" RS"`, `" S "` (I iron ingot, R redstone, S string)
- Diamond Chainsaw: `" DR"`, `"RSR"`, `" S "` (D diamond)
- Netherite Chainsaw: smithing table, netherite upgrade template + diamond chainsaw + netherite ingot (like vanilla
  netherite tools; keeps enchantments).

## Build

```sh
export JAVA_HOME=~/opt/jdk-17.0.20.1+1     # any JDK 17
./gradlew build                            # -> build/libs/localchainsaws-1.20.1-<version>.jar
./gradlew runGameTestServer                # 11 server-side tests: grow trees, cut them with a fake player, count drops
```

Art and sound are generated, not drawn by hand in an editor: `tools/make_textures.py [preview.png]` (16x16 sprites),
`tools/make_sound.py` (synthesized two-stroke engine + chain grind, Ogg Vorbis via ffmpeg), `tools/make_arena.py`
(the empty gametest structure).

The tests can also run inside a full modpack server: start it with
`-Dforge.enableGameTest=true -Dforge.gameTestServer=true -Dforge.enabledGameTestNamespaces=localchainsaws`
(it builds its own flat world, runs the tests, exits). With Tree Harvester present the sneaking test expects Tree
Harvester to fell the tree exactly once.
