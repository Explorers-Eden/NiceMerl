package eu.explorerseden.nicemerl;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

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

/**
 * "What's this?": names the mob or block the player looks at (or the held item), mob variants included, and adds
 * a short summary from the wiki page that's really about it: the pack's variant page, our wiki's page with exactly
 * that title, or the Minecraft Wiki page with exactly that title. Never a search, so nothing unrelated shows up.
 */
public final class MerlWhatsThis {
	private static final double REACH = 6.0;
	private static final int SUMMARY_CHARS = 280;
	private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+");
	/** Mob variant components; a data pack variant like nice_mob_variants:creamy shows up here. */
	private static final List<DataComponentType<?>> VARIANTS = List.of(DataComponents.COW_VARIANT, DataComponents.PIG_VARIANT,
			DataComponents.CHICKEN_VARIANT, DataComponents.WOLF_VARIANT, DataComponents.CAT_VARIANT, DataComponents.FROG_VARIANT,
			DataComponents.ZOMBIE_NAUTILUS_VARIANT, DataComponents.VILLAGER_VARIANT);

	/** A wiki summary: the page's first sentences and where to read more. */
	record Summary(String title, String text, String url, String wiki) {}

	/**
	 * @param line what Merl says ("That's a Chicken! Variant: Creamy (Nice Mob Variants).")
	 * @param variantPage the pack's variant page ("chicken/creamy"), or null
	 * @param vanillaTitle the Minecraft Wiki page to look for ("Tall Dry Grass"), or null for pack things
	 * @param edenTitle our wiki's page to look for (a pack item's name), or null
	 */
	record Answer(Component line, String variantPage, String vanillaTitle, String edenTitle) {}

	private MerlWhatsThis() {}

	static Answer answer(ServerPlayer player, boolean holding) {
		String user = player.getName().getString();
		if (!holding) {
			Entity entity = lookedAtEntity(player);
			if (entity != null) return mob(entity, user);
			HitResult hit = player.pick(REACH, 0, false);
			if (hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK) {
				BlockPos pos = block.getBlockPos();
				String name = MerlRecipes.readable(player.level().getBlockState(pos).getBlock().getName());
				return new Answer(Component.literal(MerlLines.pick("whats_block", "thing", name, "user", user)), null, name, null);
			}
		}
		ItemStack held = player.getMainHandItem();
		if (held.isEmpty()) return new Answer(Component.literal(MerlLines.pick("whats_nothing", "user", user)), null, null, null);
		String name = MerlRecipes.readable(held.getHoverName());
		// A pack item is a vanilla item with its own name (Allay Shell is really something else underneath).
		boolean custom = !name.equals(MerlRecipes.readable(held.getItem().getName(new ItemStack(held.getItem()))));
		return new Answer(Component.literal(MerlLines.pick("whats_item", "thing", name, "user", user)), null,
				custom ? null : name, custom ? name : null);
	}

	private static Answer mob(Entity entity, String user) {
		String type = MerlRecipes.readable(entity.getType().getDescription());
		String typePath = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath();
		String variant = null, pack = null, variantPage = null;
		for (DataComponentType<?> component : VARIANTS) {
			Object value = entity.get(component);
			if (value instanceof Holder<?> holder && holder.unwrapKey().isPresent()) {
				Identifier id = holder.unwrapKey().get().identifier();
				variant = MerlRecipes.packName(id.getPath().replace('/', ' '));
				if (!id.getNamespace().equals("minecraft")) {
					pack = MerlRecipes.packName(id.getNamespace());
					variantPage = typePath + "/" + id.getPath();
				}
				break;
			}
		}
		StringBuilder line = new StringBuilder(MerlLines.pick("whats_mob", "thing", type, "user", user));
		if (variant != null && !variant.equalsIgnoreCase(type)) {
			line.append(" Variant: ").append(variant).append(pack != null ? " (" + pack + ")." : ".");
		}
		if (entity.hasCustomName()) line.append(" Its name is ").append(entity.getCustomName().getString()).append('.');
		if (entity instanceof OwnableEntity ownable && ownable.getOwner() != null) {
			line.append(" It belongs to ").append(ownable.getOwner().getName().getString()).append('.');
		}
		if (entity instanceof LivingEntity living) {
			line.append(String.format(Locale.ROOT, " Health: %.0f/%.0f.", living.getHealth(), living.getMaxHealth()));
		}
		return new Answer(Component.literal(line.toString()), variantPage, type, null);
	}

	/** The summary from our wiki: the pack's variant page, or a page with exactly the thing's name. Null if none. */
	static Summary edenSummary(SearchIndex index, Answer answer, String wikiName) {
		if (index == null) return null;
		List<Section> sections = index.sections();
		if (answer.variantPage() != null) {
			String end = "/variants/" + answer.variantPage();
			List<Section> page = sections.stream().filter(s -> !s.vanilla() && s.path().endsWith(end)).toList();
			Section description = page.stream().filter(s -> s.heading().equalsIgnoreCase("Description")).findFirst()
					.orElse(page.stream().filter(s -> !s.text().startsWith("Identifier")).findFirst().orElse(null));
			if (description != null) return summary(description, wikiName);
		}
		if (answer.edenTitle() != null) {
			List<Section> page = sections.stream()
					.filter(s -> !s.vanilla() && s.pageTitle().equalsIgnoreCase(answer.edenTitle())).toList();
			Section intro = page.stream().filter(s -> s.heading().equalsIgnoreCase("Description")).findFirst()
					.orElse(page.isEmpty() ? null : page.get(0));
			if (intro != null) return summary(intro, wikiName);
		}
		return null;
	}

	private static Summary summary(Section s, String wikiName) {
		String text = s.text().replaceAll(Pattern.quote(Section.SPOILER_START) + ".*?" + Pattern.quote(Section.SPOILER_END), "")
				.replace('\n', ' ').replaceAll("\\s+", " ").strip();
		StringBuilder out = new StringBuilder();
		for (String sentence : SENTENCE_END.split(text)) {
			if (!out.isEmpty() && out.length() + sentence.length() > SUMMARY_CHARS) break;
			out.append(out.isEmpty() ? "" : " ").append(sentence);
			if (out.length() > SUMMARY_CHARS / 2) break;
		}
		if (out.isEmpty()) return null;
		return new Summary(s.pageTitle(), out.toString(), s.wiki() + "/" + s.path() + (s.anchor().isEmpty() ? "" : "#" + s.anchor()), wikiName);
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
