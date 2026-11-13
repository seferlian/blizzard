package com.blizzard.config;

import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.ConfigManager;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import com.blizzard.Tags;

/**
 * Конфигурация мода Blizzard.
 * Все параметры разбиты по категориям для удобства настройки.
 */
@Config(modid = Tags.MODID, name = "blizzard")
public class ConfigHandler {

    @Config.Name("Холод (Cold)")
    @Config.Comment("Настройки накопления холода и урона")
    public static final Cold cold = new Cold();

    @Config.Name("Тепло (Heat)")
    @Config.Comment("Настройки источников тепла")
    public static final Heat heat = new Heat();

    @Config.Name("Метель (Blizzard)")
    @Config.Comment("Настройки самой метели")
    public static final Blizzard blizzard = new Blizzard();

    @Config.Name("Визуал (Visual)")
    @Config.Comment("Настройки визуальных эффектов метели")
    public static final Visual visual = new Visual();

    public static class Cold {
        @Config.Name("Скорость накопления (за тик)")
        @Config.Comment({
                "Сколько холода прибавляется за 1 тик (20 тиков = 1 секунда).",
                "1.0 / 1200.0 = полное заполнение за 60 секунд (1 минуту).",
                "По умолчанию: 0.0008333"
        })
        @Config.RangeDouble(min = 0.0, max = 1.0)
        public double coldGainPerTick = 1.0 / 1200.0;

        @Config.Name("Скорость убывания (за тик)")
        @Config.Comment({
                "Сколько холода убавляется за 1 тик, если метели нет или игрок в укрытии.",
                "По умолчанию: 0.0008333 (сброс за 60 секунд)."
        })
        @Config.RangeDouble(min = 0.0, max = 1.0)
        public double drainPerTick = 1.0 / 1200.0;

        @Config.Name("Замедление от одной детали брони")
        @Config.Comment({
                "На сколько процентов (0.0 - 1.0) каждая надетя деталь брони замедляет накопление.",
                "0.15 = 15% за деталь. 4 детали = 60% замедления.",
                "0.25 = 25% за деталь. 4 детали = 100% защиты (холод не копится).",
                "По умолчанию: 0.15"
        })
        @Config.RangeDouble(min = 0.0, max = 1.0)
        public double armorSlotSlowdown = 0.15;

        @Config.Name("Множитель накопления холода в воде")
        @Config.Comment("По умолчанию: 2.0 (в 2 раза быстрее).")
        @Config.RangeDouble(min = 0.0, max = 10.0)
        public double waterMultiplier = 2.0;

        @Config.Name("Урон: интервал в воде (тиков)")
        @Config.Comment("Интервал между ударами, когда игрок находится в воде. По умолчанию: 10 (0.5 секунды)")
        @Config.RangeInt(min = 1, max = 200)
        public int intervalWater = 10;

        @Config.Name("Порог exposure")
        @Config.Comment({
                "Минимальная открытость (0.0 - 1.0), при которой начинает копиться холод.",
                "По умолчанию: 0.05 (5%)."
        })
        @Config.RangeDouble(min = 0.0, max = 1.0)
        public double exposureThreshold = 0.05;
    }

    public static class Heat {
        @Config.Name("Максимальный радиус тепла")
        @Config.Comment({
                "На каком расстоянии (в блоках) от игрока действует тепло от факелов, огня и лавы.",
                "По умолчанию: 5.0"
        })
        @Config.RangeDouble(min = 1.0, max = 16.0)
        public double maxHeatDistance = 5.0;

        @Config.Name("Сила факела")
        @Config.Comment("Сила тепла от факела. По умолчанию: 0.05")
        @Config.RangeDouble(min = 0.0, max = 1.0)
        public double heatTorch = 0.05;

        @Config.Name("Сила огня")
        @Config.Comment("Сила тепла от огня (костёр, горящий блок). По умолчанию: 0.15")
        @Config.RangeDouble(min = 0.0, max = 1.0)
        public double heatFire = 0.15;

        @Config.Name("Сила лавы")
        @Config.Comment("Сила тепла от лавы. По умолчанию: 0.25")
        @Config.RangeDouble(min = 0.0, max = 1.0)
        public double heatLava = 0.25;
    }

    public static class Blizzard {
        @Config.Name("Шанс авто-старта")
        @Config.Comment("Шанс (0.0 - 1.0) запуска метели при смене ванильной погоды. По умолчанию: 0.3")
        @Config.RangeDouble(min = 0.0, max = 1.0)
        public double blizzardChance = 0.3;

        @Config.Name("Мин. длительность (тиков)")
        @Config.Comment("Минимальная длительность авто-метели в тиках. По умолчанию: 24000 (20 минут)")
        @Config.RangeInt(min = 20, max = 1000000)
        public int minDuration = 20 * 60 * 20;

        @Config.Name("Макс. длительность (тиков)")
        @Config.Comment("Максимальная длительность авто-метели в тиках. По умолчанию: 36000 (30 минут)")
        @Config.RangeInt(min = 20, max = 1000000)
        public int maxDuration = 30 * 60 * 20;

        @Config.Name("Время нарастания (тиков)")
        @Config.Comment("За сколько тиков сила метели достигает 1.0. По умолчанию: 1200 (60 секунд)")
        @Config.RangeInt(min = 1, max = 100000)
        public int growTicks = 60 * 20;

        @Config.Name("Время затухания (тиков)")
        @Config.Comment("За сколько тиков сила метели падает до 0.0. По умолчанию: 200 (10 секунд)")
        @Config.RangeInt(min = 1, max = 100000)
        public int fadeTicks = 10 * 20;

