package com.blizzard.client;

import com.blizzard.config.ClientVisualConfig;
import com.blizzard.config.ConfigHandler;
import com.blizzard.raycast.BlizzardClientExposure;
import com.blizzard.weather.BlizzardReflection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * Клиентский рендер метели: снежинки (3D) + пост-процесс (туман и небо).
 *
 * Снежинки:
 *   - рисуются вокруг игрока в радиусе radius блоков;
 *   - позиции и фазы падения генерируются детерминированно из (x, z, q) через murmur3;
 *   - каждый квад — билборд, повёрнутый к камере;
 *   - размер, alpha и количество квадов пропорциональны blizzardStrength.
 *
 * Пост-процесс:
 *   - копирует кадр в copyFramebuffer и глубину в depthTexture;
 *   - шейдер применяет туман по глубине и осветление неба;
 *   - сила эффекта плавно смешивается по blizzardStrength.
 *
 * Порядок рендера:
 *   1. onRenderWorldLast (NORMAL) — снежинки (до Pintonium). Pintonium их перекрывает блоками.
 *   2. Pintonium рисует мир.
 *   3. onRenderWorldLastFog (LOWEST) — туман для ваниллы (после того, как FBO заполнен).
 *      В Pintonium туман рисуется через renderFromPintonium (ASM-хук).
 */
@SideOnly(Side.CLIENT)
public class BlizzardShader {

    /** Minecraft instance (для доступа к player, world, displayWidth и т.п.). */
    private static final Minecraft mc = Minecraft.getMinecraft();

    /** GLSL-программа пост-процесса. -1, если не инициализирована. */
    private static int shaderProgram = -1;

    /** Флаг: инициализация шейдера уже выполнена (успешно или с ошибкой). */
    private static boolean initialized = false;

    /** FBO для копии цвета кадра перед пост-процессом. */
    private static Framebuffer copyFramebuffer = null;

    /** GL-текстура для копии буфера глубины кадра. */
    private static int depthTexture = -1;

    /** Последние известные размеры экрана (для пересоздания FBO при resize). */
    private static int lastWidth = 0, lastHeight = 0;

    /** Флаг: активен ли Pintonium (туман рисуется в ASM-хуке). */
    private static boolean pintoniumActive = false;

    /** Возвращает true, если Pintonium активен. */
    public static boolean isPintoniumActive() {
        return pintoniumActive;
    }

    /** Сохранённые матрицы для ASM-хука (переиспользуются, не аллоцируются каждый кадр). */
    private static final FloatBuffer savedModelView = BufferUtils.createFloatBuffer(16);
    private static final FloatBuffer savedProjection = BufferUtils.createFloatBuffer(16);
    private static boolean matricesSaved = false;

    // ====== Настройки визуала ======

    private static float getFogShelterFloor() { return (float) ClientVisualConfig.getFogShelterFloor(); }
    private static float getFogStartMin() { return (float) ClientVisualConfig.getFogStartMin(); }
    private static float getFogStartMax() { return (float) ClientVisualConfig.getFogStartMax(); }
    private static float getFogEndMin() { return (float) ClientVisualConfig.getFogEndMin(); }
    private static float getFogEndMax() { return (float) ClientVisualConfig.getFogEndMax(); }

    /** Атлас снежинок 2×2 (4 варианта). */
    private static final ResourceLocation SNOWFLAKES_ATLAS =
            new ResourceLocation("blizzard:textures/environment/blizzard_snowflakes.png");

    // ====== Шейдер ======

    /** Вершинный шейдер: передаёт texCoord и применяет стандартную матрицу. */
    private static final String VERTEX_SHADER =
            "#version 120\n" +
                    "varying vec2 texCoord;\n" +
                    "void main() {\n" +
                    "    gl_Position = ftransform();\n" +
                    "    texCoord = gl_MultiTexCoord0.xy;\n" +
                    "}\n";

