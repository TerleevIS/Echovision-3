package com.echomod.echovision;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * Один "луч", попавший в блок при трассировке волны эха (см. EchoRayTracer).
 *
 * Это НЕ частица движка (не Particle/ParticleEngine) — просто маленький
 * неизменяемый объект с готовыми данными. Отрисовка (гизмо-каркас блока)
 * делается в EchoVisionGizmoRenderer; SoundPulse сам следит за тем, какие
 * точки уже показаны (см. SoundPulse#popNewlyReachedHits), так что этому
 * классу мутируемое состояние не нужно.
 */
public final class EchoRayHit {

    public final BlockPos blockPos;
    public final Direction face;
    public final Vec3 hitPos;                 // точка на поверхности блока, куда попал луч
    public final double distanceFromOrigin;    // расстояние от источника ВОЛНЫ (не от игрока)
    public final TextureAtlasSprite sprite;    // сейчас не используется — задел на будущее (текстура блока)

    public EchoRayHit(BlockPos blockPos, Direction face, Vec3 hitPos, double distanceFromOrigin, TextureAtlasSprite sprite) {
        this.blockPos = blockPos;
        this.face = face;
        this.hitPos = hitPos;
        this.distanceFromOrigin = distanceFromOrigin;
        this.sprite = sprite;
    }
}
