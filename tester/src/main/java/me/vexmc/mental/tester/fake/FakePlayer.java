package me.vexmc.mental.tester.fake;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import me.vexmc.mental.platform.Scheduling;
import me.vexmc.mental.platform.TaskHandle;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jpenilla.reflectionremapper.ReflectionRemapper;

/**
 * A synthetic player built straight onto the server internals — ported from
 * BukkitOldCombatMechanics' battle-tested FakePlayer. Two bootstrap branches
 * share one class:
 *
 * <ul>
 *   <li><b>Modern (1.17+):</b> NMS lives in Mojang packages
 *       ({@code net.minecraft.server.level.ServerPlayer}); reflection names
 *       route through reflection-remapper — identity on Mojang-mapped runtimes
 *       (1.20.5+), mapped via the Paper jar's reobf data on 1.17–1.20.4.</li>
 *   <li><b>Legacy (pre-1.17):</b> NMS lives in the versioned package
 *       {@code net.minecraft.server.v1_16_R3.*} and the spigot names ARE the
 *       runtime names — the remapper is {@link ReflectionRemapper#noop()} and
 *       every class/field/method is resolved by its versioned name straight
 *       off the shape pack ({@code docs/superpowers/research/2026-07-02-legacy-
 *       fakeplayer-nms-shapes.md}). Selected by
 *       {@link #detectLegacyPackage()}: a versioned CraftBukkit package whose
 *       matching versioned NMS {@code MinecraftServer} class actually loads
 *       (which excludes 1.17–1.20.4, versioned CB but Mojang-package NMS).</li>
 * </ul>
 *
 * <p>Per-instance NMS lookups are fine here: fake players are created a
 * handful of times per suite, not on any hot path.</p>
 */
public final class FakePlayer {

    /** {@code org.bukkit.craftbukkit.v1_16_R3.CraftServer} → {@code v1_16_R3}. */
    private static final Pattern CRAFTBUKKIT_VERSION =
            Pattern.compile("org\\.bukkit\\.craftbukkit\\.(v\\d+_\\d+_R\\d+)\\.");

    private final JavaPlugin plugin;
    private final Scheduling scheduling;
    private final ReflectionRemapper remapper;
    /** The versioned NMS package on a pre-1.17 server, else {@code null}. */
    private final String legacyPackage;
    private final boolean legacy;
    private final UUID uuid = UUID.randomUUID();
    private final String name = uuid.toString().substring(0, 16);

    private Object serverPlayer;
    private Object connection;
    private Object gameListener;
    private Player bukkitPlayer;
    private boolean placedViaPlayerList;
    private TaskHandle tickTask;
    private volatile Runnable preTick;

    public FakePlayer(@NotNull JavaPlugin plugin, @NotNull Scheduling scheduling) {
        this.plugin = plugin;
        this.scheduling = scheduling;
        this.legacyPackage = detectLegacyPackage();
        this.legacy = legacyPackage != null;
        // Pre-1.17 spigot names are the runtime names; skip the (doomed, noisy)
        // reobf-mapping parse entirely. Modern keeps its remapper resolution.
        this.remapper = legacy ? ReflectionRemapper.noop() : createRemapper(plugin);
        if (legacy) {
            plugin.getLogger().info("[fake] legacy NMS bootstrap on " + legacyPackage);
        }
    }

    public @NotNull UUID uuid() {
        return uuid;
    }

    public @NotNull Player player() {
        Player current = Bukkit.getPlayer(uuid);
        if (current != null) {
            return current;
        }
        if (bukkitPlayer == null) {
            throw new IllegalStateException("Fake player " + name + " is not spawned");
        }
        return bukkitPlayer;
    }

    public void attack(@NotNull Entity target) {
        // On legacy, Bukkit LivingEntity#attack routes through
        // CraftLivingEntity.attack → EntityLiving.attackEntity (the GENERIC hurt
        // path), NOT EntityHuman.attack — so a fake PLAYER attacker never runs
        // the player melee (base knockback + the inline send-then-restore that
        // fires the PlayerVelocityEvent the delivery desk awaits), and the hit
        // silently no-ops. Call the NMS player melee EntityHuman.attack(Entity)
        // directly on every legacy revision (the shape pack's documented path).
        // The modern path keeps the Bukkit call — 1.17+ routes it to the player
        // attack, and the modern full suites prove it.
        if (legacy) {
            attackLegacyNms(target);
            return;
        }
        player().attack(target);
    }

    /**
     * Runs {@code hook} on the owning thread immediately before each physics
     * tick — the slot where a real client applies its movement INPUT (the
     * acceleration happens before the move in every era's integration). The
     * era suite drives walking/sprinting victims through this.
     */
    public void preTick(@Nullable Runnable hook) {
        this.preTick = hook;
    }

    /**
     * Sets the server-side motion directly — no Bukkit event, no
     * {@code hurtMarked}. This is how the era-parity suite plays the role of
     * the client: a real victim's trajectory comes from the client applying
     * velocity packets, which a synthetic player has no client to do.
     * Owning thread only.
     */
    public void setMotion(double x, double y, double z) {
        if (legacy) {
            setMotionLegacy(x, y, z);
            return;
        }
        try {
            Class<?> entityClass = nmsClass("net.minecraft.world.entity.Entity");
            Method setter = Reflect.methodAssignable(entityClass,
                    remapMethod(entityClass, "setDeltaMovement",
                            double.class, double.class, double.class),
                    double.class, double.class, double.class);
            if (setter == null) {
                setter = Reflect.methodAssignable(entityClass, "setDeltaMovement",
                        double.class, double.class, double.class);
            }
            if (setter == null) {
                throw new NoSuchMethodException("setDeltaMovement(double,double,double)");
            }
            setter.invoke(serverPlayer, x, y, z);
        } catch (ReflectiveOperationException failure) {
            plugin.getLogger().warning("[fake] setMotion failed: " + failure);
        }
    }

