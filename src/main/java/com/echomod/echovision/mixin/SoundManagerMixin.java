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
 * ВРЕМЕННОЕ ЛОГИРОВАНИЕ: по javap подтверждено, что SoundManager#play —
 * ЕДИНСТВЕННАЯ точка входа (перегрузок нет), то есть мод технически
 * слушает правильный и единственный метод. Раз при этом эхо от звуков
 * мира всё равно не появляется — источник проблемы либо в том, что
 * sound.getX()/getY()/getZ()/getVolume() реально кидает исключение для
 * обычных игровых звуков (и тогда наш catch(Throwable) молча их
 * выбрасывает из системы), либо баг дальше по цепочке. Логи ниже (видны
 * в logs/latest.log вашей игры, не в CI) должны сразу показать, какой
 * из вариантов верный. Убрать после того, как звук от мира заработает.
 */
@Mixin(SoundManager.class)
public class SoundManagerMixin {

    @Inject(method = "play", at = @At("HEAD"), cancellable = true)
    private void echovision$onPlaySound(SoundInstance sound, CallbackInfoReturnable<Object> cir) {
        if (!EchoVisionClient.isEnabled()) {
            // Мод выключен — звук идёт как обычно
            return;
        }

        double x;
        double y;
        double z;
        float volume;
        try {
            x = sound.getX();
            y = sound.getY();
            z = sound.getZ();
            volume = sound.getVolume();
        } catch (Throwable e) {
            System.out.println("[EchoVision-DEBUG] play() поймал " + e.getClass().getName()
                    + " (" + e.getMessage() + ") для звука " + sound.getClass().getName()
                    + " — пульс НЕ создан, звук идёт как обычно.");
            // Некоторые SoundInstance (например, фоновая музыка из
            // MusicManager) резолвят внутренний Sound только внутри
            // оригинального play(), уже ПОСЛЕ точки HEAD — на этот момент
            // getVolume() может кинуть исключение (не всегда именно
            // RuntimeException — встречались и Error-наследники при смене
            // версий API, поэтому ловим Throwable, а не только
            // RuntimeException, чтобы такой звук молча не "выпадал" из
            // эха, а просто доигрывал как обычно). Раньше при жалобе "эхо
            // работает только от микрофона" ЭТО было одной из причин:
            // часть мировых звуков вообще не долетала до
            // SoundPulseManager, и без обычного мониторинга это было не
            // видно — эхо просто выглядело "тише", чем должно быть.
            return;
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
