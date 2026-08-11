package com.echomod.echovision;

import net.minecraft.world.phys.Vec3;

import java.util.Collections;
import java.util.List;

/**
 * Одна "волна" эха: точка-источник в мире + громкость + список точек
 * отражения (EchoRayHit).
 *
 * Список точек заполняется не сразу — трассировка лучей (EchoRayTracer)
 * растянута по тикам в SoundPulseManager, чтобы пачка одновременных
 * звуков не просадила FPS за один кадр. Пока resolved == false, волна
 * просто ждёт своей очереди и на экране никак не показывается.
 */
public final class SoundPulse {

    public final boolean isWorldSound;
    public final double worldX, worldY, worldZ;
    public final float volume;
    public final long createdAtMs;

    private volatile List<EchoRayHit> hits = Collections.emptyList();
    private volatile boolean resolved = false;

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
        this.hits = hits;
        this.resolved = true;
    }

    public List<EchoRayHit> getHits() {
        return hits;
    }

    /** Возраст волны в миллисекундах. */
    public long ageMs() {
        return System.currentTimeMillis() - createdAtMs;
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
