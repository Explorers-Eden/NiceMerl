package eu.explorerseden.nicemerl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.datafixers.util.Pair;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.Level;

/**
 * Companion Merl: "can you stay with me?" makes Merl's figure stay with the player (only they see her, like the
 * guide), dressed for the situation (a gold helmet in the Nether, a pickaxe and torch underground, a sword and shield
 * when monsters are around…), with a warning, a reminder or a comment now and then. "You can go" sends her home.
 */
final class MerlCompanion {
	private static final Set<UUID> WITH = ConcurrentHashMap.newKeySet();
	private static final Map<UUID, State> STATES = new ConcurrentHashMap<>();
	/** How often she looks at the situation, and the least time between her remarks of each kind, in ticks. */
	private static final int CHECK_TICKS = 20;
	private static final long CREEPER_PAUSE = 600, HURT_PAUSE = 1200, HUNGRY_PAUSE = 2400, OUTFIT_PAUSE = 1200;
	/** A comment about where they are or a tip, about every few minutes. */
	private static final long CHAT_MIN = 3600, CHAT_MAX = 7200;
	private static long ticks;

	private static final class State {
		final Map<String, Long> lastSaid = new ConcurrentHashMap<>();
		long nextChat;
		long nightNoted = -1;
	}

	private MerlCompanion() {}

	static boolean active(UUID player) {
		return WITH.contains(player);
	}

	/** "can you stay with me?" Returns what Merl says. */
	static String start(ServerPlayer player) {
		String user = player.getName().getString();
		if (!WITH.add(player.getUUID())) return MerlLines.pick("companion_already", "user", user);
		State state = STATES.computeIfAbsent(player.getUUID(), id -> new State());
		state.nextChat = ticks + CHAT_MIN;
		return MerlLines.pick("companion_start", "user", user);
	}

	/** "you can go now". Returns what Merl says. */
	static String stop(ServerPlayer player) {
		String user = player.getName().getString();
		if (!WITH.remove(player.getUUID())) return MerlLines.pick("companion_not_here", "user", user);
		STATES.remove(player.getUUID());
		if (!MerlGuide.guiding(player.getUUID()) && !MerlHideAndSeek.playing(player.getUUID())) MerlGuideNpc.stop(player.getUUID());
		return MerlLines.pick("companion_bye", "user", user);
	}

	/** Called every server tick. */
	public static void tick(MinecraftServer server) {
		ticks++;
		if (WITH.isEmpty()) return;
		if (!NiceMerl.config().companion) {
			for (UUID id : WITH) MerlGuideNpc.stop(id);
			WITH.clear();
			return;
		}
		boolean check = ticks % CHECK_TICKS == 0;
		for (UUID id : WITH) {
			ServerPlayer player = server.getPlayerList().getPlayer(id);
			// Offline or guided somewhere: she's back when they are, or once the guide is done.
			if (player == null || player.isSpectator() || MerlGuide.guiding(id) || MerlHideAndSeek.playing(id)) continue;
			MerlGuideNpc.accompany(player);
			if (!check) continue;
			State state = STATES.computeIfAbsent(id, k -> new State());
			Outfit outfit = outfit(player);
			if (MerlGuideNpc.dress(id, outfit.name(), outfit.items()) && !outfit.name().startsWith("default")) {
				say(player, state, "companion_outfit_" + outfit.name().replace("_hurt", ""), OUTFIT_PAUSE);
			}
			help(player, state);
		}
	}

	/** A warning, a reminder or now and then a comment. */
	private static void help(ServerPlayer player, State state) {
		ServerLevel level = player.level();
		if (!level.getEntitiesOfClass(Creeper.class, player.getBoundingBox().inflate(7), Creeper::isAlive).isEmpty()) {
			say(player, state, "companion_creeper", CREEPER_PAUSE);
			return;
		}
		if (player.getHealth() <= player.getMaxHealth() * 0.3f) {
			say(player, state, "companion_hurt", HURT_PAUSE);
			return;
		}
		if (player.getFoodData().getFoodLevel() <= 6) {
			say(player, state, "companion_hungry", HUNGRY_PAUSE);
			return;
		}
		if (level.dimension() == Level.OVERWORLD) {
			long time = level.getOverworldClockTime();
			long day = time / 24000;
			if (time % 24000 >= 12500 && time % 24000 < 13500 && state.nightNoted != day) {
				state.nightNoted = day;
				say(player, state, "companion_night", 0);
				return;
			}
		}
		if (ticks >= state.nextChat) {
			state.nextChat = ticks + CHAT_MIN + player.getRandom().nextInt((int) (CHAT_MAX - CHAT_MIN));
			String line = MerlLines.chance(2) ? MerlCommand.situationLine(player) : null;
			MerlCommand.replyTo(player.createCommandSourceStack(), Component.literal(line != null ? line
					: MerlLines.pick("companion_chat", "user", player.getName().getString())));
		}
	}

	private static void say(ServerPlayer player, State state, String pool, long pause) {
		Long last = state.lastSaid.get(pool);
		if (last != null && ticks - last < pause) return;
		state.lastSaid.put(pool, ticks);
		MerlCommand.replyTo(player.createCommandSourceStack(), Component.literal(MerlLines.pick(pool, "user", player.getName().getString())));
	}