    /** Must run on the global/main thread. */
    public void spawn(@NotNull Location location) throws ReflectiveOperationException {
        if (legacy) {
            spawnLegacy(location);
            return;
        }
        Object worldServer = handleOf(location.getWorld());
        Object minecraftServer = minecraftServer();
        Object gameProfile = createGameProfile();

        this.serverPlayer = createServerPlayer(minecraftServer, worldServer, gameProfile);
        setupConnection(minecraftServer);
        setGameModeSurvival();
        setPosition(location);

        fireAsyncPreLogin();
        this.placedViaPlayerList = addToPlayerList(minecraftServer);

        if (Bukkit.getPlayer(uuid) == null) {
            plugin.getLogger().info("[fake] placeNewPlayer did not register the player (online="
                    + Bukkit.getOnlinePlayers().size() + ") — registering directly");
            registerInPlayerList(minecraftServer);
            this.placedViaPlayerList = false;
        }

        this.bukkitPlayer = Bukkit.getPlayer(uuid);
        if (bukkitPlayer == null) {
            throw new IllegalStateException("Bukkit player " + uuid + " not found after placement");
        }
        if (!placedViaPlayerList) {
            addToWorld(worldServer);
        }
        // Fresh players carry 60 ticks of spawn invulnerability — three
        // seconds a suite would otherwise have to sleep away per scenario.
        clearSpawnInvulnerability();

        // The login pipeline relocates new players to the world spawn; the
        // Bukkit teleport afterwards is the authoritative way to take them
        // to the requested location on every version. Folia bans synchronous
        // Entity#teleport (it throws "Must use teleportAsync while in region
        // threading") — use the region-safe async form there; Paper keeps the
        // byte-identical sync teleport the suites' timing depends on.
        if ("folia".equals(scheduling.describe())) {
            bukkitPlayer.teleportAsync(location);
        } else {
            bukkitPlayer.teleport(location);
        }

        // placeNewPlayer fires PlayerJoinEvent itself; the direct-registration
        // fallback (older servers where placeNewPlayer misses — 1.17.x) does not.
        // Join-driven plugins set up per-player state on that event — Mental v5
        // creates the player's combat session there — so announce the join on the
        // fallback path, mirroring remove()'s explicit PlayerQuitEvent. Guarded
        // like remove(): a version needing a Component message must not abort spawn.
        if (!placedViaPlayerList) {
            try {
                Bukkit.getPluginManager().callEvent(
                        new PlayerJoinEvent(bukkitPlayer, name + " joined the game"));
            } catch (Throwable ignored) {
                // Direct join listeners still ran on the versions that accept this ctor.
            }
        }

        this.tickTask = scheduling.repeatOn(bukkitPlayer, 1L, 1L, this::tickServerPlayer, () -> {});
    }

