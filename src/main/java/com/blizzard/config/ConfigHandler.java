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
    @Config.Comment("Накопление и убывание холода, защита бронёй, урон в воде")
    public static final Cold cold = new Cold();

    @Config.Name("Тепло (Heat)")
    @Config.Comment("Источники тепла и их сила")
    public static final Heat heat = new Heat();

    @Config.Name("Метель (Blizzard)")
    @Config.Comment("Состояние метели и урон от неё")
    public static final Blizzard blizzard = new Blizzard();

    @Config.Name("Визуал (Visual)")
    @Config.Comment("Туман, небо и снежинки")
    public static final Visual visual = new Visual();

    @Config.Name("Снег (Snow)")
    @Config.Comment("Генерация снежного покрова и льда при прогрузке чанков")
    public static final Snow snow = new Snow();

    public static class Cold {
        @Config.Name("Скорость накопления (за тик)")
        @Config.Comment({
                "Сколько холода прибавляется за 1 тик (20 тиков = 1 секунда).",
                "Шкала холода: от 0.0 до 1.0.",
                "1.0 / 1200.0 = полное заполнение за 60 секунд (1 минуту).",
                "По умолчанию: 1/1200 (0.0008333)"
        })
        @Config.RangeDouble(min = 0.0, max = 1.0)
        public double coldGainPerTick = 1.0 / 1200.0;

        @Config.Name("Скорость убывания (за тик)")
        @Config.Comment({
                "Сколько холода убавляется за 1 тик, если метели нет или игрок в укрытии.",
                "1.0 / 1200.0 — полный сброс за 60 секунд.",
                "По умолчанию: 1/1200 (0.0008333)"
        })
        @Config.RangeDouble(min = 0.0, max = 1.0)
        public double drainPerTick = 1.0 / 1200.0;

        @Config.Name("Замедление от одной детали брони")
        @Config.Comment({
                "На какую долю (0.0 - 1.0) каждая надетя деталь брони замедляет накопление холода.",
                "0.15 = 15% за деталь; 4 детали = 60% замедления.",
                "0.25 = 25% за деталь; 4 детали = 100% защиты (холод не копится).",
                "По умолчанию: 0.15"
        })
        @Config.RangeDouble(min = 0.0, max = 1.0)
        public double armorSlotSlowdown = 0.15;

        @Config.Name("Множитель накопления холода в воде")
        @Config.Comment({
                "Во сколько раз быстрее копится холод, если игрок в воде.",
                "По умолчанию: 2.0 (в 2 раза быстрее)"
        })
        @Config.RangeDouble(min = 0.0, max = 10.0)
        public double waterMultiplier = 2.0;

        @Config.Name("Урон: интервал в воде (тиков)")
        @Config.Comment({
                "Интервал между ударами, когда игрок находится в воде.",
                "По умолчанию: 10 (0.5 секунды)"
        })
        @Config.RangeInt(min = 1, max = 200)
        public int intervalWater = 10;

        @Config.Name("Порог открытости (exposure)")
        @Config.Comment({
                "Минимальная открытость (0.0 - 1.0), при которой холод начинает копиться.",
                "По умолчанию: 0.05 (5%)"
        })
        @Config.RangeDouble(min = 0.0, max = 1.0)
        public double exposureThreshold = 0.05;
    }

    public static class Heat {
        @Config.Name("Максимальный радиус тепла")
        @Config.Comment({
                "Максимальное расстояние (в блоках) от игрока, на котором учитываются",
                "источники тепла (факелы, огонь, лава).",
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

        @Config.Name("Измерения с эффектами метели")
        @Config.Comment({
                "ID измерений, в которых применяются визуальные и геймплейные эффекты метели.",
                "0 = Overworld, -1 = Nether, 1 = End; у модовых измерений — их собственный ID.",
                "Сама метель (состояние погоды) рассчитывается во всех измерениях;",
                "там, где эффекты не применяются, погода считается ясной.",
                "Пустой список = эффекты применяются везде.",
                "По умолчанию: [0] (только Overworld)"
        })
        public int[] effectDimensions = new int[] { 0 };

        @Config.Name("Шанс авто-старта")
        @Config.Comment({
                "Шанс (0.0 - 1.0) запуска метели при смене ванильной погоды.",
                "По умолчанию: 0.3 (30%)"
        })
        @Config.RangeDouble(min = 0.0, max = 1.0)
        public double blizzardChance = 0.3;

        @Config.Name("Мин. длительность (тиков)")
        @Config.Comment({
                "Минимальная длительность авто-метели.",
                "По умолчанию: 24000 (20 минут)"
        })
        @Config.RangeInt(min = 20, max = 1000000)
        public int minDuration = 20 * 60 * 20;

        @Config.Name("Макс. длительность (тиков)")
        @Config.Comment({
                "Максимальная длительность авто-метели.",
                "По умолчанию: 36000 (30 минут)"
        })
        @Config.RangeInt(min = 20, max = 1000000)
        public int maxDuration = 30 * 60 * 20;

        @Config.Name("Время нарастания (тиков)")
        @Config.Comment({
                "За сколько тиков сила метели нарастает от 0.0 до 1.0.",
                "По умолчанию: 1200 (60 секунд)"
        })
        @Config.RangeInt(min = 1, max = 100000)
        public int growTicks = 60 * 20;

        @Config.Name("Время затухания (тиков)")
        @Config.Comment({
                "За сколько тиков сила метели падает от 1.0 до 0.0.",
                "По умолчанию: 200 (10 секунд)"
        })
        @Config.RangeInt(min = 1, max = 100000)
        public int fadeTicks = 10 * 20;

        @Config.Name("Урон: старт (Cold)")
        @Config.Comment({
                "Уровень холода (0.0 - 1.0), при котором начинается урон.",
                "По умолчанию: 0.2"
        })
        @Config.RangeDouble(min = 0.0, max = 1.0)
        public double damageStartCold = 0.2;

        @Config.Name("Урон: конец (Cold)")
        @Config.Comment({
                "Уровень холода (0.0 - 1.0), при котором урон достигает максимума.",
                "По умолчанию: 1.0"
        })
        @Config.RangeDouble(min = 0.0, max = 1.0)
        public double damageEndCold = 1.0;

        @Config.Name("Урон: минимальный")
        @Config.Comment({
                "Урон за один удар при холоде = уровню из \"Урон: старт\".",
                "По умолчанию: 0.5 (полсердца)"
        })
        @Config.RangeDouble(min = 0.0, max = 100.0)
        public double damageMin = 0.5;

        @Config.Name("Урон: максимальный")
        @Config.Comment({
                "Урон за один удар при холоде = уровню из \"Урон: конец\".",
                "По умолчанию: 4.0 (2 сердца)"
        })
        @Config.RangeDouble(min = 0.0, max = 100.0)
        public double damageMax = 4.0;

        @Config.Name("Урон: интервал макс (тиков)")
        @Config.Comment({
                "Интервал между ударами при холоде = уровню из \"Урон: старт\" (урон ещё слабый).",
                "По умолчанию: 100 (5 секунд)"
        })
        @Config.RangeInt(min = 1, max = 1000)
        public int intervalMax = 100;

        @Config.Name("Урон: интервал мин (тиков)")
        @Config.Comment({
                "Интервал между ударами при холоде = уровню из \"Урон: конец\" (урон максимальный).",
                "По умолчанию: 10 (0.5 секунды)"
        })
        @Config.RangeInt(min = 1, max = 1000)
        public int intervalMin = 10;
    }

    public static class Visual {
        // ===== Шейдер =====
        @Config.Name("Кривая тумана")
        @Config.Comment({
                "Степень кривой, по которой сила тумана зависит от силы метели.",
                "1.0 — линейно, 0.8 — быстрее к густому туману, 1.2 — медленнее.",
                "По умолчанию: 1.0"
        })
        @Config.RangeDouble(min = 0.1, max = 5.0)
        public double fogCurve = 1.0;

        @Config.Name("Плотность тумана в укрытии")
        @Config.Comment({
                "Доля силы тумана, остающаяся при нахождении в укрытии (пещера, дом).",
                "0.0 — в укрытии тумана нет, 1.0 — укрытие не влияет.",
                "По умолчанию: 0.2"
        })
        @Config.RangeDouble(min = 0.0, max = 1.0)
        public double fogShelterFloor = 0.2;

        @Config.Name("Начало тумана (мин)")
        @Config.Comment({
                "Начало тумана (fogStart) в блоках при максимальной силе метели (ближняя граница).",
                "По умолчанию: 0.5"
        })
        @Config.RangeDouble(min = 0.0, max = 64.0)
        public double fogStartMin = 0.5;

        @Config.Name("Начало тумана (макс)")
        @Config.Comment({
                "Начало тумана (fogStart) в блоках при нулевой силе метели (дальняя граница).",
                "По умолчанию: 3.0"
        })
        @Config.RangeDouble(min = 0.0, max = 64.0)
        public double fogStartMax = 3.0;

        @Config.Name("Конец тумана (мин)")
        @Config.Comment({
                "Конец тумана (fogEnd) в блоках при максимальной силе метели (ближняя граница).",
                "По умолчанию: 6.0"
        })
        @Config.RangeDouble(min = 1.0, max = 512.0)
        public double fogEndMin = 6.0;

        @Config.Name("Конец тумана (макс)")
        @Config.Comment({
                "Конец тумана (fogEnd) в блоках при нулевой силе метели (дальняя граница).",
                "По умолчанию: 60.0"
        })
        @Config.RangeDouble(min = 1.0, max = 512.0)
        public double fogEndMax = 60.0;

        @Config.Name("Белизна неба")
        @Config.Comment({
                "Насколько сильно небо высветляется к белому (0.0 - 1.0).",
                "По умолчанию: 0.2"
        })
        @Config.RangeDouble(min = 0.0, max = 1.0)
        public double skyWhiten = 0.2;

        @Config.Name("Десатурация неба")
        @Config.Comment({
                "Насколько сильно обесцвечивается небо (0.0 - 1.0).",
                "По умолчанию: 0.55"
        })
        @Config.RangeDouble(min = 0.0, max = 1.0)
        public double skyDesaturate = 0.55;

        @Config.Name("Серость неба")
        @Config.Comment({
                "Насколько небо смещается к серому (0.0 - 1.0).",
                "По умолчанию: 0.4"
        })
        @Config.RangeDouble(min = 0.0, max = 1.0)
        public double skyGray = 0.4;

        @Config.Name("Небо: конец интерполяции")
        @Config.Comment({
                "Доля дальности прорисовки (projFar), на которой смешивание с небом достигает 1.0 (0.0 - 1.0).",
                "По умолчанию: 0.8"
        })
        @Config.RangeDouble(min = 0.0, max = 1.0)
        public double skyInterpEnd = 0.8;

        @Config.Name("Небо: кривая интерполяции")
        @Config.Comment({
                "Форма кривой смешивания с небом.",
                "1.0 — линейно, <1.0 — быстрее к небу, >1.0 — медленнее.",
                "По умолчанию: 1.0"
        })
        @Config.RangeDouble(min = 0.1, max = 5.0)
        public double skyInterpCurve = 1.0;

        // ===== Снежинки =====
        @Config.Name("Радиус снежинок")
        @Config.Comment({
                "Радиус (в блоках) вокруг игрока, в котором появляются снежинки.",
                "По умолчанию: 12"
        })
        @Config.RangeInt(min = 1, max = 32)
        public int snowRadius = 12;

        @Config.Name("Квадов на блок")
        @Config.Comment({
                "Сколько снежинок (квадов) создаётся на каждый блок площади.",
                "Больше значение — гуще снегопад и ниже FPS.",
                "По умолчанию: 6"
        })
        @Config.RangeInt(min = 1, max = 64)
        public int snowQuadsPerBlock = 6;

        @Config.Name("Размер снежинки")
        @Config.Comment("Базовый размер снежинки. По умолчанию: 0.087")
        @Config.RangeDouble(min = 0.001, max = 1.0)
        public double snowSize = 0.087;

        @Config.Name("Снос ветром")
        @Config.Comment({
                "На сколько блоков сносит снежинку за один цикл падения.",
                "По умолчанию: 6.0"
        })
        @Config.RangeDouble(min = 0.0, max = 32.0)
        public double snowWindMultiplier = 6.0;

        @Config.Name("Цикл падения снежинки (тиков)")
        @Config.Comment({
                "За сколько тиков снежинка пролетает весь диапазон падения.",
                "Больше значение — медленнее снегопад.",
                "По умолчанию: 20 (1 секунда)"
        })
        @Config.RangeInt(min = 5, max = 200)
        public int snowFallCycle = 20;
    }

    public static class Snow {
        @Config.Name("Заполнять при загрузке чанка")
        @Config.Comment("Ставить снег при прогрузке чанка. По умолчанию: true")
        public boolean fillOnChunkLoad = true;

        @Config.Name("Измерения заполнения")
        @Config.Comment({
                "ID измерений, в которых работает заполнение снегом.",
                "0 = Overworld, -1 = Nether, 1 = End; пустой список = нигде.",
                "По умолчанию: [0] (только Overworld)"
        })
        public int[] fillDimensions = {0};

        @Config.Name("Мин. слоёв снега")
        @Config.Comment("Минимальное количество слоёв снега. По умолчанию: 1")
        @Config.RangeInt(min = 1, max = 8)
        public int fillMinLayers = 1;

        @Config.Name("Макс. слоёв снега")
        @Config.Comment({
                "Максимальное количество слоёв снега.",
                "По умолчанию: 3"
        })
        @Config.RangeInt(min = 1, max = 8)
        public int fillMaxLayers = 3;

        @Config.Name("Максимальная высота снега")
        @Config.Comment({
                "Максимальная высота (Y), на которой ставится снег.",
                "По умолчанию: 255"
        })
        @Config.RangeInt(min = 0, max = 255)
        public int fillMaxY = 255;

        @Config.Name("Глубина от heightmap")
        @Config.Comment({
                "Максимальная глубина от surface heightmap, на которой ещё ставится снег",
                "и замораживается вода (озёра и лужи в пещерах).",
                "0 — только сама поверхность (в пещерах и гротах снега и льда нет).",
                "8 — пещеры у поверхности (по умолчанию).",
                "Листва не считается поверхностью: под кроной снег и лёд ставятся",
                "независимо от высоты дерева и толщины листвы.",
                "Больше 64 не бывает: глубже скан колонки не идёт."
        })
        @Config.RangeInt(min = 0, max = 64)
        public int fillCaveDepth = 8;

        @Config.Name("Замораживать воду")
        @Config.Comment("Превращать воду в лёд при генерации чанка. По умолчанию: true")
        public boolean freezeWater = true;

        @Config.Name("Слоёв воды заморозить")
        @Config.Comment({
                "Сколько блоков воды вниз от поверхности замораживать.",
                "1 — только верхний слой (корочка льда).",
                "3 — верхние 3 блока (толстый лёд).",
                "0 — не замораживать."
        })
        @Config.RangeInt(min = 0, max = 16)
        public int freezeWaterLayers = 1;

        @Config.Name("Только источники")
        @Config.Comment({
                "true — замораживать только источники (блок с полным уровнем воды),",
                "false — замораживать и текущую воду.",
                "Для рек и океанов рекомендуется true."
        })
        public boolean freezeOnlySourceBlocks = true;

        @Config.Name("Снег поверх льда")
        @Config.Comment({
                "Класть снежный слой поверх льда (как замороженного, так и природного).",
                "false — лёд остаётся видимым (по умолчанию).",
                "true — лёд тоже засыпается снегом."
        })
        public boolean snowOnIce = false;

        @Config.Name("Не таять от света")
        @Config.Comment({
                "true — снежные слои и лёд НЕ тают от источников света (block light).",
                "false — ванильное поведение: снег и лёд тают от света.",
                "По умолчанию: true"
        })
        public boolean preventMeltFromLight = true;
    }

    /**
     * Порог block-light, при превышении которого снег/лёд тают.
     * 11    — ванильное значение (таяние включено),
     * 32767 — практически недостижимо (таяние отключено).
     *
     * Вызывается в рантайме из пропатченного BlockSnow/BlockIce.updateTick,
     * поэтому учитывает актуальное значение конфига (в т.ч. после ConfigManager.sync).
     */
    public static int meltLightThreshold() {
        return snow.preventMeltFromLight ? 32767 : 11;
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