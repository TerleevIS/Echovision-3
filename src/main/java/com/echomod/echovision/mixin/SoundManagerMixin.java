package com.echomod.echovision.mixin;

import com.echomod.echovision.EchoVisionClient;
import com.echomod.echovision.SoundPulseManager;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Перехватывает КАЖДЫЙ звук, который клиент собирается проиграть
 * (шаги, взрывы, мобы, двери, дождь — всё), и превращает его в волну
 * на радаре вместо реального звучания через колонки.
 *
 * В Minecraft 26.2 метод SoundManager#play перестал быть void (краш-лог
 * Mixin: "CallbackInfoReturnable is required!"), поэтому используем
 * CallbackInfoReturnable вместо обычного CallbackInfo. Конкретный тип
 * возвращаемого значения не важен для отмены звука — cir.cancel() без
 * setReturnValue(...) просто вернёт значение по умолчанию (null/false/0
 * в зависимости от реальной сигнатуры), а нам нужно только заглушить звук.
 *
 * НАЙДЕНО ЛОГИРОВАНИЕМ: для net.minecraft.client.resources.sounds.
 * SimpleSoundInstance (класс, которым проигрывается подавляющее
 * большинство мировых звуков — шаги, блоки, поршни, огонь, сундуки)
 * sound.getVolume() кидает NullPointerException ("this.sound" внутри
 * SimpleSoundInstance ещё null) в точке HEAD — внутренний Sound
 * (конкретный выбранный вариант звука из нескольких весовых) резолвится
 * лениво, позже, уже внутри оригинального play(). При этом getX()/getY()/
 * getZ() отрабатывают нормально — позиция звука известна всегда, а вот
 * громкость не всегда. Поэтому позицию и громкость теперь резолвим
 * ОТДЕЛЬНЫМИ try-блоками: если позиции нет — пульс действительно
 * невозможен, пропускаем. Если нет громкости — используем разумное
 * значение по умолчанию вместо того, чтобы выбрасывать звук из эха
 * целиком (это и было причиной "работает только микрофон").
 */
@Mixin(SoundManager.class)
public class SoundManagerMixin {

    // Громкость по умолчанию для звуков, у которых Sound ещё не резолвлен
    // на момент HEAD (см. комментарий класса) — типичная громкость для
    // большинства игровых звуков, чтобы такие волны не были ни "немыми",
    // ни искусственно громкими.
    private static final float FALLBACK_VOLUME = 0.7f;

    @Inject(method = "play", at = @At("HEAD"), cancellable = true)
    private void echovision$onPlaySound(SoundInstance sound, CallbackInfoReturnable<Object> cir) {
        if (!EchoVisionClient.isEnabled()) {
            // Мод выключен — звук идёт как обычно
            return;
        }

        double x;
        double y;
        double z;
        try {
            x = sound.getX();
            y = sound.getY();
            z = sound.getZ();
        } catch (Throwable e) {
            System.out.println("[EchoVision-DEBUG] play() не смог получить позицию звука ("
                    + e.getClass().getSimpleName() + ") для " + sound.getClass().getName()
                    + " — пульс НЕ создан, звук идёт как обычно.");
            // Без позиции волну пустить банально некуда — такой звук
            // (редкий случай) просто пропускаем через эхо-систему.
            return;
        }

        float volume;
        try {
            volume = sound.getVolume();
        } catch (Throwable e) {
            // Самый частый случай (см. комментарий класса): Sound внутри
            // SimpleSoundInstance ещё не резолвлен на этот момент. Не
            // выбрасываем звук целиком — используем разумную громкость по
            // умолчанию, чтобы шаги/блоки/поршни всё равно давали эхо.
            volume = FALLBACK_VOLUME;
        }

        System.out.println("[EchoVision-DEBUG] play() поймал звук " + sound.getClass().getName()
                + " x=" + x + " y=" + y + " z=" + z + " volume=" + volume
                + " -> зову SoundPulseManager.addWorldPulse(...)");

        SoundPulseManager.addWorldPulse(x, y, z, volume);

        if (EchoVisionClient.isMuteRealSound()) {
            // Полностью заглушаем реальный звук, оставляя только "визуальное" эхо.
            cir.cancel();
        }
    }
}
