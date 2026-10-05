package eu.explorerseden.nicemerl;

import java.util.Comparator;
import java.util.List;

import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Team;

/**
 * A Merl mannequin admins can place (at spawn, next to the help board…): invulnerable, silent, immovable, no name
 * or "NPC" label, no collision, never in a boat or minecart. Right-clicking it shows a configurable message.
 */
public final class MerlMannequin {
	static final String TAG = "nicemerl.mannequin";
	private static final String NEW_TAG = "nicemerl.mannequin.new";
	/** Players don't collide with it, and nothing shows above its head. */
	private static final String TEAM = "nicemerl_mannequin";
	private static final int CHECK_TICKS = 20;
	/** Mannequins turn to the closest player this close, every few ticks. */
	private static final double LOOK_RANGE = 6.0;
	private static final int LOOK_TICKS = 2;
	/** Looking straight at a mannequin this close greets you (once a minute at most). */
	private static final double EYE_CONTACT_RANGE = 4.0;
	private static final double EYE_CONTACT_COS = Math.cos(Math.toRadians(12));
	private static final long GREET_PAUSE_MS = 60_000;
	private static final java.util.Map<java.util.UUID, Long> LAST_GREETED = new java.util.concurrent.ConcurrentHashMap<>();
	/**
	 * Merl's skin (minecraftskins.com/skin/24292599/merl), hosted on Mojang's skin server (slim model) so every
	 * client can show it: the base64 "textures" value of that upload.
	 */
	static final String SKIN_TEXTURE = "ewogICJ0aW1lc3RhbXAiIDogMTc5MTE5MzA4OTk4NCwKICAicHJvZmlsZUlkIiA6ICI4MjYxOGI1ZjhhMzA0Njg2YTkyMTM2ZDcxZTlhZDkyMSIsCiAgInByb2ZpbGVOYW1lIiA6ICIxZXRobyIsCiAgInNpZ25hdHVyZVJlcXVpcmVkIiA6IHRydWUsCiAgInRleHR1cmVzIiA6IHsKICAgICJTS0lOIiA6IHsKICAgICAgInVybCIgOiAiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS9lYTZjZTFkNGRkMTUwODE2MWE0MGY5NjY0Zjg5ODY2ZWQzZjEzNzA4ZWUwZTk5NjhlZTAyMGE0MGE0OGVlOWQwIiwKICAgICAgIm1ldGFkYXRhIiA6IHsKICAgICAgICAibW9kZWwiIDogInNsaW0iCiAgICAgIH0KICAgIH0KICB9Cn0=";
	private static int ticks;
	private static final EntityTypeTest<Entity, Mannequin> MANNEQUINS = EntityTypeTest.forClass(Mannequin.class);

	private MerlMannequin() {}

