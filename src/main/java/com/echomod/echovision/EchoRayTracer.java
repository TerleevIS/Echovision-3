package com.echomod.echovision;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Пускает пачку лучей во все стороны от точки-источника звука (сам звук
 * мира, либо игрок — для микрофона) и возвращает точки, где эти лучи
 * упёрлись в блок. Это и есть "отражения" эхо-волны: они постепенно
 * проявляются на экране по мере того, как волна до них "долетает"
 * (см. SoundPulseManager и EchoVisionHud), давая игроку настоящую 3D-
 * картину окружения, а не плоский радар.
 *
 * Специально НЕ использует ванильную систему частиц (Particle/ParticleEngine)
 * — именно постоянный спавн полноценных частиц был причиной сильной
 * нагрузки на ПК в прошлой версии. Здесь вся "тяжёлая" работа — это вызовы
 * level.clip(...) (быстрый нативный voxel-DDA от Mojang, тот же механизм,
 * которым игра считает наведение прицела), а результат — лёгкие POJO
 * (EchoRayHit), без каких-либо тикающих сущностей.
 *
 * ПРО ТЕКСТУРУ БЛОКА: в этой версии Minecraft (26.2) старый путь
 * Minecraft#getBlockRenderer()#getBlockModelShaper()#getParticleIcon(state)
 * не существует — классы BlockRenderDispatcher/BlockModelShaper тут вообще
 * не найдены (см. javap-дамп в CI). Судя по полям GuiGraphicsExtractor,
 * текстуры теперь достаются через net.minecraft.client.resources.model.sprite.SpriteGetter
 * и/или AtlasManager/ModelManager — но точный метод ещё не подтверждён.
 * Пока что EchoRayHit#sprite всегда null, и EchoVisionHud рисует точку
 * заливкой цвета вместо текстуры блока. Это осознанное временное
 * упрощение, чтобы мод для начала гарантированно собрался — подключить
 * настоящую текстуру можно отдельным заходом после этого.
 */
public final class EchoRayTracer {

    private EchoRayTracer() {}

    public static List<EchoRayHit> trace(ClientLevel level, Vec3 origin, int rayCount, double maxDistance) {
        List<EchoRayHit> hits = new ArrayList<>(rayCount);
        if (rayCount <= 0) return hits;

        // Равномерное распределение направлений по сфере методом золотого
        // сечения — даёт куда более ровное покрытие, чем случайные
        // направления, при том же числе лучей (а значит, можно обойтись
        // меньшим их количеством ради производительности).
        double goldenAngle = Math.PI * (3.0 - Math.sqrt(5.0));

        for (int i = 0; i < rayCount; i++) {
            double t = (i + 0.5) / rayCount;
            double yNorm = 1.0 - 2.0 * t; // от 1 до -1
            double radius = Math.sqrt(Math.max(0.0, 1.0 - yNorm * yNorm));
            double theta = goldenAngle * i;

            double dx = Math.cos(theta) * radius;
            double dz = Math.sin(theta) * radius;

            Vec3 dir = new Vec3(dx, yNorm, dz);
            Vec3 end = origin.add(dir.scale(maxDistance));

            ClipContext ctx = new ClipContext(
                    origin, end,
                    ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE,
                    net.minecraft.world.phys.shapes.CollisionContext.empty()
            );

            HitResult result = level.clip(ctx);
            if (result.getType() != HitResult.Type.BLOCK) continue;

            BlockHitResult blockHit = (BlockHitResult) result;
            BlockPos pos = blockHit.getBlockPos();
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) continue;

            double dist = origin.distanceTo(blockHit.getLocation());
            if (dist > maxDistance) continue;

            TextureAtlasSprite sprite = null; // см. комментарий класса выше

            hits.add(new EchoRayHit(pos, blockHit.getDirection(), blockHit.getLocation(), dist, sprite));
        }

        return hits;
    }
}

