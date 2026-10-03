package eu.explorerseden.nicemerl.mixin;

import java.lang.reflect.Method;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps /merl questions private when Discord: JustSync is installed.
 *
 * <p>JustSync relays /me and /say to Discord by checking {@code command.startsWith("me")},
 * which also matches "merl …", so every question showed up as "Player: question".
 * Does nothing when JustSync isn't installed or the method has changed.
 */
@Pseudo
@Mixin(targets = "tronka.justsync.chat.MinecraftToDiscordPreprocessor", remap = false)
public class JustSyncCommandMixin {
	private static volatile Method nicemerl$command;

	@Inject(method = "onCommandExecute", at = @At("HEAD"), cancellable = true, require = 0)
	private void nicemerl$skipMerl(@Coerce Object payload, CallbackInfo ci) {
		try {
			Method command = nicemerl$command;
			if (command == null) {
				command = payload.getClass().getMethod("command");
				nicemerl$command = command;
			}
			String text = (String) command.invoke(payload);
			if (text.equals("merl") || text.startsWith("merl ")) {
				ci.cancel();
			}
		} catch (ReflectiveOperationException | ClassCastException e) {
			// Unknown JustSync version, leave it alone.
		}
	}
}
