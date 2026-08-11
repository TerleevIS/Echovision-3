package com.echomod.echovision;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Хранит и обрабатывает волны эха.
 *
 * Раньше волны сразу отрисовывались как 2D-кольца, и главной проблемой
 * была не столько отрисовка, сколько ЧАСТОТА создания волн: микрофон
 * слал новый импульс почти на каждый прочитанный буфер (десятки раз в
 * секунду, см. старую MicrophoneCapture), из-за чего короткий список волн
 * мгновенно забивался микрофонными волнами и настоящие звуки мира на
 * радаре почти не успевали появиться — отсюда и жалоба "работает только
 * от микрофона". Это исправлено на уровне MicrophoneCapture (теперь она
 * шлёт не чаще раза в MIC_PULSE_INTERVAL_MS), а здесь дополнительно есть
 * второй уровень защиты:
 *
 *  - трассировка лучей (недешёвая) не выполняется мгновенно в момент
 *    звука — волна сперва попадает в очередь ожидания (pendingQueue);
 *  - за один тик обрабатывается не больше MAX_TRACES_PER_TICK волн и не
 *    больше MAX_RAYS_PER_TICK лучей суммарно — так пачка одновременных
 *    звуков (дождь, толпа мобов) не подвесит клиент на один кадр, а
 *    просто чуть растянется по времени;
 *  - число лучей на волну зависит от громкости — тихие шаги дешёвые,
 *    громкие взрывы/крики в микрофон — куда детальнее.
 *
 * Никаких объектов Particle/ParticleEngine здесь по-прежнему нет — только
 * лёгкие SoundPulse/EchoRayHit.
 */
public final class SoundPulseManager {

    public static final long PULSE_LIFETIME_MS = 2600;

    private static final int MAX_ACTIVE_PULSES = 40;
    private static final int MAX_PENDING_QUEUE = 24;

    private static final int MIN_RAYS = 18;
    private static final int MAX_RAYS = 120;
    private static final int MAX_TRACES_PER_TICK = 3;
    private static final int MAX_RAYS_PER_TICK = 260;

    public static final double MAX_ECHO_DISTANCE = 24.0;
    public static final double WAVE_SPEED_BLOCKS_PER_SEC = 20.0;

    private static final List<SoundPulse> activePulses = new CopyOnWriteArrayList<>();
    private static final Queue<SoundPulse> pendingQueue = new ConcurrentLinkedQueue<>();

    private SoundPulseManager() {}

    public static void addWorldPulse(double x, double y, double z, float volume) {
        if (volume <= 0.01f) return;
        enqueue(SoundPulse.worldSound(x, y, z, volume));
    }

    public static void addMicPulse(double playerX, double playerY, double playerZ, float volume) {
        if (volume <= 0.02f) return;
        enqueue(SoundPulse.micSound(playerX, playerY, playerZ, volume));
    }

    private static void enqueue(SoundPulse pulse) {
        if (pendingQueue.size() >= MAX_PENDING_QUEUE) {
            // Уже есть затор из необработанных волн — новую тихо пропускаем,
            // а не копим бесконечно: иначе на шумных участках очередь росла
            // бы быстрее, чем мы успеваем её трассировать, и эхо начало бы
            // всё сильнее отставать от реальных звуков.
            return;
        }
        pendingQueue.add(pulse);
    }

    /** Вызывать раз в клиентский тик. */
    public static void tick() {
        activePulses.removeIf(p -> p.ageMs() > PULSE_LIFETIME_MS);

        Minecraft client = Minecraft.getInstance();
        ClientLevel level = client.level;
        if (level == null) {
            pendingQueue.clear();
            return;
        }

        int tracesThisTick = 0;
        int raysThisTick = 0;

        while (tracesThisTick < MAX_TRACES_PER_TICK && raysThisTick < MAX_RAYS_PER_TICK) {
            SoundPulse pulse = pendingQueue.poll();
            if (pulse == null) break;

            int rayCount = (int) (MIN_RAYS + (MAX_RAYS - MIN_RAYS) * pulse.volume);
            rayCount = Math.min(rayCount, MAX_RAYS_PER_TICK - raysThisTick);
            if (rayCount <= 0) {
                // Бюджет лучей на этот тик кончился — доделаем волну в
                // следующем тике вместо того, чтобы обрезать её детальность.
                pendingQueue.add(pulse);
                break;
            }

            List<EchoRayHit> hits = EchoRayTracer.trace(level, pulse.origin(), rayCount, MAX_ECHO_DISTANCE);
            pulse.setHits(hits);

            if (activePulses.size() >= MAX_ACTIVE_PULSES) {
                activePulses.remove(0);
            }
            activePulses.add(pulse);

            tracesThisTick++;
            raysThisTick += rayCount;
        }
    }

    public static List<SoundPulse> getActive() {
        return activePulses;
    }

    public static void clear() {
        activePulses.clear();
        pendingQueue.clear();
    }
}