    /**
     * Фрагментный шейдер:
     *  - вычисляет линейную глубину;
     *  - определяет небо по far plane;
     *  - осветляет/десатурирует небо только днём (dayFactor из Java);
     *  - применяет туман по глубине (fogColor из Java, уже с учётом ночи);
     *  - смешивает результат с исходным по blizzardStrength.
     */
    private static final String FRAGMENT_SHADER =
            "#version 120\n" +
                    "varying vec2 texCoord;\n" +
                    "uniform sampler2D gtexture;\n" +
                    "uniform sampler2D depthTexture;\n" +
                    "uniform float fogStart;\n" +
                    "uniform float fogEnd;\n" +
                    "uniform float fogCurve;\n" +
                    "uniform float blizzardStrength;\n" +
                    "uniform vec3 fogColor;\n" +
                    "uniform vec3 skyColor;\n" +
                    "uniform float projNear;\n" +
                    "uniform float projFar;\n" +
                    "uniform float skyInterpEnd;\n" +
                    "uniform float skyInterpCurve;\n" +
                    "\n" +
                    "float linearizeDepth(float depth) {\n" +
                    "    float z_n = 2.0 * depth - 1.0;\n" +
                    "    return 2.0 * projNear * projFar / (projFar + projNear - z_n * (projFar - projNear));\n" +
                    "}\n" +
                    "\n" +
                    "void main() {\n" +
                    "    vec2 uv = texCoord;\n" +
                    "    float rawDepth = texture2D(depthTexture, uv).r;\n" +
                    "    vec4 current = texture2D(gtexture, uv);\n" +
                    "    \n" +
                    "    float d = linearizeDepth(rawDepth);\n" +
                    "    \n" +
                    "    // ★ skyFactor от fogEnd до projFar * skyInterpEnd.\n" +
                    "    //   Сначала блок полностью в тумане (fogFactor = 1 на fogEnd),\n" +
                    "    //   потом туман плавно переходит в небо (skyColor).\n" +
                    "    //   Блок не смешивается с небом — только fogColor → skyColor.\n" +
                    "    float skyFactor = smoothstep(fogEnd, projFar * skyInterpEnd, d);\n" +
                    "    skyFactor = pow(skyFactor, skyInterpCurve);\n" +
                    "    \n" +
                    "    // Туман: цвет уже с учётом ночи (из Java).\n" +
                    "    float fogFactor = smoothstep(fogStart, fogEnd, d);\n" +
                    "    fogFactor = pow(fogFactor, fogCurve);\n" +
                    "    vec3 fogged = mix(current.rgb, fogColor, fogFactor);\n" +
                    "    \n" +
                    "    vec3 blizzardResult = mix(fogged, skyColor, skyFactor);\n" +
                    "    vec3 finalColor = mix(current.rgb, blizzardResult, blizzardStrength);\n" +
                    "    \n" +
                    "    gl_FragColor = vec4(finalColor, 1.0);\n" +
                    "}\n";

    /**
     * Компилирует и линкует шейдер один раз.
     * Если GL не поддерживает ARB_shader_objects — тихо выходит.
     */
    private static void initShader() {
        if (initialized) return;
        initialized = true;

        int vs = 0, fs = 0;
        try {
            if (!GLContext.getCapabilities().GL_ARB_shader_objects) return;

            vs = ARBShaderObjects.glCreateShaderObjectARB(ARBVertexShader.GL_VERTEX_SHADER_ARB);
            ARBShaderObjects.glShaderSourceARB(vs, VERTEX_SHADER);
            ARBShaderObjects.glCompileShaderARB(vs);
            checkShaderError(vs, "VERTEX");

            fs = ARBShaderObjects.glCreateShaderObjectARB(ARBFragmentShader.GL_FRAGMENT_SHADER_ARB);
            ARBShaderObjects.glShaderSourceARB(fs, FRAGMENT_SHADER);
            ARBShaderObjects.glCompileShaderARB(fs);
            checkShaderError(fs, "FRAGMENT");

            shaderProgram = ARBShaderObjects.glCreateProgramObjectARB();
            ARBShaderObjects.glAttachObjectARB(shaderProgram, vs);
            ARBShaderObjects.glAttachObjectARB(shaderProgram, fs);
            ARBShaderObjects.glLinkProgramARB(shaderProgram);

            int linked = ARBShaderObjects.glGetObjectParameteriARB(shaderProgram,
                    ARBShaderObjects.GL_OBJECT_LINK_STATUS_ARB);
            if (linked == GL11.GL_FALSE) {
                String log = ARBShaderObjects.glGetInfoLogARB(shaderProgram, 4096);
                System.err.println("[Blizzard] Program link error:\n" + log);
                ARBShaderObjects.glDeleteObjectARB(shaderProgram);
                shaderProgram = -1;
            }
        } catch (Exception e) {
            e.printStackTrace();
            shaderProgram = -1;
        } finally {
            if (vs != 0) ARBShaderObjects.glDeleteObjectARB(vs);
            if (fs != 0) ARBShaderObjects.glDeleteObjectARB(fs);
        }
    }

