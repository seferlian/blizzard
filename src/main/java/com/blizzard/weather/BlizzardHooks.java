package com.blizzard.weather;

import com.blizzard.client.BlizzardShader;
import com.blizzard.config.ConfigHandler;
import com.blizzard.network.client.PacketBlizzard;
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
    private static float getBlizzardChance() { return (float) ConfigHandler.blizzard.blizzardChance; }

    /** Минимальная длительность авто-метели (10 минут). */
    private static int getMinDuration() { return ConfigHandler.blizzard.minDuration; }

    /** Максимальная длительность авто-метели (30 минут). */
    private static int getMaxDuration() { return ConfigHandler.blizzard.maxDuration; }

    /** Общий генератор случайных чисел. */
    private static final Random RANDOM = new Random();

    /** Нарастание силы: 60 секунд (1200 тиков) до полной. */
    private static final int GROW_TICKS = 60 * 20;

    /** Затухание силы: 10 секунд (200 тиков) до нуля. */
    private static final int FADE_TICKS = 10 * 20;

    /** Шаг нарастания силы за тик. */
    private static float getGrowDelta() { return 1.0F / ConfigHandler.blizzard.growTicks; }

    /** Шаг затухания силы за тик. */
    private static float getFadeDelta() { return 1.0F / ConfigHandler.blizzard.fadeTicks; }

    // ==================== Отладка ====================

    /** Включить отладочные сообщения в чат. */
    private static final boolean DEBUG_CHAT = false;

    /** Интервал отладочных сообщений в тиках (20 = раз в секунду). */
    private static final int DEBUG_INTERVAL = 20;

    /** Последний тик, когда отправляли отладку (по игроку). */
    private static final java.util.Map<java.util.UUID, Long> LAST_DEBUG =
            new java.util.HashMap<>();

    // ==================== HUD ====================

    /** Верхняя граница правой колонки HUD (голод/воздух) в экранных координатах. */
    private static int hudRightTopY = 0;

    /** Возвращает верхнюю границу правой колонки HUD. */
    public static int getHudRightTopY() {
        return hudRightTopY;
    }

    /**
     * Вызывается из ASM-хука в конец GuiIngameForge.renderAir(int width, int height).
     * Сохраняет верхнюю границу правой колонки HUD для HUD-оверлея холода.
     *
     * @param width  ширина экрана (scaled);
     * @param height высота экрана (scaled).
     */
    public static void onRenderAirHud(int width, int height) {
        hudRightTopY = height - net.minecraftforge.client.GuiIngameForge.right_height;
    }



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
     * и рассылается пакет. Пакет отправляется КАЖДЫЙ тик, так как
     * BlizzardExposure плавно сглаживает sky и open каждый тик (фиксированным шагом).
     * Это гарантирует, что клиент получит идеально плавный поток данных.
     *
     * @param world серверный мир.
     */
    public static void tickWeather(World world) {
        // Только сервер
        if (world.isRemote) return;

        boolean isOverworld = world.provider.getDimension() == 0;

        // Запоминаем связь WorldInfo → World для writeNBT
        registerWorldInfo(world);

        // Восстанавливаем метель из NBT (один раз для каждого World)
        restoreIfNeeded(world);

        // ===== Метель активна =====
        // Читаем состояние из Overworld (где метель тикает).
        // WorldInfo может быть DerivedWorldInfo (обёрткой), которая не хранит
        // поле blizzard — поэтому читать WorldInfo нельзя.
        World overworld = isOverworld
                ? world
                : (world.getMinecraftServer() != null ? world.getMinecraftServer().getWorld(0) : null);

        boolean blizzardActive = overworld != null && BlizzardReflection.isBlizzard(overworld);

        if (blizzardActive) {
            int time;
            float current;

            // ---- Тикаем таймер и силу ТОЛЬКО в Overworld ----
            if (isOverworld) {
                boolean doWeatherCycle = world.getGameRules().getBoolean("doWeatherCycle");

                if (doWeatherCycle) {
                    time = BlizzardReflection.getBlizzardTime(world) - 1;
                    BlizzardReflection.setBlizzardTime(world, time);
                } else {
                    time = BlizzardReflection.getBlizzardTime(world);
                }

                current = BlizzardReflection.getBlizzardStrength(world);
                float target = (time > 0) ? 1.0F : 0.0F;

                if (current < target) current = Math.min(target, current + getGrowDelta());
                else if (current > target) current = Math.max(target, current - getFadeDelta());

                BlizzardReflection.setPrevBlizzardStrength(world, BlizzardReflection.getBlizzardStrength(world));
                BlizzardReflection.setBlizzardStrength(world, current);

                // Синхронизируем WorldInfo (для NBT)
                WorldInfo infoSync = world.getWorldInfo();
                if (infoSync != null) {
                    BlizzardReflection.setBlizzardInfo(infoSync, true);
                    BlizzardReflection.setBlizzardTimeInfo(infoSync, time);
                    BlizzardReflection.setBlizzardStrengthInfo(infoSync, current);
                }

                // ===== Конец метели =====
                if (time <= 0 && current <= 0.0F) {
                    BlizzardReflection.setBlizzard(world, false, 0);

                    WorldInfo infoEnd = world.getWorldInfo();
                    if (infoEnd != null) {
                        BlizzardReflection.setBlizzardInfo(infoEnd, false);
                        BlizzardReflection.setBlizzardTimeInfo(infoEnd, 0);
                        BlizzardReflection.setBlizzardStrengthInfo(infoEnd, 0.0F);
                    }

                    for (WorldServer ws : world.getMinecraftServer().worlds) {
                        for (EntityPlayer p : ws.playerEntities) {
                            if (p instanceof EntityPlayerMP) {
                                BlizzardManager.NETWORK.sendTo(
                                        new PacketBlizzard(false, 0, 0.0F, 1f, 1f),
                                        (EntityPlayerMP) p);
                            }
                        }
                    }

                    BlizzardExposure.clearAll();
                    LAST_DEBUG.clear();
                    return;
                }
            } else {
                // В не-Overworld читаем World Overworld
                time = BlizzardReflection.getBlizzardTime(overworld);
                current = BlizzardReflection.getBlizzardStrength(overworld);
            }

            // Плавно гасим ванильный дождь/грозу во ВСЕХ измерениях
            world.prevRainingStrength    = world.rainingStrength;
            world.prevThunderingStrength = world.thunderingStrength;
            world.rainingStrength    = Math.max(0.0F, world.rainingStrength    - getFadeDelta() * 2);
            world.thunderingStrength = Math.max(0.0F, world.thunderingStrength - getFadeDelta() * 2);

            // ---- Эффекты (экспозиция + пакеты) ----
            if (world instanceof WorldServer && isDimensionAffected(world.provider.getDimension())) {
                WorldServer ws = (WorldServer) world;
                long now = ws.getTotalWorldTime();

                for (EntityPlayer p : ws.playerEntities) {
                    if (!(p instanceof EntityPlayerMP)) continue;
                    EntityPlayerMP mp = (EntityPlayerMP) p;
                    if (mp.isSpectator() || mp.isDead) continue;

                    BlizzardExposure.tick(mp, ws);

                    float sky  = BlizzardExposure.getSkyExposure(mp);
                    float open = BlizzardExposure.getOpenness(mp);

                    BlizzardExposure.Entry entry = BlizzardExposure.getEntry(mp);

                    boolean timeToSend = (now % 2 == 0);
                    boolean bigChange =
                            Math.abs(sky  - entry.lastSentSky)  > 0.05f ||
                                    Math.abs(open - entry.lastSentOpen) > 0.05f;
                    boolean stale = (now - entry.lastSentTick) >= 10;

                    if (timeToSend || bigChange || stale) {
                        BlizzardManager.NETWORK.sendTo(
                                new PacketBlizzard(true, time, current, sky, open), mp);

                        entry.lastSentSky  = sky;
                        entry.lastSentOpen = open;
                        entry.lastSentTick = now;
                    }
                }

                BlizzardSnowAccumulator.tick(ws, current);
            }
            return;
        }

        // ===== Метель не активна — детект смены погоды (только в Overworld) =====
        if (!isOverworld) return;

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

        if (rainingChanged || thunderingChanged) {
            if (shouldAllowBlizzard(lastRaining, lastThundering, currentRaining, currentThundering)) {
                if (RANDOM.nextFloat() < getBlizzardChance()) {
                    int duration = getMinDuration() + RANDOM.nextInt(getMaxDuration() - getMinDuration() + 1);
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

        // Первичная рассылка — только в разрешённые измерения.
        // В запрещённых измерениях клиент не должен получить active=true,
        // иначе нарисует метель, которой там быть не должно.
        if (world.getMinecraftServer() != null) {
            float strength = BlizzardReflection.getBlizzardStrength(world);

            for (WorldServer ws : world.getMinecraftServer().worlds) {
                if (!isDimensionAffected(ws.provider.getDimension())) continue;

                long now = ws.getTotalWorldTime();

                for (EntityPlayer p : ws.playerEntities) {
                    if (!(p instanceof EntityPlayerMP)) continue;
                    EntityPlayerMP mp = (EntityPlayerMP) p;
                    if (mp.isSpectator() || mp.isDead) continue;

                    BlizzardManager.NETWORK.sendTo(
                            new PacketBlizzard(true, duration, strength, 1f, 1f), mp);

                    BlizzardExposure.Entry e = BlizzardExposure.getEntry(mp);
                    e.lastSentSky  = 1f;
                    e.lastSentOpen = 1f;
                    e.lastSentTick = now;
                }
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
            boolean isOverworld = world.provider.getDimension() == 0;

            // Для Overworld: восстанавливаем World из WorldInfo (NBT)
            if (isOverworld) {
                WorldInfo info = world.getWorldInfo();
                if (info != null) {
                    boolean infoBlizzard = BlizzardReflection.isBlizzardInfo(info);
                    int infoTime = BlizzardReflection.getBlizzardTimeInfo(info);
                    float infoStrength = BlizzardReflection.getBlizzardStrengthInfo(info);

                    // Восстанавливаем поле World из WorldInfo
                    if (infoBlizzard && (infoTime > 0 || infoStrength > 0.01F)) {
                        BlizzardReflection.setBlizzard(world, true, infoTime);
                        BlizzardReflection.setBlizzardStrength(world, infoStrength);
                        BlizzardReflection.setPrevBlizzardStrength(world, infoStrength);

                        System.out.println("[Blizzard] Restored World from WorldInfo: "
                                + infoTime + " ticks, strength " + infoStrength);
                    }

                    // Инициализируем LAST_* по текущему состоянию
                    LAST_RAINING.put(world, info.isRaining());
                    LAST_THUNDERING.put(world, info.isThundering());
                }
            }

            // Читаем состояние из Overworld (теперь World восстановлен)
            World overworld = isOverworld
                    ? world
                    : (world.getMinecraftServer() != null ? world.getMinecraftServer().getWorld(0) : null);

            if (overworld == null) return;

            boolean infoBlizzard = BlizzardReflection.isBlizzard(overworld);
            int infoTime = BlizzardReflection.getBlizzardTime(overworld);
            float infoStrength = BlizzardReflection.getBlizzardStrength(overworld);

            // Рассылка пакетов — для всех измерений с эффектами
            if (infoBlizzard && (infoTime > 0 || infoStrength > 0.01F)) {
                if (world.getMinecraftServer() != null) {
                    for (WorldServer ws : world.getMinecraftServer().worlds) {
                        if (!isDimensionAffected(ws.provider.getDimension())) continue;

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
                }

            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    /** Хук для ASM: возвращает true, если метель активна. */
    public static boolean isBlizzardActive(World world) {
        World overworld = world.provider.getDimension() == 0
                ? world
                : (world.getMinecraftServer() != null ? world.getMinecraftServer().getWorld(0) : null);
        return overworld != null && BlizzardReflection.isBlizzard(overworld);
    }






    public static void clearDebugFor(java.util.UUID id) {
        LAST_DEBUG.remove(id);
    }


    /**
     * Вызывается ASM-хуком из FinalPassRenderer.renderFinalPass().
     * Принимает RenderTargets, чтобы достать depth-текстуру Pintonium.
     */
    public static void onIrisFinalPass(Object renderTargets) {
        int depthTexId = -1;

        try {
            // Пытаемся получить getDepthTextureNoTranslucents
            java.lang.reflect.Method m = renderTargets.getClass().getMethod("getDepthTextureNoTranslucents");
            Object noTranslucents = m.invoke(renderTargets);

            if (noTranslucents != null) {
                // Пытаемся получить getTextureId
                try {
                    java.lang.reflect.Method getTextureId = noTranslucents.getClass().getMethod("getTextureId");
                    depthTexId = (int) getTextureId.invoke(noTranslucents);
                } catch (NoSuchMethodException e) {
                    System.out.println("[Blizzard] DepthTexture has no getTextureId() method");
                }
            } else {
                System.out.println("[Blizzard] getDepthTextureNoTranslucents() returned null");
            }
        } catch (Throwable t) {
            System.out.println("[Blizzard] Failed to get depth texture: " + t.getMessage());
            t.printStackTrace();
        }

        BlizzardShader.renderFromPintonium(depthTexId);
    }

    /**
     * Проверяет, применяются ли эффекты метели в данном измерении.
     * Пустой список effectDimensions = эффекты везде (обратная совместимость).
     *
     * @param dimensionId ID измерения.
     * @return true, если эффекты применяются.
     */
    public static boolean isDimensionAffected(int dimensionId) {
        int[] dims = ConfigHandler.blizzard.effectDimensions;
        if (dims == null || dims.length == 0) return true;
        for (int id : dims) {
            if (id == dimensionId) return true;
        }
        return false;
    }

}