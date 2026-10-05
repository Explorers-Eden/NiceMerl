package eu.explorerseden.nicemerl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A walkable path toward a target, for the guide: an A* search over standing spots (solid ground below, room for a
 * player above), going up one block, down up to three, up and down ladders, vines, scaffolding and water, around
 * walls and through doors, never through lava, fire or into holes.
 *
 * <p>The search runs a few thousand spots per tick ({@link Search#step}), so a big base or a maze can be searched
 * completely without slowing the server down. It only ever returns a path that really gets there: to the target
 * itself when its chunk is loaded, otherwise out under the open sky and a good bit closer (a far target gets the
 * next stretch as the player walks). A search that ends at the closest wall would lead people into it.
 */
final class MerlPath {
	/** Spots looked at per tick, and at most per search before it gives up. */
	static final int PER_TICK = 4000;
	static final int MAX_LOOKED = 250_000;
	/** For a target out of reach of loaded chunks, a stretch is done once it's this much closer, under the sky. */
	private static final double PROGRESS = 24;
	/** Close enough to the target to count as there: flat distance and height difference, in blocks. */
	private static final double GOAL_FLAT = 2.0, GOAL_HEIGHT = 4.0;
	/** A little greedier than plain A*: much faster in big bases, paths only slightly longer. */
	private static final double GREED = 1.15;
	private static final int MAX_DROP = 3;
	private static final int[][] STEPS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

	private MerlPath() {}

	/**
	 * A finished search.
	 *
	 * @param path standing spots, start first; empty when no way was found
	 * @param toTarget the path goes all the way to the target, not just the next stretch
	 * @param looked spots looked at
	 */
	record Plan(List<BlockPos> path, boolean toTarget, int looked) {}

	/** A search in progress; call {@link #step} every tick until it returns a plan. */
	static final class Search {
		private final ServerLevel level;
		private final double gx, gz;
		private final Integer goalY;
		/** The target's chunk is loaded: search all the way to it. Otherwise just out and closer. */
		private final boolean toTarget;
		private final boolean needSky;
		private final double enough;
		private final Map<BlockPos, BlockPos> cameFrom = new HashMap<>();
		private final Map<BlockPos, Double> cost = new HashMap<>();
		private final PriorityQueue<Node> open = new PriorityQueue<>();
		private int looked;

		private Search(ServerLevel level, BlockPos start, double gx, Integer goalY, double gz) {
			this.level = level;
			this.gx = gx;
			this.gz = gz;
			this.goalY = goalY;
			BlockPos goal = BlockPos.containing(gx, goalY != null ? goalY : start.getY(), gz);
			toTarget = level.isLoaded(goal);
			double far = flat(start);
			enough = far - PROGRESS;
			// Under a roof (a base, a cave), the stretch has to get outside first. Not in the Nether: no sky there.
			needSky = !toTarget && !level.dimensionType().hasCeiling() && !level.canSeeSky(start);
			cost.put(start, 0.0);
			open.add(new Node(start, guess(start)));
		}

		int looked() {
			return looked;
		}

		/** Looks at up to {@code spots} more spots. Returns the plan once done (found or given up), else null. */
		Plan step(int spots) {
			for (int i = 0; i < spots; i++) {
				if (open.isEmpty() || looked >= MAX_LOOKED) return new Plan(List.of(), toTarget, looked);
				looked++;
				Node node = open.poll();
				BlockPos at = node.pos();
				double known = cost.get(at);
				// An outdated queue entry: a cheaper way here was found after it was queued.
				if (node.score() > known + GREED * guess(at) + 1e-6) continue;
				if (done(at)) return new Plan(path(at), toTarget, looked);
				for (BlockPos next : neighbours(level, at)) {
					double stepCost = Math.hypot(next.getX() - at.getX(), next.getZ() - at.getZ())
							+ Math.abs(next.getY() - at.getY()) * 0.5
							+ (level.getFluidState(next).is(FluidTags.WATER) ? 2.0 : 0.0);
					double total = known + stepCost;
					if (total < cost.getOrDefault(next, Double.MAX_VALUE)) {
						cost.put(next, total);
						cameFrom.put(next, at);
						open.add(new Node(next, total + GREED * guess(next)));
					}
				}
			}
			return null;
		}

		private boolean done(BlockPos at) {
			if (toTarget) return flat(at) <= GOAL_FLAT && (goalY == null || Math.abs(goalY - at.getY()) <= GOAL_HEIGHT);
			return flat(at) <= enough && (!needSky || level.canSeeSky(at));
		}

		private double flat(BlockPos at) {
			return Math.hypot(gx - (at.getX() + 0.5), gz - (at.getZ() + 0.5));
		}

		/** Never more than the real cost left, so the path found is a good one. */
		private double guess(BlockPos at) {
			double left = Math.max(0, flat(at) - (toTarget ? GOAL_FLAT : 0));
			if (toTarget && goalY != null) left += Math.max(0, Math.abs(goalY - at.getY()) - GOAL_HEIGHT) * 0.5;
			return left;
		}

		private List<BlockPos> path(BlockPos end) {
			List<BlockPos> path = new ArrayList<>();
			for (BlockPos at = end; at != null; at = cameFrom.get(at)) path.add(at);
			Collections.reverse(path);
			return path;
		}
	}

	/** Starts a search from where the player stands, or returns null when they aren't standing anywhere useful. */
	static Search start(ServerLevel level, BlockPos feet, double goalX, Integer goalY, double goalZ) {
		BlockPos start = standingSpot(level, feet);
		return start == null ? null : new Search(level, start, goalX, goalY, goalZ);
	}

	private record Node(BlockPos pos, double score) implements Comparable<Node> {
		@Override
		public int compareTo(Node other) {
			return Double.compare(score, other.score);
		}
	}

	/** Every spot one move away: a step to the side (level, one up, up to three down), or up or down a climb. */
	private static List<BlockPos> neighbours(ServerLevel level, BlockPos at) {
		List<BlockPos> result = new ArrayList<>(10);
		for (int[] step : STEPS) {
			BlockPos next = move(level, at, step[0], step[1]);
			if (next != null) result.add(next);
		}
		// Ladders, vines, scaffolding and water go straight up and down.
		BlockPos up = at.above();
		if ((climbable(level, at) || climbable(level, up)) && open(level, up) && open(level, up.above())) result.add(up);
		BlockPos down = at.below();
		if (climbable(level, down) && open(level, down)) result.add(down);
		return result;
	}

	/** The spot one step over: the same height, one up, or up to three down. Diagonals can't cut corners. */
	private static BlockPos move(ServerLevel level, BlockPos at, int dx, int dz) {
		if (dx != 0 && dz != 0 && (!open(level, at.offset(dx, 0, 0)) || !open(level, at.offset(0, 0, dz)))) return null;
		BlockPos side = at.offset(dx, 0, dz);
		if (!level.isLoaded(side)) return null;
		if (standable(level, side)) return side;
		BlockPos up = side.above();
		if (open(level, at.above(2)) && standable(level, up)) return up;
		if (!open(level, side)) return null;
		for (int drop = 1; drop <= MAX_DROP; drop++) {
			BlockPos down = side.below(drop);
			if (standable(level, down)) return down;
			if (!open(level, down)) return null;
		}
		return null;
	}

	/** The spot the player stands on (their feet), or the first one below within a few blocks. */
	private static BlockPos standingSpot(ServerLevel level, BlockPos feet) {
		for (int i = 0; i <= MAX_DROP + 1; i++) {
			BlockPos at = feet.below(i);
			if (standable(level, at)) return at;
		}
		return standable(level, feet.above()) ? feet.above() : null;
	}

	/** Block ids by block, so the search doesn't build a new string for every spot it looks at. */
	private static final Map<net.minecraft.world.level.block.Block, String> IDS = new java.util.concurrent.ConcurrentHashMap<>();

	private static String id(net.minecraft.world.level.block.Block block) {
		return IDS.computeIfAbsent(block, b -> net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b).getPath());
	}

	/** Something players climb or swim up and down in: ladders, vines, scaffolding, water. */
	private static boolean climbable(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		return state.is(BlockTags.CLIMBABLE) || state.getFluidState().is(FluidTags.WATER);
	}

	/** Room for a player at feet and head height, and something to stand on (or water or a ladder to hold on to). */
	static boolean standable(ServerLevel level, BlockPos feet) {
		if (!open(level, feet) || !open(level, feet.above())) return false;
		if (climbable(level, feet)) return true;
		BlockState ground = level.getBlockState(feet.below());
		String groundId = id(ground.getBlock());
		if (groundId.equals("magma_block") || groundId.contains("campfire") || groundId.equals("cactus")) return false;
		var shape = ground.getCollisionShape(level, feet.below());
		// Fences and walls are taller than a block: you don't walk on top of those.
		return !ground.getFluidState().is(FluidTags.LAVA) && !shape.isEmpty() && shape.max(Direction.Axis.Y) <= 1.0
				&& (ground.isFaceSturdy(level, feet.below(), Direction.UP) || shape.max(Direction.Axis.Y) > 0.4);
	}

	/**
	 * Room to walk through: nothing to bump into, or something players walk through anyway (doors, fence gates and
	 * trapdoors they open, carpet and thin snow, ladders, vines and scaffolding they climb), and nothing that hurts
	 * (lava, fire, powder snow, cactus, berry bushes).
	 */
	private static boolean open(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (state.getFluidState().is(FluidTags.LAVA)) return false;
		String id = id(state.getBlock());
		if (id.contains("fire") || id.equals("powder_snow") || id.equals("cactus") || id.equals("sweet_berry_bush")) return false;
		if (state.is(BlockTags.DOORS) || state.is(BlockTags.FENCE_GATES) || state.is(BlockTags.TRAPDOORS)) return !id.contains("iron");
		if (state.is(BlockTags.CLIMBABLE)) return true;
		var shape = state.getCollisionShape(level, pos);
		return shape.isEmpty() || shape.max(Direction.Axis.Y) <= 0.1875;
	}
}