    /**
     * Проверяет статус компиляции шейдера и выводит лог в stderr при ошибке.
     */
    private static void checkShaderError(int shader, String type) {
        int compiled = ARBShaderObjects.glGetObjectParameteriARB(shader,
                ARBShaderObjects.GL_OBJECT_COMPILE_STATUS_ARB);
        if (compiled == GL11.GL_FALSE) {
            String log = ARBShaderObjects.glGetInfoLogARB(shader, 4096);
            System.err.println("[Blizzard] " + type + " shader compile error:");
            System.err.println(log);
        }
    }

    /**
     * Рендерит снежинки вокруг игрока (3D, до пост-процесса).
     */
    /**
     * Рендерит снежинки вокруг игрока (3D).
     *
     * Вызывается ПОСЛЕ тумана:
     *   - в ванилле — из onRenderWorldLastFog (LOWEST), после renderFogOnly;
     *   - в Pintonium — из renderFromPintonium, после renderFogOnly.
     *
     * Цвет снежинок = цвет тумана + 0.2 (чуть светлее), чтобы они были
     * заметны на фоне тумана и днём, и ночью.
     */
    private static void renderBlizzardSnow(float partialTicks, float blizzardStrength) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player == null || mc.world == null) return;
        if (blizzardStrength <= 0.01F) return;

        World world = mc.world;
        Entity entity = mc.getRenderViewEntity();
        if (entity == null) return;

        // ====== Параметры рендера ======
        final int radius = ClientVisualConfig.getSnowRadius();
        final int quadsPerChunk = Math.max(1, (int)(ClientVisualConfig.getSnowQuadsPerBlock() * blizzardStrength));
        final float halfSize = (float)ClientVisualConfig.getSnowSize() * blizzardStrength;
        final float alpha = blizzardStrength;
        final int fallCycle = ClientVisualConfig.getSnowFallCycle();
        final double windMultiplier = ClientVisualConfig.getSnowWindMultiplier();
        final double tileSize = 0.5;

        // Цвет снежинок = цвет тумана, но чуть светлее (на 10%)
        float[] fogColor = getFogColor();
        final float snowR = Math.min(1.0f, fogColor[0] + 0.1f);
        final float snowG = Math.min(1.0f, fogColor[1] + 0.1f);
        final float snowB = Math.min(1.0f, fogColor[2] + 0.1f);

        int i = MathHelper.floor(entity.posX);
        int k = MathHelper.floor(entity.posZ);
        double j = entity.lastTickPosY + (entity.posY - entity.lastTickPosY) * partialTicks;

        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder bufferbuilder = tessellator.getBuffer();

        // Настройка GL для прозрачных снежинок
        GlStateManager.enableDepth();
        GlStateManager.depthMask(false);
        GlStateManager.disableCull();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(
                GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE,
                GlStateManager.DestFactor.ZERO);
        GlStateManager.alphaFunc(516, 0.1F);

        // Позиция камеры с интерполяцией
        double camX = entity.lastTickPosX + (entity.posX - entity.lastTickPosX) * partialTicks;
        double camY = j;
        double camZ = entity.lastTickPosZ + (entity.posZ - entity.lastTickPosZ) * partialTicks;

        Vec3d look = entity.getLookVec();
        double lx = look.x, ly = look.y, lz = look.z;

        long worldTime = world.getTotalWorldTime();

        bufferbuilder.setTranslation(-camX, -camY, -camZ);
        bufferbuilder.begin(GL11.GL_QUADS, DefaultVertexFormats.PARTICLE_POSITION_TEX_COLOR_LMAP);

        mc.getTextureManager().bindTexture(SNOWFLAKES_ATLAS);

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        // ====== Проход по чанкам вокруг игрока ======
        for (int k1 = k - radius; k1 <= k + radius; ++k1) {
            for (int l1 = i - radius; l1 <= i + radius; ++l1) {
                for (int q = 0; q < quadsPerChunk; q++) {
                    // Детерминированный murmur3-хэш от (x, z, q)
                    long seed = ((long) l1 << 32) | (((long) k1 & 0xFFFFL) << 16) | (long) q;
                    long h = seed;
                    h ^= (h >>> 33); h *= 0xff51afd7ed558ccdL;
                    h ^= (h >>> 33); h *= 0xc4ceb9fe1a85ec53L;
                    h ^= (h >>> 33);

                    // Параметры снежинки
                    double offX = (((h >>  0) & 0xFF) / 255.0 - 0.5) * 0.9;
                    double offZ = (((h >>  8) & 0xFF) / 255.0 - 0.5) * 0.9;
                    double offY = (((h >> 16) & 0xFF) / 255.0) * 3.0;
                    double phase = (((h >> 24) & 0xFF) / 255.0) * fallCycle;
                    int tileX = (int) ((h >> 32) & 1);
                    int tileY = (int) ((h >> 33) & 1);

                    // UV тайла атласа
                    double u0 = tileX * tileSize;
                    double v0 = tileY * tileSize;
                    double u1 = u0 + tileSize;
                    double v1 = v0 + tileSize;

                    // Прогресс падения [0..1)
                    double fallProgress = (((worldTime + (long) phase) % fallCycle) + partialTicks) / (double) fallCycle;
                    if (fallProgress > 0.95) continue;

                    // Снос по ветру
                    double windShift = fallProgress * windMultiplier;

                    // Реальная позиция X/Z
                    double sx = l1 + offX + 0.5 + windShift;
                    double sz = k1 + offZ + 0.5 + windShift * 0.5;

                    // Высота поверхности в фактической (снесённой) точке
                    int rx = MathHelper.floor(sx);
                    int rz = MathHelper.floor(sz);
                    pos.setPos(rx, 0, rz);
                    int j2 = world.getPrecipitationHeight(pos).getY();
                    if (j2 <= 0) j2 = world.getHeight(rx, rz);
                    if (j2 <= 0) j2 = (int) j;

                    // Высота поверхности в точке спавна (без сноса)
                    int sx0 = MathHelper.floor(l1 + offX + 0.5);
                    int sz0 = MathHelper.floor(k1 + offZ + 0.5);
                    pos.setPos(sx0, 0, sz0);
                    int j2Spawn = world.getPrecipitationHeight(pos).getY();
                    if (j2Spawn <= 0) j2Spawn = world.getHeight(sx0, sz0);
                    if (j2Spawn <= 0) j2Spawn = (int) j;

                    double fallTop = j + 12;
                    double fallBottom = j - 10;
                    double span = fallTop - fallBottom;

                    double floorY = Math.max((double) Math.max(j2, j2Spawn), fallBottom);
                    if (floorY >= fallTop) continue;

                    double sy = fallBottom + span * (1.0 - fallProgress) + offY;
                    if (sy <= floorY) continue;

                    // Отсечение снежинок за камерой
                    double vx = sx - camX;
                    double vy = sy - camY;
                    double vz = sz - camZ;
                    if (vx * lx + vy * ly + vz * lz < -8.0) continue;

                    // Вектор от снежинки к камере (нормализуем)
                    double dx = -vx, dy = -vy, dz = -vz;
                    double lenSq = dx * dx + dy * dy + dz * dz;
                    if (lenSq < 0.0001) continue;
                    double inv = 1.0 / Math.sqrt(lenSq);
                    dx *= inv; dy *= inv; dz *= inv;

                    // right = cross((0,1,0), view)
                    double rlen = Math.sqrt(dz * dz + dx * dx);
                    double rx2, rz2;
                    if (rlen < 0.01) {
                        rx2 = 1; rz2 = 0;
                    } else {
                        rx2 = dz / rlen;
                        rz2 = -dx / rlen;
                    }

                    // up = cross(view, right)
                    double ux = dy * rz2;
                    double uy = dz * rx2 - dx * rz2;
                    double uz = -dy * rx2;

                    // 4 угла билборда
                    double x0 = sx - rx2 * halfSize - ux * halfSize;
                    double y0 = sy                 - uy * halfSize;
                    double z0 = sz - rz2 * halfSize - uz * halfSize;
                    double x1 = sx + rx2 * halfSize - ux * halfSize;
                    double y1 = sy                 - uy * halfSize;
                    double z1 = sz + rz2 * halfSize - uz * halfSize;
                    double x2 = sx + rx2 * halfSize + ux * halfSize;
                    double y2 = sy                 + uy * halfSize;
                    double z2 = sz + rz2 * halfSize + uz * halfSize;
                    double x3 = sx - rx2 * halfSize + ux * halfSize;
                    double y3 = sy                 + uy * halfSize;
                    double z3 = sz - rz2 * halfSize + uz * halfSize;

                    // Полный свет (снежинки всегда светятся)
                    int lightU = 240;
                    int lightV = 240;

                    bufferbuilder.pos(x0, y0, z0).tex(u0, v1).color(snowR, snowG, snowB, alpha).lightmap(lightU, lightV).endVertex();
                    bufferbuilder.pos(x1, y1, z1).tex(u1, v1).color(snowR, snowG, snowB, alpha).lightmap(lightU, lightV).endVertex();
                    bufferbuilder.pos(x2, y2, z2).tex(u1, v0).color(snowR, snowG, snowB, alpha).lightmap(lightU, lightV).endVertex();
                    bufferbuilder.pos(x3, y3, z3).tex(u0, v0).color(snowR, snowG, snowB, alpha).lightmap(lightU, lightV).endVertex();
                }
            }
        }

        tessellator.draw();

        bufferbuilder.setTranslation(0.0D, 0.0D, 0.0D);
        GlStateManager.enableCull();
        GlStateManager.disableBlend();
        GlStateManager.depthMask(true);
        GlStateManager.alphaFunc(516, 0.1F);
    }

    // ==================== ОСНОВНОЙ ПОСТ-ПРОЦЕСС ====================

    /**
     * Главный обработчик рендера: ТОЛЬКО СНЕЖИНКИ (3D).
     * Туман рисуется отдельно (см. onRenderWorldLastFog / renderFromPintonium).
     */
    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        if (mc.player == null || mc.world == null) return;

        float blizzardStrength = BlizzardReflection.getBlizzardStrength(mc.world);
        if (blizzardStrength <= 0.01F) return;

        // Сохраняем матрицы для ASM-хука (переиспользуем буферы)
        savedModelView.clear();
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, savedModelView);
        savedProjection.clear();
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, savedProjection);
        matricesSaved = true;

        // Снежинки рисуются ПОСЛЕ тумана:
        // В Pintonium — в renderFromPintonium (после тумана).
        /*if (!isPintoniumActive()) {
            renderBlizzardSnow(event.getPartialTicks(), blizzardStrength);
        }*/


    }

    /**
     * Обработчик рендера тумана для ВАНИЛЛЫ (без Pintonium).
     * Вызывается с LOWEST priority, чтобы быть после снежинок и после того,
     * как главный FBO заполнен. В Pintonium туман рисуется через ASM-хук.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onRenderWorldLastFog(RenderWorldLastEvent event) {
        if (mc.player == null || mc.world == null) {
            pintoniumActive = false;
            return;
        }

        float blizzardStrength = BlizzardReflection.getBlizzardStrength(mc.world);
        if (blizzardStrength <= 0.01F) {
            pintoniumActive = false;
            return;
        }

        if (isPintoniumActive()) {
            // Pintonium уже нарисовал туман в этом кадре — пропускаем и сбрасываем флаг
            pintoniumActive = false;
            return;
        }

        // 1. Туман
        renderFogOnly(blizzardStrength, -1);
        // 2. Снежинки ПОВЕРХ тумана
        renderBlizzardSnow(event.getPartialTicks(), blizzardStrength);
        pintoniumActive = false;
    }

    /**
     * Рисует ТОЛЬКО ТУМАН (пост-процессинг).
     * Не трогает снежинки и другие 3D-объекты.
     *
     * Цвет тумана вычисляется через getFogColor() — тот же метод,
     * что используется для цвета снежинок (снежинки = туман + 0.2).
     */
    private static void renderFogOnly(float blizzardStrength, int depthTexId) {
        initShader();
        if (shaderProgram == -1) return;

        // ===== Сохраняем GL-state =====
        int fboAtEntry = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int drawAtEntry = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int readAtEntry = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int prevMatrixMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        int prevActiveTex = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        int prevTexBind = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int prevBlendEq = GL11.glGetInteger(GL14.GL_BLEND_EQUATION);

        ByteBuffer prevColorMask = BufferUtils.createByteBuffer(16);
        GL11.glGetBoolean(GL11.GL_COLOR_WRITEMASK, prevColorMask);
        boolean cmR = prevColorMask.get(0) != 0;
        boolean cmG = prevColorMask.get(1) != 0;
        boolean cmB = prevColorMask.get(2) != 0;
        boolean cmA = prevColorMask.get(3) != 0;

        IntBuffer vpAtEntry = BufferUtils.createIntBuffer(16);
        GL11.glGetInteger(GL11.GL_VIEWPORT, vpAtEntry);

        int w = mc.displayWidth;
        int h = mc.displayHeight;

        if (copyFramebuffer == null || w != lastWidth || h != lastHeight) {
            if (copyFramebuffer != null) copyFramebuffer.deleteFramebuffer();
            copyFramebuffer = new Framebuffer(w, h, false);
            createDepthTexture(w, h);
            lastWidth = w;
            lastHeight = h;
        }

        if (depthTexId == -1) {
            // Ванилла: копируем глубину из главного FBO в свою текстуру
            copyDepthToTexture(w, h);
        }

        FloatBuffer projBuf = BufferUtils.createFloatBuffer(16);
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, projBuf);
        float[] proj = new float[16];
        projBuf.get(proj);

        float projNear = Math.abs(proj[14] / (proj[10] - 1.0f));
        float projFar = Math.abs(proj[14] / (proj[10] + 1.0f));
        if (projNear < 0.01f || Float.isNaN(projNear)) projNear = 0.05f;
        if (projFar < projNear || Float.isNaN(projFar)) projFar = 1000.0f;

        GlStateManager.pushAttrib();
        GL11.glPushClientAttrib(-1);

        int oldTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int oldDraw = GL11.glGetInteger(GL11.GL_DRAW_BUFFER);
        int oldRead = GL11.glGetInteger(GL11.GL_READ_BUFFER);

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, copyFramebuffer.framebufferTexture);
        GL11.glCopyTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 0, 0, w, h, 0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, oldTex);

        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glOrtho(0, w, h, 0, -1, 1);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();

        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_FOG);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_COLOR_MATERIAL);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GL11.glNormal3f(0f, 0f, 1f);

        ARBShaderObjects.glUseProgramObjectARB(shaderProgram);

        setUniform1i(shaderProgram, "gtexture", 0);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, copyFramebuffer.framebufferTexture);

        setUniform1i(shaderProgram, "depthTexture", 2);
        GL13.glActiveTexture(GL13.GL_TEXTURE2);

        int depthToUse = (depthTexId != -1) ? depthTexId : depthTexture;
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthToUse);

        float exposure = BlizzardClientExposure.getExposure();
        float fogStrength = blizzardStrength * (getFogShelterFloor() + (1f - getFogShelterFloor()) * exposure);
        float dynamicFogStart = getFogStartMax() - (getFogStartMax() - getFogStartMin()) * fogStrength;
        float dynamicFogEnd   = getFogEndMax() - (getFogEndMax() - getFogEndMin()) * fogStrength;

        setUniform1f(shaderProgram, "fogStart", dynamicFogStart);
        setUniform1f(shaderProgram, "fogEnd", dynamicFogEnd);
        setUniform1f(shaderProgram, "fogCurve", (float) ClientVisualConfig.getFogCurve());
        setUniform1f(shaderProgram, "blizzardStrength", blizzardStrength);

        // ===== Цвет тумана и неба с учётом времени суток =====
        float celestialAngle = mc.world.getCelestialAngle(mc.getRenderPartialTicks());
        float nightFactor = 1.0f - Math.abs(celestialAngle * 2.0f - 1.0f);
        float dayFactor = 1.0f - nightFactor;

        float[] fogColor = getFogColor();
        setUniform3f(shaderProgram, "fogColor", fogColor[0], fogColor[1], fogColor[2]);

        // ★ Цвет неба вычисляется в Java (не из current.rgb).
        //   Базовый цвет (голубое днём, тёмное ночью) + десатурация + осветление.
        float[] skyColor = getSkyColor(nightFactor, dayFactor);
        setUniform3f(shaderProgram, "skyColor", skyColor[0], skyColor[1], skyColor[2]);

        setUniform1f(shaderProgram, "projNear", projNear);
        setUniform1f(shaderProgram, "projFar", projFar);
        setUniform1f(shaderProgram, "skyInterpEnd", (float) ClientVisualConfig.getSkyInterpEnd());
        setUniform1f(shaderProgram, "skyInterpCurve", (float) ClientVisualConfig.getSkyInterpCurve());

        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
        buf.pos(0, h, 0).tex(0, 0).endVertex();
        buf.pos(w, h, 0).tex(1, 0).endVertex();
        buf.pos(w, 0, 0).tex(1, 1).endVertex();
        buf.pos(0, 0, 0).tex(0, 1).endVertex();
        tess.draw();

        ARBShaderObjects.glUseProgramObjectARB(0);

        GL13.glActiveTexture(GL13.GL_TEXTURE2);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, oldTex);
        GL11.glDrawBuffer(oldDraw);
        GL11.glReadBuffer(oldRead);

        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPopMatrix();

        GL11.glPopClientAttrib();
        GlStateManager.popAttrib();

        GL11.glColorMask(cmR, cmG, cmB, cmA);
        GL14.glBlendEquation(prevBlendEq);
        GL11.glMatrixMode(prevMatrixMode);
        GL20.glUseProgram(prevProgram);

        GL13.glActiveTexture(prevActiveTex);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTexBind);

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fboAtEntry);
        GL11.glViewport(vpAtEntry.get(0), vpAtEntry.get(1), vpAtEntry.get(2), vpAtEntry.get(3));
    }

    // ==================== ГЛУБИНА ====================

    private static void createDepthTexture(int w, int h) {
        if (depthTexture != -1) GL11.glDeleteTextures(depthTexture);
        depthTexture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTexture);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_DEPTH_COMPONENT, w, h, 0,
                GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, (ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
    }

    private static void copyDepthToTexture(int w, int h) {
        if (depthTexture == -1) return;

        int mainFbo = mc.getFramebuffer().framebufferObject;
        int tempFbo = GL30.glGenFramebuffers();

        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, tempFbo);
        GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                GL11.GL_TEXTURE_2D, depthTexture, 0);

        if (GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE) {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, mainFbo);
            GL30.glBlitFramebuffer(0, 0, w, h, 0, 0, w, h,
                    GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
        }

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, mainFbo);
        GL30.glDeleteFramebuffers(tempFbo);
    }

    /**
     * Возвращает цвет неба (r, g, b) с учётом времени суток,
     * десатурации и осветления (из конфига).
     *
     * Базовый цвет: голубое днём, тёмно-синее ночью.
     * Десатурация (skyDesaturate) — обесцвечивание.
     * Серость (skyGray) — сдвиг к серому.
     * Осветление (skyWhiten) — сдвиг к белому (только днём).
     */
    private static float[] getSkyColor(float nightFactor, float dayFactor) {
        // Базовый цвет неба: голубое днём, тёмное ночью
        float dayR = 0.5f, dayG = 0.7f, dayB = 1.0f;
        float nightR = 0.05f, nightG = 0.05f, nightB = 0.15f;

        float r = dayR + (nightR - dayR) * nightFactor;
        float g = dayG + (nightG - dayG) * nightFactor;
        float b = dayB + (nightB - dayB) * nightFactor;

        // Десатурация (skyDesaturate)
        float gray = 0.299f * r + 0.587f * g + 0.114f * b;
        float desat = (float) ClientVisualConfig.getSkyDesaturate() * dayFactor;
        r = r + (gray - r) * desat;
        g = g + (gray - g) * desat;
        b = b + (gray - b) * desat;

        // Серость (skyGray)
        float grayShift = (float) ClientVisualConfig.getSkyGray() * dayFactor;
        r = r + (0.5f - r) * grayShift;
        g = g + (0.5f - g) * grayShift;
        b = b + (0.5f - b) * grayShift;

        // Осветление (skyWhiten)
        float whiten = (float) ClientVisualConfig.getSkyWhiten() * dayFactor;
        r = r + (1.0f - r) * whiten;
        g = g + (1.0f - g) * whiten;
        b = b + (1.0f - b) * whiten;

        return new float[]{ r, g, b };
    }

    // ==================== UNIFORM ====================

    private static void setUniform1i(int p, String n, int v) {
        int l = ARBShaderObjects.glGetUniformLocationARB(p, n);
        if (l != -1) ARBShaderObjects.glUniform1iARB(l, v);
    }

    private static void setUniform1f(int p, String n, float v) {
        int l = ARBShaderObjects.glGetUniformLocationARB(p, n);
        if (l != -1) ARBShaderObjects.glUniform1fARB(l, v);
    }

    private static void setUniform3f(int p, String n, float x, float y, float z) {
        int l = ARBShaderObjects.glGetUniformLocationARB(p, n);
        if (l != -1) ARBShaderObjects.glUniform3fARB(l, x, y, z);
    }

    /**
     * Вызывается из BlizzardHooks.onIrisFinalPass() (ASM-хук Pintonium).
     * Устанавливает depth-текстуру Pintonium и рисует туман.
     */
    public static void renderFromPintonium(int depthTexId) {
        pintoniumActive = true;
        if (mc.player == null || mc.world == null) return;

        float blizzardStrength = BlizzardReflection.getBlizzardStrength(mc.world);
        if (blizzardStrength <= 0.01F) return;

        // 1. Туман
        renderFogOnly(blizzardStrength, depthTexId);

        // 2. Копируем depth Pintonium в главный FBO
        if (depthTexId != -1) {
            copyPintoniumDepthToMainFbo(depthTexId);
        }

        // 3. Восстанавливаем матрицы (туман их сбил)
        if (matricesSaved) {
            savedProjection.rewind();
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glLoadMatrix(savedProjection);

            savedModelView.rewind();
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glLoadMatrix(savedModelView);

            matricesSaved = false;
        }

        // 4. Снежинки с depth test (не видны сквозь блоки, но поверх тумана)
        renderBlizzardSnow(1.0f, blizzardStrength);
    }

    private static void copyPintoniumDepthToMainFbo(int depthTexId) {
        if (depthTexId == -1) return;
        if (!GLContext.getCapabilities().OpenGL30) return;

        int w = mc.displayWidth;
        int h = mc.displayHeight;
        int mainFbo = mc.getFramebuffer().framebufferObject;

        int tempFbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, tempFbo);
        GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                GL11.GL_TEXTURE_2D, depthTexId, 0);

        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, mainFbo);

        if (GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE
                && GL30.glCheckFramebufferStatus(GL30.GL_READ_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE) {
            GL30.glBlitFramebuffer(0, 0, w, h, 0, 0, w, h,
                    GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
        }

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, mainFbo);
        GL30.glDeleteFramebuffers(tempFbo);
    }

    /**
     * Возвращает текущий цвет тумана (r, g, b) с учётом времени суток.
     * Используется и для тумана (шейдер), и для снежинок (vertex color).
     */
    private static float[] getFogColor() {
        float celestialAngle = mc.world.getCelestialAngle(mc.getRenderPartialTicks());
        float nightFactor = 1.0f - Math.abs(celestialAngle * 2.0f - 1.0f);

        float dayR = 1.0f, dayG = 1.0f, dayB = 1.0f;
        float nightR = 0.15f, nightG = 0.2f, nightB = 0.4f;

        float r = dayR + (nightR - dayR) * nightFactor;
        float g = dayG + (nightG - dayG) * nightFactor;
        float b = dayB + (nightB - dayB) * nightFactor;
        return new float[]{ r, g, b };
    }

    // ==================== DISPOSE ====================

    /**
     * Удаляет GL-ресурсы мода. Вызывать при выгрузке мира / смене контекста.
     * Каждый GL-вызов в try/catch, чтобы при мёртвом контексте не накапливать GL 1282.
     */
    public static void dispose() {
        try {
            if (copyFramebuffer != null) {
                copyFramebuffer.deleteFramebuffer();
            }
        } catch (Throwable t) {
            // Контекст мёртв — пропускаем
        }
        copyFramebuffer = null;

        try {
            if (depthTexture != -1) {
                GL11.glDeleteTextures(depthTexture);
            }
        } catch (Throwable t) {
            // Контекст мёртв — пропускаем
        }
        depthTexture = -1;

        try {
            if (shaderProgram != -1) {
                ARBShaderObjects.glDeleteObjectARB(shaderProgram);
            }
        } catch (Throwable t) {
            // Контекст мёртв — пропускаем
        }
        shaderProgram = -1;

        initialized = false;
        lastWidth = lastHeight = 0;
        matricesSaved = false;
        pintoniumActive = false;
    }

    /**
     * Вызывается при отключении от сервера (в Netty-потоке).
     * Планируем dispose в главном потоке, где GL-контекст ещё активен.
     */
    @SubscribeEvent
    public void onClientDisconnect(net.minecraftforge.fml.common.network.FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        Minecraft.getMinecraft().addScheduledTask(() -> {
            try {
                dispose();
                com.blizzard.raycast.BlizzardClientExposure.reset();
            } catch (Throwable t) {
                com.blizzard.Main.LOGGER.warn("[Blizzard] dispose() failed on disconnect: {}", t.getMessage());
            }
        });
    }

    @SubscribeEvent
    public void onWorldLoad(net.minecraftforge.event.world.WorldEvent.Load event) {
        if (event.getWorld().isRemote) {
            BlizzardClientExposure.reset();  // сброс, чтобы первый пакет установил мгновенно
        }
    }


}