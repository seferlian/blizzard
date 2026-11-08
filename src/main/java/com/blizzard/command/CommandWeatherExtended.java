package com.blizzard.command;

import com.blizzard.weather.BlizzardHooks;
import com.blizzard.weather.BlizzardManager;
import com.blizzard.weather.BlizzardReflection;
import com.blizzard.weather.PacketBlizzard;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Расширенная команда /weather.
 *
 * Поддерживает 5 подкоманд:
 *   clear    — ясная погода на N тиков;
 *   rain     — дождь на N тиков;
 *   thunder  — гроза на N тиков;
 *   blizzard — снежная метель на N тиков (4-е состояние погоды);
 *   status   — вывести текущее состояние погоды (только чтение).
 *
 * Плавные переходы:
 *   при /weather clear|rain|thunder, если идёт метель — она не сбрасывается
 *   мгновенно, а плавно затухает (blizzardTime = 0); новая погода применяется
 *   сразу, но визуально появляется только после затухания метели.
 */
public class CommandWeatherExtended extends CommandBase {

    /** Допустимые подкоманды (для Tab-автодополнения). */
    private static final List<String> STATES =
            Arrays.asList("clear", "rain", "thunder", "blizzard", "status");

    /** @return имя команды (переопределяет ванильную /weather). */
    @Override
    public String getName() { return "weather"; }

    /** @return уровень доступа (2 = оператор). */
    @Override
    public int getRequiredPermissionLevel() { return 2; }

    /** @return строка-подсказка для /weather без аргументов. */
    @Override
    public String getUsage(ICommandSender sender) {
        return "/weather <clear|rain|thunder|blizzard|status> [duration]";
    }

    /**
     * Основная логика команды.
     *
     * @param server сервер;
     * @param sender тот, кто ввёл команду;
     * @param args   аргументы команды.
     * @throws CommandException при неверном синтаксисе.
     */
    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        // Без аргументов — ошибка синтаксиса
        if (args.length < 1) throw new WrongUsageException(getUsage(sender));

        // Работаем только с Overworld (dimension 0)
        WorldServer world = server.getWorld(0);
        if (world == null) {
            sender.sendMessage(new TextComponentString("World not loaded."));
            return;
        }

        // Подкоманда в нижнем регистре и длительность (по умолчанию 6000 тиков = 5 мин)
        String mode = args[0].toLowerCase();
        int duration = args.length > 1 ? parseInt(args[1], 1, 1000000) : 6000;

        // /weather status — только чтение, ничего не меняем
        if (mode.equals("status")) {
            sendStatus(sender, world);
            return;
        }

        // Для всех команд, кроме blizzard: гасим метель (плавно или сразу)
        if (!mode.equals("blizzard")) {
            if (BlizzardReflection.isBlizzard(world)) {
                // Метель идёт — запускаем плавное затухание.
                // strength продолжит падать в tickWeather (FADE_DELTA),
                // а новая погода начнёт действовать после затухания.
                BlizzardReflection.setBlizzardTime(world, 0);
                BlizzardReflection.setBlizzardTimeInfo(world.getWorldInfo(), 0);
                BlizzardReflection.setBlizzardInfo(world.getWorldInfo(), false);
            } else {
                // Метели нет — сбрасываем на всякий случай
                BlizzardReflection.setBlizzard(world, false, 0);
                BlizzardReflection.setBlizzardStrength(world, 0.0F);
                BlizzardManager.NETWORK.sendToAll(new PacketBlizzard(false, 0, 0.0F));
                BlizzardReflection.setBlizzardInfo(world.getWorldInfo(), false);
            }
        }

