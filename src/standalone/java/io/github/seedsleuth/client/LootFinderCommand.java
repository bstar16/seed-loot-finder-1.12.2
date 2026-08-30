package io.github.seedsleuth.client;

import io.github.seedsleuth.loot.LootScanner;
import io.github.seedsleuth.loot.LootTarget;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;

import java.util.List;

public final class LootFinderCommand extends CommandBase {
    private final LootFinderController controller;
    LootFinderCommand(LootFinderController controller) { this.controller = controller; }
    @Override public String getName() { return "lootfinder"; }
    @Override public String getUsage(ICommandSender sender) { return "/lootfinder seed <seed> | on|off | add <target> | remove <n> | list | clear | radius <1-8> | status"; }
    @Override public int getRequiredPermissionLevel() { return 0; }
    @Override public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length == 0) { controller.say(getUsage(sender)); return; }
        String action = args[0].toLowerCase(java.util.Locale.ROOT);
        LootScanner scanner = controller.getScanner();
        if ("seed".equals(action)) {
            if (args.length != 2) throw new CommandException("Usage: /lootfinder seed <signed 64-bit seed>");
            try { controller.setSeed(Long.parseLong(args[1])); } catch (NumberFormatException e) { throw new CommandException("That is not a signed 64-bit seed."); }
            controller.say("Seed set. Enable with /lootfinder on.");
        } else if ("on".equals(action) || "off".equals(action)) {
            controller.setEnabled("on".equals(action)); controller.say("Loot finding " + action + ".");
        } else if ("add".equals(action)) {
            if (args.length != 2) throw new CommandException("Usage: /lootfinder add <item|gapple|book:enchantment[:level]|ench:enchantment[:level]>");
            try { scanner.addTarget(args[1]); } catch (IllegalArgumentException e) { throw new CommandException(e.getMessage()); }
            controller.say("Target added: " + args[1]);
        } else if ("remove".equals(action)) {
            if (args.length != 2) throw new CommandException("Usage: /lootfinder remove <number from list>");
            int index; try { index = Integer.parseInt(args[1]) - 1; } catch (NumberFormatException e) { throw new CommandException("Use a target number."); }
            if (!scanner.removeTarget(index)) throw new CommandException("No target with that number.");
            controller.say("Target removed.");
        } else if ("list".equals(action) || "targets".equals(action)) {
            List<LootTarget> targets = scanner.getTargets();
            for (int i = 0; i < targets.size(); i++) controller.say((i + 1) + ": " + targets.get(i));
        } else if ("clear".equals(action)) {
            scanner.rescan();
            controller.say("Finds cleared; loaded chunks will be checked again.");
        }
        else if ("radius".equals(action)) { if (args.length != 2) throw new CommandException("Usage: /lootfinder radius <1-8>"); try { controller.setRadius(Integer.parseInt(args[1])); } catch (NumberFormatException e) { throw new CommandException("Use a number from 1 to 8."); } controller.say("Radius set to " + controller.getRadius() + " chunks."); }
        else if ("status".equals(action)) controller.say("" + (scanner.isEnabled() ? "on" : "off") + "; seed " + (controller.getSeed() == null ? "not set" : controller.getSeed()) + "; " + scanner.hitCount() + " matches.");
        else throw new CommandException(getUsage(sender));
    }
}
