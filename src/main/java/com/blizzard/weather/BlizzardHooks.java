package com.blizzard.weather;

import com.blizzard.raycast.BlizzardExposure;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.WorldInfo;

import java.util.Collections;
import java.util.Map;
import java.util.Random;
import java.util.WeakHashMap;

/**
 * Центральный класс логики метели.
 * Содержит все ASM-хуки и серверную логику:
 * - NBT-сохранение/загрузка (writeNBT/readNBT);
 * - тик метели (tickWeather) — плавное нарастание/затухание силы;
 * - запуск метели (startBlizzard);
 * - авто-старт при естественной смене ванильной погоды;
 * - восстановление состояния из NBT при загрузке мира (restoreIfNeeded).
 *
 * Object-перегрузки методов нужны потому, что BlizzardHooks ремапится в prod (Notch),
 * а CoreMod — нет. Дескрипторы вида (Ljava/lang/Object;...)V не ремапятся и линкуются всегда.
 */
public class BlizzardHooks {

    /** Миры, для которых уже выполнено восстановление из NBT. */
    private static final Map<World, Boolean> RESTORED_WORLDS =
            Collections.synchronizedMap(new WeakHashMap<World, Boolean>());

    /** Последнее известное состояние ванильного дождя (для детекта смены). */
    private static final Map<World, Boolean> LAST_RAINING =
            Collections.synchronizedMap(new WeakHashMap<World, Boolean>());

    /** Последнее известное состояние ванильной грозы (для детекта смены). */
    private static final Map<World, Boolean> LAST_THUNDERING =
            Collections.synchronizedMap(new WeakHashMap<World, Boolean>());

    /**
     * WorldInfo → World.
     * Нужно, чтобы в writeNBT получить World по info и синхронизировать
     * World → WorldInfo прямо перед записью в NBT.
     * WeakHashMap — чтобы миры не утекали.
     */
    private static final Map<WorldInfo, World> WORLD_BY_INFO =
            Collections.synchronizedMap(new WeakHashMap<WorldInfo, World>());

    /** Шанс запуска метели при естественной смене погоды. */
    private static final float BLIZZARD_CHANCE = 0.3F;

    /** Минимальная длительность авто-метели (10 минут). */
    private static final int MIN_DURATION = 10 * 60 * 20;

    /** Максимальная длительность авто-метели (30 минут). */
    private static final int MAX_DURATION = 30 * 60 * 20;

    /** Общий генератор случайных чисел. */
    private static final Random RANDOM = new Random();

    /** Нарастание силы: 60 секунд (1200 тиков) до полной. */
    private static final int GROW_TICKS = 60 * 20;

    /** Затухание силы: 10 секунд (200 тиков) до нуля. */
    private static final int FADE_TICKS = 10 * 20;

    /** Шаг нарастания силы за тик. */
    private static final float GROW_DELTA = 1.0F / GROW_TICKS;

    /** Шаг затухания силы за тик. */
    private static final float FADE_DELTA = 1.0F / FADE_TICKS;

    // ==================== Отладка ====================

    /** Включить отладочные сообщения в чат. */
    private static final boolean DEBUG_CHAT = false;

    /** Интервал отладочных сообщений в тиках (20 = раз в секунду). */
    private static final int DEBUG_INTERVAL = 20;

    /** Последний тик, когда отправляли отладку (по игроку). */
    private static final java.util.Map<java.util.UUID, Long> LAST_DEBUG =
            new java.util.HashMap<>();

    /** Отправляет игроку в чат проценты открытости. */
    private static void sendDebugToChat(net.minecraft.entity.player.EntityPlayerMP p,
                                        float sky, float open, int time, float strength) {
        if (!DEBUG_CHAT) return;
        long now = p.world.getTotalWorldTime();
        Long last = LAST_DEBUG.get(p.getUniqueID());
        if (last != null && now - last < DEBUG_INTERVAL) return;
        LAST_DEBUG.put(p.getUniqueID(), now);

        String msg = String.format(
                "§b[Blizzard] §fоткрыто §e%.0f%% §f| вверх §e%.0f%% §f| сила §e%.2f §f| таймер §e%d",
                open * 100f, sky * 100f, strength, time);

        p.sendMessage(new net.minecraft.util.text.TextComponentString(msg));
    }