        // Применяем новую погоду
        switch (mode) {
            case "clear":
                // Ясно на duration тиков
                world.getWorldInfo().setCleanWeatherTime(duration);
                world.getWorldInfo().setRainTime(0);
                world.getWorldInfo().setRaining(false);
                world.getWorldInfo().setThunderTime(0);
                world.getWorldInfo().setThundering(false);
                // Синхронизируем LAST_*, чтобы детектор смены не запустил метель
                BlizzardHooks.syncWeatherState(world);
                notifyCommandListener(sender, this, "commands.weather.clear");
                break;

            case "rain":
                // Дождь на duration тиков
                world.getWorldInfo().setCleanWeatherTime(0);
                world.getWorldInfo().setRainTime(duration);
                world.getWorldInfo().setRaining(true);
                world.getWorldInfo().setThunderTime(0);
                world.getWorldInfo().setThundering(false);
                BlizzardHooks.syncWeatherState(world);
                notifyCommandListener(sender, this, "commands.weather.rain");
                break;

            case "thunder":
                // Гроза на duration тиков
                world.getWorldInfo().setCleanWeatherTime(0);
                world.getWorldInfo().setRainTime(duration);
                world.getWorldInfo().setRaining(true);
                world.getWorldInfo().setThunderTime(duration);
                world.getWorldInfo().setThundering(true);
                BlizzardHooks.syncWeatherState(world);
                notifyCommandListener(sender, this, "commands.weather.thunder");
                break;

            case "blizzard":
                // Метель на duration тиков (принудительно, без шанса)
                BlizzardHooks.startBlizzard(world, duration, "command");
                sender.sendMessage(new TextComponentString("Blizzard started for " + duration + " ticks."));
                break;

            default:
                // Неизвестная подкоманда
                throw new WrongUsageException(getUsage(sender));
        }
    }

    /**
     * Выводит в чат текущее состояние погоды (только чтение).
     * Используется подкомандой /weather status.
     *
     * @param sender тот, кому отправлять сообщения;
     * @param world  мир, чьё состояние читаем.
     */
    private void sendStatus(ICommandSender sender, WorldServer world) {
        // Состояние метели
        boolean blizzard = BlizzardReflection.isBlizzard(world);
        int blizzardTime = BlizzardReflection.getBlizzardTime(world);
        float blizzardStrength = BlizzardReflection.getBlizzardStrength(world);

        // Состояние ванильной погоды
        boolean raining = world.isRaining();
        boolean thundering = world.isThundering();
        float rainStrength = world.rainingStrength;
        float thunderStrength = world.thunderingStrength;

        // Определяем «текущее» состояние
        String current;
        if (blizzard) current = "blizzard";
        else if (thundering) current = "thunder";
        else if (raining) current = "rain";
        else current = "clear";

        // --- Вывод ---
        sender.sendMessage(new TextComponentString("§6=== Weather Status ==="));
        sender.sendMessage(new TextComponentString("§eCurrent: §f" + current));
        sender.sendMessage(new TextComponentString("§eDimension: §f" + world.provider.getDimension()));
        sender.sendMessage(new TextComponentString(""));

        // Блок метели
        sender.sendMessage(new TextComponentString("§bBlizzard:"));
        sender.sendMessage(new TextComponentString("  §7active: §f" + blizzard));
        sender.sendMessage(new TextComponentString("  §7time left: §f" + blizzardTime
                + " ticks (" + (blizzardTime / 20) + " sec)"));
        sender.sendMessage(new TextComponentString("  §7strength: §f"
                + String.format("%.3f", blizzardStrength)));
        sender.sendMessage(new TextComponentString(""));

        // Блок ванильной погоды
        sender.sendMessage(new TextComponentString("§bVanilla weather:"));
        sender.sendMessage(new TextComponentString("  §7isRaining: §f" + raining));
        sender.sendMessage(new TextComponentString("  §7isThundering: §f" + thundering));
        sender.sendMessage(new TextComponentString("  §7rainStrength: §f"
                + String.format("%.3f", rainStrength)));
        sender.sendMessage(new TextComponentString("  §7thunderStrength: §f"
                + String.format("%.3f", thunderStrength)));
        sender.sendMessage(new TextComponentString(""));

        // Блок WorldInfo (NBT)
        sender.sendMessage(new TextComponentString("§bWorldInfo (NBT):"));
        sender.sendMessage(new TextComponentString("  §7info.isRaining: §f"
                + world.getWorldInfo().isRaining()));
        sender.sendMessage(new TextComponentString("  §7info.isThundering: §f"
                + world.getWorldInfo().isThundering()));
        sender.sendMessage(new TextComponentString("  §7info.rainTime: §f"
                + world.getWorldInfo().getRainTime()));
        sender.sendMessage(new TextComponentString("  §7info.thunderTime: §f"
                + world.getWorldInfo().getThunderTime()));
        sender.sendMessage(new TextComponentString("  §7info.blizzard: §f"
                + BlizzardReflection.isBlizzardInfo(world.getWorldInfo())));
        sender.sendMessage(new TextComponentString("  §7info.blizzardTime: §f"
                + BlizzardReflection.getBlizzardTimeInfo(world.getWorldInfo())));
        sender.sendMessage(new TextComponentString("  §7info.blizzardStrength: §f"
                + String.format("%.3f", BlizzardReflection.getBlizzardStrengthInfo(world.getWorldInfo()))));
    }

    /**
     * Tab-автодополнение для /weather.
     * Подсказываем только первый аргумент (подкоманду).
     *
     * @param server сервер;
     * @param sender тот, кто ввёл команду;
     * @param args   аргументы;
     * @param targetPos позиция таргета (для команд, работающих с блоками).
     * @return список подсказок.
     */
    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender,
                                          String[] args, @Nullable BlockPos targetPos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, STATES);
        }
        return Collections.emptyList();
    }
}