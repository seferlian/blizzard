package com.blizzard.client;

import com.blizzard.client.BlizzardClientCold;
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
 * Текстура: blizzard:textures/gui/cold.png — 447×98.
 *
 * Координаты в текстуре (пиксели):
 *   Рамка:        (0, 0)   – (440, 47)   w=440 h=47
 *   Пустая шкала: (34, 12) – (405, 37)   w=371 h=25
 *   Заполненная:  (34, 62) – (405, 87)   w=371 h=25
 *
 * Порядок отрисовки:
 *   1. Рамка (всегда при cold > 0)
 *   2. Пустая шкала (внутри рамки)
 *   3. Заполненная шкала (поверх пустой, обрезка по cold слева направо)
 */
@SideOnly(Side.CLIENT)
public class BlizzardColdHud {

    private static final ResourceLocation TEXTURE =
            new ResourceLocation("blizzard", "textures/gui/cold.png");

    /** Реальный размер cold.png в пикселях. */
    private static final int TEX_W = 447;
    private static final int TEX_H = 98;

    // ===== Координаты в текстуре =====
    private static final int FRAME_X = 0,  FRAME_Y = 0,  FRAME_W = 440, FRAME_H = 47;
    private static final int EMPTY_X = 34, EMPTY_Y = 12, EMPTY_W = 371, EMPTY_H = 25;
    private static final int FULL_X  = 34, FULL_Y  = 62, FULL_W  = 371, FULL_H  = 25;

    // ===== Размер на экране =====
    /** Ширина как у хотбара (182). */
    private static final int WIDTH = 91;
    /** Пропорционально 440:47 → 182:19. */
    private static final int HEIGHT = 9;

    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Post event) {
        if (event.getType() != RenderGameOverlayEvent.ElementType.HOTBAR) return;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player == null) return;

        float cold = BlizzardClientCold.getCold();
        if (cold <= 0.001f) return;

        ScaledResolution sr = new ScaledResolution(mc);
        int screenW = sr.getScaledWidth();
        int screenH = sr.getScaledHeight();

        int x = screenW / 2 - 91;
        int y = screenH - 32 - HEIGHT - 2;

        float kx = WIDTH  / (float) FRAME_W;   // 182 / 440
        float ky = HEIGHT / (float) FRAME_H;   // 19 / 47

        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(
                GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE,
                GlStateManager.DestFactor.ZERO);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        mc.getTextureManager().bindTexture(TEXTURE);

        // ===== 1. Рамка (внутри неё уже нарисована пустая шкала) =====
        drawTex(x, y, WIDTH, HEIGHT,
                FRAME_X, FRAME_Y, FRAME_W, FRAME_H);

        // ===== 2. Заполнение поверх области (34,12)–(405,37) =====
        // Экранная позиция — из EMPTY_X/EMPTY_Y (внутри рамки).
        // Текстура — из FULL_X/FULL_Y (нижняя половина cold.png).
        int barScreenX = x + (int) (EMPTY_X * kx);
        int barScreenY = y + (int) (EMPTY_Y * ky);
        int barScreenW = (int) (EMPTY_W * kx);
        int barScreenH = (int) (EMPTY_H * ky);

        int fillScreenW = (int) (barScreenW * cold);
        if (fillScreenW > 0) {
            int fillTexW = (int) ((float) fillScreenW / barScreenW * FULL_W);

            drawTex(barScreenX, barScreenY, fillScreenW, barScreenH,
                    FULL_X, FULL_Y, fillTexW, FULL_H);
        }

        GlStateManager.disableBlend();
    }

    /**
     * Рисует часть текстуры в экранный прямоугольник.
     *
     * @param x   экранный X
     * @param y   экранный Y
     * @param w   ширина на экране
     * @param h   высота на экране
     * @param tx  левый X в текстуре (пиксели)
     * @param ty  верхний Y в текстуре (пиксели)
     * @param tw  ширина в текстуре (пиксели)
     * @param th  высота в текстуре (пиксели)
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