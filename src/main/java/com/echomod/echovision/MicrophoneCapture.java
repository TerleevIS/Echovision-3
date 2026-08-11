package com.echomod.echovision;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.TargetDataLine;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Слушает системный микрофон в отдельном потоке и превращает громкость
 * (RMS уровня сигнала) в импульсы для SoundPulseManager.
 *
 * ВАЖНО про приватность: аудио нигде не сохраняется и не отправляется
 * никуда — из буфера сразу считается громкость (одно число), а сами
 * сэмплы отбрасываются. Работает только пока включено в игре
 * (см. EchoVisionClient — переключается клавишей).
 *
 * ВАЖНО про частоту: линия читается буферами примерно раз в ~20-25 мс —
 * то есть без ограничения микрофон рождал бы 40+ волн в секунду. Раньше
 * так и было, и именно это (а не отрисовка) было основной причиной жалоб
 * "работает только микрофон" (мировые звуки просто не успевали попасть
 * в короткий список волн, вытесняясь микрофонными) и "слишком большая
 * нагрузка на ПК". Поэтому здесь импульс от микрофона теперь собирается
 * за окно в MIC_PULSE_INTERVAL_MS (берём пиковую громкость за окно) и
 * шлётся не чаще, чем раз в это окно — на два порядка реже, чем раньше.
 */
public final class MicrophoneCapture {

    private static final AtomicBoolean running = new AtomicBoolean(false);
    private static Thread captureThread;

    // Порог тишины и чувствительность — подстрой под свой микрофон
    private static final float SILENCE_THRESHOLD = 0.015f;
    private static final float SENSITIVITY = 6.0f;

    // Не чаще одного импульса эха за это окно (мс), см. комментарий выше.
    private static final long MIC_PULSE_INTERVAL_MS = 150;

    private MicrophoneCapture() {}

    public static boolean isRunning() {
        return running.get();
    }

    public static synchronized void start() {
        if (running.get()) return;
        running.set(true);

        captureThread = new Thread(MicrophoneCapture::runCaptureLoop, "EchoVision-Mic-Capture");
        captureThread.setDaemon(true);
        captureThread.start();
    }

    public static synchronized void stop() {
        running.set(false);
        if (captureThread != null) {
            try {
                captureThread.join(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            captureThread = null;
        }
    }

    private static void runCaptureLoop() {
        AudioFormat format = new AudioFormat(44100f, 16, 1, true, false);
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);

        if (!AudioSystem.isLineSupported(info)) {
            System.err.println("[EchoVision] Микрофон не поддерживается этим форматом, отключаю захват звука.");
            running.set(false);
            return;
        }

        try (TargetDataLine line = (TargetDataLine) AudioSystem.getLine(info)) {
            line.open(format, 2048);
            line.start();

            byte[] buffer = new byte[1024];

            long windowStart = System.currentTimeMillis();
            float windowPeakVolume = 0f;

            while (running.get()) {
                int bytesRead = line.read(buffer, 0, buffer.length);
                if (bytesRead > 0) {
                    float rms = computeRms(buffer, bytesRead);
                    if (rms > SILENCE_THRESHOLD) {
                        float volume = Math.min(1f, (rms - SILENCE_THRESHOLD) * SENSITIVITY);
                        windowPeakVolume = Math.max(windowPeakVolume, volume);
                    }
                }

                long now = System.currentTimeMillis();
                if (now - windowStart >= MIC_PULSE_INTERVAL_MS) {
                    if (windowPeakVolume > 0f) {
                        emitMicPulse(windowPeakVolume);
                    }
                    windowPeakVolume = 0f;
                    windowStart = now;
                }
            }
        } catch (Exception e) {
            System.err.println("[EchoVision] Не удалось открыть микрофон: " + e.getMessage());
        } finally {
            running.set(false);
        }
    }

    private static void emitMicPulse(float volume) {
        // Трассировка лучей идёт от реальной позиции игрока в мире (а не
        // от условного центра плоского радара, как раньше) — это и делает
        // эхо от микрофона таким же 3D, как эхо от звуков мира.
        //
        // Примечание: читаем позицию игрока не из клиентского потока, а из
        // потока захвата звука. Для одной пары double-координат, которая
        // используется только для визуального эффекта (не для геймплейной
        // логики/коллизий), эта небольшая гонка не критична — в худшем
        // случае волна стартует на кадр позже актуальной позиции игрока.
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null) return;

        SoundPulseManager.addMicPulse(player.getX(), player.getEyeY(), player.getZ(), volume);
    }

    /** RMS громкости по 16-битным little-endian сэмплам. */
    private static float computeRms(byte[] buffer, int length) {
        long sumSquares = 0;
        int sampleCount = length / 2;
        if (sampleCount == 0) return 0f;

        for (int i = 0; i + 1 < length; i += 2) {
            short sample = (short) ((buffer[i + 1] << 8) | (buffer[i] & 0xFF));
            sumSquares += (long) sample * sample;
        }

        double meanSquare = sumSquares / (double) sampleCount;
        double rms = Math.sqrt(meanSquare) / 32768.0; // нормируем в 0..1
        return (float) rms;
    }
}
