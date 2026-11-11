package com.blizzard.client;

import com.blizzard.capabilities.BlizzardColdCapability;
import com.blizzard.registry.BlizzardCapabilities;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * HUD-оверлей накопленного холода.
 *
 * Рисует вертикальную синюю шкалу слева от хотбара,
 * только если cold > 0.
 */
@SideOnly(Side.CLIENT)
public class BlizzardColdHud {

    private static final int WIDTH = 6;
    private static final int HEIGHT = 60;

    private static final int BG_COLOR = 0x80000000;
    private static final int COLD_COLOR_TOP = 0xFF4FC3F7;    // светло-голубой
    private static final int COLD_COLOR_BOTTOM = 0xFF0277BD; // тёмно-синий

    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Post event) {
        if (event.getType() != RenderGameOverlayEvent.ElementType.HOTBAR) return;

        EntityPlayer player = Minecraft.getMinecraft().player;
        if (player == null) return;

        BlizzardColdCapability cap = player.getCapability(BlizzardCapabilities.BLIZZARD_COLD, null);
        if (cap == null) return;

        float cold = cap.getCold();
        if (cold <= 0.001f) return;

        ScaledResolution sr = new ScaledResolution(Minecraft.getMinecraft());
        int screenW = sr.getScaledWidth();
        int screenH = sr.getScaledHeight();

        // Слева от хотбара
        int x = screenW / 2 - 100 - WIDTH - 4;
        int y = screenH - HEIGHT - 8;

        // Фон
        Gui.drawRect(x - 1, y - 1, x + WIDTH + 1, y + HEIGHT + 1, BG_COLOR);

        // Заливка снизу вверх
        int fill = (int) (HEIGHT * cold);
        int fillY = y + HEIGHT - fill;

        for (int i = 0; i < fill; i++) {
            float t = (float) i / Math.max(1, fill - 1);
            int color = lerpColor(COLD_COLOR_BOTTOM, COLD_COLOR_TOP, t);
            Gui.drawRect(x, fillY + i, x + WIDTH, fillY + i + 1, color);
        }

        // Рамка
        Gui.drawRect(x - 1, y - 1, x + WIDTH + 1, y, 0xFF000000);
        Gui.drawRect(x - 1, y + HEIGHT, x + WIDTH + 1, y + HEIGHT + 1, 0xFF000000);
        Gui.drawRect(x - 1, y, x, y + HEIGHT, 0xFF000000);
        Gui.drawRect(x + WIDTH, y, x + WIDTH + 1, y + HEIGHT, 0xFF000000);
    }

    private static int lerpColor(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF, aa = (a >> 24) & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int r = (int) (ar + (br - ar) * t);
        int g = (int) (ag + (bg - ag) * t);
        int bl = (int) (ab + (bb - ab) * t);
        return (aa << 24) | (r << 16) | (g << 8) | bl;
    }
}