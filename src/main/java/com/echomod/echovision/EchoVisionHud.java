package com.echomod.echovision;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;

import java.util.List;

/**
 * Отрисовка эха.
 *
 * ПОЧЕМУ НЕ Gizmos.cuboid(...) НАПРЯМУЮ: гизмо рисуются как часть 3D-
 * рендера МИРА, а чёрный экран (эффект слепоты) — это GUI-слой, который
 * ВСЕГДА рисуется поверх мира целиком (фундаментальное свойство
 * пайплайна, не баг порядка вызовов). Поэтому гизмо физически не видны,
 * пока слепота включена. Честная альтернатива — навесить на игрока
 * ванильный эффект Blindness вместо своего чёрного прямоугольника — была
 * рассмотрена и отклонена: эффект синхронизируется с сервером, и даже в
 * одиночной игре встроенный сервер периодически "перезатирал" бы наш
 * клиентский хак, вызывая мерцание. Поэтому вместо рискованной новой
 * архитектуры этот класс улучшает то, что гарантированно работает:
 * рисует ПРОЕЦИРОВАННЫЙ КАРКАС БЛОКА (все 8 вершин, все 12 рёбер, с
 * настоящей перспективой через камеру игрока) прямо на GUI-слое, ПОСЛЕ
 * чёрной заливки — то есть гарантированно поверх черноты, в отличие от
 * гизмо. Внешне это уже полноценный wireframe-куб, а не плоский квадрат.
 *
 * Camera в этой версии Minecraft отдаёт позицию через position(), а
 * направления — через forwardVector()/upVector()/leftVector() в виде
 * JOML Vector3fc (не Vec3), поэтому конвертируем явно. mainCamera() —
 * обычный метод (не "get..."), а gameRenderer у Minecraft — само
 * публичное поле, не геттер (все имена подтверждены через javap).
 *
 * Стандартный прицел (crosshair) этот класс не трогает — см.
 * EchoVisionClient, где HUD-элемент подключён ПЕРЕД CROSSHAIR.
 */
public final class EchoVisionHud {

    private static final int WORLD_ECHO_TINT = 0xFFFFC04D; // тёплый — настоящие звуки мира
    private static final int MIC_ECHO_TINT = 0xFF4DE8FF;   // холодный — звук от микрофона

    private static final long REVEAL_LIFETIME_MS = 850;
    private static final int LINE_THICKNESS_PX = 2;

    // 8 вершин единичного куба блока (offset от BlockPos) и 12 рёбер между ними.
    private static final double[][] CORNER_OFFSETS = {
            {0, 0, 0}, {1, 0, 0}, {1, 1, 0}, {0, 1, 0}, // "передняя" грань (z=0)
            {0, 0, 1}, {1, 0, 1}, {1, 1, 1}, {0, 1, 1}  // "задняя" грань (z=1)
    };
    private static final int[][] EDGES = {
            {0, 1}, {1, 2}, {2, 3}, {3, 0}, // передняя грань
            {4, 5}, {5, 6}, {6, 7}, {7, 4}, // задняя грань
            {0, 4}, {1, 5}, {2, 6}, {3, 7}  // соединяющие рёбра
    };

    private EchoVisionHud() {}

    public static void render(GuiGraphicsExtractor context, DeltaTracker tickCounter) {
        if (!EchoVisionClient.isEnabled()) return;

        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null) return;

        int width = context.guiWidth();
        int height = context.guiHeight();

        // Игрок по-прежнему ничего не видит "глазами" — только через эхо.
        context.fill(0, 0, width, height, 0xFF000000);

        Camera camera = client.gameRenderer.mainCamera();
        Vec3 camPos = camera.position();
        Vec3 forward = toVec3(camera.forwardVector());
        Vec3 up = toVec3(camera.upVector());
        Vec3 left = toVec3(camera.leftVector());

        double fovDeg = client.options.fov().get();
        double focal = (height / 2.0) / Math.tan(Math.toRadians(fovDeg) / 2.0);

