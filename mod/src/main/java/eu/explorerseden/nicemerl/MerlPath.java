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
 * A walkable path toward a target, for the sparkle trail: an A* search over standing spots (solid ground below,
 * room for a player above), going up one block, down up to three, around walls and through water, never through
 * lava, fire or into holes. It only plans the next stretch (about 28 blocks); far targets get a new stretch as the
 * player walks.
 */
final class MerlPath {
	/** How far ahead one stretch is planned, in blocks. */
	static final int STRETCH = 28;
	/** Most spots looked at per search, so a maze or an ocean can't slow the server down. */
	private static final int BUDGET = 6000;
	private static final int MAX_DROP = 3;
	private static final int[][] STEPS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

	private MerlPath() {}

	/**
	 * Standing spots from the start toward the goal, start first. When the goal can't be reached within the
	 * budget, the path ends at the spot that got closest. Empty when the player isn't standing anywhere useful.
	 *
	 * @param goalY the goal's height, or null when any height will do
	 */
	static List<BlockPos> find(ServerLevel level, BlockPos from, double goalX, Integer goalY, double goalZ) {
		BlockPos start = standingSpot(level, from);
		if (start == null) return List.of();
		// The goal of this stretch: the target itself when it's close, else a point STRETCH blocks toward it.
		double dx = goalX - start.getX(), dz = goalZ - start.getZ();
		double far = Math.hypot(dx, dz);
		double gx = far > STRETCH ? start.getX() + dx / far * STRETCH : goalX;
		double gz = far > STRETCH ? start.getZ() + dz / far * STRETCH : goalZ;
		boolean useHeight = goalY != null && far <= STRETCH;

		Map<BlockPos, BlockPos> cameFrom = new HashMap<>();
		Map<BlockPos, Double> cost = new HashMap<>();
		PriorityQueue<Node> open = new PriorityQueue<>();
		cost.put(start, 0.0);
		open.add(new Node(start, guess(start, gx, goalY, gz, useHeight)));
		BlockPos best = start;
		double bestGuess = guess(start, gx, goalY, gz, useHeight);
		int looked = 0;
		while (!open.isEmpty() && looked++ < BUDGET) {
			BlockPos at = open.poll().pos();
			double left = guess(at, gx, goalY, gz, useHeight);
			if (left < bestGuess) {
				best = at;
				bestGuess = left;
			}
			if (left <= 1.5) {
				best = at;
				break;
			}
			for (int[] step : STEPS) {
				BlockPos next = move(level, at, step[0], step[1]);
				if (next == null) continue;
				double stepCost = (step[0] != 0 && step[1] != 0 ? 1.414 : 1.0) + Math.abs(next.getY() - at.getY()) * 0.5
						+ (level.getFluidState(next).is(FluidTags.WATER) ? 2.0 : 0.0);
				double total = cost.get(at) + stepCost;
				if (total < cost.getOrDefault(next, Double.MAX_VALUE)) {
					cost.put(next, total);
					cameFrom.put(next, at);
					open.add(new Node(next, total + guess(next, gx, goalY, gz, useHeight)));
				}
			}
		}
		List<BlockPos> path = new ArrayList<>();
		for (BlockPos at = best; at != null; at = cameFrom.get(at)) path.add(at);
		Collections.reverse(path);
		return path;
	}

	private record Node(BlockPos pos, double score) implements Comparable<Node> {
		@Override
		public int compareTo(Node other) {
			return Double.compare(score, other.score);
		}
	}

	private static double guess(BlockPos at, double gx, Integer goalY, double gz, boolean useHeight) {
		double flat = Math.hypot(gx - at.getX(), gz - at.getZ());
		return useHeight ? flat + Math.abs(goalY - at.getY()) : flat;
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

	/** Room for a player at feet and head height, and something to stand on (or water to swim in). */
	static boolean standable(ServerLevel level, BlockPos feet) {
		if (!open(level, feet) || !open(level, feet.above())) return false;
		if (level.getFluidState(feet).is(FluidTags.WATER)) return true;
		BlockState ground = level.getBlockState(feet.below());
		String groundId = ground.getBlock().builtInRegistryHolder().key().identifier().getPath();
		if (groundId.equals("magma_block") || groundId.contains("campfire") || groundId.equals("cactus")) return false;
		var shape = ground.getCollisionShape(level, feet.below());
		// Fences and walls are taller than a block: you don't walk on top of those.
		return !ground.getFluidState().is(FluidTags.LAVA) && !shape.isEmpty() && shape.max(Direction.Axis.Y) <= 1.0
				&& (ground.isFaceSturdy(level, feet.below(), Direction.UP) || shape.max(Direction.Axis.Y) > 0.4);
	}

	/**
	 * Room to walk through: nothing to bump into, or something players walk through anyway (doors, fence gates and
	 * trapdoors they open, carpet and thin snow), and nothing that hurts (lava, fire, powder snow, cactus, berry bushes).
	 */
	private static boolean open(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (state.getFluidState().is(FluidTags.LAVA)) return false;
		String id = state.getBlock().builtInRegistryHolder().key().identifier().getPath();
		if (id.contains("fire") || id.equals("powder_snow") || id.equals("cactus") || id.equals("sweet_berry_bush")) return false;
		if (state.is(BlockTags.DOORS) || state.is(BlockTags.FENCE_GATES) || state.is(BlockTags.TRAPDOORS)) return !id.contains("iron");
		var shape = state.getCollisionShape(level, pos);
		return shape.isEmpty() || shape.max(Direction.Axis.Y) <= 0.1875;
	}
}
