package com.createoptimizedtrains.profiler.client;

import net.createmod.catnip.config.ui.BaseConfigScreen;
import net.createmod.catnip.gui.element.TextStencilElement;
import net.createmod.catnip.gui.widget.BoxWidget;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Botões com o visual do Create (BoxWidget da Catnip, a mesma usada no ecrã de
 * config do Create): caixa com borda em gradiente e texto em stencil.
 */
@OnlyIn(Dist.CLIENT)
public final class CotWidgets {

    public static final int HEIGHT = 16;

    private CotWidgets() {
    }

    public static BoxWidget box(Font font, String label, int x, int y, int width, Runnable onClick) {
        return box(font, label, x, y, width, onClick, true);
    }

    public static BoxWidget box(Font font, String label, int x, int y, int width, Runnable onClick, boolean active) {
        TextStencilElement text = new TextStencilElement(font, Component.literal(label)).centered(true, true);
        BoxWidget widget = new BoxWidget(x, y, width, HEIGHT);
        widget.showingElement(text);
        widget.withCallback(onClick);
        text.withElementRenderer(BoxWidget.gradientFactory.apply(widget));
        if (!active) {
            widget.setActive(false);
            widget.updateGradientFromState();
            text.withElementRenderer(BaseConfigScreen.DISABLED_RENDERER);
        }
        return widget;
    }
}
