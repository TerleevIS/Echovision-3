package com.echomod.echovision;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;

import java.util.List;

/**
 * Отрисовка эха.
 *
 * ВАЖНО, почему этот класс снова проецирует точки на экран вручную, а не
 * полагается только на Gizmos.cuboid(...) (см. EchoVisionGizmoRenderer):
 * гизмо рисуются как часть 3D-рендера МИРА, а чёрный экран (эффект
 * слепоты) — это GUI-слой, который всегда рисуется ПОВЕРХ мира и поэтому
 * полностью перекрывает любые гизмо, пока слепота включена. То есть
 * гизмо были видны только на долю секунды при выключении мода — ровно
 * симптом, о котором сообщил игрок.
 *
 * Решение: рисовать чёрный фон, а ПОСЛЕ него — те же самые точки
 * отражения, но уже спроецированные на экран через камеру игрока и
 * нарисованные прямо на GUI-слое (context.outline(...)), поэтому они
 * гарантированно видны поверх черноты, независимо от того, что творится
 * в 3D-мире. Gizmos.cuboid(...) в EchoVisionGizmoRenderer при этом не
 * убран — это задел на случай, если слепота когда-нибудь станет
 * полупрозрачной, тогда настоящие 3D-контуры будут просвечивать сквозь
 * неё естественным образом.
 *
 * Camera в этой версии Minecraft отдаёт позицию через position(), а
 * направления — через forwardVector()/upVector()/leftVector() в виде
 * JOML Vector3fc (не Vec3), поэтому конвертируем явно. mainCamera() —
 * обычный метод (не "get..."), а gameRenderer у Minecraft — само
 * публичное поле, не геттер (все имена подтверждены через javap).
 *
 * Стандартный прицел (crosshair) этот класс не трогает — см.
 * EchoVisionClient, где HUD-элемент подключён ПЕРЕД CROSSHAIR, чтобы
 * ванильный прицел рисовался поверх нашего чёрного фона и оставался
 * видимым.
 */
public final class EchoVisionHud {

    private static final int WORLD_ECHO_TINT = 0xFFFFC04D; // тёплый — настоящие звуки мира
    private static final int MIC_ECHO_TINT = 0xFF4DE8FF;   // холодный — звук от микрофона

    // Сколько миллисекунд точка остаётся видна ПОСЛЕ того, как волна её
    // достигла — короткая вспышка в момент попадания, затем угасание.
    private static final long REVEAL_LIFETIME_MS = 850;

    // "Физический" размер отметки в блоках — масштаб перспективы.
    private static final double BASE_MARK_SIZE_BLOCKS = 1.0;
    private static final int MIN_PIXEL_SIZE = 4;
    private static final int MAX_PIXEL_SIZE = 48;

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
            if (!pulse.isResolved()) continue; // трассировка ещё не дошла до этой волны

            double elapsedSec = pulse.ageMs() / 1000.0;
            double waveRadius = elapsedSec * SoundPulseManager.WAVE_SPEED_BLOCKS_PER_SEC;
            int tint = pulse.isWorldSound ? WORLD_ECHO_TINT : MIC_ECHO_TINT;

            for (EchoRayHit hit : pulse.getHits()) {
                if (waveRadius < hit.distanceFromOrigin) continue; // волна ещё не долетела сюда

                long revealAge = pulse.ageMs()
                        - (long) (hit.distanceFromOrigin / SoundPulseManager.WAVE_SPEED_BLOCKS_PER_SEC * 1000.0);
                if (revealAge < 0 || revealAge > REVEAL_LIFETIME_MS) continue;

                float fade = 1f - (float) revealAge / REVEAL_LIFETIME_MS;
                float intensity = Math.min(1f, fade * 1.6f) * (0.35f + pulse.volume * 0.65f);
                if (intensity <= 0.02f) continue;

                projectAndDraw(context, hit, camPos, forward, up, left, focal, width, height, tint, intensity);
            }
        }
    }

    private static void projectAndDraw(GuiGraphicsExtractor context, EchoRayHit hit, Vec3 camPos, Vec3 forward,
                                        Vec3 up, Vec3 left, double focal, int width, int height,
                                        int tint, float intensity) {
        Vec3 rel = hit.hitPos.subtract(camPos);
        double zCam = rel.dot(forward);
        if (zCam <= 0.1) return; // точка позади камеры — не проецируем

        double xCam = -rel.dot(left); // left "смотрит" влево, экранный X растёт вправо
        double yCam = rel.dot(up);

        double screenX = width / 2.0 + (xCam / zCam) * focal;
        double screenY = height / 2.0 - (yCam / zCam) * focal;

        if (screenX < -32 || screenX > width + 32 || screenY < -32 || screenY > height + 32) return;

        int pixelSize = (int) Math.round((BASE_MARK_SIZE_BLOCKS / zCam) * focal);
        pixelSize = Math.max(MIN_PIXEL_SIZE, Math.min(MAX_PIXEL_SIZE, pixelSize));

        int alpha = Math.min(255, (int) (intensity * 255));
        int argb = (alpha << 24) | (tint & 0x00FFFFFF);

        int x = (int) screenX - pixelSize / 2;
        int y = (int) screenY - pixelSize / 2;

        // Рисуем ПОСЛЕ чёрного фона — поэтому гарантированно видно, в
        // отличие от Gizmos.cuboid(...), который рисуется под чёрным
        // экраном. Контур (outline), а не заливка — визуально ближе к
        // "каркасу" блока, а не к сплошному квадрату.
        context.outline(x, y, pixelSize, pixelSize, argb);
    }

    private static Vec3 toVec3(Vector3fc v) {
        return new Vec3(v.x(), v.y(), v.z());
    }
}
