package com.blizzard.client;

import com.blizzard.client.BlizzardClientCold;
import com.blizzard.weather.BlizzardHooks;
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
 * HUD-оверлей накопленного холода.
 *
 * Текстура cold.png — 447×98 (или 440×98).
 * Логические размеры рамки — 440×47, заполненной полоски — 371×25.
 */
@SideOnly(Side.CLIENT)
public class BlizzardColdHud {

    private static final ResourceLocation TEXTURE =
            new ResourceLocation("blizzard", "textures/gui/cold.png");

    /** Реальный размер файла cold.png в пикселях. */
    private static final int TEX_W = 447;
    private static final int TEX_H = 98;

    // ===== Координаты в текстуре =====
    /** Рамка (целиком). */
    private static final int FRAME_X = 0,  FRAME_Y = 0,  FRAME_W = 440, FRAME_H = 47;

    /** Область, где должна отображаться шкала (внутри рамки). */
    private static final int EMPTY_X = 34, EMPTY_Y = 12, EMPTY_W = 371, EMPTY_H = 25;

    /** Заполненная шкала (источник текстуры, нижняя половина). */
    private static final int FULL_X  = 34, FULL_Y  = 62, FULL_W  = 371, FULL_H  = 25;

    // ===== Размер на экране =====
    /** Ширина как у хотбара (182), уменьшено вдвое. */
    private static final int WIDTH = 91;
    /** Пропорционально 440:47 → 91:9.7 → 10. */
    private static final int HEIGHT = 10;

    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Post event) {
        if (event.getType() != RenderGameOverlayEvent.ElementType.ALL) return;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player == null) return;

        float cold = BlizzardClientCold.getCold();
        if (cold <= 0.001f) return;

        ScaledResolution sr = new ScaledResolution(mc);
        int screenW = sr.getScaledWidth();
        int screenH = sr.getScaledHeight();

        // Верх правой колонки HUD (голод/воздух), полученный из ASM-хука.
        // Если ASM-хук не сработал, используем фолбэк (над голодом).
        int baseY = BlizzardHooks.getHudRightTopY();
        if (baseY <= 0) {
            baseY = screenH - 39;
        }

        // Выравниваем по правому краю хотбара (где заканчивается голод)
        int hungerRightEdge = screenW / 2 + 91;
        int x = hungerRightEdge - WIDTH + 3;

        // Позиция шкалы: ровно над голодом/пузырьками.
        int y = baseY - HEIGHT + 10 - 2;

        // Коэффициенты масштабирования
        float kx = WIDTH  / (float) FRAME_W;   // 91 / 440
        float ky = HEIGHT / (float) FRAME_H;   // 10 / 47

        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(
                GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE,
                GlStateManager.DestFactor.ZERO);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        mc.getTextureManager().bindTexture(TEXTURE);

        // ===== 1. Рамка (рисуется целиком 440x47) =====
        drawTex(x, y, WIDTH, HEIGHT,
                FRAME_X, FRAME_Y, FRAME_W, FRAME_H);

        // ===== 2. Пустая шкала (рисуется на месте 34,12 - 405,37) =====
        int barScreenX = x + (int) (EMPTY_X * kx);
        int barScreenY = y + (int) (EMPTY_Y * ky);
        int barScreenW = (int) (EMPTY_W * kx);
        int barScreenH = (int) (EMPTY_H * ky);

        drawTex(barScreenX, barScreenY, barScreenW, barScreenH,
                EMPTY_X, EMPTY_Y, EMPTY_W, EMPTY_H);

        // ===== 3. Заполненная шкала (берем из 34,62 - 405,87) =====
        int fillScreenW = (int) (barScreenW * cold);
        if (fillScreenW > 0) {
            // Сколько текселей заполненной шкалы взять пропорционально заливке
            int fillTexW = (int) ((float) fillScreenW / barScreenW * FULL_W);

            // Сдвигаем заполненную шкалу на 1 пиксель вниз по сравнению с пустой
            int fillScreenY = barScreenY + 1;

            drawTex(barScreenX, fillScreenY, fillScreenW, barScreenH,
                    FULL_X, FULL_Y, fillTexW, FULL_H);
        }

        GlStateManager.disableBlend();
    }

    /**
     * Рисует часть текстуры в экранный прямоугольник.
     */
    private static void drawTex(int x, int y, int w, int h,
                                int tx, int ty, int tw, int th) {
        float u0 = tx / (float) TEX_W;
        float v0 = ty / (float) TEX_H;
        float u1 = (tx + tw) / (float) TEX_W;
        float v1 = (ty + th) / (float) TEX_H;

        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        buf.begin(7, DefaultVertexFormats.POSITION_TEX); // GL_QUADS
        buf.pos(x,     y + h, 0).tex(u0, v1).endVertex();
        buf.pos(x + w, y + h, 0).tex(u1, v1).endVertex();
        buf.pos(x + w, y,     0).tex(u1, v0).endVertex();
        buf.pos(x,     y,     0).tex(u0, v0).endVertex();
        tess.draw();
    }
}