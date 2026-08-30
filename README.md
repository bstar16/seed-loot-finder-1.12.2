# Seed Loot Finder — Forge 1.12.2

A small client-only loot finder for vanilla-compatible Minecraft Java **1.12.2** worlds. It
does not crack seeds, locate unrelated structures, track players, or add any server commands.
For multiplayer prediction, you enter the world's seed yourself.

## Install

Install Forge **14.23.5.2859** for Minecraft 1.12.2, then place
`seed-loot-finder-1.12.2-1.0.1.jar` in the instance's `mods` folder. The server needs nothing.

## Use

All commands are local and never sent to the server.

```
/lootfinder seed <signed 64-bit world seed>
/lootfinder on
/lootfinder add <target>
/lootfinder list
```

Useful target forms:

```
gapple                         enchanted golden apple
minecraft:diamond              a specific item
book:mending                   a Mending enchanted book
book:fortune:3                 Fortune III or higher enchanted book
ench:mending                   Mending on any item, including enchanted gear
ench:sharpness:4               Sharpness IV or higher on any item
```

Other controls:

```
/lootfinder off
/lootfinder remove <number>
/lootfinder clear
/lootfinder radius <1-8>
/lootfinder status
```

The default targets are an enchanted golden apple and a Mending book. The on-screen list shows
coordinates and every requested match found in each container.

## Accuracy model

* **Singleplayer/LAN host:** the mod reads each loaded generated container's stored loot-table
  ID and loot seed, then rolls that table into a throwaway list. This is exact, does not open the
  container, and does not consume its loot.
* **Remote multiplayer:** the server does not send pending loot. The mod replays vanilla
  `ChunkGeneratorOverworld` or `ChunkGeneratorEnd`, as appropriate, from the seed entered with
  `/lootfinder seed`, including the neighbouring population passes that can write into a target
  chunk. It reads the generated container's exact loot seed and applies the regular vanilla loot
  table with `Random(lootSeed)`. This includes End City treasure chests.
* Before showing remote predictions, the replay must match at least eight received chunks at
  90%+ surface-height agreement. A mismatch suppresses results rather than presenting guesses.

The remote predictor is intentionally limited to the Overworld and End with the vanilla 1.12.2
generators (including normal and Large Biomes in the Overworld). Do not rely on it with a wrong
seed, custom terrain, a modified world generator, or server-side loot-table/worldgen changes.

### Mineshafts

Mineshaft chest minecarts are deliberately excluded. They are entities created by a recursively
assembled shaft graph; a local partial replay can yield a convincing but wrong minecart or loot
seed. This build reports only generated `TileEntityLockableLoot` containers, where the seed and
table are recoverable from the fully replayed population window. Accuracy is preferred over an
incomplete mineshaft feature.

## Why this is deterministic

1.12.2 loot containers store a loot-table identifier and seed; Forge exposes the corresponding
`setLootTable(ResourceLocation, long)` API. Rolling the same table with the same `Random` state
therefore yields the same item stacks. Forge's 1.12 loot-table documentation describes table
generation through `generateLootForPools`, and the 1.12.2 `ChunkGeneratorOverworld` API exposes
the matching chunk generation and population stages used by this mod.

Sources: [Forge 1.12 loot-table documentation](https://docs.minecraftforge.net/en/1.12.x/items/loot_tables/),
[TileEntityLockableLoot 1.12 API](https://skmedix.github.io/ForgeJavaDocs/javadoc/forge/1.9.4-12.17.0.2051/net/minecraft/tileentity/TileEntityLockableLoot.html),
[ChunkGeneratorOverworld 1.12.2 API](https://nekoyue.github.io/ForgeJavaDocs-NG/javadoc/1.12.2/net/minecraft/world/gen/ChunkGeneratorOverworld.html).

## Build

JDK 8 is required for ForgeGradle 3.

```powershell
$env:JAVA_HOME = 'C:\Path\To\JDK8'
.\gradlew.bat clean build
```

The output is `build\libs\seed-loot-finder-1.12.2-1.0.1.jar`.
