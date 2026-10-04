package eu.explorerseden.nicemerl;

import java.util.List;
import java.util.Locale;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** "What's this?": names the mob or block the player looks at (or the held item), mob variants included. */
public final class MerlWhatsThis {
	private static final double REACH = 6.0;
	/** Mob variant components; a data pack variant like nice_mob_variants:creamy shows up here. */
	private static final List<DataComponentType<?>> VARIANTS = List.of(DataComponents.COW_VARIANT, DataComponents.PIG_VARIANT,
			DataComponents.CHICKEN_VARIANT, DataComponents.WOLF_VARIANT, DataComponents.CAT_VARIANT, DataComponents.FROG_VARIANT,
			DataComponents.ZOMBIE_NAUTILUS_VARIANT, DataComponents.VILLAGER_VARIANT);

	/**
	 * @param line what Merl says ("That's a Creamy Cow from Nice Mob Variants!")
	 * @param query what to look up in the wiki ("creamy cow")
	 */
	record Answer(Component line, String query) {}

	private MerlWhatsThis() {}

	static Answer answer(ServerPlayer player, boolean holding) {
		String user = player.getName().getString();
		if (!holding) {
			Entity entity = lookedAtEntity(player);
			if (entity != null) return mob(entity, user);
			HitResult hit = player.pick(REACH, 0, false);
			if (hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK) {
				BlockPos pos = block.getBlockPos();
				Component name = player.level().getBlockState(pos).getBlock().getName();
				String readable = MerlRecipes.readable(name);
				return new Answer(Component.literal(MerlLines.pick("whats_block", "thing", readable, "user", user)), readable);
			}
		}
		ItemStack held = player.getMainHandItem();
		if (held.isEmpty()) return new Answer(Component.literal(MerlLines.pick("whats_nothing", "user", user)), null);
		String readable = MerlRecipes.readable(held.getHoverName());
		return new Answer(Component.literal(MerlLines.pick("whats_item", "thing", readable, "user", user)), readable);
	}

	private static Answer mob(Entity entity, String user) {
		String type = MerlRecipes.readable(entity.getType().getDescription());
		String variant = null, pack = null;
		for (DataComponentType<?> component : VARIANTS) {
			Object value = entity.get(component);
			if (value instanceof Holder<?> holder && holder.unwrapKey().isPresent()) {
				Identifier id = holder.unwrapKey().get().identifier();
				variant = MerlRecipes.packName(id.getPath().replace('/', ' '));
				if (!id.getNamespace().equals("minecraft")) pack = MerlRecipes.packName(id.getNamespace());
				break;
			}
		}
		String name = variant != null && !variant.equalsIgnoreCase(type) ? variant + " " + type : type;
		StringBuilder extra = new StringBuilder();
		if (entity.hasCustomName()) extra.append(" Its name is ").append(entity.getCustomName().getString()).append('.');
		if (entity instanceof OwnableEntity ownable && ownable.getOwner() != null) {
			extra.append(" It belongs to ").append(ownable.getOwner().getName().getString()).append('.');
		}
		if (entity instanceof LivingEntity living) {
			extra.append(String.format(Locale.ROOT, " Health: %.0f/%.0f.", living.getHealth(), living.getMaxHealth()));
		}
		String line = pack != null
				? MerlLines.pick("whats_pack_mob", "thing", name, "pack", pack, "user", user)
				: MerlLines.pick("whats_mob", "thing", name, "user", user);
		return new Answer(Component.literal(line + extra), name);
	}

	/** The closest entity in the player's line of sight, unless a block is in the way. */
	private static Entity lookedAtEntity(ServerPlayer player) {
		Vec3 eye = player.getEyePosition();
		Vec3 look = player.getViewVector(1.0f);
		Vec3 end = eye.add(look.scale(REACH));
		HitResult block = player.pick(REACH, 0, false);
		double limit = block.getType() == HitResult.Type.MISS ? REACH * REACH : block.getLocation().distanceToSqr(eye);
		AABB box = player.getBoundingBox().expandTowards(look.scale(REACH)).inflate(1.0);
		EntityHitResult hit = ProjectileUtil.getEntityHitResult(player, eye, end, box, e -> !e.isSpectator() && e.isPickable(), limit);
		return hit != null ? hit.getEntity() : null;
	}
}
