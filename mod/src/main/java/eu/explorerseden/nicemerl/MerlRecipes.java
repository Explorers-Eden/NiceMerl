package eu.explorerseden.nicemerl;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.item.enchantment.Enchantment;

/**
 * "How do I craft a waypoint hub?" from the server's own recipes (so our packs' recipes are included), with a
 * link to the recipe picture on explorerseden.eu, and "what can I enchant this with?" for the held item.
 */
public final class MerlRecipes {
	/** Most recipes shown for one question (the rest are named on hover). */
	private static final int SHOWN = 2;
	private static final Set<String> GENERIC = Set.of("recipe");

	/** A craftable result: its readable name and words, and the recipe. */
	private record Entry(String name, Set<String> words, RecipeHolder<?> holder, ItemStack result) {}

	private static volatile List<Entry> entries = List.of();
	private static volatile RecipeManager entriesFor;
	/** Recipe id → picture URL on the website, from its recipe manifest. */
	private static volatile Map<String, String> pictures = Map.of();

	private MerlRecipes() {}

	/** Downloads the website's recipe manifest (called with the wiki reindex, off the server thread). */
	static void loadPictures(String manifestUrl) {
		if (manifestUrl == null || manifestUrl.isBlank()) return;
		try {
			HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL).build();
			HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(manifestUrl))
					.timeout(Duration.ofSeconds(60)).header("User-Agent", "NiceMerl").GET().build(), HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) throw new IllegalStateException("HTTP " + response.statusCode());
			URI base = URI.create(manifestUrl);
			Map<String, String> found = new LinkedHashMap<>();
			for (JsonElement e : JsonParser.parseString(response.body()).getAsJsonArray()) {
				JsonObject o = e.getAsJsonObject();
				if (o.has("id") && o.has("imageUrl") && !o.get("imageUrl").isJsonNull()) {
					found.put(o.get("id").getAsString(), base.resolve(o.get("imageUrl").getAsString()).toString());
				}
			}
			pictures = found;
			NiceMerl.LOGGER.info("Found {} recipe pictures", found.size());
		} catch (Exception e) {
			NiceMerl.LOGGER.warn("Could not load the recipe pictures, keeping the previous ones: {}", e.toString());
		}
	}

	/** The recipe answer for the asked item, or null when the server has no crafting recipe for it. */
	static Component recipe(MinecraftServer server, String asked, String user) {
		Set<String> words = new HashSet<>(SearchIndex.tokenize(asked));
		words.removeAll(GENERIC);
		if (words.isEmpty()) return null;
		List<Entry> exact = new ArrayList<>(), family = new ArrayList<>();
		for (Entry entry : entries(server)) {
			if (entry.words().equals(words)) exact.add(entry);
			else if (entry.words().containsAll(words)) family.add(entry);
		}
		List<Entry> found = !exact.isEmpty() ? exact : family;
		if (found.isEmpty()) return null;
		MutableComponent message = Component.literal(MerlLines.pick(found.size() > 1 ? "recipe_many" : "recipe_found",
				"item", found.get(0).name(), "count", String.valueOf(found.size()), "user", user));
		for (Entry entry : found.subList(0, Math.min(SHOWN, found.size()))) message.append(describe(entry));
		if (found.size() > SHOWN) {
			MutableComponent rest = Component.empty();
			for (int i = SHOWN; i < found.size(); i++) rest.append((i > SHOWN ? "\n" : "") + found.get(i).name());
			message.append(Component.literal("\n and " + (found.size() - SHOWN) + " more").withStyle(Style.EMPTY
					.withColor(ChatFormatting.GRAY).withUnderlined(true).withHoverEvent(new HoverEvent.ShowText(rest))));
		}
		return message;
	}

	/** " ▸ Acacia Waypoint Hub: 2× Raw Gold, … (needs a crafting table) [Recipe picture]" */
	private static MutableComponent describe(Entry entry) {
		CraftingRecipe recipe = (CraftingRecipe) entry.holder().value();
		List<Ingredient> ingredients = recipe.placementInfo().ingredients();
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (Ingredient ingredient : ingredients) counts.merge(ingredientName(ingredient), 1, Integer::sum);
		StringBuilder list = new StringBuilder();
		counts.forEach((name, n) -> list.append(list.isEmpty() ? "" : ", ").append(n).append("× ").append(name));
		boolean table = recipe instanceof ShapedRecipe shaped ? shaped.getWidth() > 2 || shaped.getHeight() > 2 : ingredients.size() > 4;
		MutableComponent line = Component.literal("\n ▸ ").withStyle(ChatFormatting.DARK_GRAY)
				.append(entry.result().getHoverName().copy().withStyle(ChatFormatting.YELLOW))
				.append(Component.literal(entry.result().getCount() > 1 ? " ×" + entry.result().getCount() : "").withStyle(ChatFormatting.YELLOW))
				.append(Component.literal(": " + list + (table ? " (crafting table)" : "")).withStyle(ChatFormatting.WHITE));
		if (recipe instanceof ShapedRecipe shaped) line.withStyle(Style.EMPTY.withHoverEvent(new HoverEvent.ShowText(grid(shaped))));
		String picture = pictures.get(entry.holder().id().identifier().toString());
		if (picture != null) {
			line.append(Component.literal(" [Recipe picture]").withStyle(Style.EMPTY.withColor(ChatFormatting.AQUA)
					.withClickEvent(new ClickEvent.OpenUrl(URI.create(picture)))
					.withHoverEvent(new HoverEvent.ShowText(Component.literal("Open the recipe picture on explorerseden.eu")))));
		}
		return line;
	}

	/** The shape, row by row, for the hover text. */
	private static Component grid(ShapedRecipe shaped) {
		MutableComponent out = Component.literal("Shape:").withStyle(ChatFormatting.GRAY);
		List<java.util.Optional<Ingredient>> slots = shaped.getIngredients();
		for (int row = 0; row < shaped.getHeight(); row++) {
			StringBuilder line = new StringBuilder();
			for (int col = 0; col < shaped.getWidth(); col++) {
				java.util.Optional<Ingredient> slot = slots.get(row * shaped.getWidth() + col);
				line.append(col > 0 ? " | " : "").append(slot.map(MerlRecipes::ingredientName).orElse("—"));
			}
			out.append(Component.literal("\n" + line).withStyle(ChatFormatting.WHITE));
		}
		return out;
	}

	/** "Raw Gold", or "any Planks" for an ingredient that takes several items. */
	static String ingredientName(Ingredient ingredient) {
		List<String> names = ingredient.items().limit(16).map(h -> readable(new ItemStack(h).getHoverName())).toList();
		if (names.isEmpty()) return "?";
		if (names.size() == 1) return names.get(0);
		String[] first = names.get(0).split(" ");
		String common = first[first.length - 1];
		boolean shared = names.stream().allMatch(n -> n.endsWith(" " + common) || n.equals(common));
		return "any " + (shared ? common : names.get(0));
	}

	/** Every craftable result with its readable name, built once per recipe reload. */
	private static List<Entry> entries(MinecraftServer server) {
		RecipeManager manager = server.getRecipeManager();
		if (entriesFor == manager) return entries;
		ContextMap context = SlotDisplayContext.fromLevel(server.overworld());
		List<Entry> out = new ArrayList<>();
		for (RecipeHolder<?> holder : manager.getRecipes()) {
			if (!(holder.value() instanceof CraftingRecipe recipe) || recipe.isSpecial() || recipe.display().isEmpty()) continue;
			ItemStack result = recipe.display().get(0).result().resolveForFirstStack(context);
			if (result.isEmpty()) continue;
			String name = readable(result.getHoverName());
			out.add(new Entry(name, new HashSet<>(SearchIndex.tokenize(name)), holder, result));
		}
		entries = out;
		entriesFor = manager;
		return out;
	}

	/** "What can I enchant this with?": every enchantment that fits the held item, each a click away from Merl. */
	static Component enchantments(ServerPlayer player) {
		return enchantments(player.getMainHandItem(), player.level().registryAccess(), player.getName().getString());
	}

	static Component enchantments(ItemStack held, net.minecraft.core.RegistryAccess registries, String user) {
		if (held.isEmpty()) return Component.literal(MerlLines.pick("enchant_nothing_held", "user", user));
		List<Holder.Reference<Enchantment>> fitting = registries.lookupOrThrow(Registries.ENCHANTMENT)
				.listElements().filter(e -> e.value().isSupportedItem(held)).toList();
		if (fitting.isEmpty()) {
			return Component.literal(MerlLines.pick("enchant_none", "item", readable(held.getHoverName()), "user", user));
		}
		MutableComponent message = Component.literal(MerlLines.pick("enchant_list", "item", readable(held.getHoverName()),
				"count", String.valueOf(fitting.size()), "user", user) + " ");
		List<Holder.Reference<Enchantment>> sorted = new ArrayList<>(fitting);
		sorted.sort((a, b) -> readable(a.value().description()).compareToIgnoreCase(readable(b.value().description())));
		for (int i = 0; i < sorted.size(); i++) {
			Holder.Reference<Enchantment> e = sorted.get(i);
			String name = readable(e.value().description());
			boolean pack = !e.key().identifier().getNamespace().equals("minecraft");
			if (i > 0) message.append(Component.literal(", ").withStyle(ChatFormatting.GRAY));
			message.append(e.value().description().copy().withStyle(Style.EMPTY
					.withColor(pack ? ChatFormatting.LIGHT_PURPLE : ChatFormatting.YELLOW)
					.withClickEvent(new ClickEvent.RunCommand("/merl what does " + name + " do"))
					.withHoverEvent(new HoverEvent.ShowText(Component.literal((pack ? "From " + packName(e.key().identifier().getNamespace()) + "\n" : "")
							+ "Click to ask Merl about " + name)))));
		}
		return message;
	}

	/** A name as players see it, also for data pack items whose translation the server doesn't have. */
	static String readable(Component component) {
		String text = component.getString();
		if (component.getContents() instanceof TranslatableContents translatable && text.equals(translatable.getKey())) {
			if (translatable.getFallback() != null) return translatable.getFallback();
			String key = translatable.getKey();
			String last = key.substring(key.lastIndexOf('.') + 1).replace('_', ' ');
			return titleCase(last);
		}
		return text;
	}

	/** "nice_mob_variants" → "Nice Mob Variants". */
	static String packName(String namespace) {
		return titleCase(namespace.replace('_', ' '));
	}

	private static String titleCase(String words) {
		StringBuilder out = new StringBuilder();
		for (String w : words.split(" ")) {
			if (w.isEmpty()) continue;
			if (!out.isEmpty()) out.append(' ');
			out.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1).toLowerCase(Locale.ROOT));
		}
		return out.toString();
	}
}
