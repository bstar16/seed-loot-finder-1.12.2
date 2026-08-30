package io.github.seedsleuth.sim;

import net.minecraft.tileentity.TileEntityLockableLoot;

import java.lang.reflect.Field;

/**
 * Reads a generated container's loot seed.
 *
 * <p>{@code TileEntityLockableLoot.lootTableSeed} has no accessor, so it is looked up
 * reflectively once and cached. A field is tried under its MCP name, then the SRG name a
 * reobfuscated runtime uses, and finally by type -- it is the only {@code long} declared on its
 * class. If all three fail the reader returns null forever rather than guessing a seed, because
 * a wrong seed produces confident, completely fictional loot.
 */
public final class LootSeedReader {
    private static final SeedField TILE =
        new SeedField(TileEntityLockableLoot.class, "field_184285_n");

    private LootSeedReader() {
    }

    /** The loot seed of a generated chest-like tile entity, or null. */
    public static Long read(TileEntityLockableLoot container) {
        return TILE.read(container);
    }

    public static boolean hasFailed() {
        return TILE.failed;
    }

    private static final class SeedField {
        private final Class<?> owner;
        private final String srgName;
        private volatile Field field;
        volatile boolean failed;

        SeedField(Class<?> owner, String srgName) {
            this.owner = owner;
            this.srgName = srgName;
        }

        Long read(Object container) {
            if (failed) {
                return null;
            }
            try {
                Field resolved = field;
                if (resolved == null) {
                    resolved = resolve();
                    if (resolved == null) {
                        failed = true;
                        return null;
                    }
                    field = resolved;
                }
                return Long.valueOf(resolved.getLong(container));
            } catch (Throwable error) {
                failed = true;
                return null;
            }
        }

        private Field resolve() {
            for (String name : new String[] {"lootTableSeed", srgName}) {
                try {
                    Field candidate = owner.getDeclaredField(name);
                    candidate.setAccessible(true);
                    return candidate;
                } catch (NoSuchFieldException ignored) {
                    // Try the next spelling.
                }
            }
            for (Field candidate : owner.getDeclaredFields()) {
                if (candidate.getType() == long.class) {
                    candidate.setAccessible(true);
                    return candidate;
                }
            }
            return null;
        }
    }
}
