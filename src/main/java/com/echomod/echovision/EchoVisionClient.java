package com.echomod.echovision;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public class EchoVisionClient implements ClientModInitializer {

    // Слепота + радар включены/выключены
    private static boolean enabled = true;
    // Заглушать ли настоящий звук игры (иначе слышно и видно одновременно)
    private static boolean muteRealSound = true;
    // Слушать ли микрофон
    private static boolean micEnabled = false;

    private static KeyMapping toggleModKey;
    private static KeyMapping toggleMicKey;
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath("echovision", "category")
    );

    public static boolean isEnabled() {
        return enabled;
    }

    public static boolean isMuteRealSound() {
        return muteRealSound;
    }

    public static boolean isMicEnabled() {
        return micEnabled;
    }

    @Override
    public void onInitializeClient() {
        toggleModKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.echovision.toggle",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_APOSTROPHE, // клавиша ' — смени, если конфликтует
                CATEGORY
        ));

        toggleMicKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.echovision.togglemic",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_SEMICOLON, // клавиша ;
                CATEGORY
        ));

        // Отрисовка чёрного экрана + 3D-проекции эха поверх интерфейса.
        // HudRenderCallback убран из Fabric API начиная с 26.1 — вместо
        // него используется HudElementRegistry (см. docs.fabricmc.net).
        //
        // ВАЖНО: раньше подключались ПЕРЕД CHAT. Чат в списке HUD-элементов
        // рисуется позже прицела, поэтому наш полноэкранный чёрный фон
        // оказывался поверх прицела и перекрывал его. Теперь подключаемся
        // ПЕРЕД CROSSHAIR — фон рисуется первым, а ванильный прицел
        // выводится уже поверх него и остаётся видимым, как обычно.
        HudElementRegistry.attachElementBefore(
                VanillaHudElements.CROSSHAIR,
                Identifier.fromNamespaceAndPath("echovision", "echo"),
                EchoVisionHud::render
        );

        // Обработка нажатий клавиш + чистка старых волн + рисование
        // гизмо-контуров новых точек отражения, раз в тик.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            SoundPulseManager.tick();
            EchoVisionGizmoRenderer.tick();

            while (toggleModKey.consumeClick()) {
                enabled = !enabled;
                sendActionBarMessage(client, enabled
                        ? "EchoVision: включено (ты слеп)"
                        : "EchoVision: выключено");
            }

            while (toggleMicKey.consumeClick()) {
                setMicEnabled(!micEnabled);
                sendActionBarMessage(client, micEnabled
                        ? "EchoVision: микрофон слушается"
                        : "EchoVision: микрофон выключен");
            }
        });
    }

    private static void setMicEnabled(boolean value) {
        micEnabled = value;
        if (value) {
            MicrophoneCapture.start();
        } else {
            MicrophoneCapture.stop();
        }
    }

    private static void sendActionBarMessage(Minecraft client, String message) {
        if (client.player != null) {
            // displayClientMessage(Component, boolean overlay) переименован в
            // sendSystemMessage(Component) — флаг action-bar в новом API не
            // сохранился, сообщение теперь всегда идёт в обычный чат.
            client.player.sendSystemMessage(Component.literal(message));
        }
    }
}
