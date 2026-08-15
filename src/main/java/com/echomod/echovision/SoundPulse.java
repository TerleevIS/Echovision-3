package com.echomod.echovision;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Одна "волна" эха: точка-источник в мире + громкость + список точек
 * отражения (EchoRayHit).
 *
 * Список точек заполняется не сразу — трассировка лучей (EchoRayTracer)
 * растянута по тикам в SoundPulseManager, чтобы пачка одновременных
 * звуков не просадила FPS за один кадр. Пока resolved == false, волна
 * просто ждёт своей очереди и на экране никак не показывается.
 *
 * Точки отсортированы по расстоянию от источника, а nextHitIndex —
 * указатель на первую ещё не "показанную" точку. EchoVisionGizmoRenderer
 * на каждом тике продвигает этот указатель по мере того, как волна до
 * этих точек долетает, и рисует гизмо-каркас блока ровно один раз для
 * каждой — дальше движок сам плавно гасит его через
 * GizmoProperties#fadeOut()/persistForMillis(...), без ручного пересчёта
 * альфы каждый кадр с нашей стороны.
 */
public final class SoundPulse {

    public final boolean isWorldSound;
    public final double worldX, worldY, worldZ;
    public final float volume;
    public final long createdAtMs;

    private volatile List<EchoRayHit> hits = Collections.emptyList();
    private volatile boolean resolved = false;
    private int nextHitIndex = 0;

    private SoundPulse(boolean isWorldSound, double x, double y, double z, float volume) {
        this.isWorldSound = isWorldSound;
        this.worldX = x;
        this.worldY = y;
        this.worldZ = z;
        this.volume = clamp(volume);
        this.createdAtMs = System.currentTimeMillis();
    }

    /** Волна от настоящего звука игры — источник там, где звук реально проигрался. */
    public static SoundPulse worldSound(double x, double y, double z, float volume) {
        return new SoundPulse(true, x, y, z, volume);
    }

    /** Волна от микрофона — источник условно там, где сейчас игрок (уши/рот). */
    public static SoundPulse micSound(double playerX, double playerY, double playerZ, float volume) {
        return new SoundPulse(false, playerX, playerY, playerZ, volume);
    }

    public Vec3 origin() {
        return new Vec3(worldX, worldY, worldZ);
    }

    public boolean isResolved() {
        return resolved;
    }

    public void setHits(List<EchoRayHit> hits) {
        List<EchoRayHit> sorted = new ArrayList<>(hits);
        sorted.sort(Comparator.comparingDouble(h -> h.distanceFromOrigin));
        this.hits = sorted;
        this.resolved = true;
    }

    public List<EchoRayHit> getHits() {
        return hits;
    }

    /**
     * Возвращает точки, которые волна достигла с прошлого вызова (то есть
     * distanceFromOrigin &lt;= waveRadius), и сдвигает внутренний указатель,
     * чтобы повторно их не вернуть. Список хитов уже отсортирован по
     * расстоянию, так что это просто "выдать следующий отрезок".
     */
    public List<EchoRayHit> popNewlyReachedHits(double waveRadius) {
        if (!resolved || nextHitIndex >= hits.size()) return Collections.emptyList();
        int start = nextHitIndex;
        int end = start;
        while (end < hits.size() && hits.get(end).distanceFromOrigin <= waveRadius) {
            end++;
        }
        nextHitIndex = end;
        if (end == start) return Collections.emptyList();
        return hits.subList(start, end);
    }

    /** Возраст волны в миллисекундах. */
    public long ageMs() {
        return System.currentTimeMillis() - createdAtMs;
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