    // ==================== NBT ====================

    /**
     * Вызывается из WorldInfo.updateTagCompound (через ASM) перед записью в NBT.
     * Синхронизирует World → WorldInfo, затем пишет поля в NBT.
     *
     * @param info объект WorldInfo, из которого берутся значения;
     * @param nbt  NBT-тег, в который пишутся поля.
     */
    public static void writeNBT(WorldInfo info, NBTTagCompound nbt) {
        // Ищем World по WorldInfo, чтобы синхронизировать актуальное состояние
        World world = WORLD_BY_INFO.get(info);
        if (world != null) {
            // Синхронизация World → WorldInfo прямо перед writeNBT.
            // Нужно, потому что tickWeather не всегда вызывается перед сохранением,
            // а WorldEvent.Save срабатывает уже ПОСЛЕ writeToNBT.
            BlizzardReflection.setBlizzardInfo(info, BlizzardReflection.isBlizzard(world));
            BlizzardReflection.setBlizzardTimeInfo(info, BlizzardReflection.getBlizzardTime(world));
            BlizzardReflection.setBlizzardStrengthInfo(info, BlizzardReflection.getBlizzardStrength(world));
        }

        // Пишем поля в NBT
        nbt.setBoolean("blizzard", BlizzardReflection.isBlizzardInfo(info));
        nbt.setInteger("blizzardTime", BlizzardReflection.getBlizzardTimeInfo(info));
        nbt.setFloat("blizzardStrength", BlizzardReflection.getBlizzardStrengthInfo(info));
    }

    /**
     * Вызывается из конструктора WorldInfo(NBTTagCompound) (через ASM).
     * Читает поля из NBT в WorldInfo.
     *
     * @param info объект WorldInfo, в который пишутся значения;
     * @param nbt  NBT-тег, из которого читаются поля.
     */
    public static void readNBT(WorldInfo info, NBTTagCompound nbt) {
        BlizzardReflection.setBlizzardInfo(info, nbt.getBoolean("blizzard"));
        BlizzardReflection.setBlizzardTimeInfo(info, nbt.getInteger("blizzardTime"));
        BlizzardReflection.setBlizzardStrengthInfo(info, nbt.getFloat("blizzardStrength"));
    }

    // ==================== Object-перегрузки для ASM ====================

    /** Object-обёртка writeNBT для ASM-хука. */
    public static void writeNBT(Object info, Object nbt) {
        if (!(info instanceof WorldInfo) || !(nbt instanceof NBTTagCompound)) return;
        writeNBT((WorldInfo) info, (NBTTagCompound) nbt);
    }

    /** Object-обёртка readNBT для ASM-хука. */
    public static void readNBT(Object info, Object nbt) {
        if (!(info instanceof WorldInfo) || !(nbt instanceof NBTTagCompound)) return;
        readNBT((WorldInfo) info, (NBTTagCompound) nbt);
    }

    /** Object-обёртка tickWeather для ASM-хука. */
    public static void tickWeather(Object world) {
        // Только сервер, только Overworld
        if (!(world instanceof World)) return;
        tickWeather((World) world);
    }

    /** Object-обёртка isBlizzardActive для ASM-хука. */
    public static boolean isBlizzardActive(Object world) {
        if (!(world instanceof World)) return false;
        return isBlizzardActive((World) world);
    }

    /** Object-обёртка copyFrom для ASM-хука в конструкторе копирования WorldInfo. */
    public static void copyFrom(Object dst, Object src) {
        if (!(dst instanceof WorldInfo) || !(src instanceof WorldInfo)) return;
        WorldInfo dstInfo = (WorldInfo) dst;
        WorldInfo srcInfo = (WorldInfo) src;
        BlizzardReflection.setBlizzardInfo(dstInfo, BlizzardReflection.isBlizzardInfo(srcInfo));
        BlizzardReflection.setBlizzardTimeInfo(dstInfo, BlizzardReflection.getBlizzardTimeInfo(srcInfo));
        BlizzardReflection.setBlizzardStrengthInfo(dstInfo, BlizzardReflection.getBlizzardStrengthInfo(srcInfo));
    }

    // ==================== Синхронизация состояния ====================