	record Outfit(String name, List<Pair<EquipmentSlot, ItemStack>> items) {}

	/** What she wears and holds for the situation, most important first. */
	static Outfit outfit(ServerPlayer player) {
		ServerLevel level = player.level();
		Outfit outfit;
		ItemStack held = player.getMainHandItem();
		if (player.isUnderWater()) {
			outfit = outfit("underwater", stack(Items.TRIDENT), ItemStack.EMPTY, stack(Items.TURTLE_HELMET), null, null, null);
		} else if (level.dimension() == Level.NETHER) {
			// Gold keeps the piglins friendly; fire resistance for the lava.
			outfit = outfit("nether", stack(Items.NETHERITE_SWORD), PotionContents.createItemStack(Items.POTION, Potions.FIRE_RESISTANCE),
					stack(Items.GOLDEN_HELMET), stack(Items.NETHERITE_CHESTPLATE), stack(Items.NETHERITE_LEGGINGS), stack(Items.NETHERITE_BOOTS));
		} else if (level.dimension() == Level.END) {
			// A carved pumpkin, so the endermen don't mind her looking around.
			outfit = outfit("end", stack(Items.BOW), stack(Items.ENDER_PEARL), stack(Items.CARVED_PUMPKIN),
					stack(Items.DIAMOND_CHESTPLATE), stack(Items.DIAMOND_LEGGINGS), stack(Items.DIAMOND_BOOTS));
		} else if (!level.getEntitiesOfClass(Monster.class, player.getBoundingBox().inflate(12), Monster::isAlive).isEmpty()) {
			outfit = outfit("danger", stack(Items.IRON_SWORD), stack(Items.SHIELD), stack(Items.IRON_HELMET),
					stack(Items.IRON_CHESTPLATE), stack(Items.IRON_LEGGINGS), stack(Items.IRON_BOOTS));
		} else if (held.is(Items.FISHING_ROD)) {
			outfit = outfit("fishing", stack(Items.FISHING_ROD), stack(Items.COD_BUCKET), stack(Items.LEATHER_HELMET), null, null, stack(Items.LEATHER_BOOTS));
		} else if (held.is(ItemTags.HOES) || held.is(ItemTags.VILLAGER_PLANTABLE_SEEDS) || held.is(Items.BONE_MEAL)) {
			outfit = outfit("farming", stack(Items.IRON_HOE), stack(Items.WHEAT_SEEDS), null, null, null, stack(Items.LEATHER_BOOTS));
		} else if (held.is(ItemTags.PICKAXES) || !level.canSeeSky(player.blockPosition()) && player.getY() < level.getSeaLevel()) {
			outfit = outfit("mining", stack(Items.IRON_PICKAXE), stack(Items.TORCH), stack(Items.IRON_HELMET), null, null, null);
		} else if (held.getItem() instanceof BlockItem) {
			outfit = outfit("building", held.copyWithCount(1), stack(Items.SCAFFOLDING), null, null, null, null);
		} else if (level.getBiome(player.blockPosition()).value().coldEnoughToSnow(player.blockPosition(), level.getSeaLevel())) {
			outfit = outfit("snowy", stack(Items.SNOWBALL), stack(Items.COMPASS), stack(Items.LEATHER_HELMET),
					stack(Items.LEATHER_CHESTPLATE), stack(Items.LEATHER_LEGGINGS), stack(Items.LEATHER_BOOTS));
		} else {
			outfit = outfit("default", MerlGuideNpc.map(player), stack(Items.COMPASS), null, null, null, null);
		}
		// Hurt: a golden apple ready in her other hand.
		if (player.getHealth() <= player.getMaxHealth() * 0.3f) {
			List<Pair<EquipmentSlot, ItemStack>> items = new ArrayList<>(outfit.items());
			items.replaceAll(p -> p.getFirst() == EquipmentSlot.OFFHAND ? Pair.of(EquipmentSlot.OFFHAND, stack(Items.GOLDEN_APPLE)) : p);
			outfit = new Outfit(outfit.name() + "_hurt", items);
		}
		return outfit;
	}

	private static Outfit outfit(String name, ItemStack main, ItemStack off, ItemStack head, ItemStack chest, ItemStack legs, ItemStack feet) {
		return new Outfit(name, List.of(Pair.of(EquipmentSlot.MAINHAND, main), Pair.of(EquipmentSlot.OFFHAND, off),
				Pair.of(EquipmentSlot.HEAD, orEmpty(head)), Pair.of(EquipmentSlot.CHEST, orEmpty(chest)),
				Pair.of(EquipmentSlot.LEGS, orEmpty(legs)), Pair.of(EquipmentSlot.FEET, orEmpty(feet))));
	}

	private static ItemStack stack(net.minecraft.world.item.Item item) {
		return new ItemStack(item);
	}

	private static ItemStack orEmpty(ItemStack stack) {
		return stack == null ? ItemStack.EMPTY : stack;
	}
}
