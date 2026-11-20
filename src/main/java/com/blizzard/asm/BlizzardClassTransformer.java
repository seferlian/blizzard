package com.blizzard.asm;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * ASM-трансформер мода Blizzard.
 *
 * Что делает:
 *  1. Патчит WorldInfo: добавляет поля blizzard/blizzardTime/blizzardStrength,
 *     геттеры/сеттеры, а также хуки в readNBT / writeNBT / copyFrom
 *     для сохранения состояния метели в NBT.
 *  2. Патчит World: добавляет поля/методы (для IBlizzardWorld) и хуки:
 *     - в updateWeatherBody: tickWeather + ранний return при активной метели;
 *     - в isRaining / isThundering: возвращают false при активной метели.
 *  3. Патчит WorldServer и WorldClient: только поля/методы (без хуков) —
 *     чтобы рефлексия BlizzardReflection нашла их и там.
 *  4. Патчит BlockSnow / BlockIce: снежные слои и лёд больше не тают
 *     от источников света (block light). Порог сравнения «свет > 11»
 *     заменяется на недостижимое значение, поэтому ветка таяния не
 *     выполняется никогда (структура метода не меняется).
 *
 * Важно: в prod (реобфусцированном) BlizzardHooks ремапится, а этот CoreMod — нет.
 * Поэтому дескрипторы хуков используют Object-версии (Ljava/lang/Object;...)V,
 * которые не ремапятся и линкуются всегда.
 */
public class BlizzardClassTransformer implements IClassTransformer {

    /** Отладка: выводить имена классов и найденные методы/поля в лог. */
    private static final boolean DEBUG = false;

    // ============ MCP-имена (для сравнения с transformedName) ============
    private static final String CLS_WORLD_NAME        = "net.minecraft.world.World";
    private static final String CLS_WORLD_SERVER_NAME = "net.minecraft.world.WorldServer";
    private static final String CLS_WORLD_CLIENT_NAME = "net.minecraft.client.multiplayer.WorldClient";
    private static final String CLS_WORLD_INFO_NAME   = "net.minecraft.world.storage.WorldInfo";

    // ============ ASM internal names (для дескрипторов) ============
    private static final String CLS_WORLD        = "net/minecraft/world/World";
    private static final String CLS_WORLD_SERVER = "net/minecraft/world/WorldServer";
    private static final String CLS_WORLD_CLIENT = "net/minecraft/client/multiplayer/WorldClient";
    private static final String CLS_WORLD_INFO   = "net/minecraft/world/storage/WorldInfo";

    // ============ Таяние от света: BlockSnow / BlockIce ============
    private static final String CLS_BLOCK_SNOW_NAME = "net.minecraft.block.BlockSnow";
    private static final String CLS_BLOCK_ICE_NAME  = "net.minecraft.block.BlockIce";

    /** Ванильный порог block-light, при превышении которого снег/лёд тают. */
    private static final int MELT_LIGHT_THRESHOLD = 11;

    /** Хук конфига: возвращает актуальный порог таяния (11 или 32767). */
    private static final String HOOK_CONFIG_OWNER        = "com/blizzard/config/ConfigHandler";
    private static final String HOOK_MELT_THRESHOLD_NAME = "meltLightThreshold";
    private static final String DESC_MELT_THRESHOLD      = "()I";


    // ============ Дескрипторы BlizzardHooks (Object-версии) ============
    // Используем Object, потому что BlizzardHooks ремапится в prod (Notch),
    // а этот coremod — нет. (Ljava/lang/Object;...)V не ремапится и линкуется всегда.
    private static final String DESC_HOOK_WORLD_V = "(Ljava/lang/Object;)V";
    private static final String DESC_HOOK_WORLD_Z = "(Ljava/lang/Object;)Z";
    private static final String DESC_HOOK_TWO_V   = "(Ljava/lang/Object;Ljava/lang/Object;)V";