    /** Обновляет LAST_RAINING / LAST_THUNDERING по текущему WorldInfo. */
    public static void syncWeatherState(World world) {
        WorldInfo info = world.getWorldInfo();
        LAST_RAINING.put(world, info.isRaining());
        LAST_THUNDERING.put(world, info.isThundering());
    }

    /** Заполняет карту WorldInfo → World, чтобы writeNBT мог найти World по info. */
    public static void registerWorldInfo(World world) {
        if (world == null || world.isRemote) return;
        WorldInfo info = world.getWorldInfo();
        if (info != null) {
            WORLD_BY_INFO.put(info, world);
        }
    }

    // ==================== World Tick ====================

    /**
     * Вызывается каждый тик из World.updateWeatherBody (через ASM).
     * Сначала — тик метели (плавное нарастание/затухание, гашение дождя/грозы).
     * Если метель не активна — детект смены ванильной погоды и возможный авто-старт.
     *
     * Во время метели раз в тик обновляется экспозиция каждого игрока
     * и рассылается пакет:
     *   - сразу, если exposure изменился сильнее SEND_THRESHOLD;
     *   - иначе раз в 20 тиков для синхронизации таймера на клиенте.
     *
     * @param world серверный мир.
     */
    public static void tickWeather(World world) {
        // Только сервер, только Overworld
        if (world.isRemote) return;
        if (world.provider.getDimension() != 0) return;
        // Запоминаем связь WorldInfo → World для writeNBT
        registerWorldInfo(world);
        // Восстанавливаем метель из NBT (один раз после загрузки мира)
        restoreIfNeeded(world);

        // ===== Метель активна =====
        if (BlizzardReflection.isBlizzard(world)) {
            // Тикаем таймер метели
            int time = BlizzardReflection.getBlizzardTime(world) - 1;
            BlizzardReflection.setBlizzardTime(world, time);
            // Плавно двигаем силу: target = 1.0 пока time > 0, иначе 0.0
            float current = BlizzardReflection.getBlizzardStrength(world);
            float target  = (time > 0) ? 1.0F : 0.0F;

            if (current < target) current = Math.min(target, current + GROW_DELTA);
            else if (current > target) current = Math.max(target, current - FADE_DELTA);

            BlizzardReflection.setPrevBlizzardStrength(world, BlizzardReflection.getBlizzardStrength(world));
            BlizzardReflection.setBlizzardStrength(world, current);
            // Плавно гасим дождь/грозу, пока идёт метель
            world.prevRainingStrength     = world.rainingStrength;
            world.prevThunderingStrength  = world.thunderingStrength;
            world.rainingStrength    = Math.max(0.0F, world.rainingStrength    - FADE_DELTA * 2);
            world.thunderingStrength = Math.max(0.0F, world.thunderingStrength - FADE_DELTA * 2);

            // ===== Экспозиция + адресная рассылка =====
            if (world instanceof WorldServer) {
                WorldServer ws = (WorldServer) world;
                long now = ws.getTotalWorldTime();

                for (EntityPlayer p : ws.playerEntities) {
                    if (!(p instanceof EntityPlayerMP)) continue;
                    EntityPlayerMP mp = (EntityPlayerMP) p;
                    if (mp.isSpectator() || mp.isDead) continue;

                    BlizzardExposure.tick(mp, ws);
                    BlizzardExposure.Entry entry = BlizzardExposure.getEntry(mp);

                    float sky  = BlizzardExposure.getSkyExposure(mp);
                    float open = BlizzardExposure.getOpenness(mp);

                    // Exposure изменился сильнее порога — шлём сразу.
                    // Иначе — раз в 20 тиков, чтобы клиент обновлял таймер.
                    boolean exposureChanged =
                            entry.lastSentSky < 0f
                                    || Math.abs(sky  - entry.lastSentSky)  > BlizzardExposure.SEND_THRESHOLD
                                    || Math.abs(open - entry.lastSentOpen) > BlizzardExposure.SEND_THRESHOLD;

                    boolean timerSync = now - entry.lastSentTick >= 20;

                    if (exposureChanged || timerSync) {
                        BlizzardManager.NETWORK.sendTo(
                                new PacketBlizzard(true, time, current, sky, open), mp);

                        entry.lastSentSky  = sky;
                        entry.lastSentOpen = open;
                        entry.lastSentTick = now;
                    }

                    // ===== Отладка в чат раз в секунду =====
                    sendDebugToChat(mp, sky, open, time, current);
                }
            }

            // ===== Конец метели =====
            if (time <= 0 && current <= 0.0F) {
                BlizzardReflection.setBlizzard(world, false, 0);
                if (world instanceof WorldServer) {
                    WorldServer ws = (WorldServer) world;
                    for (EntityPlayer p : ws.playerEntities) {
                        if (!(p instanceof EntityPlayerMP)) continue;
                        BlizzardManager.NETWORK.sendTo(
                                new PacketBlizzard(false, 0, 0.0F, 1f, 1f),
                                (EntityPlayerMP) p);
                    }
                }
                BlizzardExposure.clearAll();
                LAST_DEBUG.clear();
            }
            return;
        }

        // ===== Метель не активна — детект смены ванильной погоды =====
        WorldInfo info = world.getWorldInfo();
        boolean currentRaining = info.isRaining();
        boolean currentThundering = info.isThundering();

        Boolean lastRainingObj = LAST_RAINING.get(world);
        Boolean lastThunderingObj = LAST_THUNDERING.get(world);
        boolean lastRaining = lastRainingObj != null && lastRainingObj;
        boolean lastThundering = lastThunderingObj != null && lastThunderingObj;

        boolean rainingChanged = currentRaining != lastRaining;
        boolean thunderingChanged = currentThundering != lastThundering;

        LAST_RAINING.put(world, currentRaining);
        LAST_THUNDERING.put(world, currentThundering);

        // Если смена — проверяем, разрешён ли запуск метели
        if (rainingChanged || thunderingChanged) {
            if (shouldAllowBlizzard(lastRaining, lastThundering, currentRaining, currentThundering)) {
                if (RANDOM.nextFloat() < BLIZZARD_CHANCE) {
                    int duration = MIN_DURATION + RANDOM.nextInt(MAX_DURATION - MIN_DURATION + 1);
                    startBlizzard(world, duration, "weather change");
                }
            }
        }
    }

