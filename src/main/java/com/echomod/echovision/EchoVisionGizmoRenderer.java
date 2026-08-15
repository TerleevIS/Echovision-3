package com.echomod.echovision;

import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.GizmoProperties;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;

import java.util.List;

/**
 * Рисует контуры блоков-отражателей прямо в 3D-мире через штатный
 * движковый API net.minecraft.gizmos.* — это ДАЁТ правильную перспективу,
 * перекрытие по глубине и масштаб, в отличие от прошлой версии (проекция
 * точек на плоский HUD вручную через матрицы камеры).
 *
 * Как это работает: на каждом клиентском тике для каждой активной волны
 * (SoundPulse) вычисляем, докуда долетел фронт волны (waveRadius), и
 * запрашиваем у волны точки, которые она достигла с прошлого тика
 * (popNewlyReachedHits). Для каждой такой точки рисуем
 * Gizmos.cuboid(...) вокруг блока — один раз, с fadeOut()/persistForMillis(...),
 * дальше движок сам анимирует угасание каждый кадр, без участия мода.
 *
 * Gizmos.* пишет в игровой ThreadLocal-коллектор, который нужно явно
 * открыть — см. Minecraft#collectPerTickGizmos(). Вызов идёт из
 * ClientTickEvents.END_CLIENT_TICK (см. EchoVisionClient), то есть на
 * клиентском потоке, как и требуется.
 */
public final class EchoVisionGizmoRenderer {

    // Тёплый цвет для звуков мира, холодный — для микрофона (ARGB).
    private static final int WORLD_ECHO_COLOR = 0xFFFFC04D;
    private static final int MIC_ECHO_COLOR = 0xFF4DE8FF;

    private static final float MIN_LINE_WIDTH = 1.0f;
    private static final float MAX_LINE_WIDTH = 3.0f;

    // Сколько мс контур блока виден после появления, прежде чем полностью погаснет.
    private static final int REVEAL_LIFETIME_MS = 900;

    private EchoVisionGizmoRenderer() {}

    /** Вызывать раз в клиентский тик, после SoundPulseManager.tick(). */
    public static void tick() {
        if (!EchoVisionClient.isEnabled()) return;

        Minecraft client = Minecraft.getInstance();
        if (client.level == null) return;

        List<SoundPulse> pulses = SoundPulseManager.getActive();
        if (pulses.isEmpty()) return;

        // Открываем окно для Gizmos.* — привязываем к тому же коллектору,
        // которым движок собирает свои собственные (отладочные) гизмо на
        // этот тик. Закрывается автоматически в конце try-with-resources.
        try (var ignored = client.collectPerTickGizmos()) {
            for (SoundPulse pulse : pulses) {
                if (!pulse.isResolved()) continue;

                double elapsedSec = pulse.ageMs() / 1000.0;
                double waveRadius = elapsedSec * SoundPulseManager.WAVE_SPEED_BLOCKS_PER_SEC;

                List<EchoRayHit> newlyReached = pulse.popNewlyReachedHits(waveRadius);
                if (newlyReached.isEmpty()) continue;

                int baseColor = pulse.isWorldSound ? WORLD_ECHO_COLOR : MIC_ECHO_COLOR;
                float lineWidth = MIN_LINE_WIDTH + (MAX_LINE_WIDTH - MIN_LINE_WIDTH) * pulse.volume;
                GizmoStyle style = GizmoStyle.stroke(baseColor, lineWidth);

                for (EchoRayHit hit : newlyReached) {
                    GizmoProperties props = Gizmos.cuboid(hit.blockPos, style);
                    props.fadeOut().persistForMillis(REVEAL_LIFETIME_MS);
                }
            }
        }
    }
}
