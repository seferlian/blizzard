package com.blizzard.command;

import com.blizzard.Tags;
import com.blizzard.network.server.VisualSyncSender;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.ConfigManager;

import java.util.Collections;
import java.util.List;

/**
 * /blizzard reload
 * Перечитывает конфиг с диска и рассылает визуальные настройки всем игрокам.
 * Требует уровня оператора.
 */
public class CommandBlizzard extends CommandBase {

    @Override
    public String getName() {
        return "blizzard";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/blizzard reload";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2; // как у /gamemode и т.п.
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length != 1 || !"reload".equalsIgnoreCase(args[0])) {
            throw new CommandException("Usage: " + getUsage(sender));
        }

        // Перечитываем файл конфига с диска в статический ConfigHandler
        ConfigManager.sync(Tags.MODID, Config.Type.INSTANCE);

        // Рассылаем новые значения всем игрокам
        VisualSyncSender.broadcast(server);

        sender.sendMessage(new TextComponentString("[Blizzard] Config reloaded and synced to all players."));
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender,
                                          String[] args, net.minecraft.util.math.BlockPos targetPos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, "reload");
        }
        return Collections.emptyList();
    }
}