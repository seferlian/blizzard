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

    @Config.Name("Накопление снега (Accumulation)")
    @Config.Comment("Постепенное накопление снега и замерзание воды во время метели")
    public static final Accumulation accumulation = new Accumulation();

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
        @Config.Comment("Ставить снег при прогрузке чанка. По умолчанию: false")
        public boolean fillOnChunkLoad = false;

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
                "По умолчанию: 2"
        })
        @Config.RangeInt(min = 1, max = 8)
        public int fillMaxLayers = 2;

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
        @Config.Comment("Превращать воду в лёд при генерации чанка. По умолчанию: false")
        public boolean freezeWater = false;

        @Config.Name("Слоёв воды заморозить")
        @Config.Comment({
                "Сколько блоков воды вниз от поверхности замораживать.",
                "1 — только верхний слой (корочка льда).",
                "3 — верхние 3 блока (толстый лёд).",
                "0 — не замораживать."
        })
        @Config.RangeInt(min = 0, max = 16)
        public int freezeWaterLayers = 3;

        @Config.Name("Только источники")
        @Config.Comment({
                "true — замораживать только источники (блок с полным уровнем воды),",
                "false — замораживать и текущую воду.",
                "Для рек и океанов рекомендуется true."
        })
        public boolean freezeOnlySourceBlocks = true;

        @Config.Name("Снег поверх льда")
        @Config.Comment({
                "Присыпать лёд снежным слоем при заполнении чанка",
                "(freezeWater + fillOnChunkLoad).",
                "false — лёд остаётся видимым (по умолчанию и рекомендуется).",
                "true — лёд тоже присыпается, НО: ванильный BlockSnow в 1.12",
                "не держится на льду (checkAndDropBlock сносит слой), поэтому",
                "такой снег исчезает при первом же обновлении блока, а снос",
                "по большой плоскости льда уходит в каскад и роняет сервер",
                "(StackOverflowError). Накопление метели на лёд снег не кладёт",
                "никогда, независимо от этой опции."
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


    public static class Accumulation {

        @Config.Name("Включить накопление снега")
        @Config.Comment("Накопление снега слоями во время метели. По умолчанию: true")
        public boolean enabled = true;

        @Config.Name("Измерения накопления")
        @Config.Comment({
                "ID измерений, в которых идёт накопление снега и заморозка.",
                "Пустой список = нигде. По умолчанию: [0] (Overworld)"
        })
        public int[] dimensions = {0};

        @Config.Name("Радиус накопления (блоков)")
        @Config.Comment({
                "Радиус вокруг игрока, где растёт снег.",
                "Чем больше радиус, тем больше площадь (радиус 128 — это ~66 000 колонок)",
                "По умолчанию 128 — как у ванильного накопления снега: 8 чанков вокруг",
                "игрока. Нагрузка от радиуса почти",
                "не растёт: за тик делается фиксированное число попыток, просто каждая",
                "колонка получает касание реже, а полная засыпка длится дольше.",
                "По умолчанию: 128"
        })
        @Config.RangeInt(min = 1, max = 128)
        public int radius = 128;

        @Config.Name("Попыток роста за тик")
        @Config.Comment({
                "Сколько колонок за ОДНО касание пытаться присыпать — НА ЗОНУ, а не на игрока:",
                "игроки, стоящие рядом, считаются одной зоной и НЕ множат силу снегопада.",
                "Колонки выбираются ПСЕВДОСЛУЧАЙНО по всей площади радиуса,",
                "поэтому снег идёт «то тут, то там», а не фронтом с одной стороны.",
                "Каждая удачная попытка добавляет РОВНО ОДИН слой (1/8 блока).",
                "Касания идут не каждый тик, а каждый N-й (см. \"Касания: каждый N-й тик\"),",
                "поэтому скорость засыпки = attemptsPerTick / touchIntervalTicks слоёв за тик.",
                "Время полной засыпки ~ (площадь * средние слои) / скорость.",
                "При радиусе 128 (~66 000 колонок), 1024 попытках и N=20 полная",
                "засыпка до целевой функции занимает ~1 реальный час метели.",
                "При слишком большом радиусе код сам поднимет это значение,",
                "чтобы каждая колонка получала касание хотя бы раз в ~256 касаний.",
                "0 — накопление снега отключено. По умолчанию: 32"
        })
        @Config.RangeInt(min = 0, max = 2048)
        public int attemptsPerTick = 32;

        @Config.Name("Касания: каждый N-й тик")
        @Config.Comment({
                "Снег накапливается не каждый тик, а каждый N-й тик:",
                "1 — каждый тик (максимальная скорость),",
                "2 — через тик, 10 — 2 раза в секунду, 20 — раз в секунду (по умолчанию).",
                "За одно такое «касание» делается attemptsPerTick попыток добавить слой.",
                "Скорость засыпки обратно пропорциональна N, поэтому этим параметром",
                "удобно задавать темп в реальном времени: при N=20 и 1024 попытках",
                "зона радиуса 128 заполняется до целевой функции примерно за 1 час",
                "непрерывной метели (нужно ~2 000 000 слоёв: ~66 000 колонок * ~32 слоя).",
                "Хотите в 2 раза быстрее — N=10, в 2 раза медленнее — N=40.",
                "Заморозка воды этим параметром не управляется: у неё свои настройки",
                "(радиус, попытки, шанс) и она работает каждый тик.",
                "1 — снег идёт каждый тик. По умолчанию: 20"
        })
        @Config.RangeInt(min = 1, max = 1200)
        public int touchIntervalTicks = 20;

        @Config.Name("Шанс роста")
        @Config.Comment({
                "Дополнительный шанс, что принятая попытка действительно добавит слой.",
                "Основной отбор делает «Перекос касаний к гребням», поэтому 1.0 — норма:",
                "скорость задаётся величиной attemptsPerTick.",
                "Высота растёт по одному слою: сугроб в 7 блоков — это 56 касаний.",
                "1.0 — норма; 0.3-0.5 — если сервер или клиент подтормаживает",
                "(это прямо пропорционально снижает число обновлений блоков за тик).",
                "0.0 — снег не растёт. По умолчанию: 0.03"
        })
        @Config.RangeDouble(min = 0.0, max = 1.0)
        public double growChance = 0.03;

        @Config.Name("Перекос касаний к гребням")
        @Config.Comment({
                "Насколько чаще присыпаются гребни по сравнению с впадинами (0.0 - 1.0).",
                "Вес касания = (1-bias) + bias * (t - min) / (max - min), где t — целевая",
                "высота колонки, min/max — границы min/maxBlocksPerColumn.",
                "0.85 — впадины получают ~15% касаний гребня: холмы вырастают раньше,",
                "конечная функция рельефа проступает заранее, а впадины заполняются",
                "последними. 0.0 — все колонки равноправны (равномерный снегопад).",
                "По умолчанию: 0.85"
        })
        @Config.RangeDouble(min = 0.0, max = 1.0)
        public double ridgeBias = 0.85;

        @Config.Name("Мин. сила метели")
        @Config.Comment("Минимальная сила метели (0-1), при которой идёт накопление. По умолчанию: 0.7")
        @Config.RangeDouble(min = 0.0, max = 1.0)
        public double minStrength = 0.7;

        @Config.Name("Минимум блоков снега в колонке")
        @Config.Comment({
                "Минимальная высота снега в одной колонке, в полных блоках по 8 слоёв.",
                "1 — вся площадь под метелью засыпана хотя бы одним блоком,",
                "во впадинах снег есть, но он заметно ниже гребней (нет голых участков).",
                "0 — в самых глубоких впадинах снега не будет вовсе:",
                "это выглядит как проплешины голой земли между сугробами.",
                "У самой кромки воды снег всё равно сходит на нет — это следствие",
                "ограничения \"Макс. перепад слоёв\" с соседними колонками.",
                "По умолчанию: 1"
        })
        @Config.RangeInt(min = 0, max = 16)
        public int minBlocksPerColumn = 1;

        @Config.Name("Максимум блоков снега в колонке")
        @Config.Comment({
                "Максимальная высота снега в одной колонке, в полных блоках по 8 слоёв.",
                "ЖЁСТКИЙ предел: ни целевая высота, ни фактическая колонка его не превышают.",
                "Именно это значение задаёт высоту гребней: 7 = сугробы до 7 блоков.",
                "Сумма baseLayers + amplitudeLayers + noiseAmplitude должна быть",
                "примерно на 30% больше maxBlocksPerColumn * 8: сумма трёх синусоид",
                "редко доходит до ±1, поэтому нужен запас (32 + 22 + 12 = 66 при пределе 56).",
                "По умолчанию: 7 (56 слоёв)"
        })
        @Config.RangeInt(min = 1, max = 16)
        public int maxBlocksPerColumn = 7;

        @Config.Name("База слоёв")
        @Config.Comment({
                "Средний уровень снега (слоёв), вокруг которого ходят холмы и впадины.",
                "Ставьте baseLayers = (min + max) * 4: середина разрешённого диапазона",
                "(для 1..7 блоков это 4 блока = 32 слоя). Тогда синус опускается до",
                "минимума и поднимается до предела, а не упирается в кламп,",
                "давая ровные «плоские» впадины или плоские плато на гребнях.",
                "По умолчанию: 32.0 (4 блока при диапазоне 1..7)"
        })
        @Config.RangeDouble(min = 0.0, max = 64.0)
        public double baseLayers = 32.0;

        @Config.Name("Амплитуда синусоиды (слоёв)")
        @Config.Comment({
                "Размах холмов и впадин от синусоид, в слоях (8 слоёв = 1 блок).",
                "22 — крупные холмы ±2.75 блока вокруг baseLayers; вместе с шумом",
                "перепад получается ~6-7 блоков от дна впадины до гребня.",
                "Синусоид три (freq, 2*freq, 4*freq), поэтому крутизна ~",
                "1.7 * amplitudeLayers * freq (1.7 * 22 * 0.16 = 6 слоёв на блок)",
                "должна быть не больше \"Макс. перепад слоёв\", иначе гребни срежутся.",
                "По умолчанию: 22.0 (2.75 блока)"
        })
        @Config.RangeDouble(min = 0.0, max = 64.0)
        public double amplitudeLayers = 22.0;

        @Config.Name("Частота по X")
        @Config.Comment({
                "Коэффициент при x в sin(freqX*x + offsetX).",
                "Длина волны = 2*pi/freq блоков: 0.16 — ~39 блоков,",
                "то есть крупные холмы примерно каждые 20 блоков.",
                "Синусоид три: freqX, 2*freqX, 4*freqX (крупные, средние и мелкие гряды).",
                "Меньше 0.05 — один пологий склон на весь радиус. По умолчанию: 0.16"
        })
        @Config.RangeDouble(min = 0.0, max = 10.0)
        public double freqX = 0.16;

        @Config.Name("Частота по Z")
        @Config.Comment({
                "Коэффициент при z в sin(freqZ*z + offsetZ).",
                "Длина волны = 2*pi/freq блоков: 0.16 — ~39 блоков,",
                "то есть крупные холмы примерно каждые 20 блоков.",
                "Синусоид три: freqZ, 2*freqZ, 4*freqZ. По умолчанию: 0.16"
        })
        @Config.RangeDouble(min = 0.0, max = 10.0)
        public double freqZ = 0.16;

        @Config.Name("Смещение по X")
        @Config.Comment("Фазовый сдвиг синусоиды по X. По умолчанию: 0.0")
        public double offsetX = 0.0;

        @Config.Name("Смещение по Z")
        @Config.Comment("Фазовый сдвиг синусоиды по Z. По умолчанию: 0.0")
        public double offsetZ = 0.0;

        @Config.Name("Амплитуда шума (слоёв)")
        @Config.Comment({
                "Насколько многооктавный шум искажает холмы и впадины, в слоях.",
                "12 — ±1.5 блока рваных «заносов» поверх синусоиды:",
                "гребни перестают быть правильной решёткой, впадины получаются чашами.",
                "Слишком большое значение (близко к amplitudeLayers) превращает рельеф",
                "в «кашу» без читаемых гряд. По умолчанию: 12.0 (1.5 блока)"
        })
        @Config.RangeDouble(min = 0.0, max = 32.0)
        public double noiseAmplitude = 12.0;

        @Config.Name("Масштаб шума (блоков)")
        @Config.Comment({
                "Размер самой крупной ячейки шума (в блоках).",
                "Складываются 3 октавы: cell, cell/2, cell/4.",
                "20 — заносы шириной ~20 блоков ломают правильную решётку синуса",
                "(период синуса ~39 блоков), при этом гряды ещё читаются.",
                "Мельче 8 — мелкая «рябь» вместо сугробов, крупнее 48 — один бугор",
                "на весь радиус. По умолчанию: 20"
        })
        @Config.RangeInt(min = 1, max = 128)
        public int noiseScale = 20;

        @Config.Name("Сид шума")
        @Config.Comment("Сид генератора шума. По умолчанию: 1337")
        public int noiseSeed = 1337;

        @Config.Name("Макс. перепад слоёв")
        @Config.Comment({
                "Максимальная разница высот между соседними колонками при накоплении, в слоях.",
                "8 = один блок на блок — это и есть максимальная крутизна сугробов.",
                "Должно быть не меньше крутизны рельефа: три синусоиды дают",
                "~1.7*amplitudeLayers*freq (при дефолтах ~6), плюс шум ~2.",
                "Меньше — гребни срежутся и на склонах появятся плоские полки.",
                "Больше 10 — сугробы-стены. По умолчанию: 8"
        })
        @Config.RangeInt(min = 1, max = 16)
        public int maxNeighborDiff = 8;

        // ===== Заморозка воды =====
        @Config.Name("Замораживать воду при метели")
        @Config.Comment("Случайно превращать поверхностную воду в лёд во время метели. По умолчанию: true")
        public boolean freezeWater = true;

        @Config.Name("Радиус заморозки (блоков)")
        @Config.Comment("Радиус вокруг игрока, где морозится вода. По умолчанию: 64")
        @Config.RangeInt(min = 1, max = 128)
        public int freezeRadius = 64;

        @Config.Name("Попыток заморозки за тик")
        @Config.Comment("Сколько случайных колонок за тик проверять на заморозку. По умолчанию: 16")
        @Config.RangeInt(min = 0, max = 2048)
        public int freezeAttemptsPerTick = 16;

        @Config.Name("Шанс заморозки")
        @Config.Comment("Вероятность заморозить воду в выбранной колонке. По умолчанию: 0.05")
        @Config.RangeDouble(min = 0.0, max = 1.0)
        public double freezeChance = 0.02;

        @Config.Name("Слоёв воды заморозить")
        @Config.Comment("Сколько блоков воды вниз от поверхности превращать в лёд. По умолчанию: 1")
        @Config.RangeInt(min = 0, max = 16)
        public int freezeLayers = 1;

        @Config.Name("Только источники")
        @Config.Comment("true — морозить только полные источники воды. По умолчанию: true")
        public boolean freezeOnlySources = true;
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