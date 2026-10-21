package eu.explorerseden.nicemerl.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.google.gson.JsonElement;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;

import net.minecraft.resources.ResourceKey;

import eu.explorerseden.nicemerl.MerlQuickActions;

/** A registry entry's JSON from a data pack, before it's read: see {@link MerlQuickActions}. */
@Mixin(targets = "net.minecraft.resources.RegistryLoadTask$PendingRegistration")
public abstract class NiceActionsMenuMixin {
	@ModifyExpressionValue(method = "loadFromResource",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/util/StrictJsonParser;parse(Ljava/io/Reader;)Lcom/google/gson/JsonElement;"))
	private static JsonElement nicemerl$niceActionsMenu(JsonElement json, @Local(argsOnly = true) ResourceKey<?> key) {
		return MerlQuickActions.patch(key, json);
	}
}
