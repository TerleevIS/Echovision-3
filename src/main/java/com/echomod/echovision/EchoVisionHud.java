package com.echomod.echovision;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Отрисовка эха.
 *
 * Раньше здесь был плоский 2D-радар, потом — проекция точек отражения на
 * HUD через матрицы камеры вручную. Теперь всё это не нужно: контуры
 * блоков-отражателей рисуются напрямую в 3D-мире через штатный движковый
 * API net.minecraft.gizmos.* (см. EchoVisionGizmoRenderer) — движок сам
 * даёт правильную перспективу, перекрытие по глубине и угасание со
 * временем, без единой строчки ручной проекционной математики с нашей
 * стороны.
 *
 * Этому классу остаётся только одно: закрасить экран чёрным (игрок по-
 * прежнему "слеп" глазами — это и есть смысл мода). Стандартный прицел
 * (crosshair) этот класс не трогает — он остаётся полностью ванильным;
 * см. EchoVisionClient, где этот элемент подключён ПЕРЕД CROSSHAIR, чтобы
 * ванильный прицел рисовался поверх нашего чёрного фона и оставался
 * видимым.
 */
public final class EchoVisionHud {

    private EchoVisionHud() {}

    public static void render(GuiGraphicsExtractor context, DeltaTracker tickCounter) {
        if (!EchoVisionClient.isEnabled()) return;

        int width = context.guiWidth();
        int height = context.guiHeight();
        context.fill(0, 0, width, height, 0xFF000000);
    }
}
