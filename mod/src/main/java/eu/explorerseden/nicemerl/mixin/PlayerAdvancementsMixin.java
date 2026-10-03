package eu.explorerseden.nicemerl.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;

import eu.explorerseden.nicemerl.MerlCommand;

/** Lets Merl congratulate players when they complete an advancement (see MerlCommand.celebrate). */
@Mixin(PlayerAdvancements.class)
public abstract class PlayerAdvancementsMixin {
	@Shadow
	private ServerPlayer player;

	@Shadow
	public abstract AdvancementProgress getOrStartProgress(AdvancementHolder holder);

	@Unique
	private boolean nicemerl$wasDone;

	@Inject(method = "award", at = @At("HEAD"))
	private void nicemerl$before(AdvancementHolder holder, String criterion, CallbackInfoReturnable<Boolean> cir) {
		nicemerl$wasDone = getOrStartProgress(holder).isDone();
	}

	@Inject(method = "award", at = @At("RETURN"))
	private void nicemerl$after(AdvancementHolder holder, String criterion, CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValueZ() && !nicemerl$wasDone && getOrStartProgress(holder).isDone()) {
			MerlCommand.celebrate(player, holder);
		}
	}
}
