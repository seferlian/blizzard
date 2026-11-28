package com.blizzard.command;

import com.blizzard.Tags;
import com.blizzard.network.server.VisualSyncSender;
import com.blizzard.registry.BlizzardItems;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.ConfigManager;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * /blizzard reload — перечитывает конфиг с диска и рассылает визуальные настройки всем игрокам.
 * /blizzard plate <процент> [игрок] — выдаёт термочайный пакет с произвольной защитой.
 * Требует уровня оператора.
 */
public class CommandBlizzard extends CommandBase {

    private static final List<String> SUBCOMMANDS = Arrays.asList("reload", "plate");
    private static final List<String> PERCENTS = Arrays.asList("5", "10", "15", "20", "25", "50", "100");

    @Override
    public String getName() {
        return "blizzard";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/blizzard <reload|plate <percent> [player]>";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2; // как у /gamemode и т.п.
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 1) {
            throw new CommandException("Usage: " + getUsage(sender));
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        if ("reload".equals(sub)) {
            executeReload(server, sender, args);
            return;
        }
        if ("plate".equals(sub)) {
            executePlate(server, sender, args);
            return;
        }
        throw new CommandException("Usage: " + getUsage(sender));
    }

    /** /blizzard reload — только конфиг визуала/холода; рецепты пересобираются при перезапуске. */
    private void executeReload(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length != 1) {
            throw new CommandException("Usage: /blizzard reload");
        }

        ConfigManager.sync(Tags.MODID, Config.Type.INSTANCE);
        VisualSyncSender.broadcast(server);

        sender.sendMessage(new TextComponentString("[Blizzard] Config reloaded and synced to all players."));
        sender.sendMessage(new TextComponentString("[Blizzard] Thermoplate recipes are rebuilt only on game restart."));
    }

    /** /blizzard plate <процент> [игрок] — пакет с точным значением защиты в NBT. */
    private void executePlate(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 2 || args.length > 3) {
            throw new CommandException("Usage: /blizzard plate <percent> [player]");
        }

        double percent = parseDouble(args[1], 0.0, 100.0);
        EntityPlayerMP target = args.length == 3
                ? getPlayer(server, sender, args[2])
                : getCommandSenderAsPlayer(sender);

        ItemStack stack = BlizzardItems.THERMO_TEA_PACK.createStack((float) (percent / 100.0));
        if (!target.inventory.addItemStackToInventory(stack)) {
            target.dropItem(stack, false);
        }

        String message = String.format(Locale.ROOT, "[Blizzard] Thermo tea pack (%s) given to %s.",
                formatPercent(percent), target.getName());
        sender.sendMessage(new TextComponentString(message));
        if (sender != target) {
            target.sendMessage(new TextComponentString(message));
        }
    }

    private static String formatPercent(double percent) {
        return String.format(Locale.ROOT, "%.1f%%", percent);
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender,
                                          String[] args, BlockPos targetPos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, SUBCOMMANDS);
        }
        if (args.length == 2 && "plate".equalsIgnoreCase(args[0])) {
            return getListOfStringsMatchingLastWord(args, PERCENTS);
        }
        if (args.length == 3 && "plate".equalsIgnoreCase(args[0])) {
            return getListOfStringsMatchingLastWord(args, server.getOnlinePlayerNames());
        }
        return Collections.emptyList();
    }
}