package eu.explorerseden.nicemerl;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * "How much should I sell diamonds for?", "what's this worth?": a price from the Nice Control Center's player
 * market (/market), from what that item sold for lately and what it's listed for now, per item and per the lot
 * size players use. The market is used through reflection (MarketApi): without it, price questions go to the wiki.
 */
public final class MerlPrices {
	private MerlPrices() {
	}

	/** MarketApi's enabled(), currency(), prices(String); null when the Nice Control Center isn't there. */
	private static volatile Method[] api;
	private static volatile boolean looked;

	private static Method[] api() {
		if (looked) return api;
		looked = true;
		if (!FabricLoader.getInstance().isModLoaded("nicecontrolcenter")) return null;
		try {
			Class<?> c = Class.forName("eu.explorerseden.nicecontrolcenter.api.MarketApi", true, MerlPrices.class.getClassLoader());
			api = new Method[] { c.getMethod("enabled"), c.getMethod("currency"), c.getMethod("prices", String.class) };
			NiceMerl.LOGGER.info("Nice Control Center market found: Merl can suggest prices");
		} catch (ReflectiveOperationException | LinkageError e) {
			// An older Nice Control Center without the market API.
		}
		return api;
	}

	static boolean available() {
		return api() != null;
	}

	/** The answer, or null when there's no market to ask (the question goes on to the wiki then). */
	@SuppressWarnings("unchecked")
	static Component answer(ServerPlayer player, String question) {
		Method[] m = api();
		if (m == null) return null;
		String user = player.getName().getString();
		try {
			if (!(Boolean) m[0].invoke(null)) return Component.literal(MerlLines.pick("price_closed", "user", user));
			Item currency = BuiltInRegistries.ITEM.getValue(Identifier.tryParse((String) m[1].invoke(null)));
			if (currency == null || currency == Items.AIR) currency = Items.DIAMOND;
			Item item = itemIn(question);
			if (item == null && !player.getMainHandItem().isEmpty()) item = player.getMainHandItem().getItem();
			if (item == null) return Component.literal(MerlLines.pick("price_what", "user", user));
			String name = new ItemStack(item).getHoverName().getString();
			String coins = plural(new ItemStack(currency).getHoverName().getString());
			if (item == currency) return Component.literal(MerlLines.pick("price_currency", "item", name, "user", user));
			Map<String, Object> p = (Map<String, Object>) m[2].invoke(null, BuiltInRegistries.ITEM.getKey(item).toString());
			int sales = ((Number) p.getOrDefault("sales", 0)).intValue();
			int listings = ((Number) p.getOrDefault("listings", 0)).intValue();
			int lot = Math.max(1, ((Number) p.getOrDefault("lot", 1)).intValue());
			if (sales == 0 && listings == 0) return Component.literal(MerlLines.pick("price_none", "item", name, "user", user));
			StringBuilder out = new StringBuilder();
			double suggest;
			if (sales > 0) {
				double median = ((Number) p.get("median")).doubleValue();
				double low = ((Number) p.get("low")).doubleValue();
				double high = ((Number) p.get("high")).doubleValue();
				out.append(name).append(" sold ").append(sales == 1 ? "once" : sales + " times").append(" lately, for about ").append(each(median, coins))
						.append(low < high ? " (" + num(low) + " to " + num(high) + ")" : "").append(". ");
				suggest = median;
				if (listings > 0) {
					double cheapest = ((Number) p.get("cheapest")).doubleValue();
					out.append("The cheapest on the market right now is ").append(each(cheapest, coins)).append(". ");
					// Undercutting the cheapest listing sells first, if that's still close to what it sells for.
					if (cheapest < median && cheapest >= median * 0.7) suggest = cheapest;
				}
			} else {
				double cheapest = ((Number) p.get("cheapest")).doubleValue();
				out.append("Nobody bought ").append(name).append(" yet, but it's listed from ").append(each(cheapest, coins)).append(". ");
				suggest = cheapest * 0.9;
			}
			out.append("I'd ask about ").append(each(suggest, coins));
			int forLot = lot > 1 ? lot : suggest < 1 ? (int) Math.ceil(1 / suggest) : 0;
			if (forLot > 1) out.append(", that's ").append(Math.max(1, Math.round(suggest * forLot))).append(" per ").append(forLot);
			out.append(" (\"For how many\" in /market's sell menu).");
			return Component.literal(out.toString());
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}

	private static String each(double v, String coins) {
		return v >= 1 ? num(v) + " " + coins + " each" : "1 " + singular(coins) + " per " + Math.max(2, Math.round(1 / v));
	}

	private static String num(double v) {
		return v >= 10 || v == Math.rint(v) ? String.format(Locale.ROOT, "%,d", Math.round(v)) : String.format(Locale.ROOT, "%.1f", v);
	}

	private static String plural(String s) {
		return s.endsWith("s") ? s : s.endsWith("y") ? s.substring(0, s.length() - 1) + "ies" : s + "s";
	}

	private static String singular(String s) {
		return s.endsWith("ies") ? s.substring(0, s.length() - 3) + "y" : s.endsWith("s") ? s.substring(0, s.length() - 1) : s;
	}

	private static final Pattern NON_WORD = Pattern.compile("[^a-z0-9 ]+");

	/** The item named in the question (longest name wins: "diamond pickaxe" over "diamond"), plurals too; null when none is. */
	static Item itemIn(String question) {
		String text = " " + NON_WORD.matcher(question.toLowerCase(Locale.ROOT)).replaceAll(" ").replaceAll("\\s+", " ") + " ";
		Item best = null;
		int bestLength = 0;
		for (Item item : BuiltInRegistries.ITEM) {
			if (item == Items.AIR) continue;
			String name = NON_WORD.matcher(new ItemStack(item).getHoverName().getString().toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
			if (name.length() <= bestLength || name.isEmpty()) continue;
			if (text.contains(" " + name + " ") || text.contains(" " + plural(name) + " ") || text.contains(" " + name + "es ")) {
				best = item;
				bestLength = name.length();
			}
		}
		return best;
	}
}
