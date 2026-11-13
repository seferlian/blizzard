package com.blizzard.client;

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
 *   - Включает frame-rate-независимое сглаживание силы тумана,
 *     чтобы туман не дёргался при ступенчатом обновлении exposure с сервера.
 *
 * Всё активируется только при blizzardStrength > 0.01F.
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

    // ====== Настройки визуала ======

    private static float getFogShelterFloor() { return (float) ConfigHandler.visual.fogShelterFloor; }
    private static float getFogStartMin() { return (float) ConfigHandler.visual.fogStartMin; }
    private static float getFogStartMax() { return (float) ConfigHandler.visual.fogStartMax; }
    private static float getFogEndMin() { return (float) ConfigHandler.visual.fogEndMin; }
    private static float getFogEndMax() { return (float) ConfigHandler.visual.fogEndMax; }

    /** Цвет тумана (белый). */
    private static final float FOG_R = 1.0f;
    private static final float FOG_G = 1.0f;
    private static final float FOG_B = 1.0f;

    /** Атлас снежинок 2×2 (4 варианта). */
    private static final ResourceLocation SNOWFLAKES_ATLAS =
            new ResourceLocation("blizzard:textures/environment/blizzard_snowflakes.png");

    // ====== Сглаживание тумана ======
    /** Текущее сглаженное значение силы тумана. */
    private static float smoothedFogStrength = 0.0f;
    /** Время последнего обновления сглаживания (наносекунды). */
    private static long lastFogUpdateNano = 0;

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
     *  - осветляет/десатурирует небо только днём;
     *  - применяет туман по глубине;
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
                    "uniform float skyWhiten;\n" +
                    "uniform float skyDesaturate;\n" +
                    "uniform float skyGray;\n" +
                    "uniform float blizzardStrength;\n" +
                    "uniform vec3 fogColor;\n" +
                    "uniform float projNear;\n" +
                    "uniform float projFar;\n" +
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
                    "    float skyFactor = step(projFar * 0.995, d);\n" +
                    "    \n" +
                    "    float brightness = dot(current.rgb, vec3(0.299, 0.587, 0.114));\n" +
                    "    float dayFactor = smoothstep(0.1, 0.3, brightness);\n" +
                    "    \n" +
                    "    float gray = dot(current.rgb, vec3(0.299, 0.587, 0.114));\n" +
                    "    vec3 desaturated = mix(current.rgb, vec3(gray), skyDesaturate * dayFactor);\n" +
                    "    vec3 grayed = mix(desaturated, vec3(0.5), skyGray * dayFactor);\n" +
                    "    vec3 skyColor = mix(grayed, vec3(1.0), skyWhiten * dayFactor);\n" +
                    "    \n" +
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
        try {
            if (!GLContext.getCapabilities().GL_ARB_shader_objects) return;

            int vs = ARBShaderObjects.glCreateShaderObjectARB(ARBVertexShader.GL_VERTEX_SHADER_ARB);
            ARBShaderObjects.glShaderSourceARB(vs, VERTEX_SHADER);
            ARBShaderObjects.glCompileShaderARB(vs);
            checkShaderError(vs, "VERTEX");

            int fs = ARBShaderObjects.glCreateShaderObjectARB(ARBFragmentShader.GL_FRAGMENT_SHADER_ARB);
            ARBShaderObjects.glShaderSourceARB(fs, FRAGMENT_SHADER);
            ARBShaderObjects.glCompileShaderARB(fs);
            checkShaderError(fs, "FRAGMENT");

            shaderProgram = ARBShaderObjects.glCreateProgramObjectARB();
            ARBShaderObjects.glAttachObjectARB(shaderProgram, vs);
            ARBShaderObjects.glAttachObjectARB(shaderProgram, fs);
            ARBShaderObjects.glLinkProgramARB(shaderProgram);

            ARBShaderObjects.glDeleteObjectARB(vs);
            ARBShaderObjects.glDeleteObjectARB(fs);
        } catch (Exception e) {
            e.printStackTrace();
            shaderProgram = -1;
        }
    }

    /**
     * Проверяет статус компиляции шейдера и выводит лог в stderr при ошибке.
     *
     * @param shader GL-хэндл шейдера;
     * @param type   "VERTEX" или "FRAGMENT" (для лога).
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
     * Количество квадов, размер и alpha пропорциональны blizzardStrength.
     *
     * @param partialTicks     интерполяция между тиками;
     * @param blizzardStrength сила метели (0..1).
     */
    private static void renderBlizzardSnow(float partialTicks, float blizzardStrength) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player == null || mc.world == null) return;
        if (blizzardStrength <= 0.01F) return;

        World world = mc.world;
        Entity entity = mc.getRenderViewEntity();
        if (entity == null) return;

        // ====== Параметры рендера ======
        final int radius = ConfigHandler.visual.snowRadius;                                                        // радиус спавна в блоках
        final int quadsPerChunk = Math.max(1, (int)(ConfigHandler.visual.snowQuadsPerBlock * blizzardStrength));   // квадов на блок
        final float halfSize = (float)ConfigHandler.visual.snowSize * blizzardStrength;                            // половина размера снежинки
        final float alpha = blizzardStrength;                                                                      // прозрачность
        final int fallCycle = 20;                                                                                  // тиков на полный цикл падения
        final double windMultiplier = ConfigHandler.visual.snowWindMultiplier;                                     // снос по X за цикл (блоков)
        final double tileSize = 0.5;                                                                               // 1/2 атласа (2x2)

        int i = MathHelper.floor(entity.posX);
        int k = MathHelper.floor(entity.posZ);
        double j = entity.lastTickPosY + (entity.posY - entity.lastTickPosY) * partialTicks;

        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder bufferbuilder = tessellator.getBuffer();

        // Настройка GL для прозрачных снежинок
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
                    double offY = (((h >> 16) & 0xFF) / 255.0) * 0.5;
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
                    if (fallProgress > 0.95) continue; // снежинка «умерла» у земли

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



                    /*// Низ траектории — не ниже поверхности ни в спавне, ни в текущей точке.
                    // Это не даёт снежинке опуститься ниже крыши/стены снесённого столбца.
                    double k2 = Math.max((double) Math.max(j2, j2Spawn), j - 10);
                    double l2 = j + 12;
                    if (k2 >= l2) continue;

                    double fallY = (l2 - k2) * (1.0 - fallProgress);
                    double sy = k2 + offY * (l2 - k2) * 0.5 + fallY;*/

                    double fallTop = j + 12;
                    double fallBottom = j - 10;
                    double span = fallTop - fallBottom;

                    double floorY = Math.max((double) Math.max(j2, j2Spawn), fallBottom);
                    if (floorY >= fallTop) continue;

                    double sy = fallBottom + span * (1.0 - fallProgress) + offY * 0.5;
                    if (sy <= floorY) continue;   // касание поверхности — смерть, без скольжения

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

                    // Полный свет (снежинки всегда белые, независимо от освещения)
                    int lightU = 240;
                    int lightV = 240;

                    // Заполняем вершины
                    bufferbuilder.pos(x0, y0, z0).tex(u0, v1).color(1.0F, 1.0F, 1.0F, alpha).lightmap(lightU, lightV).endVertex();
                    bufferbuilder.pos(x1, y1, z1).tex(u1, v1).color(1.0F, 1.0F, 1.0F, alpha).lightmap(lightU, lightV).endVertex();
                    bufferbuilder.pos(x2, y2, z2).tex(u1, v0).color(1.0F, 1.0F, 1.0F, alpha).lightmap(lightU, lightV).endVertex();
                    bufferbuilder.pos(x3, y3, z3).tex(u0, v0).color(1.0F, 1.0F, 1.0F, alpha).lightmap(lightU, lightV).endVertex();
                }
            }
        }

        tessellator.draw();

        bufferbuilder.setTranslation(0.0D, 0.0D, 0.0D);
        GlStateManager.enableCull();
        GlStateManager.disableBlend();
        GlStateManager.alphaFunc(516, 0.1F);
    }

    // ==================== ОСНОВНОЙ ПОСТ-ПРОЦЕСС ====================

    /**
     * Главный обработчик рендера: снежинки + шейдер.
     * Вызывается каждый кадр после рендера мира, но до HUD.
     *
     * @param event событие RenderWorldLastEvent.
     */
    /**
     * Главный обработчик рендера: снежинки + шейдер.
     * Вызывается каждый кадр после рендера мира, но до HUD.
     *
     * @param event событие RenderWorldLastEvent.
     */
    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        if (mc.player == null || mc.world == null) return;

        // Сила метели на клиенте (0..1). Приходит с пакетами каждый тик.
        float blizzardStrength = BlizzardReflection.getBlizzardStrength(mc.world);

        // Если метель закончилась, сбрасываем накопленное сглаживание тумана
        // и выходим, чтобы не рисовать лишнего.
        if (blizzardStrength <= 0.01F) {
            smoothedFogStrength = 0.0f;
            return;
        }

        float partialTicks = event.getPartialTicks();

        // 1. Снежинки (3D, до пост-процесса)
        renderBlizzardSnow(partialTicks, blizzardStrength);

        // 2. Шейдер (пост-процесс)
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

        // Пересоздаём FBO при первом кадре или resize
        if (copyFramebuffer == null || w != lastWidth || h != lastHeight) {
            if (copyFramebuffer != null) copyFramebuffer.deleteFramebuffer();
            copyFramebuffer = new Framebuffer(w, h, false);
            createDepthTexture(w, h);
            lastWidth = w;
            lastHeight = h;
        }

        // Копируем глубину в depthTexture
        copyDepthToTexture(w, h);

        // Извлекаем near/far из матрицы проекции
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

        // Копируем цвет экрана в copyFramebuffer
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, copyFramebuffer.framebufferTexture);
        GL11.glCopyTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 0, 0, w, h, 0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, oldTex);

        // Переключаемся на ортографическую проекцию 0..w, 0..h
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glOrtho(0, w, h, 0, -1, 1);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();

        // Отключаем всё, что может помешать рисовать полноэкранный квад
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_FOG);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_COLOR_MATERIAL);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GL11.glNormal3f(0f, 0f, 1f);

        // Биндим шейдер и текстуры
        ARBShaderObjects.glUseProgramObjectARB(shaderProgram);

        setUniform1i(shaderProgram, "gtexture", 0);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, copyFramebuffer.framebufferTexture);

        // depthTexture привязан к GL_TEXTURE2, чтобы не затронуть lightmap на GL_TEXTURE1
        setUniform1i(shaderProgram, "depthTexture", 2);
        GL13.glActiveTexture(GL13.GL_TEXTURE2);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTexture);

        // Настройки шейдера
        setUniform1f(shaderProgram, "skyDesaturate", (float) ConfigHandler.visual.skyDesaturate);
        setUniform1f(shaderProgram, "skyGray", (float) ConfigHandler.visual.skyGray);

        // Сила тумана с учётом укрытия
        float exposure = BlizzardClientExposure.getExposure();
        float fogStrength = blizzardStrength * (getFogShelterFloor() + (1f - getFogShelterFloor()) * exposure);

        // Динамический туман
        float dynamicFogStart = getFogStartMax() - (getFogStartMax() - getFogStartMin()) * fogStrength;
        float dynamicFogEnd   = getFogEndMax() - (getFogEndMax() - getFogEndMin()) * fogStrength;

        setUniform1f(shaderProgram, "fogStart", dynamicFogStart);
        setUniform1f(shaderProgram, "fogEnd", dynamicFogEnd);
        setUniform1f(shaderProgram, "fogCurve", (float) ConfigHandler.visual.fogCurve);
        setUniform1f(shaderProgram, "skyWhiten", (float) ConfigHandler.visual.skyWhiten);
        setUniform1f(shaderProgram, "blizzardStrength", blizzardStrength);
        setUniform3f(shaderProgram, "fogColor", FOG_R, FOG_G, FOG_B);
        setUniform1f(shaderProgram, "projNear", projNear);
        setUniform1f(shaderProgram, "projFar", projFar);

        // Полноэкранный квад
        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
        buf.pos(0, h, 0).tex(0, 0).endVertex();
        buf.pos(w, h, 0).tex(1, 0).endVertex();
        buf.pos(w, 0, 0).tex(1, 1).endVertex();
        buf.pos(0, 0, 0).tex(0, 1).endVertex();
        tess.draw();

        // Отвязываем шейдер
        ARBShaderObjects.glUseProgramObjectARB(0);

        // Восстанавливаем GL-state
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

        // Восстанавливаем то, что popAttrib не покрывает
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

    /**
     * Создаёт GL-текстуру для хранения глубины.
     * При resize — удаляет старую и создаёт новую.
     *
     * @param w ширина;
     * @param h высота.
     */
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

    /**
     * Копирует буфер глубины основного FBO в depthTexture через blitFramebuffer.
     *
     * @param w ширина;
     * @param h высота.
     */
    private static void copyDepthToTexture(int w, int h) {
        if (depthTexture == -1) return;

        int mainFbo = mc.getFramebuffer().framebufferObject;
        int tempFbo = GL30.glGenFramebuffers();

        // Привязываем depthTexture к временному FBO
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, tempFbo);
        GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                GL11.GL_TEXTURE_2D, depthTexture, 0);

        // Копируем глубину из основного FBO
        if (GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE) {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, mainFbo);
            GL30.glBlitFramebuffer(0, 0, w, h, 0, 0, w, h,
                    GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
        }

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, mainFbo);
        GL30.glDeleteFramebuffers(tempFbo);
    }

    // ==================== UNIFORM ====================

    /** Устанавливает int-uniform по имени. */
    private static void setUniform1i(int p, String n, int v) {
        int l = ARBShaderObjects.glGetUniformLocationARB(p, n);
        if (l != -1) ARBShaderObjects.glUniform1iARB(l, v);
    }

    /** Устанавливает float-uniform по имени. */
    private static void setUniform1f(int p, String n, float v) {
        int l = ARBShaderObjects.glGetUniformLocationARB(p, n);
        if (l != -1) ARBShaderObjects.glUniform1fARB(l, v);
    }

    /** Устанавливает vec3-uniform по имени. */
    private static void setUniform3f(int p, String n, float x, float y, float z) {
        int l = ARBShaderObjects.glGetUniformLocationARB(p, n);
        if (l != -1) ARBShaderObjects.glUniform3fARB(l, x, y, z);
    }
}