	static void register() {
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (!isMannequin(entity)) return InteractionResult.PASS;
			// Handled here for both hands, so name tags, leads or dyes never get used on it.
			if (player instanceof ServerPlayer serverPlayer && hand == net.minecraft.world.InteractionHand.MAIN_HAND) greet(serverPlayer);
			return InteractionResult.SUCCESS;
		});
		AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (!isMannequin(entity)) return InteractionResult.PASS;
			if (player instanceof ServerPlayer serverPlayer) greet(serverPlayer);
			return InteractionResult.FAIL;
		});
	}

	static boolean isMannequin(Entity entity) {
		return entity instanceof Mannequin && entity.entityTags().contains(TAG);
	}

	private static void greet(ServerPlayer player) {
		LAST_GREETED.put(player.getUUID(), System.currentTimeMillis());
		MerlConfig config = NiceMerl.config();
		String text = config.mannequinMessage == null || config.mannequinMessage.isBlank()
				? MerlLines.pick("mannequin_greeting", "user", player.getName().getString())
				: config.mannequinMessage.replace("{user}", player.getName().getString());
		if ("actionbar".equalsIgnoreCase(config.mannequinMessageType)) {
			player.sendOverlayMessage(Component.literal(text).withStyle(ChatFormatting.LIGHT_PURPLE));
			MerlCommand.plop(player);
		} else {
			MerlCommand.replyTo(player.createCommandSourceStack(), Component.literal(text));
		}
	}

	/** Summons a mannequin where the player stands, facing the same way. Returns a status line for the admin. */
	static Component spawn(CommandSourceStack source) {
		ServerPlayer player = source.getPlayer();
		if (player == null) return Component.literal("Only players can place a Merl mannequin.");
		ServerLevel level = player.level();
		String profile = SKIN_TEXTURE.isEmpty() ? "" : "{properties:[{name:\"textures\",value:\"" + SKIN_TEXTURE + "\"}]}";
		String nbt = "{Tags:[\"" + TAG + "\",\"" + NEW_TAG + "\"],Invulnerable:1b,Silent:1b,PersistenceRequired:1b,"
				+ "immovable:1b,hide_description:1b,Rotation:[" + player.getYRot() + "f,0f]" + (profile.isEmpty() ? "" : ",profile:" + profile) + "}";
		MinecraftServer server = source.getServer();
		CommandSourceStack console = server.createCommandSourceStack().withSuppressedOutput().withLevel(level).withPosition(player.position());
		server.getCommands().performPrefixedCommand(console, "summon minecraft:mannequin ~ ~ ~ " + nbt);
		List<? extends Mannequin> spawned = level.getEntities(MANNEQUINS, m -> m.entityTags().contains(NEW_TAG));
		if (spawned.isEmpty()) return Component.literal("The mannequin couldn't be placed here.").withStyle(ChatFormatting.RED);
		for (Mannequin mannequin : spawned) {
			mannequin.removeTag(NEW_TAG);
			joinTeam(server, mannequin);
		}
		return Component.literal("Merl mannequin placed. Right-click it to try it, or remove it with /nicemerl mannequin remove.");
	}

	/** Removes the closest Merl mannequin within 8 blocks. */
	static Component remove(CommandSourceStack source) {
		ServerPlayer player = source.getPlayer();
		if (player == null) return Component.literal("Only players can remove a Merl mannequin.");
		Mannequin closest = player.level().getEntities(MANNEQUINS, m -> m.entityTags().contains(TAG) && m.distanceToSqr(player) < 64)
				.stream().map(m -> (Mannequin) m).min(Comparator.comparingDouble(m -> m.distanceToSqr(player))).orElse(null);
		if (closest == null) return Component.literal("There's no Merl mannequin within 8 blocks.");
		closest.discard();
		return Component.literal("Merl mannequin removed.");
	}

	/**
	 * Every couple of ticks mannequins look at the closest player nearby; every second they're also kept out of
	 * boats and minecarts and in the no-collision team.
	 */
	public static void tick(MinecraftServer server) {
		ticks++;
		boolean look = ticks % LOOK_TICKS == 0, check = ticks % CHECK_TICKS == 0;
		if (!look && !check) return;
		for (ServerLevel level : server.getAllLevels()) {
			if (level.players().isEmpty()) continue;
			for (Mannequin mannequin : level.getEntities(MANNEQUINS, MerlMannequin::isMannequin)) {
				if (look) {
					lookAtClosestPlayer(level, mannequin);
					if (NiceMerl.config().mannequinGreetOnLook) greetOnEyeContact(level, mannequin);
				}
				if (!check) continue;
				if (mannequin.isPassenger()) mannequin.stopRiding();
				if (mannequin.getTeam() == null) joinTeam(server, mannequin);
			}
		}
	}

	/** A player within a few blocks looking straight at the mannequin's face, with nothing in between, gets the message. */
	private static void greetOnEyeContact(ServerLevel level, Mannequin mannequin) {
		long now = System.currentTimeMillis();
		Vec3 face = mannequin.getEyePosition();
		for (ServerPlayer player : level.players()) {
			if (player.isSpectator() || player.distanceToSqr(mannequin) > EYE_CONTACT_RANGE * EYE_CONTACT_RANGE) continue;
			Long last = LAST_GREETED.get(player.getUUID());
			if (last != null && now - last < GREET_PAUSE_MS) continue;
			Vec3 toFace = face.subtract(player.getEyePosition()).normalize();
			if (player.getViewVector(1.0f).dot(toFace) < EYE_CONTACT_COS || !player.hasLineOfSight(mannequin)) continue;
			greet(player);
		}
	}

	private static void lookAtClosestPlayer(ServerLevel level, Mannequin mannequin) {
		ServerPlayer closest = null;
		double best = LOOK_RANGE * LOOK_RANGE;
		for (ServerPlayer player : level.players()) {
			if (player.isSpectator()) continue;
			double d = player.distanceToSqr(mannequin);
			if (d < best) {
				best = d;
				closest = player;
			}
		}
		if (closest == null) return;
		Vec3 eyes = mannequin.getEyePosition(), target = closest.getEyePosition();
		double dx = target.x - eyes.x, dy = target.y - eyes.y, dz = target.z - eyes.z;
		float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
		float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
		mannequin.setYRot(yaw);
		mannequin.setYHeadRot(yaw);
		mannequin.setYBodyRot(yaw);
		mannequin.setXRot(Math.max(-60, Math.min(60, pitch)));
	}

	private static void joinTeam(MinecraftServer server, Entity entity) {
		Scoreboard scoreboard = server.getScoreboard();
		PlayerTeam team = scoreboard.getPlayerTeam(TEAM);
		if (team == null) {
			team = scoreboard.addPlayerTeam(TEAM);
			team.setCollisionRule(Team.CollisionRule.NEVER);
			team.setNameTagVisibility(Team.Visibility.NEVER);
		}
		scoreboard.addPlayerToTeam(entity.getScoreboardName(), team);
	}
}
