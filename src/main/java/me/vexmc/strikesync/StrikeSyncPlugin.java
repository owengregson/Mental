package me.vexmc.strikesync;

import com.github.retrooper.packetevents.PacketEvents;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import me.vexmc.strikesync.command.StrikeSyncCommand;
import me.vexmc.strikesync.config.ConfigManager;
import me.vexmc.strikesync.core.ModuleManager;
import me.vexmc.strikesync.core.StrikeSyncService;
import me.vexmc.strikesync.module.compensation.LatencyCompensationModule;
import me.vexmc.strikesync.module.hitreg.HitRegModule;
import me.vexmc.strikesync.module.knockback.KnockbackModule;
import me.vexmc.strikesync.util.Logging;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Bootstrap. Owns nothing of substance; everything lives in
 * {@link StrikeSyncService} and {@link ModuleManager}.
 *
 * <h2>PacketEvents lifecycle</h2>
 * PacketEvents requires a three-stage lifecycle:
 * <ol>
 * <li>{@link #onLoad()}: build the API instance and call {@code load()}.
 * This is also where listeners <em>may</em> be registered, but we defer
 * registration until module enable so a module that's disabled in
 * config never installs a listener.</li>
 * <li>{@link #onEnable()}: build config + service + modules, register
 * commands, then call {@code init()} to finalise PacketEvents.</li>
 * <li>{@link #onDisable()}: tear down modules, then {@code terminate()}.</li>
 * </ol>
 *
 * <p>
 * This ordering is important: PacketEvents must be initialised
 * <em>after</em> all listeners that want to bind on enable have registered;
 * conversely, modules must shut down <em>before</em> termination so they can
 * cleanly unregister.
 */
public final class StrikeSyncPlugin extends JavaPlugin {

	private static final String COMMAND_NAME = "strikesync";

	private StrikeSyncService service;
	private ModuleManager modules;
	private ConfigManager config;
	private Logging log;

	@Override
	public void onLoad() {
		// Stand up PacketEvents as early as possible so that any code path
		// touching `PacketEvents.getAPI()` after this point is safe.
		PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
		PacketEvents.getAPI().getSettings()
				.checkForUpdates(false)
				.reEncodeByDefault(false);
		PacketEvents.getAPI().load();
	}

	@Override
	public void onEnable() {
		this.log = new Logging(getLogger());
		this.config = new ConfigManager(this);
		this.config.reload();
		this.log.setDebug(config.debug().enabled());

		this.service = new StrikeSyncService(this, config, log);
		this.modules = new ModuleManager(log);

		modules.register(new HitRegModule(service));
		modules.register(new KnockbackModule(service));
		modules.register(new LatencyCompensationModule(service));

		registerCommand();

		modules.enableAll();

		// Init PacketEvents AFTER modules have registered their listeners.
		PacketEvents.getAPI().init();

		log.info("StrikeSync enabled — modules up.");
	}

	@Override
	public void onDisable() {
		if (modules != null) {
			modules.disableAll();
		}
		// Terminate PacketEvents only after our modules have unregistered.
		try {
			if (PacketEvents.getAPI() != null) {
				PacketEvents.getAPI().terminate();
			}
		} catch (Throwable t) {
			getLogger().warning("PacketEvents termination threw: " + t.getMessage());
		}
		log = null;
		config = null;
		service = null;
		modules = null;
		getLogger().info("StrikeSync disabled.");
	}

	/**
	 * Re-read the config snapshot and bounce every module's settings.
	 *
	 * <p>
	 * Public so the {@code /ss reload} command and {@code /ss toggle}
	 * variants can call it without going through Bukkit's static singletons.
	 */
	public void reloadAll() {
		if (config == null || modules == null)
			return;
		config.reload();
		log.setDebug(config.debug().enabled());
		modules.reloadAll();
	}

	private void registerCommand() {
		PluginCommand command = getCommand(COMMAND_NAME);
		if (command == null) {
			log.warn("Could not find '" + COMMAND_NAME + "' command in plugin.yml — commands disabled.");
			return;
		}
		StrikeSyncCommand handler = new StrikeSyncCommand(service);
		command.setExecutor(handler);
		command.setTabCompleter(handler);
	}
}
