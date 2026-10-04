package eu.explorerseden.nicemerl;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;

/** "What can I craft?": what the player can make right now from their inventory, like the recipe book knows. */
public final class MerlCrafting {
	private static final int SHOWN = 10;

	private MerlCrafting() {}

	/** Merl's answer: the things the player can craft now, or why there are none. */
	static Component answer(ServerPlayer player, String message) {
		StackedItemContents contents = new StackedItemContents();
		player.getInventory().fillStackedContents(contents);
		List<ItemStack> carried = new ArrayList<>();
		for (ItemStack stack : player.getInventory()) if (!stack.isEmpty()) carried.add(stack);
		return answer(contents, carried, player.getMainHandItem(), player.level(), player.getName().getString(), message);
	}

	static Component answer(StackedItemContents contents, List<ItemStack> inventory, ItemStack mainHand,
			net.minecraft.world.level.Level level, String user, String message) {
		ItemStack held = MerlLines.craftingWithHeld(message) ? mainHand : ItemStack.EMPTY;
		if (MerlLines.craftingWithHeld(message) && held.isEmpty()) {
			return Component.literal(MerlLines.pick("craft_nothing_held", "user", user));
		}
		ContextMap context = SlotDisplayContext.fromLevel(level);
		// One entry per result item; it needs a table only if every recipe for it is bigger than 2×2.
		Map<Item, Boolean> needsTable = new LinkedHashMap<>();
		Map<Item, ItemStack> results = new LinkedHashMap<>();
		for (RecipeHolder<?> holder : level.getServer().getRecipeManager().getRecipes()) {
			if (!(holder.value() instanceof CraftingRecipe recipe) || recipe.isSpecial()
					|| recipe.placementInfo().isImpossibleToPlace()) continue;
			List<Ingredient> ingredients = recipe.placementInfo().ingredients();
			if (!held.isEmpty() && ingredients.stream().noneMatch(i -> i.test(held))) continue;
			if (!contents.canCraft(recipe, null)) continue;
			if (recipe.display().isEmpty()) continue;
			ItemStack result = recipe.display().get(0).result().resolveForFirstStack(context);
			if (result.isEmpty()) continue;
			boolean big = recipe instanceof ShapedRecipe shaped ? shaped.getWidth() > 2 || shaped.getHeight() > 2 : ingredients.size() > 4;
			results.putIfAbsent(result.getItem(), result);
			needsTable.merge(result.getItem(), big, Boolean::logicalAnd);
		}
		if (results.isEmpty()) {
			return Component.literal(held.isEmpty()
					? MerlLines.pick("craft_none", "user", user)
					: MerlLines.pick("craft_none_held", "item", held.getHoverName().getString(), "user", user));
		}
		// Things the player doesn't have yet come first.
		Set<Item> carried = new HashSet<>();
		for (ItemStack stack : inventory) carried.add(stack.getItem());
		List<Item> order = new ArrayList<>(results.keySet());
		order.sort((a, b) -> Boolean.compare(carried.contains(a), carried.contains(b)));

		String count = String.valueOf(order.size());
		MutableComponent message0 = Component.literal(held.isEmpty()
				? MerlLines.pick("craft_list", "count", count, "user", user)
				: MerlLines.pick("craft_list_held", "item", held.getHoverName().getString(), "count", count, "user", user));
		message0.append(" ");
		for (int i = 0; i < Math.min(SHOWN, order.size()); i++) {
			Item item = order.get(i);
			if (i > 0) message0.append(Component.literal(", ").withStyle(ChatFormatting.GRAY));
			message0.append(results.get(item).getHoverName().copy().withStyle(Style.EMPTY.withColor(ChatFormatting.YELLOW)
					.withHoverEvent(new HoverEvent.ShowText(Component.literal(needsTable.get(item)
							? "Needs a crafting table" : "Fits in your inventory's crafting grid")))));
		}
		if (order.size() > SHOWN) {
			MutableComponent rest = Component.empty();
			for (int i = SHOWN; i < order.size(); i++) {
				if (i > SHOWN) rest.append(i % 4 == 0 ? "\n" : ", ");
				rest.append(results.get(order.get(i)).getHoverName());
			}
			message0.append(Component.literal(" and " + (order.size() - SHOWN) + " more")
					.withStyle(Style.EMPTY.withColor(ChatFormatting.GRAY).withUnderlined(true)
							.withHoverEvent(new HoverEvent.ShowText(rest))));
		}
		return message0;
	}
}
