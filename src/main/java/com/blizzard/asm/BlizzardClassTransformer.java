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

    // ============ Дескрипторы BlizzardHooks (Object-версии) ============
    // Используем Object, потому что BlizzardHooks ремапится в prod (Notch),
    // а этот coremod — нет. (Ljava/lang/Object;...)V не ремапится и линкуется всегда.
    private static final String DESC_HOOK_WORLD_V = "(Ljava/lang/Object;)V";
    private static final String DESC_HOOK_WORLD_Z = "(Ljava/lang/Object;)Z";
    private static final String DESC_HOOK_TWO_V   = "(Ljava/lang/Object;Ljava/lang/Object;)V";

    /**
     * Точка входа трансформера. Forge вызывает его для каждого класса.
     * Патчим только WorldInfo, World, WorldServer, WorldClient.
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
        } catch (Throwable t) {
            System.err.println("[Blizzard ASM] !!! EXCEPTION while patching " + transformedName);
            t.printStackTrace();
        }
        return basicClass;
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