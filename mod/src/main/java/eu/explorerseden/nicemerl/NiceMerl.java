package eu.explorerseden.nicemerl;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NiceMerl implements ModInitializer {
	public static final String MOD_ID = "nicemerl";
	public static final Logger LOGGER = LoggerFactory.getLogger("NiceMerl");

	private static volatile SearchIndex index = null;
	/** The meaning-based search model, once it's loaded; null means keyword search only. */
	private static volatile SemanticModel model = null;
	private static volatile SettingLabels settingLabels = SettingLabels.EMPTY;
	private static MerlConfig config;
	private static ScheduledExecutorService scheduler;
	private static List<VanillaWiki> mediaWikis = List.of();
	/** The last good download of each Wiki.js wiki, by URL. */
	private static Map<String, List<Section>> wikiSections = Map.of();
	/** Runs live wiki lookups, so questions never wait on the network on the server thread. */
	private static final ExecutorService LOOKUPS = Executors.newFixedThreadPool(2, r -> {
		Thread t = new Thread(r, "NiceMerl Lookup");
		t.setDaemon(true);
		return t;
	});

	@Override
	public void onInitialize() {
		config = MerlConfig.load();
		MerlState.load();
		mediaWikis = config.mediaWikis().stream()
				.map(w -> new VanillaWiki(w.name, w.url, config.excerptLength))
				.toList();

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				MerlCommand.register(dispatcher));

		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
				Thread t = new Thread(r, "NiceMerl Wiki Indexer");
				t.setDaemon(true);
				return t;
			});
			scheduler.execute(() -> scanSettingLabels(server.getResourceManager()));
			if (config.semanticSearch) scheduler.execute(NiceMerl::loadModel);
			long hours = Math.max(1, Math.round(config.reindexHours));
			scheduler.scheduleWithFixedDelay(NiceMerl::reindex, 0, hours, TimeUnit.HOURS);
		});

		// Data packs may have changed: re-read their config dialogs for setting names.
		ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) -> {
			ScheduledExecutorService s = scheduler;
			if (success && s != null) {
				s.execute(() -> scanSettingLabels(server.getResourceManager()));
			}
		});

		// Statistic milestones (100,000 blocks mined, …), checked every few minutes.
		ServerTickEvents.END_SERVER_TICK.register(MerlStats::tick);
		// Sparkle trails for players Merl is guiding somewhere.
		ServerTickEvents.END_SERVER_TICK.register(MerlGuide::tick);
		// Reminders: due ones every second, missed ones when the player comes back.
		ServerTickEvents.END_SERVER_TICK.register(MerlReminders::tick);
		// Merl mannequins: right-click messages, and they never end up in boats.
		MerlMannequin.register();
		ServerTickEvents.END_SERVER_TICK.register(MerlMannequin::tick);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
				server.execute(() -> MerlReminders.deliver(handler.getPlayer(), System.currentTimeMillis())));

		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			if (scheduler != null) {
				scheduler.shutdownNow();
				scheduler = null;
			}
		});
	}

	/**
	 * Downloads every Wiki.js wiki and swaps in a fresh index. A wiki that fails keeps the pages
	 * from its last successful download, so one broken wiki doesn't empty the others.
	 */
	public static synchronized boolean reindex() {
		boolean ok = true;
		Map<String, List<Section>> fresh = new LinkedHashMap<>();
		for (MerlConfig.WikiSource wiki : config.wikiJsWikis()) {
			try {
				List<Section> sections = new WikiClient(wiki.url).fetchSections();
				if (sections.isEmpty()) {
					throw new IllegalStateException("no sections");
				}
				fresh.put(wiki.url, sections);
				LOGGER.info("Fetched {} sections from {}", sections.size(), wiki.name);
			} catch (Exception e) {
				ok = false;
				List<Section> previous = wikiSections.get(wiki.url);
				LOGGER.error("Reindex of {} failed, keeping its previous pages", wiki.name, e);
				if (previous != null) fresh.put(wiki.url, previous);
			}
		}
		List<Section> all = fresh.values().stream().flatMap(List::stream).toList();
		if (all.isEmpty()) {
			LOGGER.warn("Reindex returned no sections, keeping the previous index");
			return false;
		}
		wikiSections = fresh;
		index = new SearchIndex(all, model);
		if (config.recipeHelp) MerlRecipes.loadPictures(config.recipesUrl);
		LOGGER.info("Indexed {} sections from {} pages", all.size(), index.pageCount());
		return ok;
	}

	/** Loads (and the first time downloads) the meaning-based search model; runs before the first reindex. */
	private static void loadModel() {
		if (model != null) return;
		try {
			LOGGER.info("Loading the meaning-based search model (the first time, this downloads about 31 MB)");
			model = SemanticModel.loadOrDownload(FabricLoader.getInstance().getConfigDir().resolve(MOD_ID).resolve("model"));
			LOGGER.info("Meaning-based search is on");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (Exception e) {
			LOGGER.warn("Meaning-based search is off, searching by keywords only: {}", e.toString());
		}
	}

	private static void scanSettingLabels(net.minecraft.server.packs.resources.ResourceManager resources) {
		try {
			settingLabels = SettingLabels.scan(resources);
			LOGGER.info("Found readable names for {} data pack settings", settingLabels.size());
		} catch (Exception e) {
			LOGGER.error("Could not read setting names from data packs", e);
		}
	}

	public static SettingLabels settingLabels() {
		return settingLabels;
	}

	/** Runs a reindex on the background thread and calls back with the result. */
	public static void reindexAsync(Consumer<Boolean> callback) {
		ScheduledExecutorService s = scheduler;
		if (s == null) {
			callback.accept(false);
			return;
		}
		s.execute(() -> callback.accept(reindex()));
	}

	/** Runs the task off the server thread and hands its result to the callback (also off-thread). */
	public static <T> void lookupAsync(Supplier<T> task, Consumer<T> callback) {
		LOOKUPS.execute(() -> callback.accept(task.get()));
	}

	/** The wikis searched live (the Minecraft Wiki by default); empty when there are none. */
	public static List<VanillaWiki> mediaWikis() {
		return mediaWikis;
	}

	public static SearchIndex index() {
		return index;
	}

	public static MerlConfig config() {
		return config;
	}
}
