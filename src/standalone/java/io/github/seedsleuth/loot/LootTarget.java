package io.github.seedsleuth.loot;

import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

import java.util.Locale;
import java.util.Map;

/**
 * Something worth flying to.
 *
 * <p>Three spellings, all accepted by {@code /seedcracker loot add}:
 * <ul>
 *   <li>{@code gapple} or {@code enchanted_golden_apple} - the notch apple</li>
 *   <li>{@code book:mending} or {@code book:fortune:3} - an enchanted book, optionally at or
 *       above a level</li>
 *   <li>{@code ench:mending} or {@code ench:sharpness:4} - the enchantment on <em>anything</em>,
 *       book or gear. End city chests, for example, hold no books at all in 1.12.2; their
 *       Mending arrives on treasure-enchanted diamond equipment.</li>
 *   <li>{@code minecraft:diamond} or {@code diamond:0} - any item, optionally at a metadata</li>
 * </ul>
 */
public final class LootTarget {
    private final String spec;
    private final Item item;
    private final int metadata;
    private final ResourceLocation enchantmentId;
    private final int minimumLevel;

    private LootTarget(String spec, Item item, int metadata, ResourceLocation enchantmentId,
                       int minimumLevel) {
        this.spec = spec;
        this.item = item;
        this.metadata = metadata;
        this.enchantmentId = enchantmentId;
        this.minimumLevel = minimumLevel;
    }

    public static LootTarget parse(String raw) {
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Empty target");
        }
        if ("gapple".equals(value) || "notch_apple".equals(value)
            || "enchanted_golden_apple".equals(value)) {
            return new LootTarget("enchanted_golden_apple", Items.GOLDEN_APPLE, 1, null, 0);
        }
        if (value.startsWith("book:") || value.startsWith("ench:")) {
            boolean anyItem = value.startsWith("ench:");
            String[] parts = value.split(":");
            if (parts.length < 2 || parts.length > 3) {
                throw new IllegalArgumentException(
                    "Use " + parts[0] + ":<enchantment>[:<minLevel>]");
            }
            String name = parts[1].contains(".") || parts[1].contains("/")
                ? parts[1] : "minecraft:" + parts[1];
            ResourceLocation enchantmentId = new ResourceLocation(name);
            Enchantment enchantment = Enchantment.REGISTRY.getObject(enchantmentId);
            // During early client initialisation the registry can still be empty. Keep the ID and
            // resolve it while matching instead of silently dropping the default Mending target.
            if (enchantment == null && !Enchantment.REGISTRY.getKeys().isEmpty()) {
                throw new IllegalArgumentException("Unknown enchantment: " + parts[1]);
            }
            int level = 1;
            if (parts.length == 3) {
                try {
                    level = Integer.parseInt(parts[2]);
                } catch (NumberFormatException error) {
                    throw new IllegalArgumentException("Not a level: " + parts[2]);
                }
            }
            return new LootTarget(value, anyItem ? null : Items.ENCHANTED_BOOK, -1,
                enchantmentId, level);
        }
        if ("book".equals(value) || "any_book".equals(value)) {
            return new LootTarget("book", Items.ENCHANTED_BOOK, -1, null, 0);
        }

        String[] parts = value.split(":");
        String id;
        int meta = -1;
        if (parts.length == 3) {
            id = parts[0] + ":" + parts[1];
            meta = parseMeta(parts[2]);
        } else if (parts.length == 2 && parts[0].equals("minecraft")) {
            id = value;
        } else if (parts.length == 2) {
            id = "minecraft:" + parts[0];
            meta = parseMeta(parts[1]);
        } else {
            id = "minecraft:" + value;
        }
        Item resolved = Item.REGISTRY.getObject(new ResourceLocation(id));
        if (resolved == null) {
            throw new IllegalArgumentException("Unknown item: " + id);
        }
        return new LootTarget(value, resolved, meta, null, 0);
    }

    private static int parseMeta(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Not a metadata value: " + raw);
        }
    }

    /** Description of the match, or null when this stack is not interesting. */
    public String match(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        // A null item means "any item carrying the enchantment" (the ench: spelling).
        if (item != null && stack.getItem() != item) {
            return null;
        }
        if (metadata >= 0 && stack.getMetadata() != metadata) {
            return null;
        }
        if (enchantmentId == null) {
            return describe(stack);
        }
        // getEnchantments reads stored enchantments for books and the ench tag for gear, so one
        // loop covers both carriers.
        Map<Enchantment, Integer> enchantments = EnchantmentHelper.getEnchantments(stack);
        for (Map.Entry<Enchantment, Integer> entry : enchantments.entrySet()) {
            ResourceLocation actualId = Enchantment.REGISTRY.getNameForObject(entry.getKey());
            int level = entry.getValue().intValue();
            if (enchantmentId.equals(actualId) && level >= minimumLevel) {
                String name = entry.getKey().getTranslatedName(level);
                return item == null ? stack.getDisplayName() + " (" + name + ")" : name;
            }
        }
        return null;
    }

    private static String describe(ItemStack stack) {
        Map<Enchantment, Integer> enchantments = EnchantmentHelper.getEnchantments(stack);
        if (enchantments.isEmpty()) {
            return (stack.getCount() > 1 ? stack.getCount() + "x " : "") + stack.getDisplayName();
        }
        StringBuilder result = new StringBuilder(stack.getDisplayName());
        result.append(" (");
        boolean first = true;
        for (Map.Entry<Enchantment, Integer> entry : enchantments.entrySet()) {
            if (!first) {
                result.append(", ");
            }
            result.append(entry.getKey().getTranslatedName(entry.getValue().intValue()));
            first = false;
        }
        return result.append(')').toString();
    }

    public String getSpec() {
        return spec;
    }

    @Override
    public String toString() {
        return spec;
    }
}