    /**
     * Removes the fake player. On Paper: main thread. On Folia: the player's
     * OWNING REGION thread (an off-region entity removal trips the tick-thread
     * check).
     */
    public void remove() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        if (bukkitPlayer == null) {
            return;
        }
        try {
            Bukkit.getPluginManager().callEvent(new PlayerQuitEvent(bukkitPlayer, name + " left the game"));
        } catch (Throwable ignored) {
            // Some versions require a quit reason; the kick below still cleans up.
        }
        try {
            bukkitPlayer.kickPlayer(name + " left the game");
        } catch (Throwable ignored) {
            // Stubbed connection — fall through to direct list removal.
        }
        // The direct reflective PlayerList.remove is the fallback for Paper, where
        // kicking a stubbed connection may not fully deregister the player. On
        // FOLIA it is HARMFUL: kickPlayer queues a disconnect that Folia processes
        // in RegionizedWorldData.tickConnections, which removes the player and
        // retires its EntityScheduler exactly once. A second direct remove here
        // retires the scheduler again -> IllegalStateException("Already retired"),
        // which is an uncaught region-tick failure that HARD-CRASHES the region
        // (not catchable here — it throws on a later tick). So on Folia we let the
        // kick's disconnect be the single removal path.
        if (legacy) {
            removeLegacyDirect();
        } else if (!"folia".equals(scheduling.describe())) {
            try {
                Object playerList = playerList(minecraftServer());
                Method remove = Reflect.methodAssignable(playerList.getClass(),
                        remapMethod(playerList.getClass(), "remove", serverPlayer.getClass()),
                        serverPlayer.getClass());
                if (remove == null) {
                    remove = Reflect.methodAssignable(playerList.getClass(), "remove", serverPlayer.getClass());
                }
                if (remove != null) {
                    remove.invoke(playerList, serverPlayer);
                }
            } catch (Throwable ignored) {
                // Already removed by the kick path.
            }
        }
        bukkitPlayer = null;
    }

    /* ------------------------------------------------------------------ */
    /*  NMS bootstrap                                                      */
    /* ------------------------------------------------------------------ */

    private static volatile ReflectionRemapper sharedRemapper;

    /**
     * Parsing the reobf mappings out of the Paper jar costs real time on
     * spigot-mapped runtimes; one parse serves every fake player in the JVM.
     */
    private static ReflectionRemapper createRemapper(JavaPlugin plugin) {
        ReflectionRemapper cached = sharedRemapper;
        if (cached != null) {
            return cached;
        }
        synchronized (FakePlayer.class) {
            if (sharedRemapper == null) {
                try {
                    sharedRemapper = ReflectionRemapper.forReobfMappingsInPaperJar();
                } catch (Throwable mojangMappedRuntime) {
                    plugin.getLogger().info(
                            "No reobf mappings present — Mojang-mapped runtime, using identity remapper.");
                    sharedRemapper = ReflectionRemapper.noop();
                }
            }
            return sharedRemapper;
        }
    }

    private Class<?> nmsClass(String mojangName) throws ClassNotFoundException {
        String remapped = remapper.remapClassName(mojangName);
        try {
            return Class.forName(remapped, true, minecraftServerUnchecked().getClass().getClassLoader());
        } catch (ReflectiveOperationException failure) {
            throw new ClassNotFoundException(mojangName, failure);
        }
    }

    private Object minecraftServerUnchecked() throws ReflectiveOperationException {
        return minecraftServer();
    }

    private Object minecraftServer() throws ReflectiveOperationException {
        Object server = Bukkit.getServer();
        return invoke(method(server.getClass(), "getServer"), server);
    }

    private Object handleOf(Object craftObject) throws ReflectiveOperationException {
        return invoke(method(craftObject.getClass(), "getHandle"), craftObject);
    }

    private Object playerList(Object minecraftServer) throws ReflectiveOperationException {
        Class<?> serverClass = nmsClass("net.minecraft.server.MinecraftServer");
        Method getter = Reflect.method(serverClass, remapMethod(serverClass, "getPlayerList"));
        if (getter == null) {
            getter = Reflect.method(serverClass, "getPlayerList");
        }
        if (getter == null) {
            throw new NoSuchMethodException("getPlayerList not resolvable on " + serverClass);
        }
        return getter.invoke(minecraftServer);
    }

    private Object createGameProfile() throws ReflectiveOperationException {
        Class<?> profileClass = Class.forName("com.mojang.authlib.GameProfile");
        return profileClass.getConstructor(UUID.class, String.class).newInstance(uuid, name);
    }

    private Object createServerPlayer(Object minecraftServer, Object worldServer, Object gameProfile)
            throws ReflectiveOperationException {
        Class<?> serverPlayerClass = nmsClass("net.minecraft.server.level.ServerPlayer");
        Class<?> minecraftServerClass = nmsClass("net.minecraft.server.MinecraftServer");
        Class<?> profileClass = gameProfile.getClass();

        List<Constructor<?>> constructors = new ArrayList<>(Arrays.asList(serverPlayerClass.getConstructors()));
        constructors.sort(Comparator.comparingInt(Constructor::getParameterCount));

        for (Constructor<?> constructor : constructors) {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length < 3
                    || !parameters[0].isAssignableFrom(minecraftServerClass)
                    || !parameters[1].isAssignableFrom(worldServer.getClass())
                    || !parameters[2].isAssignableFrom(profileClass)) {
                continue;
            }
            List<Object> arguments = new ArrayList<>(List.of(minecraftServer, worldServer, gameProfile));
            boolean supported = true;
            for (int i = 3; i < parameters.length; i++) {
                switch (parameters[i].getSimpleName()) {
                    case "ProfilePublicKey" -> arguments.add(null);
                    case "ClientInformation" -> arguments.add(createDefaultClientInformation(parameters[i]));
                    default -> supported = false;
                }
                if (!supported) {
                    break;
                }
            }
            if (supported) {
                return constructor.newInstance(arguments.toArray());
            }
        }
        throw new NoSuchMethodException("No compatible ServerPlayer constructor on " + serverPlayerClass);
    }

    private Object createDefaultClientInformation(Class<?> clientInfoClass) throws ReflectiveOperationException {
        Method createDefault = Reflect.method(clientInfoClass,
                remapper.remapMethodName(clientInfoClass, "createDefault"));
        if (createDefault == null) {
            createDefault = Reflect.method(clientInfoClass, "createDefault");
        }
        if (createDefault == null) {
            throw new NoSuchMethodException("createDefault not found on " + clientInfoClass);
        }
        return createDefault.invoke(null);
    }

    private void setupConnection(Object minecraftServer) throws ReflectiveOperationException {
        Class<?> connectionClass = nmsClass("net.minecraft.network.Connection");
        Class<?> packetFlowClass = nmsClass("net.minecraft.network.protocol.PacketFlow");

        Object packetFlow = Reflect.enumConstant(packetFlowClass,
                remapper.remapFieldName(packetFlowClass, "SERVERBOUND"), "SERVERBOUND");
        this.connection = connectionClass.getConstructor(packetFlowClass).newInstance(packetFlow);

        EmbeddedChannel channel = newVoidOutboundChannel();

        setField(connectionClass, connection, "channel", channel);
        setField(connectionClass, connection, "address", new InetSocketAddress("127.0.0.1", 9999));

        Object listener = createGamePacketListener(minecraftServer, connectionClass);
        this.gameListener = listener;
        Field connectionField = Reflect.field(serverPlayer.getClass(),
                remapper.remapFieldName(serverPlayer.getClass(), "connection"));
        if (connectionField == null) {
            connectionField = Reflect.field(serverPlayer.getClass(), "connection");
        }
        if (connectionField != null) {
            connectionField.set(serverPlayer, listener);
        }

        Method setListener = Reflect.methodAssignable(connectionClass,
                remapMethod(connectionClass, "setListener", listener.getClass()), listener.getClass());
        if (setListener == null) {
            setListener = Reflect.methodAssignable(connectionClass, "setListener", listener.getClass());
        }
        if (setListener != null) {
            setListener.invoke(connection, listener);
        }
    }

    private Object createGamePacketListener(Object minecraftServer, Class<?> connectionClass)
            throws ReflectiveOperationException {
        Class<?> listenerClass = nmsClass("net.minecraft.server.network.ServerGamePacketListenerImpl");
        Class<?> minecraftServerClass = nmsClass("net.minecraft.server.MinecraftServer");

        List<Constructor<?>> constructors = new ArrayList<>(Arrays.asList(listenerClass.getConstructors()));
        constructors.sort(Comparator.comparingInt(Constructor::getParameterCount));

        for (Constructor<?> constructor : constructors) {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length < 3
                    || !parameters[0].isAssignableFrom(minecraftServerClass)
                    || !parameters[1].isAssignableFrom(connectionClass)
                    || !parameters[2].isAssignableFrom(serverPlayer.getClass())) {
                continue;
            }
            List<Object> arguments = new ArrayList<>(List.of(minecraftServer, connection, serverPlayer));
            boolean supported = true;
            for (int i = 3; i < parameters.length; i++) {
                if (parameters[i].getSimpleName().equals("CommonListenerCookie")) {
                    arguments.add(createListenerCookie(parameters[i]));
                } else {
                    supported = false;
                    break;
                }
            }
            if (supported) {
                return constructor.newInstance(arguments.toArray());
            }
        }
        throw new NoSuchMethodException("No compatible ServerGamePacketListenerImpl constructor on " + listenerClass);
    }

    private Object createListenerCookie(Class<?> cookieClass) throws ReflectiveOperationException {
        Object gameProfile = invoke(
                methodWithFallback(serverPlayer.getClass(), "getGameProfile"), serverPlayer);
        Method createInitial = Reflect.methodAssignable(cookieClass,
                remapMethod(cookieClass, "createInitial", gameProfile.getClass(), boolean.class),
                gameProfile.getClass(), boolean.class);
        if (createInitial == null) {
            createInitial = Reflect.methodAssignable(cookieClass, "createInitial",
                    gameProfile.getClass(), boolean.class);
        }
        if (createInitial == null) {
            throw new NoSuchMethodException("createInitial not found on " + cookieClass);
        }
        return createInitial.invoke(null, gameProfile, false);
    }

    private void setGameModeSurvival() throws ReflectiveOperationException {
        Class<?> gameTypeClass = nmsClass("net.minecraft.world.level.GameType");
        Object survival = Reflect.enumConstant(gameTypeClass,
                remapper.remapFieldName(gameTypeClass, "SURVIVAL"), "SURVIVAL");
        Method setGameMode = Reflect.methodAssignable(serverPlayer.getClass(),
                remapMethod(serverPlayer.getClass(), "setGameMode", gameTypeClass), gameTypeClass);
        if (setGameMode == null) {
            setGameMode = Reflect.methodAssignable(serverPlayer.getClass(), "setGameMode", gameTypeClass);
        }
        if (setGameMode != null) {
            setGameMode.invoke(serverPlayer, survival);
        }
    }

    private void setPosition(Location location) throws ReflectiveOperationException {
        Class<?> entityClass = nmsClass("net.minecraft.world.entity.Entity");
        Method setPos = Reflect.methodAssignable(entityClass,
                remapMethod(entityClass, "setPos", double.class, double.class, double.class),
                double.class, double.class, double.class);
        if (setPos == null) {
            setPos = Reflect.methodAssignable(entityClass, "setPos", double.class, double.class, double.class);
        }
        if (setPos != null) {
            setPos.invoke(serverPlayer, location.getX(), location.getY(), location.getZ());
        }
    }

    private void fireAsyncPreLogin() {
        try {
            InetAddress address = InetAddress.getByName("127.0.0.1");
            @SuppressWarnings("deprecation") // legacy ctor works across the whole supported range
            AsyncPlayerPreLoginEvent event = new AsyncPlayerPreLoginEvent(name, address, uuid);
            Thread async = new Thread(() -> Bukkit.getPluginManager().callEvent(event),
                    "mental-test-prelogin");
            async.start();
            async.join(5000L);
        } catch (Exception failure) {
            plugin.getLogger().warning("Pre-login event for fake player failed: " + failure);
        }
    }

    private boolean addToPlayerList(Object minecraftServer) throws ReflectiveOperationException {
        Object playerList = playerList(minecraftServer);
        Class<?> playerListClass = nmsClass("net.minecraft.server.players.PlayerList");
        String placeName = remapMethod(playerListClass, "placeNewPlayer",
                connection.getClass(), serverPlayer.getClass());
        plugin.getLogger().info("[fake] placeNewPlayer resolves to '" + placeName
                + "' on " + playerListClass.getName());

        for (Method method : playerListClass.getMethods()) {
            if (!method.getName().equals(placeName) && !method.getName().equals("placeNewPlayer")) {
                continue;
            }
            Class<?>[] parameters = method.getParameterTypes();
            if (parameters.length == 3
                    && parameters[0].isAssignableFrom(connection.getClass())
                    && parameters[1].isAssignableFrom(serverPlayer.getClass())
                    && parameters[2].getSimpleName().equals("CommonListenerCookie")) {
                plugin.getLogger().info("[fake] placing via cookie overload " + method);
                method.invoke(playerList, connection, serverPlayer, createListenerCookie(parameters[2]));
                return true;
            }
            if (parameters.length == 2
                    && parameters[0].isAssignableFrom(connection.getClass())
                    && parameters[1].isAssignableFrom(serverPlayer.getClass())) {
                plugin.getLogger().info("[fake] placing via classic overload " + method);
                method.invoke(playerList, connection, serverPlayer);
                return true;
            }
        }
        plugin.getLogger().info("[fake] no placeNewPlayer overload matched — registering directly");
        return false;
    }

    /** Registers straight into the PlayerList structures (no login pipeline). */
    private void registerInPlayerList(Object minecraftServer) throws ReflectiveOperationException {
        Object playerList = playerList(minecraftServer);
        Class<?> playerListClass = nmsClass("net.minecraft.server.players.PlayerList");

        Method load = Reflect.methodAssignable(playerListClass,
                remapMethod(playerListClass, "load", serverPlayer.getClass()), serverPlayer.getClass());
        if (load == null) {
            load = Reflect.methodAssignable(playerListClass, "load", serverPlayer.getClass());
        }
        if (load != null) {
            try {
                load.invoke(playerList, serverPlayer);
            } catch (Throwable failure) {
                plugin.getLogger().info("[fake] PlayerList.load failed (" + failure.getCause()
                        + ") — continuing with raw registration");
            }
        }

        Field playersField = Reflect.field(playerListClass,
                remapper.remapFieldName(playerListClass, "players"));
        if (playersField != null) {
            @SuppressWarnings("unchecked")
            List<Object> players = (List<Object>) playersField.get(playerList);
            if (!players.contains(serverPlayer)) {
                players.add(serverPlayer);
            }
        }
        Field byUuid = mapField(playerListClass, serverPlayer.getClass());
        if (byUuid != null) {
            @SuppressWarnings("unchecked")
            Map<UUID, Object> map = (Map<UUID, Object>) byUuid.get(playerList);
            map.put(uuid, serverPlayer);
        }
        // The by-name map drives Bukkit.getPlayerExact (vanilla keys it
        // lowercased); without it, command paths cannot target this player.
        Field byName = playerMapField(playerListClass, String.class, serverPlayer.getClass());
        if (byName != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) byName.get(playerList);
            map.put(name.toLowerCase(Locale.ROOT), serverPlayer);
        }
    }

    private void addToWorld(Object worldServer) {
        for (String candidate : new String[] {"addNewPlayer", "addPlayer", "addFreshEntity", "addEntity"}) {
            try {
                String remapped = remapMethod(worldServer.getClass(), candidate, serverPlayer.getClass());
                Method method = Reflect.methodAssignable(worldServer.getClass(), remapped, serverPlayer.getClass());
                if (method == null) {
                    method = Reflect.methodAssignable(worldServer.getClass(), candidate, serverPlayer.getClass());
                }
                if (method != null) {
                    method.invoke(worldServer, serverPlayer);
                    return;
                }
            } catch (Throwable next) {
                // try the next candidate
            }
        }
        plugin.getLogger().warning("Could not add fake player to the world directly");
    }

    /**
     * Join protection moved across the version range: through 1.21.1 it is
     * {@code ServerPlayer.spawnInvulnerableTime} (60 ticks); from 1.21.2 the
     * player is invulnerable until their client confirms loading —
     * {@code hasClientLoaded()} on the game packet listener, backed by
     * {@code clientLoadedTimeoutTimer} (and {@code waitingForRespawn} on the
     * newest versions), which a clientless player can only time out. Both
     * mechanisms are cleared; whichever exists on this version takes effect.
     *
     * <p>The listener must be read back off the player: modern
     * {@code placeNewPlayer} builds its <em>own</em> listener, replacing the
     * one this bootstrap created.</p>
     */
    private void clearSpawnInvulnerability() {
        if (legacy) {
            // Pre-1.17 spawn protection is EntityPlayer.invulnerableTicks (60
            // ticks), consulted in damageEntity — a fake victim would be
            // damage-immune for three seconds otherwise, and no staged hit
            // would land. (noDamageTicks is cleared per-hit by the suites.)
            setField(serverPlayer, "invulnerableTicks", 0);
            return;
        }
        // Through 1.21.1: a plain timer on ServerPlayer.
        setField(serverPlayer, "spawnInvulnerableTime", 0);
        // 1.21.2 through 1.21.x: client-loaded state on the Player entity.
        setField(serverPlayer, "clientLoaded", true);
        setField(serverPlayer, "clientLoadedTimeoutTimer", 0);
        try {
            Field connectionField = Reflect.field(serverPlayer.getClass(),
                    remapper.remapFieldName(serverPlayer.getClass(), "connection"));
            if (connectionField == null) {
                connectionField = Reflect.field(serverPlayer.getClass(), "connection");
            }
            Object liveListener = connectionField == null ? null : connectionField.get(serverPlayer);
            if (liveListener != null) {
                setField(liveListener, "clientLoadedTimeoutTimer", 0);
                setField(liveListener, "waitingForRespawn", false);
            }
        } catch (Throwable ignored) {
            // Best effort — pre-1.21.2 versions have neither field.
        }
    }

    private void setField(Object target, String mojangName, Object value) {
        try {
            Field field = Reflect.field(target.getClass(),
                    remapper.remapFieldName(target.getClass(), mojangName));
            if (field == null) {
                field = Reflect.field(target.getClass(), mojangName);
            }
            if (field != null) {
                field.set(target, value);
            }
        } catch (Throwable ignored) {
            // Best effort — the field may not exist on this version.
        }
    }

    private void tickServerPlayer() {
        Runnable hook = preTick;
        if (hook != null) {
            try {
                hook.run();
            } catch (Throwable ignored) {
                // Input emulation is best-effort, like the tick itself.
            }
        }
        try {
            Method tick = legacy
                    ? resolveTickMethodLegacy(serverPlayer.getClass())
                    : resolveTickMethod(serverPlayer.getClass());
            if (tick != null) {
                tick.invoke(serverPlayer);
            }
        } catch (Throwable ignored) {
            // Ticking is best-effort; physics-critical tests sync state explicitly.
        }
    }

    private Method cachedTick;

    /**
     * The per-tick player update whose physics the era suites depend on. The
     * modern path calls {@code doTick} (the player tick, ex-{@code playerTick}).
     * Pre-1.17 the same method is spigot {@code playerTick()} from 1.11.2 up;
     * on 1.9.4/1.10.2 there is no separate {@code playerTick}, and the Entity
     * per-tick override {@code m()} on EntityPlayer IS the player tick (its
     * prologue — invulnerableTicks/noDamageTicks decrement, interact-manager
     * tick — is byte-for-byte 1.11.2's {@code playerTick}, javap-verified).
     */
    private Method resolveTickMethodLegacy(Class<?> serverPlayerClass) {
        if (cachedTick != null) {
            return cachedTick;
        }
        for (String candidate : new String[] {"playerTick", "m", "A_", "B_", "tick"}) {
            Method method = Reflect.method(serverPlayerClass, candidate);
            if (method != null && method.getParameterCount() == 0) {
                cachedTick = method;
                plugin.getLogger().info("[fake] legacy tick via " + candidate + "()");
                return method;
            }
        }
        return null;
    }

    private Method resolveTickMethod(Class<?> serverPlayerClass) {
        if (cachedTick != null) {
            return cachedTick;
        }
        for (String candidate : new String[] {"doTick", "tick", "baseTick"}) {
            String remapped = remapMethod(serverPlayerClass, candidate);
            Method method = Reflect.method(serverPlayerClass, remapped);
            if (method == null) {
                method = Reflect.method(serverPlayerClass, candidate);
            }
            if (method != null && method.getParameterCount() == 0) {
                cachedTick = method;
                return method;
            }
        }
        return null;
    }

    /**
     * The single-threaded {@link EmbeddedChannel} that voids all outbound
     * traffic (release + complete promise). Shared by both bootstrap branches:
     * a clientless player's packets go nowhere by definition, and the live
     * server writes to connections from several threads (1.19/1.20's
     * PlayerChunkLoader streams chunks mid-tick), which would corrupt the
     * embedded buffer (null-promise NPEs) and wedge the main thread inside the
     * send loop without this.
     */
    private EmbeddedChannel newVoidOutboundChannel() {
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
        channel.pipeline().addFirst("mental-void-outbound", new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
                ReferenceCountUtil.release(message);
                if (promise != null && !promise.isVoid()) {
                    promise.trySuccess();
                }
            }

            @Override
            public void flush(ChannelHandlerContext context) {}
        });
        if (channel.pipeline().get("decoder") == null) {
            channel.pipeline().addLast("decoder", new ChannelInboundHandlerAdapter());
        }
        if (channel.pipeline().get("encoder") == null) {
            channel.pipeline().addLast("encoder", new ChannelOutboundHandlerAdapter());
        }
        return channel;
    }

    /* ------------------------------------------------------------------ */
    /*  Legacy (pre-1.17) NMS bootstrap — versioned packages, noop remapper */
    /* ------------------------------------------------------------------ */

    /**
     * Resolves the versioned NMS package on a pre-1.17 server, or {@code null}
     * on a modern one. A versioned CraftBukkit package ({@code v1_16_R3}) is
     * necessary but not sufficient — 1.17–1.20.4 keep it while NMS moved to
     * Mojang packages — so the matching versioned NMS {@code MinecraftServer}
     * must actually load. Pre-1.17: yes (versioned NMS). 1.17–1.20.4: no
     * (Mojang-package NMS) ⇒ modern path. 1.20.5+: no version token at all.
     */
    private static String detectLegacyPackage() {
        Matcher matcher = CRAFTBUKKIT_VERSION.matcher(Bukkit.getServer().getClass().getName());
        if (!matcher.find()) {
            return null;
        }
        String token = matcher.group(1);
        String versioned = "net.minecraft.server." + token;
        try {
            Class.forName(versioned + ".MinecraftServer", false,
                    Bukkit.getServer().getClass().getClassLoader());
            return versioned;
        } catch (ClassNotFoundException notLegacy) {
            return null;
        }
    }

    private Class<?> legacyClass(@NotNull String simpleName) throws ClassNotFoundException {
        return Class.forName(legacyPackage + "." + simpleName, false,
                Bukkit.getServer().getClass().getClassLoader());
    }

    private Class<?> legacyClassOrNull(@NotNull String simpleName) {
        try {
            return legacyClass(simpleName);
        } catch (ClassNotFoundException absent) {
            return null;
        }
    }

    /** Must run on the global/main thread. */
    private void spawnLegacy(@NotNull Location location) throws ReflectiveOperationException {
        Object worldServer = handleOf(location.getWorld());
        Object minecraftServer = minecraftServer();
        Object gameProfile = createGameProfile();

        this.serverPlayer = createServerPlayerLegacy(minecraftServer, worldServer, gameProfile);
        setupConnectionLegacy(minecraftServer);
        setGameModeSurvivalLegacy();
        setPositionLegacy(location);
        fireAsyncPreLogin();

        Object playerList = playerListLegacy(minecraftServer);
        if (legacyAsyncJoin()) {
            // Paper 1.14+ (here 1.15.2/1.16.5) split player login across an async
            // chunk load: PlayerList.a chunk-gates the join on
            // getChunkAtAsynchronously(...).thenAccept(postChunkLoadJoin), which
            // NEVER completes for a ticketless, clientless fake player (the join
            // chunk has no loader), so a() would hang the player in limbo forever
            // (javap-verified on v1_15_R1/v1_16_R3.PlayerList). Join SYNCHRONOUSLY
            // instead — the player is already positioned in the loaded arena
            // chunk: add it to the world as a live entity (WorldServer.addPlayerJoin,
            // the player-specific add — the generic addEntity refuses players) and
            // register it directly into the Bukkit player list.
            plugin.getLogger().info("[fake] legacy async-login revision — joining synchronously");
            addPlayerToWorldLegacy(worldServer);
            registerChunkLoaderLegacy(worldServer);
            registerInPlayerListLegacy(minecraftServer);
            this.placedViaPlayerList = false;
        } else {
            // 1.9.4–1.13.2: PlayerList.a(NetworkManager, EntityPlayer) is a
            // synchronous login — it fires PlayerJoinEvent (where Mental creates
            // the combat session) and adds the player to the world.
            Class<?> networkManagerClass = legacyClass("NetworkManager");
            Class<?> entityPlayerClass = legacyClass("EntityPlayer");
            Method register = playerList.getClass().getMethod("a", networkManagerClass, entityPlayerClass);
            plugin.getLogger().info("[fake] legacy sync join via PlayerList.a(NetworkManager, EntityPlayer)");
            register.invoke(playerList, connection, serverPlayer);
            this.placedViaPlayerList = Bukkit.getPlayer(uuid) != null;
            if (!placedViaPlayerList) {
                plugin.getLogger().info("[fake] PlayerList.a did not register — registering directly");
                addPlayerToWorldLegacy(worldServer);
                registerInPlayerListLegacy(minecraftServer);
            }
        }

        this.bukkitPlayer = Bukkit.getPlayer(uuid);
        if (bukkitPlayer == null) {
            throw new IllegalStateException("Bukkit player " + uuid + " not found after legacy placement");
        }
        // Relocate to the arena, then clear the 60-tick invulnerableTicks spawn
        // shield LAST so no join step leaves it re-armed (it gates damageEntity —
        // a shielded victim takes no staged hit).
        if ("folia".equals(scheduling.describe())) {
            bukkitPlayer.teleportAsync(location);
        } else {
            bukkitPlayer.teleport(location);
        }
        clearSpawnInvulnerability();

        // The synchronous PlayerList.a fires PlayerJoinEvent itself; the manual
        // (async-revision + fallback) path does not — announce it there, mirroring
        // the modern direct-registration fallback.
        if (!placedViaPlayerList) {
            try {
                Bukkit.getPluginManager().callEvent(
                        new PlayerJoinEvent(bukkitPlayer, name + " joined the game"));
            } catch (Throwable ignored) {
                // Direct join listeners still ran on the versions that accept this ctor.
            }
        }

        this.tickTask = scheduling.repeatOn(bukkitPlayer, 1L, 1L, this::tickServerPlayer, () -> {});
    }

    /**
     * Paper split login across an async chunk load from 1.14 up: the private
     * {@code postChunkLoadJoin} on the PlayerList class is its marker. Probed on
     * PlayerList itself (not the DedicatedPlayerList subclass the server returns,
     * nor via getMethods() — the method is private and declared on the base).
     */
    private boolean legacyAsyncJoin() {
        try {
            for (Method method : legacyClass("PlayerList").getDeclaredMethods()) {
                if (method.getName().equals("postChunkLoadJoin")) {
                    return true;
                }
            }
        } catch (ClassNotFoundException impossible) {
            // PlayerList always resolves on a legacy server.
        }
        return false;
    }

    private Object createServerPlayerLegacy(Object minecraftServer, Object worldServer, Object gameProfile)
            throws ReflectiveOperationException {
        Class<?> entityPlayerClass = legacyClass("EntityPlayer");
        Class<?> minecraftServerClass = legacyClass("MinecraftServer");
        Class<?> worldServerClass = legacyClass("WorldServer");
        Class<?> interactManagerClass = legacyClass("PlayerInteractManager");
        Class<?> profileClass = Class.forName("com.mojang.authlib.GameProfile");

        Object interactManager = newInteractManagerLegacy(interactManagerClass, worldServer);
        // Uniform 4-arg ctor on every revision:
        //   EntityPlayer(MinecraftServer, WorldServer, GameProfile, PlayerInteractManager)
        Constructor<?> constructor = entityPlayerClass.getConstructor(
                minecraftServerClass, worldServerClass, profileClass, interactManagerClass);
        return constructor.newInstance(minecraftServer, worldServer, gameProfile, interactManager);
    }

    /** PlayerInteractManager(World) ≤1.13.2 vs (WorldServer) 1.15.2+; a WorldServer satisfies both. */
    private Object newInteractManagerLegacy(Class<?> interactManagerClass, Object worldServer)
            throws ReflectiveOperationException {
        for (Constructor<?> constructor : interactManagerClass.getConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length == 1 && parameters[0].isAssignableFrom(worldServer.getClass())) {
                return constructor.newInstance(worldServer);
            }
        }
        throw new NoSuchMethodException("No 1-arg PlayerInteractManager ctor on " + interactManagerClass);
    }

    private void setupConnectionLegacy(Object minecraftServer) throws ReflectiveOperationException {
        Class<?> networkManagerClass = legacyClass("NetworkManager");
        Class<?> directionClass = legacyClass("EnumProtocolDirection");
        Object serverbound = Reflect.enumConstant(directionClass, "SERVERBOUND");
        this.connection = networkManagerClass.getConstructor(directionClass).newInstance(serverbound);

        EmbeddedChannel channel = newVoidOutboundChannel();
        setField(networkManagerClass, connection, "channel", channel);
        setSocketAddressLegacy(networkManagerClass);

        Class<?> connectionClass = legacyClass("PlayerConnection");
        Class<?> minecraftServerClass = legacyClass("MinecraftServer");
        Class<?> entityPlayerClass = legacyClass("EntityPlayer");
        // PlayerConnection(MinecraftServer, NetworkManager, EntityPlayer) — uniform.
        Object listener = connectionClass.getConstructor(
                        minecraftServerClass, networkManagerClass, entityPlayerClass)
                .newInstance(minecraftServer, connection, serverPlayer);
        this.gameListener = listener;

        setField(entityPlayerClass, serverPlayer, "playerConnection", listener);

        Class<?> packetListenerClass = legacyClass("PacketListener");
        Method setPacketListener = networkManagerClass.getMethod("setPacketListener", packetListenerClass);
        setPacketListener.invoke(connection, listener);
    }

    /** The SocketAddress field: {@code socketAddress} (1.13.2+) or {@code l} (≤1.12.2). */
    private void setSocketAddressLegacy(Class<?> networkManagerClass) {
        SocketAddress address = new InetSocketAddress("127.0.0.1", 9999);
        for (String candidate : new String[] {"socketAddress", "l"}) {
            Field field = Reflect.field(networkManagerClass, candidate);
            if (field != null && SocketAddress.class.isAssignableFrom(field.getType())) {
                try {
                    field.set(connection, address);
                    return;
                } catch (Throwable next) {
                    // Try the next candidate / the type scan below.
                }
            }
        }
        for (Field field : networkManagerClass.getDeclaredFields()) {
            // Exact type match excludes the InetSocketAddress virtualHost field.
            if (field.getType() == SocketAddress.class) {
                field.setAccessible(true);
                try {
                    field.set(connection, address);
                    return;
                } catch (Throwable ignored) {
                    // Best effort — a() logs the address but tolerates null.
                }
            }
        }
    }

    private void setGameModeSurvivalLegacy() {
        try {
            // EnumGamemode from 1.11.2; WorldSettings$EnumGamemode on 1.9.4/1.10.2.
            Class<?> gamemodeClass = legacyClassOrNull("EnumGamemode");
            if (gamemodeClass == null) {
                gamemodeClass = legacyClass("WorldSettings$EnumGamemode");
            }
            Object survival = Reflect.enumConstant(gamemodeClass, "SURVIVAL");
            // EntityPlayer.a(EnumGamemode) sets the interact-manager gamemode.
            Method setGameMode = Reflect.methodAssignable(serverPlayer.getClass(), "a", gamemodeClass);
            if (setGameMode != null) {
                setGameMode.invoke(serverPlayer, survival);
            }
        } catch (Throwable ignored) {
            // Best effort — PlayerList.a sets the server default gamemode (survival) anyway.
        }
    }

    private void setPositionLegacy(Location location) throws ReflectiveOperationException {
        Method setPosition = legacyClass("Entity")
                .getMethod("setPosition", double.class, double.class, double.class);
        setPosition.invoke(serverPlayer, location.getX(), location.getY(), location.getZ());
    }

    private Object playerListLegacy(Object minecraftServer) throws ReflectiveOperationException {
        Method getter = Reflect.method(legacyClass("MinecraftServer"), "getPlayerList");
        if (getter == null) {
            throw new NoSuchMethodException("getPlayerList not resolvable on legacy MinecraftServer");
        }
        return getter.invoke(minecraftServer);
    }

    /** Direct registration fallback: the stable legacy PlayerList field/method names. */
    @SuppressWarnings("unchecked")
    private void registerInPlayerListLegacy(Object minecraftServer) throws ReflectiveOperationException {
        Object playerList = playerListLegacy(minecraftServer);
        Class<?> playerListClass = legacyClass("PlayerList");

        Field playersField = Reflect.field(playerListClass, "players");
        if (playersField != null) {
            List<Object> players = (List<Object>) playersField.get(playerList);
            if (!players.contains(serverPlayer)) {
                players.add(serverPlayer);
            }
        }
        Map<UUID, Object> uuidMap = legacyUuidMap(playerListClass, playerList);
        if (uuidMap != null) {
            uuidMap.put(uuid, serverPlayer);
        }
        Field byName = Reflect.field(playerListClass, "playersByName");
        if (byName != null) {
            Map<String, Object> map = (Map<String, Object>) byName.get(playerList);
            map.put(name.toLowerCase(Locale.ROOT), serverPlayer);
        }
    }

    /** The UUID→EntityPlayer map: getUUIDMap() where present, else the stable field {@code j}. */
    @SuppressWarnings("unchecked")
    private Map<UUID, Object> legacyUuidMap(Class<?> playerListClass, Object playerList) {
        Method getUuidMap = Reflect.method(playerListClass, "getUUIDMap");
        if (getUuidMap != null) {
            try {
                return (Map<UUID, Object>) getUuidMap.invoke(playerList);
            } catch (ReflectiveOperationException ignored) {
                // Fall through to the field.
            }
        }
        Field field = Reflect.field(playerListClass, "j");
        if (field != null && Map.class.isAssignableFrom(field.getType())) {
            try {
                return (Map<UUID, Object>) field.get(playerList);
            } catch (ReflectiveOperationException ignored) {
                // No map available.
            }
        }
        return null;
    }

    /**
     * Adds the player to the world as a live, tickable, damageable entity.
     * {@code addPlayerJoin(EntityPlayer)} is the player-specific add on 1.15.2/
     * 1.16.5 (the generic {@code addEntity} refuses EntityHuman subclasses, so a
     * player added that way is never a live combat entity — the trap the first
     * legacy pass fell into). On 1.9.4–1.13.2 the synchronous PlayerList.a
     * already added the player; this only runs on that fallback, where the
     * generic {@code addEntity} is the entry point.
     */
    /**
     * Makes the fake player a CHUNK LOADER on 1.15.2/1.16.5, so its chunk stays
     * entity-ticking. {@code addPlayerJoin} registers only the entity tracker
     * ({@code registerEntity}), not the distance-map player ticket the real
     * (async) login adds — and the melee delivery desk AWAITS the
     * {@code PlayerVelocityEvent} the tracker fires only for a tracked entity in
     * a ticking chunk. Without this the event fires sporadically and melee knocks
     * are dropped (projectile/fishing knocks use a direct setVelocity and were
     * unaffected). Path:
     * {@code WorldServer.getChunkProvider().playerChunkMap.addPlayerToDistanceMaps}.
     */
    private void registerChunkLoaderLegacy(Object worldServer) {
        try {
            Object chunkProvider = null;
            for (Method method : worldServer.getClass().getMethods()) {
                if (!method.getName().equals("getChunkProvider") || method.getParameterCount() != 0) {
                    continue;
                }
                Object candidate = method.invoke(worldServer);
                if (candidate != null && Reflect.field(candidate.getClass(), "playerChunkMap") != null) {
                    chunkProvider = candidate;
                    break;
                }
            }
            if (chunkProvider == null) {
                return;
            }
            Object playerChunkMap = Reflect.field(chunkProvider.getClass(), "playerChunkMap").get(chunkProvider);
            Method addToDistanceMaps = Reflect.methodAssignable(
                    playerChunkMap.getClass(), "addPlayerToDistanceMaps", serverPlayer.getClass());
            if (addToDistanceMaps != null) {
                addToDistanceMaps.invoke(playerChunkMap, serverPlayer);
                plugin.getLogger().info("[fake] legacy chunk-loader registered (distance maps)");
            }
        } catch (Throwable failure) {
            plugin.getLogger().warning("[fake] legacy chunk-loader registration failed: " + failure);
        }
    }

    private void addPlayerToWorldLegacy(Object worldServer) {
        for (String candidate : new String[] {"addPlayerJoin", "addEntity", "addFreshEntity"}) {
            Method method = Reflect.methodAssignable(
                    worldServer.getClass(), candidate, serverPlayer.getClass());
            if (method != null) {
                try {
                    method.invoke(worldServer, serverPlayer);
                    plugin.getLogger().info("[fake] legacy world-add via " + candidate);
                    return;
                } catch (Throwable next) {
                    // Try the next candidate.
                }
            }
        }
        plugin.getLogger().warning("[fake] could not add legacy fake player to the world directly");
    }

    private void setMotionLegacy(double x, double y, double z) {
        try {
            Class<?> entityClass = legacyClass("Entity");
            // 1.15.2+: setMot(double,double,double); ≤1.13.2: public motX/motY/motZ.
            Method setMot = Reflect.methodAssignable(
                    entityClass, "setMot", double.class, double.class, double.class);
            if (setMot != null) {
                setMot.invoke(serverPlayer, x, y, z);
                return;
            }
            Field motX = Reflect.field(entityClass, "motX");
            Field motY = Reflect.field(entityClass, "motY");
            Field motZ = Reflect.field(entityClass, "motZ");
            if (motX != null && motY != null && motZ != null) {
                motX.setDouble(serverPlayer, x);
                motY.setDouble(serverPlayer, y);
                motZ.setDouble(serverPlayer, z);
                return;
            }
            throw new NoSuchMethodException("no legacy motion accessor (setMot / motX)");
        } catch (ReflectiveOperationException failure) {
            plugin.getLogger().warning("[fake] legacy setMotion failed: " + failure);
        }
    }

    /** Pre-1.15.2 melee: NMS EntityHuman.attack(Entity) on the victim's handle. */
    private void attackLegacyNms(@NotNull Entity target) {
        try {
            Object targetHandle = handleOf(target);
            Class<?> humanClass = legacyClass("EntityHuman");
            Class<?> entityClass = legacyClass("Entity");
            Method attack = humanClass.getMethod("attack", entityClass);
            attack.invoke(serverPlayer, targetHandle);
        } catch (ReflectiveOperationException failure) {
            plugin.getLogger().warning("[fake] legacy NMS attack failed: " + failure);
        }
    }

    /** Legacy removal: PlayerList.disconnect(EntityPlayer) — the full clean deregister. */
    private void removeLegacyDirect() {
        try {
            Object playerList = playerListLegacy(minecraftServer());
            Method disconnect = Reflect.methodAssignable(
                    legacyClass("PlayerList"), "disconnect", serverPlayer.getClass());
            if (disconnect != null) {
                disconnect.invoke(playerList, serverPlayer);
            }
        } catch (Throwable ignored) {
            // Already removed by the kick path, or the player never fully registered.
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Small helpers                                                      */
    /* ------------------------------------------------------------------ */

    private String remapMethod(Class<?> owner, String name, Class<?>... parameterTypes) {
        try {
            return remapper.remapMethodName(owner, name, parameterTypes);
        } catch (Throwable unmappable) {
            return name;
        }
    }

    private Method methodWithFallback(Class<?> owner, String mojangName) throws NoSuchMethodException {
        Method method = Reflect.method(owner, remapMethod(owner, mojangName));
        if (method == null) {
            method = Reflect.method(owner, mojangName);
        }
        if (method == null) {
            throw new NoSuchMethodException(mojangName + " not found on " + owner);
        }
        return method;
    }

    private static Method method(Class<?> owner, String name) throws NoSuchMethodException {
        Method method = Reflect.method(owner, name);
        if (method == null) {
            throw new NoSuchMethodException(name + " not found on " + owner);
        }
        return method;
    }

    private static Object invoke(Method method, Object target, Object... arguments)
            throws ReflectiveOperationException {
        return method.invoke(target, arguments);
    }

    private void setField(Class<?> owner, Object target, String mojangName, Object value)
            throws ReflectiveOperationException {
        Field field = Reflect.field(owner, remapper.remapFieldName(owner, mojangName));
        if (field == null) {
            field = Reflect.field(owner, mojangName);
        }
        if (field == null) {
            throw new NoSuchFieldException(mojangName + " not found on " + owner);
        }
        field.set(target, value);
    }

    private static Field mapField(Class<?> owner, Class<?> valueType) {
        for (Field field : owner.getDeclaredFields()) {
            if (!Map.class.isAssignableFrom(field.getType())) {
                continue;
            }
            field.setAccessible(true);
            if (field.getGenericType().getTypeName().contains("UUID")) {
                return field;
            }
        }
        return null;
    }

    /** The Map field keyed by {@code keyType} whose values can hold a ServerPlayer. */
    private static Field playerMapField(Class<?> owner, Class<?> keyType, Class<?> playerClass) {
        for (Field field : owner.getDeclaredFields()) {
            if (!Map.class.isAssignableFrom(field.getType())
                    || !(field.getGenericType()
                            instanceof ParameterizedType parameterized)) {
                continue;
            }
            Type[] arguments = parameterized.getActualTypeArguments();
            if (arguments.length == 2
                    && arguments[0] == keyType
                    && arguments[1] instanceof Class<?> valueType
                    && valueType.isAssignableFrom(playerClass)) {
                field.setAccessible(true);
                return field;
            }
        }
        return null;
    }
}