        @Config.Name("Урон: старт (Cold)")
        @Config.Comment("При каком уровне холода начинается урон. По умолчанию: 0.2")
        @Config.RangeDouble(min = 0.0, max = 1.0)
        public double damageStartCold = 0.2;

        @Config.Name("Урон: конец (Cold)")
        @Config.Comment("При каком уровне холода достигается максимум урона. По умолчанию: 1.0")
        @Config.RangeDouble(min = 0.0, max = 1.0)
        public double damageEndCold = 1.0;

        @Config.Name("Урон: минимальный")
        @Config.Comment("Урон за удар при cold = damageStartCold. По умолчанию: 0.5")
        @Config.RangeDouble(min = 0.0, max = 100.0)
        public double damageMin = 0.5;

        @Config.Name("Урон: максимальный")
        @Config.Comment("Урон за удар при cold = damageEndCold. По умолчанию: 4.0")
        @Config.RangeDouble(min = 0.0, max = 100.0)
        public double damageMax = 4.0;

        @Config.Name("Урон: интервал макс (тиков)")
        @Config.Comment("Интервал между ударами при cold = damageStartCold. По умолчанию: 100 (5 секунд)")
        @Config.RangeInt(min = 1, max = 1000)
        public int intervalMax = 100;

        @Config.Name("Урон: интервал мин (тиков)")
        @Config.Comment("Интервал между ударами при cold = damageEndCold. По умолчанию: 10 (0.5 секунды)")
        @Config.RangeInt(min = 1, max = 1000)
        public int intervalMin = 10;
    }

    public static class Visual {
        // ===== Шейдер =====
        @Config.Name("Кривая тумана")
        @Config.Comment("1.0 — линейно, 0.8 — быстрее к белому, 1.2 — медленнее. По умолчанию: 1.0")
        @Config.RangeDouble(min = 0.1, max = 5.0)
        public double fogCurve = 1.0;

        @Config.Name("Плотность тумана в укрытии")
        @Config.Comment("Доля силы тумана, остающаяся в пещере (0.0 - 1.0). По умолчанию: 0.2")
        @Config.RangeDouble(min = 0.0, max = 1.0)
        public double fogShelterFloor = 0.2;

        @Config.Name("Начало тумана (мин)")
        @Config.Comment("fogStart при максимальной силе метели (близко). По умолчанию: 0.5")
        @Config.RangeDouble(min = 0.0, max = 64.0)
        public double fogStartMin = 0.5;

        @Config.Name("Начало тумана (макс)")
        @Config.Comment("fogStart при нулевой силе метели (далеко). По умолчанию: 3.0")
        @Config.RangeDouble(min = 0.0, max = 64.0)
        public double fogStartMax = 3.0;

        @Config.Name("Конец тумана (мин)")
        @Config.Comment("fogEnd при максимальной силе метели (близко). По умолчанию: 6.0")
        @Config.RangeDouble(min = 1.0, max = 512.0)
        public double fogEndMin = 6.0;

        @Config.Name("Конец тумана (макс)")
        @Config.Comment("fogEnd при нулевой силе метели (далеко). По умолчанию: 60.0")
        @Config.RangeDouble(min = 1.0, max = 512.0)
        public double fogEndMax = 60.0;

        @Config.Name("Белизна неба")
        @Config.Comment("Насколько сильно белит небо (0.0 - 1.0). По умолчанию: 0.2")
        @Config.RangeDouble(min = 0.0, max = 1.0)
        public double skyWhiten = 0.2;

        @Config.Name("Десатурация неба")
        @Config.Comment("Обесцвечивание неба (0.0 - 1.0). По умолчанию: 0.55")
        @Config.RangeDouble(min = 0.0, max = 1.0)
        public double skyDesaturate = 0.55;

        @Config.Name("Серость неба")
        @Config.Comment("Смещение неба к серому (0.0 - 1.0). По умолчанию: 0.4")
        @Config.RangeDouble(min = 0.0, max = 1.0)
        public double skyGray = 0.4;

        // ===== Снежинки =====
        @Config.Name("Радиус снежинок")
        @Config.Comment("Радиус спавна снежинок вокруг игрока в блоках. По умолчанию: 12")
        @Config.RangeInt(min = 1, max = 32)
        public int snowRadius = 12;

        @Config.Name("Квадов на блок")
        @Config.Comment("Сколько снежинок (квадов) спавнится на каждый блок. По умолчанию: 6")
        @Config.RangeInt(min = 1, max = 64)
        public int snowQuadsPerBlock = 6;

        @Config.Name("Размер снежинки")
        @Config.Comment("Базовый размер снежинки. По умолчанию: 0.087")
        @Config.RangeDouble(min = 0.001, max = 1.0)
        public double snowSize = 0.087;

        @Config.Name("Снос ветром")
        @Config.Comment("На сколько блоков сносит снежинку за цикл падения. По умолчанию: 6.0")
        @Config.RangeDouble(min = 0.0, max = 32.0)
        public double snowWindMultiplier = 6.0;
    }

    @Mod.EventBusSubscriber(modid = Tags.MODID)
    public static class EventHandler {
        @SubscribeEvent
        public static void onConfigChanged(ConfigChangedEvent.OnConfigChangedEvent event) {
            if (event.getModID().equals(Tags.MODID)) {
                ConfigManager.sync(Tags.MODID, net.minecraftforge.common.config.Config.Type.INSTANCE);
            }
        }
    }
}