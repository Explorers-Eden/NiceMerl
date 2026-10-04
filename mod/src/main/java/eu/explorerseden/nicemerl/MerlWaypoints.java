package eu.explorerseden.nicemerl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import me.lucko.fabric.api.permissions.v0.Permissions;

/**
 * "Where's the closest waypoint?" with the Warping Wonders data pack: Merl reads its Waypoint Hubs and
 * points to the closest one the player may use (their own, public ones, and locked ones they're trusted on).
 */
public final class MerlWaypoints {
	private static final Identifier DATABASE = Identifier.fromNamespaceAndPath("eden", "database");
	/** A Warping Wonders function, to tell whether the pack is installed. */
	private static final Identifier PACK_FUNCTION = Identifier.fromNamespaceAndPath("wawo", "waypoint_hub/place/exec");
	/** Warping Wonders' tag for players who can use every Waypoint Hub. */
	private static final String ADMIN_TAG = "wawo.admin";

	record Hub(String name, String owner, BlockPos pos, Identifier dimension, String dimensionName) {}

	private MerlWaypoints() {}

	/** Answers waypoint questions when Warping Wonders is installed, and returns true; otherwise false. */
	static boolean handle(CommandSourceStack source, ServerPlayer player, String question, MerlConfig config) {
		if (player == null) return false;
		return handle(source, player.getUUID(), player.getName().getString(), player.entityTags().contains(ADMIN_TAG), question, config);
	}

	static boolean handle(CommandSourceStack source, UUID me, String user, boolean admin, String question, MerlConfig config) {
		if (!config.locateWaypoints || !MerlLines.waypointQuestion(question)) return false;
		if (!Permissions.check(source, MerlLocate.PERMISSION_LOCATE, true)) return false;
		MinecraftServer server = source.getServer();
		if (server.getFunctions().get(PACK_FUNCTION).isEmpty()) return false;
		boolean onlyMine = MerlLines.waypointOnlyMine(question);
		List<Hub> hubs = usable(server, me, user, admin, onlyMine);
		if (hubs.isEmpty()) {
			MerlCommand.replyTo(source, Component.literal(MerlLines.pick(onlyMine ? "waypoint_none_mine" : "waypoint_none", "user", user)));
			return true;
		}
		BlockPos from = BlockPos.containing(source.getPosition());
		Identifier here = source.getLevel().dimension().identifier();
		// A waypoint named in the question ("where is the castle waypoint"), else the closest one here.
		String text = question.toLowerCase(Locale.ROOT);
		Hub named = hubs.stream().filter(h -> h.name().length() >= 3 && text.contains(h.name().toLowerCase(Locale.ROOT)))
				.max(Comparator.comparingInt(h -> h.name().length())).orElse(null);
		List<Hub> nearby = hubs.stream().filter(h -> h.dimension().equals(here))
				.sorted(Comparator.comparingDouble(h -> h.pos().distSqr(from))).toList();
		Hub hub = named != null ? named : nearby.isEmpty() ? null : nearby.get(0);
		if (hub == null) {
			// None in this dimension: say where they are.
			Hub other = hubs.get(0);
			MerlCommand.replyTo(source, Component.literal(MerlLines.pick("waypoint_other_dimension", "user", user,
					"count", String.valueOf(hubs.size()), "dimension", dimension(other), "waypoint", other.name())));
			return true;
		}
		if (!hub.dimension().equals(here)) {
			MerlCommand.replyTo(source, Component.literal(MerlLines.pick("waypoint_named_elsewhere", "user", user,
					"waypoint", hub.name(), "dimension", dimension(hub))));
			return true;
		}
		double dx = hub.pos().getX() - from.getX(), dz = hub.pos().getZ() - from.getZ();
		String line = MerlLines.pick(hub.owner().equalsIgnoreCase(user) ? "waypoint_found_mine" : "waypoint_found",
				"user", user, "waypoint", hub.name(), "owner", hub.owner(),
				"distance", String.format(Locale.ROOT, "%,d", Math.round(Math.sqrt(dx * dx + dz * dz))),
				"direction", BiomeNames.direction(dx, dz), "count", String.valueOf(hubs.size()));
		MerlCommand.replyTo(source, MerlLocate.found(line, hub.pos().getX(), hub.pos().getY(), hub.pos().getZ(),
				MerlLocate.canTeleport(source), hub.name()));
		return true;
	}

	/** The Waypoint Hubs the player may use, like the Waypoint Hub menu shows them. */
	static List<Hub> usable(MinecraftServer server, UUID me, String name, boolean admin, boolean onlyMine) {
		CompoundTag hubs = server.getCommandStorage().get(DATABASE).getCompoundOrEmpty("waypoints").getCompoundOrEmpty("hubs");
		List<Hub> out = new ArrayList<>();
		for (String id : hubs.keySet()) {
			CompoundTag hub = hubs.getCompoundOrEmpty(id);
			CompoundTag profile = hub.getCompoundOrEmpty("profile");
			boolean mine = profile.getIntArray("id").filter(a -> a.length == 4).map(UUIDUtil::uuidFromIntArray).map(me::equals).orElse(false)
					|| name.equals(profile.getStringOr("name", ""));
			boolean trusted = hub.getListOrEmpty("trust").stream().anyMatch(t -> t.asString().map(name::equals).orElse(false));
			boolean open = "public".equals(hub.getStringOr("access", "public"));
			if (onlyMine ? !mine : !(mine || open || trusted || admin)) continue;
			CompoundTag pos = hub.getCompoundOrEmpty("pos");
			Identifier dimension = Identifier.tryParse(pos.getStringOr("dimension", "minecraft:overworld"));
			if (dimension == null || pos.getInt("x").isEmpty() || pos.getInt("z").isEmpty()) continue;
			String owner = profile.getStringOr("name", "someone");
			String title = hub.get("waypoint_name") != null ? text(server, hub.get("waypoint_name")) : "";
			out.add(new Hub(title.isBlank() ? "Unnamed Waypoint" : title, owner,
					new BlockPos(pos.getIntOr("x", 0), pos.getIntOr("y", 64), pos.getIntOr("z", 0)),
					dimension, hub.getStringOr("dimension_name", "")));
		}
		return out;
	}

	/** A stored name, which is a text component (a plain string, or {"text": …} and so on). */
	private static String text(MinecraftServer server, Tag tag) {
		if (tag.asString().isPresent()) return tag.asString().get();
		return ComponentSerialization.CODEC.parse(server.registryAccess().createSerializationContext(NbtOps.INSTANCE), tag)
				.result().map(Component::getString).orElse("");
	}

	private static String dimension(Hub hub) {
		return hub.dimensionName().isBlank() ? MerlLocate.dimensionName(hub.dimension()) : hub.dimensionName();
	}
}
