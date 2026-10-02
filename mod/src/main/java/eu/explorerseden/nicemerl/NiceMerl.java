package eu.explorerseden.nicemerl;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NiceMerl implements ModInitializer {
	public static final String MOD_ID = "nicemerl";
	public static final Logger LOGGER = LoggerFactory.getLogger("NiceMerl");

	private static volatile SearchIndex index = null;
	private static volatile SettingLabels settingLabels = SettingLabels.EMPTY;
	private static MerlConfig config;
	private static ScheduledExecutorService scheduler;

	@Override
	public void onInitialize() {
		config = MerlConfig.load();

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				MerlCommand.register(dispatcher));

		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
				Thread t = new Thread(r, "NiceMerl Wiki Indexer");
				t.setDaemon(true);
				return t;
			});
			scheduler.execute(() -> scanSettingLabels(server.getResourceManager()));
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

		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			if (scheduler != null) {
				scheduler.shutdownNow();
				scheduler = null;
			}
		});
	}

	/** Downloads the wiki and swaps in a fresh index. Keeps the old one on failure. */
	public static boolean reindex() {
		try {
			List<Section> sections = new WikiClient(config.wikiUrl).fetchSections();
			if (sections.isEmpty()) {
				LOGGER.warn("Reindex returned no sections, keeping the previous index");
				return false;
			}
			index = new SearchIndex(sections);
			LOGGER.info("Indexed {} sections from {} pages", sections.size(), index.pageCount());
			return true;
		} catch (Exception e) {
			LOGGER.error("Reindex failed, keeping the previous index", e);
			return false;
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
	public static void reindexAsync(java.util.function.Consumer<Boolean> callback) {
		ScheduledExecutorService s = scheduler;
		if (s == null) {
			callback.accept(false);
			return;
		}
		s.execute(() -> callback.accept(reindex()));
	}

	public static SearchIndex index() {
		return index;
	}

	public static MerlConfig config() {
		return config;
	}
}