    /**
     * Запускает метель. Сбрасывает ванильную погоду и синхронизирует WorldInfo.
     * Если метель уже шла — сохраняет текущий strength (не сбрасывает в 0).
     *
     * При старте кэш экспозиции и отладочный таймер очищаются, чтобы
     * следующий тик tickWeather точно разослал пакеты каждому игроку.
     *
     * @param world    мир, в котором запускается метель;
     * @param duration длительность в тиках;
     * @param reason   причина запуска (для лога).
     */
    public static void startBlizzard(World world, int duration, String reason) {
        registerWorldInfo(world);
        BlizzardExposure.clearAll();     // сброс кэша экспозиции
        LAST_DEBUG.clear();              // сброс отладочного таймера

        boolean wasBlizzard = BlizzardReflection.isBlizzard(world);
        BlizzardReflection.setBlizzard(world, true, duration);
        if (!wasBlizzard) {
            // Новая метель — сила с 0, плавно растёт
            BlizzardReflection.setBlizzardStrength(world, 0.0F);
            BlizzardReflection.setPrevBlizzardStrength(world, 0.0F);
        }
        // Если метель уже шла — strength остаётся, только blizzardTime сбрасывается

        WorldInfo info = world.getWorldInfo();
        if (info != null) {
            BlizzardReflection.setBlizzardInfo(info, true);
            BlizzardReflection.setBlizzardTimeInfo(info, duration);
            BlizzardReflection.setBlizzardStrengthInfo(info, BlizzardReflection.getBlizzardStrength(world));
        }

        // Первичная рассылка — с дефолтными exposure (1/1),
        // реальные значения придут в следующем тике tickWeather.
        // Обновляем lastSent*, чтобы первый tickWeather не дублировал пакет.
        if (world instanceof WorldServer) {
            WorldServer ws = (WorldServer) world;
            long now = ws.getTotalWorldTime();
            float strength = BlizzardReflection.getBlizzardStrength(world);

            for (EntityPlayer p : ws.playerEntities) {
                if (!(p instanceof EntityPlayerMP)) continue;
                EntityPlayerMP mp = (EntityPlayerMP) p;

                BlizzardManager.NETWORK.sendTo(
                        new PacketBlizzard(true, duration, strength, 1f, 1f), mp);

                BlizzardExposure.Entry e = BlizzardExposure.getEntry(mp);
                e.lastSentSky  = 1f;
                e.lastSentOpen = 1f;
                e.lastSentTick = now;
            }
        }

        // Гасим ванильную погоду
        if (info != null) {
            info.setRaining(false);
            info.setThundering(false);
        }

        // Обновляем LAST, чтобы детектор не запустил метель повторно
        LAST_RAINING.put(world, false);
        LAST_THUNDERING.put(world, false);

        System.out.println("[Blizzard] Storm started (" + reason + "): " + duration + " ticks.");
    }