        List<SoundPulse> pulses = SoundPulseManager.getActive();
        for (SoundPulse pulse : pulses) {
            if (!pulse.isResolved()) continue;

            double elapsedSec = pulse.ageMs() / 1000.0;
            double waveRadius = elapsedSec * SoundPulseManager.WAVE_SPEED_BLOCKS_PER_SEC;
            int tint = pulse.isWorldSound ? WORLD_ECHO_TINT : MIC_ECHO_TINT;

            for (EchoRayHit hit : pulse.getHits()) {
                if (waveRadius < hit.distanceFromOrigin) continue;

                long revealAge = pulse.ageMs()
                        - (long) (hit.distanceFromOrigin / SoundPulseManager.WAVE_SPEED_BLOCKS_PER_SEC * 1000.0);
                if (revealAge < 0 || revealAge > REVEAL_LIFETIME_MS) continue;

                float fade = 1f - (float) revealAge / REVEAL_LIFETIME_MS;
                float intensity = Math.min(1f, fade * 1.6f) * (0.35f + pulse.volume * 0.65f);
                if (intensity <= 0.02f) continue;

                drawWireframeCube(context, hit.blockPos, camPos, forward, up, left, focal, width, height, tint, intensity);
            }
        }
    }

    private static void drawWireframeCube(GuiGraphicsExtractor context, BlockPos pos, Vec3 camPos, Vec3 forward,
                                           Vec3 up, Vec3 left, double focal, int width, int height,
                                           int tint, float intensity) {
        double[][] screenCorners = new double[8][];
        for (int i = 0; i < 8; i++) {
            double wx = pos.getX() + CORNER_OFFSETS[i][0];
            double wy = pos.getY() + CORNER_OFFSETS[i][1];
            double wz = pos.getZ() + CORNER_OFFSETS[i][2];
            screenCorners[i] = projectPoint(wx, wy, wz, camPos, forward, up, left, focal, width, height);
        }

        int alpha = Math.min(255, (int) (intensity * 255));
        int argb = (alpha << 24) | (tint & 0x00FFFFFF);

        for (int[] edge : EDGES) {
            double[] a = screenCorners[edge[0]];
            double[] b = screenCorners[edge[1]];
            if (a == null || b == null) continue; // одна из вершин за камерой/вне экрана — рёбер не рисуем
            drawLine(context, a[0], a[1], b[0], b[1], LINE_THICKNESS_PX, argb);
        }
    }

    /** Возвращает {screenX, screenY} или null, если точка позади камеры или далеко за краем экрана. */
    private static double[] projectPoint(double wx, double wy, double wz, Vec3 camPos, Vec3 forward,
                                          Vec3 up, Vec3 left, double focal, int width, int height) {
        Vec3 rel = new Vec3(wx, wy, wz).subtract(camPos);

        double zCam = rel.dot(forward);
        if (zCam <= 0.1) return null;

        double xCam = -rel.dot(left);
        double yCam = rel.dot(up);

        double screenX = width / 2.0 + (xCam / zCam) * focal;
        double screenY = height / 2.0 - (yCam / zCam) * focal;

        if (screenX < -200 || screenX > width + 200 || screenY < -200 || screenY > height + 200) return null;

        return new double[]{screenX, screenY};
    }

    /** Рисует линию из мелких квадратиков — context.fill не умеет диагональные линии сам по себе. */
    private static void drawLine(GuiGraphicsExtractor context, double x1, double y1, double x2, double y2,
                                  int thickness, int argb) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double length = Math.sqrt(dx * dx + dy * dy);
        int steps = Math.max(1, Math.min(48, (int) (length / 2.0)));
        int half = Math.max(1, thickness / 2);

        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            int px = (int) Math.round(x1 + dx * t);
            int py = (int) Math.round(y1 + dy * t);
            context.fill(px - half, py - half, px - half + thickness, py - half + thickness, argb);
        }
    }

    private static Vec3 toVec3(Vector3fc v) {
        return new Vec3(v.x(), v.y(), v.z());
    }
}
