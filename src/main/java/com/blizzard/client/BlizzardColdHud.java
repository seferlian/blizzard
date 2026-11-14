package com.blizzard.client;

import com.blizzard.client.BlizzardClientCold;
import com.blizzard.weather.BlizzardHooks;
import com.blizzard.weather.BlizzardReflection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * HUD-оверлей накопленного холода в виде сердечек.
 *
 * При потере сердечка (полного или половинки) все сердечки (полные и пустые)
 * кратковременно мигают (используют текстуру с блеском).
 */
@SideOnly(Side.CLIENT)
public class BlizzardColdHud {

    private static final ResourceLocation TEXTURE =
            new ResourceLocation("blizzard", "textures/gui/cold_hearts.png");

    // ===== РАЗМЕРЫ ТЕКСТУРЫ =====
    private static final int TEX_W = 256;
    private static final int TEX_H = 256;

    // ===== КООРДИНАТЫ В ТЕКСТУРЕ (В ПИКСЕЛЯХ) =====
    private static final int HEART_SIZE = 9;

    private static final int U_FULL      = 0;
    private static final int U_HALF      = 10;
    private static final int U_CONTAINER = 20;

    /** Y координата обычного ряда с сердечками */
    private static final int V_HEART = 0;
    /** Y координата ряда с блеском (мигание) */
    private static final int V_HEART_BLINK = 10;

    // ===== РАЗМЕРЫ НА ЭКРАНЕ =====
    private static final int HEARTS_COUNT = 10;
    private static final int SPACING = 8;

    // ===== АНИМАЦИЯ МИГАНИЯ =====
    private static int lastHalf = -1;
    private static int flashTimer = 0;
    private static long lastBlinkTick = 0;

    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Post event) {
        if (event.getType() != RenderGameOverlayEvent.ElementType.ALL) return;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player == null) return;

        // Не рисуем шкалу в креативе, наблюдателе и после смерти
        if (mc.player.isCreative() || mc.player.isSpectator() || mc.player.isDead) {
            lastHalf = -1;
            flashTimer = 0;
            return;
        }

        float cold = BlizzardClientCold.getCold();
        boolean blizzardActive = BlizzardReflection.isBlizzard(mc.world);

        // Логика отображения
        if (!blizzardActive && cold <= 0.001f) {
            lastHalf = -1;
            flashTimer = 0;
            return;
        }

        ScaledResolution sr = new ScaledResolution(mc);
        int screenW = sr.getScaledWidth();
        int screenH = sr.getScaledHeight();

        int baseY = BlizzardHooks.getHudRightTopY();
        if (baseY <= 0) {
            baseY = screenH - 39;
        }

        int y = baseY - 1;

        int rightEdge = screenW / 2 + 91;
        int startX = rightEdge - (HEARTS_COUNT * SPACING) - 2;

        float healthRemaining = (1.0f - cold) * HEARTS_COUNT * 2f;
        int heartsHalf = (int) Math.ceil(healthRemaining);

        // ================= ЛОГИКА МИГАНИЯ =================
        if (lastHalf != -1 && heartsHalf < lastHalf) {
            flashTimer = 12; // Длительность мигания в тиках
        }
        lastHalf = heartsHalf;

        long worldTime = mc.world.getTotalWorldTime();
        boolean blink = false;

        if (flashTimer > 0) {
            blink = (worldTime % 6) < 3;

            if (worldTime != lastBlinkTick) {
                lastBlinkTick = worldTime;
                flashTimer--;
            }
        }
        // ==================================================

        GlStateManager.enableBlend();
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
        mc.getTextureManager().bindTexture(TEXTURE);

        for (int i = 0; i < HEARTS_COUNT; i++) {
            int x = startX + i * SPACING;
            int halfInSlot = heartsHalf - i * 2;

            // Выбираем V-координату: с блеском или без
            int currentV = blink ? V_HEART_BLINK : V_HEART;

            // 1. Рисуем фон (пустой контейнер) - ТЕПЕРЬ ТОЖЕ МИГАЕТ
            drawHeart(x, y, U_CONTAINER, currentV, HEART_SIZE, HEART_SIZE);

            // 2. Рисуем заливку поверх фона
            if (halfInSlot >= 2) {
                // Полное сердечко
                drawHeart(x, y, U_FULL, currentV, HEART_SIZE, HEART_SIZE);
            } else if (halfInSlot == 1) {
                // Половинка сердечка
                drawHeart(x, y, U_HALF, currentV, HEART_SIZE, HEART_SIZE);
            }
        }

        GlStateManager.disableBlend();
    }

    private static void drawHeart(int x, int y, int u, int v, int w, int h) {
        float u0 = u / (float) TEX_W;
        float v0 = v / (float) TEX_H;
        float u1 = (u + w) / (float) TEX_W;
        float v1 = (v + h) / (float) TEX_H;

        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        buf.begin(7, DefaultVertexFormats.POSITION_TEX);
        buf.pos(x,     y + h, 0).tex(u0, v1).endVertex();
        buf.pos(x + w, y + h, 0).tex(u1, v1).endVertex();
        buf.pos(x + w, y,     0).tex(u1, v0).endVertex();
        buf.pos(x,     y,     0).tex(u0, v0).endVertex();
        tess.draw();
    }
}