    /**
     * Точка входа трансформера. Forge вызывает его для каждого класса.
     * Патчим только WorldInfo, World, WorldServer, WorldClient,
     * BlockSnow, BlockIce (+ GuiIngameForge и Iris FinalPassRenderer).
     *
     * @param name            имя класса в текущем окружении (Notch или MCP);
     * @param transformedName имя класса после ремапа (MCP);
     * @param basicClass      байткод класса.
     * @return пропатченный байткод (или исходный, если класс не наш).
     */
    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null) return basicClass;
        if (transformedName == null) return basicClass;

        if (DEBUG) System.out.println("[Blizzard ASM] transform: name=" + name
                + " transformedName=" + transformedName);

        try {
            if (transformedName.equals(CLS_WORLD_INFO_NAME)) {
                if (DEBUG) System.out.println("[Blizzard ASM] >>> Patching WorldInfo START");
                byte[] result = patchWorldInfo(basicClass);
                if (DEBUG) System.out.println("[Blizzard ASM] >>> Patching WorldInfo DONE");
                return result;
            }
            if (transformedName.equals(CLS_WORLD_NAME)) {
                if (DEBUG) System.out.println("[Blizzard ASM] >>> Patching World START");
                byte[] result = patchWorld(basicClass);
                if (DEBUG) System.out.println("[Blizzard ASM] >>> Patching World DONE");
                return result;
            }
            if (transformedName.equals(CLS_WORLD_SERVER_NAME)) {
                if (DEBUG) System.out.println("[Blizzard ASM] >>> Patching WorldServer START");
                byte[] result = patchWorldServer(basicClass);
                if (DEBUG) System.out.println("[Blizzard ASM] >>> Patching WorldServer DONE");
                return result;
            }
            if (transformedName.equals(CLS_WORLD_CLIENT_NAME)) {
                if (DEBUG) System.out.println("[Blizzard ASM] >>> Patching WorldClient START");
                byte[] result = patchWorldClient(basicClass);
                if (DEBUG) System.out.println("[Blizzard ASM] >>> Patching WorldClient DONE");
                return result;
            }

            // Снежные слои и лёд не должны таять от источников света.
            if (transformedName.equals(CLS_BLOCK_SNOW_NAME)) {
                if (DEBUG) System.out.println("[Blizzard ASM] >>> Patching BlockSnow START");
                byte[] result = patchBlockMeltFromLight(basicClass, "BlockSnow");
                if (DEBUG) System.out.println("[Blizzard ASM] >>> Patching BlockSnow DONE");
                return result;
            }
            if (transformedName.equals(CLS_BLOCK_ICE_NAME)) {
                if (DEBUG) System.out.println("[Blizzard ASM] >>> Patching BlockIce START");
                byte[] result = patchBlockMeltFromLight(basicClass, "BlockIce");
                if (DEBUG) System.out.println("[Blizzard ASM] >>> Patching BlockIce DONE");
                return result;
            }


            if (transformedName.equals("net.minecraftforge.client.GuiIngameForge")) {
                return patchGuiIngameForge(basicClass);
            }



            if (transformedName.equals("net.irisshaders.iris.pipeline.FinalPassRenderer")) {
                return patchIrisFinalPass(basicClass);
            }

        } catch (Throwable t) {
            System.err.println("[Blizzard ASM] !!! EXCEPTION while patching " + transformedName);
            t.printStackTrace();
        }
        return basicClass;
    }




    private byte[] patchIrisFinalPass(byte[] basicClass) {
        ClassNode cn = new ClassNode();
        new ClassReader(basicClass).accept(cn, 0);

        for (MethodNode m : cn.methods) {
            if (m.name.equals("renderFinalPass") && m.desc.equals("()V")) {
                InsnList insns = new InsnList();
                // Загружаем this (FinalPassRenderer)
                insns.add(new VarInsnNode(ALOAD, 0));
                // Загружаем поле renderTargets
                insns.add(new FieldInsnNode(GETFIELD,
                        "net/irisshaders/iris/pipeline/FinalPassRenderer",
                        "renderTargets",
                        "Lnet/irisshaders/iris/targets/RenderTargets;"));
                // Вызываем BlizzardHooks.onIrisFinalPass(RenderTargets)
                insns.add(new MethodInsnNode(INVOKESTATIC,
                        "com/blizzard/weather/BlizzardHooks",
                        "onIrisFinalPass",
                        "(Ljava/lang/Object;)V", false));
                insertBeforeLastReturn(m, insns);
            }
        }

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cn.accept(cw);
        return cw.toByteArray();
    }

    // =====================================================================
    // WorldInfo
    // =====================================================================

    /**
     * Патч WorldInfo:
     *  - implements IWorldInfoBlizzard;
     *  - добавляет поля blizzard (Z), blizzardTime (I), blizzardStrength (F);
     *  - геттеры/сеттеры;
     *  - хук readNBT в конструктор (LNBTTagCompound;)V;
     *  - хук copyFrom в копирующий конструктор (LWorldInfo;)V;
     *  - хук writeNBT в приватный updateTagCompound (LNBTTagCompound;LNBTTagCompound;)V.
     */
    private byte[] patchWorldInfo(byte[] basicClass) {
        ClassNode cn = new ClassNode();
        new ClassReader(basicClass).accept(cn, 0);

        if (DEBUG) {
            System.out.println("[Blizzard ASM] WorldInfo methods:");
            for (MethodNode m : cn.methods) {
                System.out.println("  M " + m.name + " : " + m.desc);
            }
            System.out.println("[Blizzard ASM] WorldInfo fields:");
            for (FieldNode f : cn.fields) {
                System.out.println("  F " + f.name + " : " + f.desc);
            }
        }

        // Добавляем интерфейс (если ещё нет)
        if (!cn.interfaces.contains("com/blizzard/weather/IWorldInfoBlizzard")) {
            cn.interfaces.add("com/blizzard/weather/IWorldInfoBlizzard");
        }

        // Поля (если ещё нет)
        if (!hasField(cn, "blizzard")) {
            cn.fields.add(new FieldNode(ACC_PRIVATE, "blizzard", "Z", null, false));
        }
        if (!hasField(cn, "blizzardTime")) {
            cn.fields.add(new FieldNode(ACC_PRIVATE, "blizzardTime", "I", null, 0));
        }
        if (!hasField(cn, "blizzardStrength")) {
            cn.fields.add(new FieldNode(ACC_PRIVATE, "blizzardStrength", "F", null, 0.0F));
        }

        // Геттеры
        if (!hasMethod(cn, "isBlizzard", "()Z")) {
            cn.methods.add(makeGetter("isBlizzard", "blizzard", "Z", CLS_WORLD_INFO));
        }
        if (!hasMethod(cn, "getBlizzardTime", "()I")) {
            cn.methods.add(makeGetter("getBlizzardTime", "blizzardTime", "I", CLS_WORLD_INFO));
        }
        if (!hasMethod(cn, "getBlizzardStrength", "()F")) {
            cn.methods.add(makeGetter("getBlizzardStrength", "blizzardStrength", "F", CLS_WORLD_INFO));
        }

        // Сеттеры
        if (!hasMethod(cn, "setBlizzard", "(Z)V")) {
            cn.methods.add(makeSetter("setBlizzard", "blizzard", "Z", CLS_WORLD_INFO));
        }
        if (!hasMethod(cn, "setBlizzardTime", "(I)V")) {
            cn.methods.add(makeSetter("setBlizzardTime", "blizzardTime", "I", CLS_WORLD_INFO));
        }
        if (!hasMethod(cn, "setBlizzardStrength", "(F)V")) {
            cn.methods.add(makeSetter("setBlizzardStrength", "blizzardStrength", "F", CLS_WORLD_INFO));
        }

        // Хуки в конструкторы и updateTagCompound
        for (MethodNode m : cn.methods) {
            // readNBT — конструктор из NBT: (LNBTTagCompound;)V
            // MCP: (Lnet/minecraft/nbt/NBTTagCompound;)V
            // Notch: (Lfy;)V
            boolean isCtorNBT = m.name.equals("<init>") &&
                    (m.desc.equals("(Lnet/minecraft/nbt/NBTTagCompound;)V")
                            || m.desc.equals("(Lfy;)V")
                            || m.desc.equals("(Lnbttagcompound;)V"));

            // Копирующий конструктор: (LWorldInfo;)V
            // MCP: (Lnet/minecraft/world/storage/WorldInfo;)V
            // Notch: (Lbfd;)V
            boolean isCtorCopy = m.name.equals("<init>") &&
                    (m.desc.equals("(Lnet/minecraft/world/storage/WorldInfo;)V")
                            || m.desc.equals("(Lbfd;)V"));

            // updateTagCompound — приватный void с двумя NBTTagCompound.
            // Именно его вызывает cloneNBTCompound при сохранении на диск.
            // MCP: (Lnet/minecraft/nbt/NBTTagCompound;Lnet/minecraft/nbt/NBTTagCompound;)V
            // Notch: (Lfy;Lfy;)V
            boolean isUpdateNBTPrivate =
                    m.desc.equals("(Lnet/minecraft/nbt/NBTTagCompound;Lnet/minecraft/nbt/NBTTagCompound;)V")
                            || m.desc.equals("(Lfy;Lfy;)V")
                            || m.desc.equals("(Lnbttagcompound;Lnbttagcompound;)V");

            if (isCtorNBT) {
                // Хук readNBT(this, nbt) в конец конструктора
                InsnList insns = new InsnList();
                insns.add(new VarInsnNode(ALOAD, 0));
                insns.add(new VarInsnNode(ALOAD, 1));
                insns.add(new MethodInsnNode(INVOKESTATIC,
                        "com/blizzard/weather/BlizzardHooks",
                        "readNBT",
                        DESC_HOOK_TWO_V, false));
                insertBeforeLastReturn(m, insns);
                if (DEBUG) System.out.println("[Blizzard ASM] Hooked readNBT into <init> (" + m.desc + ")");
            } else if (isCtorCopy) {
                // Хук copyFrom(this, other) в конец копирующего конструктора
                InsnList insns = new InsnList();
                insns.add(new VarInsnNode(ALOAD, 0));
                insns.add(new VarInsnNode(ALOAD, 1));
                insns.add(new MethodInsnNode(INVOKESTATIC,
                        "com/blizzard/weather/BlizzardHooks",
                        "copyFrom",
                        DESC_HOOK_TWO_V, false));
                insertBeforeLastReturn(m, insns);
                if (DEBUG) System.out.println("[Blizzard ASM] Hooked copyFrom into <init> (" + m.desc + ")");
            } else if (isUpdateNBTPrivate) {
                // Хук writeNBT(this, nbt) в конец updateTagCompound.
                // nbt в этом методе — тот самый, что уйдёт на диск через cloneNBTCompound.
                InsnList insns = new InsnList();
                insns.add(new VarInsnNode(ALOAD, 0));
                insns.add(new VarInsnNode(ALOAD, 1));
                insns.add(new MethodInsnNode(INVOKESTATIC,
                        "com/blizzard/weather/BlizzardHooks",
                        "writeNBT",
                        DESC_HOOK_TWO_V, false));
                insertBeforeLastReturn(m, insns);
                if (DEBUG) System.out.println("[Blizzard ASM] Hooked writeNBT into updateTagCompound (" + m.desc + ")");
            }
        }

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cn.accept(cw);
        return cw.toByteArray();
    }

    // =====================================================================
    // World
    // =====================================================================

    /**
     * Патч World:
     *  - implements IBlizzardWorld, поля/методы;
     *  - хук в updateWeatherBody (tickWeather + return при isBlizzard);
     *  - хук в isRaining / isThundering (return false при isBlizzard).
     */
    private byte[] patchWorld(byte[] basicClass) {
        ClassNode cn = new ClassNode();
        new ClassReader(basicClass).accept(cn, 0);

        if (DEBUG) {
            System.out.println("[Blizzard ASM] World methods:");
            for (MethodNode m : cn.methods) {
                System.out.println("  M " + m.name + " : " + m.desc);
            }
            System.out.println("[Blizzard ASM] World fields:");
            for (FieldNode f : cn.fields) {
                System.out.println("  F " + f.name + " : " + f.desc);
            }
        }

        addBlizzardFieldsAndMethods(cn, CLS_WORLD);

        // 1. updateWeatherBody — только по точному MCP/SRG-имени.
        //    НЕ используем hasMethodCall(m, "a") — "a" это обфусцированный
        //    updateWeather, но также куча других методов; из-за этого
        //    патчился initCapabilities() и другие ()V — фатально для Forge.
        patchUpdateWeatherBody(cn);

        // 2. isRaining / isThundering — по Notch-именам D / X, плюс MCP/SRG.
        patchThinBooleanGetters(cn);

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Хук в updateWeatherBody:
     *  в начало метода вставляем:
     *    BlizzardHooks.tickWeather(this);
     *    if (BlizzardHooks.isBlizzardActive(this)) return;
     *  Это блокирует ванильную логику (дождь/гроза не тикают) при активной метели.
     */
    private void patchUpdateWeatherBody(ClassNode cn) {
        boolean found = false;
        for (MethodNode m : cn.methods) {
            if (!m.desc.equals("()V")) continue;
            if (m.name.equals("<init>") || m.name.equals("<clinit>")) continue;

            boolean byName = m.name.equals("updateWeatherBody")
                    || m.name.equals("func_72907_a");
            if (!byName) continue;

            InsnList insns = new InsnList();

            // tickWeather(this)
            insns.add(new VarInsnNode(ALOAD, 0));
            insns.add(new MethodInsnNode(INVOKESTATIC,
                    "com/blizzard/weather/BlizzardHooks",
                    "tickWeather",
                    DESC_HOOK_WORLD_V, false));

            // if (isBlizzardActive(this)) return;
            insns.add(new VarInsnNode(ALOAD, 0));
            insns.add(new MethodInsnNode(INVOKESTATIC,
                    "com/blizzard/weather/BlizzardHooks",
                    "isBlizzardActive",
                    DESC_HOOK_WORLD_Z, false));
            LabelNode notBlizzard = new LabelNode();
            insns.add(new JumpInsnNode(IFEQ, notBlizzard));
            insns.add(new InsnNode(RETURN));
            insns.add(notBlizzard);

            m.instructions.insertBefore(m.instructions.getFirst(), insns);
            if (DEBUG) System.out.println("[Blizzard ASM] Hooked tickWeather + RETURN into "
                    + m.name + " ()V");
            found = true;
        }
        if (!found && DEBUG) {
            System.out.println("[Blizzard ASM] WARNING: updateWeatherBody not found");
        }
    }

    /**
     * Хук в isRaining / isThundering:
     *  в начало вставляем if (isBlizzardActive(this)) return false;
     *  В prod Notch-имена: isRaining = D, isThundering = X.
     *  Подстраховка: MCP isRaining/isThundering, SRG func_72911_R / func_72913_Q.
     */
    private void patchThinBooleanGetters(ClassNode cn) {
        String[] rainingNames    = {"isRaining",    "func_72911_R", "D"};
        String[] thunderingNames = {"isThundering", "func_72913_Q", "X"};

        int found = 0;

        // Патч isRaining / isThundering по имени
        for (MethodNode m : cn.methods) {
            if (!m.desc.equals("()Z")) continue;

            boolean isRaining    = matchesAnyName(m, rainingNames);
            boolean isThundering = matchesAnyName(m, thunderingNames);

            if (!isRaining && !isThundering) continue;

            injectBlizzardFalseReturn(m);
            found++;
            if (DEBUG) System.out.println("[Blizzard ASM] Hooked "
                    + (isRaining ? "isRaining" : "isThundering")
                    + " into " + m.name + " ()Z");
        }

        if (DEBUG && found == 0) {
            System.out.println("[Blizzard ASM] WARNING: no isRaining/isThundering hooked");
        }
    }


    private byte[] patchGuiIngameForge(byte[] basicClass) {
        ClassNode cn = new ClassNode();
        new ClassReader(basicClass).accept(cn, 0);

        // Патчим renderAir(int, int) — последний правый элемент HUD
        for (MethodNode m : cn.methods) {
            if (!m.name.equals("renderAir")) continue;
            if (!m.desc.equals("(II)V")) continue;

            // В конец метода: BlizzardHooks.onRenderAirHud(width, height)
            // но нужны локальные переменные width/height.
            // В renderAir они: int width (local 1), int height (local 2).
            // Вставляем перед последним RETURN:
            InsnList insns = new InsnList();
            insns.add(new VarInsnNode(ILOAD, 1)); // width
            insns.add(new VarInsnNode(ILOAD, 2)); // height
            insns.add(new MethodInsnNode(INVOKESTATIC,
                    "com/blizzard/weather/BlizzardHooks",
                    "onRenderAirHud",
                    "(II)V", false));
            insertBeforeLastReturn(m, insns);
        }

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cn.accept(cw);
        return cw.toByteArray();
    }


    /** Проверяет, совпадает ли имя метода с одним из заданных. */
    private boolean matchesAnyName(MethodNode m, String[] names) {
        for (String n : names) {
            if (m.name.equals(n)) return true;
        }
        return false;
    }

    /**
     * Вставляет в начало метода:
     *   if (BlizzardHooks.isBlizzardActive(this)) return false;
     */
    private void injectBlizzardFalseReturn(MethodNode m) {
        InsnList insns = new InsnList();
        insns.add(new VarInsnNode(ALOAD, 0));
        insns.add(new MethodInsnNode(INVOKESTATIC,
                "com/blizzard/weather/BlizzardHooks",
                "isBlizzardActive",
                DESC_HOOK_WORLD_Z, false));
        LabelNode skip = new LabelNode();
        insns.add(new JumpInsnNode(IFEQ, skip));
        insns.add(new InsnNode(ICONST_0));
        insns.add(new InsnNode(IRETURN));
        insns.add(skip);

        m.instructions.insertBefore(m.instructions.getFirst(), insns);
    }

    // =====================================================================
    // WorldServer
    // =====================================================================

    /**
     * Патч WorldServer: только поля/методы (без хуков).
     * Нужно, чтобы ASM не падал при вызове setBlizzard и т.д. на WorldServer.
     */
    private byte[] patchWorldServer(byte[] basicClass) {
        ClassNode cn = new ClassNode();
        new ClassReader(basicClass).accept(cn, 0);

        if (DEBUG) {
            System.out.println("[Blizzard ASM] WorldServer methods:");
            for (MethodNode m : cn.methods) {
                System.out.println("  M " + m.name + " : " + m.desc);
            }
        }

        addBlizzardFieldsAndMethods(cn, CLS_WORLD_SERVER);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cn.accept(cw);
        return cw.toByteArray();
    }

    // =====================================================================
    // WorldClient
    // =====================================================================

    /**
     * Патч WorldClient: только поля/методы (без хуков).
     * Нужно, чтобы клиентский WorldClient имел isBlizzard/blizzardTime/blizzardStrength
     * для синхронизации пакетами и рефлексии.
     */
    private byte[] patchWorldClient(byte[] basicClass) {
        ClassNode cn = new ClassNode();
        new ClassReader(basicClass).accept(cn, 0);

        if (DEBUG) {
            System.out.println("[Blizzard ASM] WorldClient methods:");
            for (MethodNode m : cn.methods) {
                System.out.println("  M " + m.name + " : " + m.desc);
            }
        }

        addBlizzardFieldsAndMethods(cn, CLS_WORLD_CLIENT);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cn.accept(cw);
        return cw.toByteArray();
    }

    // =====================================================================
    // Поля/методы (общие для World / WorldServer / WorldClient)
    // =====================================================================

    /**
     * Добавляет в класс:
     *  - implements IBlizzardWorld;
     *  - поля isBlizzard (Z), blizzardTime (I), blizzardStrength (F), prevBlizzardStrength (F);
     *  - геттеры/сеттеры;
     *  - перегрузку setBlizzard(ZI)V для интерфейса.
     */
    private void addBlizzardFieldsAndMethods(ClassNode cn, String owner) {
        if (!cn.interfaces.contains("com/blizzard/weather/IBlizzardWorld")) {
            cn.interfaces.add("com/blizzard/weather/IBlizzardWorld");
        }

        // Поля (если ещё нет)
        if (!hasField(cn, "isBlizzard")) {
            cn.fields.add(new FieldNode(ACC_PUBLIC, "isBlizzard", "Z", null, false));
        }
        if (!hasField(cn, "blizzardTime")) {
            cn.fields.add(new FieldNode(ACC_PUBLIC, "blizzardTime", "I", null, 0));
        }
        if (!hasField(cn, "blizzardStrength")) {
            cn.fields.add(new FieldNode(ACC_PUBLIC, "blizzardStrength", "F", null, 0.0F));
        }
        if (!hasField(cn, "prevBlizzardStrength")) {
            cn.fields.add(new FieldNode(ACC_PUBLIC, "prevBlizzardStrength", "F", null, 0.0F));
        }

        // Геттеры
        if (!hasMethod(cn, "isBlizzard", "()Z")) {
            cn.methods.add(makeGetter("isBlizzard", "isBlizzard", "Z", owner));
        }
        if (!hasMethod(cn, "getBlizzardTime", "()I")) {
            cn.methods.add(makeGetter("getBlizzardTime", "blizzardTime", "I", owner));
        }
        if (!hasMethod(cn, "getBlizzardStrength", "()F")) {
            cn.methods.add(makeGetter("getBlizzardStrength", "blizzardStrength", "F", owner));
        }

        // Сеттеры
        if (!hasMethod(cn, "setBlizzard", "(Z)V")) {
            cn.methods.add(makeSetter("setBlizzard", "isBlizzard", "Z", owner));
        }
        if (!hasMethod(cn, "setBlizzardTime", "(I)V")) {
            cn.methods.add(makeSetter("setBlizzardTime", "blizzardTime", "I", owner));
        }
        if (!hasMethod(cn, "setBlizzardStrength", "(F)V")) {
            cn.methods.add(makeSetter("setBlizzardStrength", "blizzardStrength", "F", owner));
        }

        // Перегрузка setBlizzard(ZI)V — для IBlizzardWorld
        if (!hasMethod(cn, "setBlizzard", "(ZI)V")) {
            MethodNode setBlizzardTwo = new MethodNode(ACC_PUBLIC, "setBlizzard", "(ZI)V", null, null);
            setBlizzardTwo.instructions.add(new VarInsnNode(ALOAD, 0));
            setBlizzardTwo.instructions.add(new VarInsnNode(ILOAD, 1));
            setBlizzardTwo.instructions.add(new FieldInsnNode(PUTFIELD, owner, "isBlizzard", "Z"));
            setBlizzardTwo.instructions.add(new VarInsnNode(ALOAD, 0));
            setBlizzardTwo.instructions.add(new VarInsnNode(ILOAD, 2));
            setBlizzardTwo.instructions.add(new FieldInsnNode(PUTFIELD, owner, "blizzardTime", "I"));
            setBlizzardTwo.instructions.add(new InsnNode(RETURN));
            setBlizzardTwo.maxStack = 2;
            setBlizzardTwo.maxLocals = 3;
            cn.methods.add(setBlizzardTwo);
        }

        if (DEBUG) System.out.println("[Blizzard ASM] Added fields+methods to " + owner);
    }

    // =====================================================================
    // BlockSnow / BlockIce — запрет таяния от источников света
    // =====================================================================

    /**
     * Патчит updateTick у BlockSnow / BlockIce так, чтобы блоки не таяли от света.
     *
     * Ванильная логика:
     *   BlockIce.updateTick:  if (world.getLightFor(BLOCK, pos) > 11 - opacity)
     *                             turnIntoWater(...)
     *   BlockSnow.updateTick: if (world.provider.isNether()
     *                             && world.getLightFor(BLOCK, pos) > 11)
     *                             dropBlock / setBlockToAir
     *
     * Метод целиком не выключаем (это ломает любую будущую логику в нём),
     * а делаем недостижимой саму ветку таяния: пороговая константа 11
     * заменяется на огромное значение, поэтому сравнение «свет > порог»
     * всегда ложно.
     *
     * @param basicClass байткод класса BlockSnow / BlockIce;
     * @param label      имя класса для лога.
     * @return пропатченный байткод.
     */
    private byte[] patchBlockMeltFromLight(byte[] basicClass, String label) {
        ClassNode cn = new ClassNode();
        new ClassReader(basicClass).accept(cn, 0);

        if (DEBUG) {
            System.out.println("[Blizzard ASM] " + label + " methods:");
            for (MethodNode m : cn.methods) {
                System.out.println("  M " + m.name + " : " + m.desc);
            }
        }

        int patched = 0;
        for (MethodNode m : cn.methods) {
            if (!isUpdateTickMethod(m)) continue;
            int n = neutralizeLightMelt(m);
            patched += n;
            if (DEBUG && n > 0) {
                System.out.println("[Blizzard ASM] " + label
                        + ": patched " + m.name + m.desc + " (replacements=" + n + ")");
            }
        }

        if (patched == 0) {
            // Важно: раньше это молчало. Если паттерн не найден — блок продолжит таять.
            System.err.println("[Blizzard ASM] WARNING: " + label
                    + " light-melt pattern not found — block will still melt from light!");
        } else if (DEBUG) {
            System.out.println("[Blizzard ASM] " + label
                    + ": light-melt checks neutralized = " + patched);
        }

        // Меняем только константу: BIPUSH 11 -> LDC 32767, глубина стека и фреймы
        // не меняются. COMPUTE_MAXS достаточно. COMPUTE_FRAMES в prod может дёрнуть
        // getCommonSuperClass / Class.forName на ещё не загруженных Notch-классах.
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }


    /**
     * Является ли метод ванильным updateTick.
     * Основной критерий — дескриптор (он одинаков в dev и prod),
     * имя проверяется как подстраховка (MCP updateTick / SRG func_180645_a).
     */
    private boolean isUpdateTickMethod(MethodNode m) {
        // dev (MCP) и prod (SRG)
        if (m.name.equals("updateTick") || m.name.equals("func_180645_a")) return true;

        // prod (Notch): имя однобуквенное, но сигнатура стабильна:
        // void (World, BlockPos, IBlockState, java.util.Random)
        // java.util.Random не обфусцируется.
        if (Type.getReturnType(m.desc).getSort() != Type.VOID) return false;
        Type[] args = Type.getArgumentTypes(m.desc);
        return args.length == 4
                && args[3].getSort() == Type.OBJECT
                && "Ljava/util/Random;".equals(args[3].getDescriptor());
    }

    /**
     * Делает ветку таяния недостижимой: находит в updateTick последовательность
     *   ... getLightFor(..., BlockPos)  ->  BIPUSH 11  ->  ...  ->  IF_ICMPxx
     * и заменяет константу 11.
     *
     * @return количество замен (0 или 1 на метод).
     */
    private int neutralizeLightMelt(MethodNode m) {
        int patched = 0;
        AbstractInsnNode insn = m.instructions.getFirst();

        while (insn != null) {
            AbstractInsnNode next = insn.getNext();

            Integer pushed = getPushedInt(insn);
            if (pushed != null && pushed.intValue() == MELT_LIGHT_THRESHOLD
                    && hasLightLookupBefore(insn, 8)
                    && findConditionalJumpAhead(insn, 10) != null) {

                m.instructions.insertBefore(insn, new MethodInsnNode(
                        INVOKESTATIC,
                        HOOK_CONFIG_OWNER,
                        HOOK_MELT_THRESHOLD_NAME,
                        DESC_MELT_THRESHOLD,
                        false));
                m.instructions.remove(insn);
                patched++;

                if (DEBUG) {
                    System.out.println("[Blizzard ASM] " + m.name + m.desc
                            + ": melt threshold " + MELT_LIGHT_THRESHOLD
                            + " -> ConfigHandler.meltLightThreshold()");
                }
            }

            insn = next;
        }
        return patched;
    }

    // =====================================================================
    // Хелперы
    // =====================================================================

    /**
     * Создаёт геттер:
     *   public T name() { return this.field; }
     */
    private MethodNode makeGetter(String name, String field, String desc, String owner) {
        MethodNode m = new MethodNode(ACC_PUBLIC, name, "()" + desc, null, null);
        m.instructions.add(new VarInsnNode(ALOAD, 0));
        m.instructions.add(new FieldInsnNode(GETFIELD, owner, field, desc));
        m.instructions.add(new InsnNode(desc.equals("Z") || desc.equals("I") ? IRETURN : FRETURN));
        m.maxStack = 1;
        m.maxLocals = 1;
        return m;
    }

    /**
     * Создаёт сеттер:
     *   public void name(T value) { this.field = value; }
     */
    private MethodNode makeSetter(String name, String field, String desc, String owner) {
        MethodNode m = new MethodNode(ACC_PUBLIC, name, "(" + desc + ")V", null, null);
        m.instructions.add(new VarInsnNode(ALOAD, 0));
        m.instructions.add(new VarInsnNode(desc.equals("Z") || desc.equals("I") ? ILOAD : FLOAD, 1));
        m.instructions.add(new FieldInsnNode(PUTFIELD, owner, field, desc));
        m.instructions.add(new InsnNode(RETURN));
        m.maxStack = 2;
        m.maxLocals = 2;
        return m;
    }

    /**
     * Вставляет инструкции перед последним *RETURN (RETURN, ARETURN, IRETURN, ...).
     * Если RETURN нет — ничего не вставляет.
     */
    private void insertBeforeLastReturn(MethodNode m, InsnList insns) {
        for (int i = m.instructions.size() - 1; i >= 0; i--) {
            AbstractInsnNode insn = m.instructions.get(i);
            int op = insn.getOpcode();
            if (op == RETURN || op == ARETURN || op == IRETURN
                    || op == LRETURN || op == FRETURN || op == DRETURN) {
                m.instructions.insertBefore(insn, insns);
                return;
            }
        }
    }

    /**
     * Проверяет, является ли метод «тонким boolean-геттером»:
     *   ALOAD 0; GETFIELD Z; IRETURN (с пропуском псевдо-инструкций).
     *   На данный момент не нужен
     */
    private boolean isThinBooleanGetter(MethodNode m) {
        AbstractInsnNode[] arr = m.instructions.toArray();
        int idx = 0;

        while (idx < arr.length && isPseudo(arr[idx])) idx++;
        if (idx >= arr.length || arr[idx].getOpcode() != ALOAD) return false;
        VarInsnNode aload = (VarInsnNode) arr[idx];
        if (aload.var != 0) return false;
        idx++;

        while (idx < arr.length && isPseudo(arr[idx])) idx++;
        if (idx >= arr.length || arr[idx].getOpcode() != GETFIELD) return false;
        FieldInsnNode getfield = (FieldInsnNode) arr[idx];
        if (!getfield.desc.equals("Z")) return false;
        idx++;

        while (idx < arr.length && isPseudo(arr[idx])) idx++;
        if (idx >= arr.length || arr[idx].getOpcode() != IRETURN) return false;

        return true;
    }

    /** Является ли инструкция «псевдо» (метка, номер строки, фрейм). */
    private boolean isPseudo(AbstractInsnNode n) {
        return n instanceof LabelNode || n instanceof LineNumberNode || n instanceof FrameNode;
    }

    /**
     * Возвращает int-константу, которую кладёт на стек инструкция,
     * либо null, если инструкция не является пушем int.
     */
    private Integer getPushedInt(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        if (op == ICONST_M1) return Integer.valueOf(-1);
        if (op >= ICONST_0 && op <= ICONST_5) return Integer.valueOf(op - ICONST_0);
        if (op == BIPUSH || op == SIPUSH) return Integer.valueOf(((IntInsnNode) insn).operand);
        if (op == LDC) {
            Object cst = ((LdcInsnNode) insn).cst;
            if (cst instanceof Integer) return (Integer) cst;
        }
        return null;
    }

    /**
     * Ищет первый условный переход в пределах {@code window} «настоящих»
     * инструкций после {@code insn}. Если раньше встретился безусловный
     * переход — возвращает null (значит структура не та, что мы ждём).
     */
    private JumpInsnNode findConditionalJumpAhead(AbstractInsnNode insn, int window) {
        int real = 0;
        for (AbstractInsnNode cur = insn.getNext(); cur != null && real < window; cur = cur.getNext()) {
            if (isPseudo(cur)) continue;
            if (cur instanceof JumpInsnNode) {
                return isConditionalJump(cur.getOpcode()) ? (JumpInsnNode) cur : null;
            }
            real++;
        }
        return null;
    }

    /** Условный переход (IFEQ..IF_ACMPNE, IFNULL, IFNONNULL). */
    private boolean isConditionalJump(int opcode) {
        return (opcode >= IFEQ && opcode <= IF_ACMPNE)
                || opcode == IFNULL || opcode == IFNONNULL;
    }

    /**
     * Есть ли перед инструкцией вызов «уровня света по BlockPos», т.е.
     * {@code getLightFor(EnumSkyBlock, BlockPos)}.
     * Матчим по дескриптору, а не по имени: в prod метод называется
     * func_175642_b, в dev — getLightFor.
     */
    private boolean hasLightLookupBefore(AbstractInsnNode insn, int window) {
        int real = 0;
        for (AbstractInsnNode cur = insn.getPrevious(); cur != null && real < window; cur = cur.getPrevious()) {
            if (isPseudo(cur)) continue;
            if (cur instanceof MethodInsnNode && looksLikeLightLookup((MethodInsnNode) cur)) {
                return true;
            }
            real++;
        }
        return false;
    }

    /**
     * World.getLightFor(EnumSkyBlock, BlockPos) во всех стадиях имеет форму
     * «два объектных аргумента -> int». В MCP/SRG это видно по именам классов,
     * в Notch имена классов обфусцированы (одна буква), поэтому матчим по форме.
     */
    private boolean looksLikeLightLookup(MethodInsnNode mi) {
        if (Type.getReturnType(mi.desc).getSort() != Type.INT) return false;
        Type[] args = Type.getArgumentTypes(mi.desc);
        return args.length == 2
                && args[0].getSort() == Type.OBJECT
                && args[1].getSort() == Type.OBJECT;
    }


    /** Проверяет, есть ли в классе поле с указанным именем. */
    private boolean hasField(ClassNode cn, String name) {
        for (FieldNode f : cn.fields) {
            if (f.name.equals(name)) return true;
        }
        return false;
    }

    /** Проверяет, есть ли в классе метод с указанным именем и дескриптором. */
    private boolean hasMethod(ClassNode cn, String name, String desc) {
        for (MethodNode m : cn.methods) {
            if (m.name.equals(name) && m.desc.equals(desc)) return true;
        }
        return false;
    }
}