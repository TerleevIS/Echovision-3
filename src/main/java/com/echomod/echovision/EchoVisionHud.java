package com.echomod.echovision;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Отрисовка эха.
 *
 * Раньше здесь был плоский 2D-радар в центре экрана: круг с кольцами,
 * никак не связанный с тем, куда игрок реально смотрит в 3D. Теперь его
 * нет вообще. Вместо этого:
 *
 *  - экран по-прежнему полностью чёрный (игрок "слеп" глазами — это и
 *    есть смысл мода);
 *  - но точки, до которых уже долетела волна эха (EchoRayHit из
 *    SoundPulse), проецируются из их РЕАЛЬНЫХ координат в мире на экран
 *    через камеру игрока — то есть двигаются вместе с поворотом и
 *    перемещением камеры так же, как обычная 3D-геометрия игры, а не как
 *    плоская мини-карта;
 *  - каждая такая точка рисуется текстурой того самого блока, от
 *    которого отразилась волна (EchoRayHit#sprite), поэтому окружение
 *    действительно узнаётся по текстурам, а не только по светящимся
 *    точкам.
 *
 * Стандартный прицел (crosshair) этот класс не трогает и не рисует —
 * он остаётся полностью ванильным. Раньше чёрный фон рисовался ПЕРЕД
 * чатом (attachElementBefore(CHAT, ...)), из-за чего заодно перекрывал и
 * прицел, который в списке HUD-элементов идёт раньше; теперь в
 * EchoVisionClient точка подключения — ПЕРЕД CROSSHAIR, поэтому ванильный
 * прицел рисуется поверх нашего чёрного фона и остаётся видимым.
 */
public final class EchoVisionHud {

    private static final int WORLD_ECHO_TINT = 0xFFFFC04D; // тёплый — настоящие звуки мира
    private static final int MIC_ECHO_TINT = 0xFF4DE8FF;   // холодный — звук от микрофона

    // Сколько миллисекунд точка остаётся видна ПОСЛЕ того, как волна её
    // достигла — короткая вспышка в момент попадания, затем угасание.
    private static final long REVEAL_LIFETIME_MS = 850;

    // "Физический" размер отметки в блоках — просто масштаб перспективы,
    // не связан напрямую с реальным размером блока.
    private static final double BASE_MARK_SIZE_BLOCKS = 1.0;
    private static final int MIN_PIXEL_SIZE = 3;
    private static final int MAX_PIXEL_SIZE = 40;

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

        Camera camera = client.gameRenderer.getMainCamera();
        Vec3 camPos = camera.getPosition();
        Vec3 forward = camera.getLookVector();
        Vec3 up = camera.getUpVector();
        Vec3 left = camera.getLeftVector();

        double fovDeg = client.options.fov().get();
        double focal = (height / 2.0) / Math.tan(Math.toRadians(fovDeg) / 2.0);

        List<SoundPulse> pulses = SoundPulseManager.getActive();
        for (SoundPulse pulse : pulses) {
            if (!pulse.isResolved()) continue; // трассировка ещё не дошла до этой волны — пока нечего рисовать

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

    /**
     * Переводит 3D-точку отражения в экранные координаты через камеру
     * игрока (стандартная проекция "камера-пространство -> экран" по
     * фокусному расстоянию, посчитанному из текущего FOV) и рисует её.
     */
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

        // Небольшой запас за краями экрана, чтобы отметки не обрезались резко
        if (screenX < -32 || screenX > width + 32 || screenY < -32 || screenY > height + 32) return;

        int pixelSize = (int) Math.round((BASE_MARK_SIZE_BLOCKS / zCam) * focal);
        pixelSize = Math.max(MIN_PIXEL_SIZE, Math.min(MAX_PIXEL_SIZE, pixelSize));

        int alpha = Math.min(255, (int) (intensity * 255));
        int argb = (alpha << 24) | (tint & 0x00FFFFFF);

        int x = (int) screenX - pixelSize / 2;
        int y = (int) screenY - pixelSize / 2;

        if (hit.sprite != null) {
            // Настоящая текстура блока, от которого отразилась волна — это
            // и даёт "частично увидеть текстуру блока" из ТЗ, а не просто
            // силуэт/точку.
            //
            // ПРИМЕЧАНИЕ ПО API: точную сигнатуру блита спрайта с тинтом
            // нужно свериться с фактическим GuiGraphicsExtractor в вашем
            // дев-окружении (в присланных файлах этого интерфейса нет —
            // видимо, он из зависимости Fabric API/Minecraft, которая сюда
            // не входила). Здесь используется наиболее вероятный по
            // аналогии вызов; если сигнатура отличается, скорее всего
            // потребуется что-то вроде
            // context.blit(RenderType::guiTextured, sprite.atlasLocation(),
            //     x, y, sprite.getU0(), sprite.getV0(), pixelSize, pixelSize, ..., argb)
            // — замените вызов ниже на актуальный для вашей версии.
            context.blitSprite(hit.sprite, x, y, pixelSize, pixelSize, argb);
        } else {
            // Фолбэк, если спрайт блока получить не удалось — просто цветной пиксель.
            context.fill(x, y, x + pixelSize, y + pixelSize, argb);
        }
    }
}