    /**
     * Проверяет, разрешён ли запуск метели при данной смене погоды.
     * Запрещено: rain → thunder, thunder → rain.
     * Разрешено: clear → rain, rain → clear, clear → thunder, thunder → clear.
     *
     * @return true, если метель может запуститься при этой смене.
     */
    private static boolean shouldAllowBlizzard(boolean lastRaining, boolean lastThundering,
                                               boolean currentRaining, boolean currentThundering) {
        if (lastRaining && !lastThundering && currentRaining && currentThundering) return false; // rain -> thunder
        if (lastRaining && lastThundering && currentRaining && !currentThundering) return false; // thunder -> rain
        return true;
    }

    /**
     * Восстанавливает метель из WorldInfo при первом тике после загрузки мира.
     * Срабатывает один раз для каждого World.
     *
     * Exposure считается сразу для каждого игрока и отправляется адресно,
     * чтобы клиент не ждал следующего тика tickWeather.
     *
     * @param world серверный мир.
     */
    private static void restoreIfNeeded(World world) {
        if (RESTORED_WORLDS.containsKey(world)) return;
        RESTORED_WORLDS.put(world, Boolean.TRUE);

        try {
            WorldInfo info = world.getWorldInfo();
            if (info == null) return;

            // Инициализируем LAST_* по текущему состоянию, чтобы первый тик не считался сменой
            LAST_RAINING.put(world, info.isRaining());
            LAST_THUNDERING.put(world, info.isThundering());

            boolean infoBlizzard = BlizzardReflection.isBlizzardInfo(info);
            int infoTime = BlizzardReflection.getBlizzardTimeInfo(info);
            float infoStrength = BlizzardReflection.getBlizzardStrengthInfo(info);

            // Восстанавливаем, если метель активна ИЛИ ещё идёт затухание (time=0, strength>0)
            if (infoBlizzard && (infoTime > 0 || infoStrength > 0.01F)) {
                BlizzardReflection.setBlizzard(world, true, infoTime);
                BlizzardReflection.setBlizzardStrength(world, infoStrength);
                BlizzardReflection.setPrevBlizzardStrength(world, infoStrength);

                // Адресная рассылка с посчитанной экспозицией.
                // WorldServer.playerEntities имеет тип List<EntityPlayer>, поэтому
                // фильтруем через instanceof — на сервере в списке только EntityPlayerMP.
                if (world instanceof WorldServer) {
                    WorldServer ws = (WorldServer) world;
                    long now = ws.getTotalWorldTime();

                    for (EntityPlayer p : ws.playerEntities) {
                        if (!(p instanceof EntityPlayerMP)) continue;
                        EntityPlayerMP mp = (EntityPlayerMP) p;
                        if (mp.isSpectator() || mp.isDead) continue;

                        BlizzardExposure.tick(mp, ws);
                        BlizzardExposure.Entry e = BlizzardExposure.getEntry(mp);

                        float sky  = BlizzardExposure.getSkyExposure(mp);
                        float open = BlizzardExposure.getOpenness(mp);

                        BlizzardManager.NETWORK.sendTo(
                                new PacketBlizzard(true, infoTime, infoStrength, sky, open), mp);

                        e.lastSentSky  = sky;
                        e.lastSentOpen = open;
                        e.lastSentTick = now;
                    }
                }

                System.out.println("[Blizzard] Restored blizzard from NBT: "
                        + infoTime + " ticks, strength " + infoStrength);
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    /** Хук для ASM: возвращает true, если метель активна. */
    public static boolean isBlizzardActive(World world) {
        return BlizzardReflection.isBlizzard(world);
    }


    public static void clearDebugFor(java.util.UUID id) {
        LAST_DEBUG.remove(id);
    }
}