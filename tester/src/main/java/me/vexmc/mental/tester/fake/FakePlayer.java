package me.vexmc.mental.tester.fake;

import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import me.vexmc.mental.common.scheduling.Scheduling;
import me.vexmc.mental.common.scheduling.TaskHandle;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import xyz.jpenilla.reflectionremapper.ReflectionRemapper;

/**
 * A synthetic player built straight onto the server internals — ported from
 * BukkitOldCombatMechanics' battle-tested FakePlayer and trimmed to the
 * 1.17+ window (no legacy 1.9/1.12 paths). Reflection names are routed
 * through reflection-remapper: identity on Mojang-mapped runtimes (1.20.5+),
 * mapped via the Paper jar's reobf data below that.
 *
 * <p>Per-instance NMS lookups are fine here: fake players are created a
 * handful of times per suite, not on any hot path.</p>
 */
public final class FakePlayer {

    private final JavaPlugin plugin;
    private final Scheduling scheduling;
    private final ReflectionRemapper remapper;
    private final UUID uuid = UUID.randomUUID();
    private final String name = uuid.toString().substring(0, 16);

    private Object serverPlayer;
    private Object connection;
    private Player bukkitPlayer;
    private boolean placedViaPlayerList;
    private TaskHandle tickTask;

    public FakePlayer(@NotNull JavaPlugin plugin, @NotNull Scheduling scheduling) {
        this.plugin = plugin;
        this.scheduling = scheduling;
        this.remapper = createRemapper(plugin);
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
        player().attack(target);
    }

    /** Must run on the global/main thread. */
    public void spawn(@NotNull Location location) throws ReflectiveOperationException {
        Object worldServer = handleOf(location.getWorld());
        Object minecraftServer = minecraftServer();
        Object gameProfile = createGameProfile();

        this.serverPlayer = createServerPlayer(minecraftServer, worldServer, gameProfile);
        setupConnection(minecraftServer);
        setGameModeSurvival();
        setPosition(location);

        fireAsyncPreLogin();
        this.placedViaPlayerList = addToPlayerList(minecraftServer);

        this.bukkitPlayer = Bukkit.getPlayer(uuid);
        if (bukkitPlayer == null) {
            throw new IllegalStateException("Bukkit player " + uuid + " not found after placement");
        }
        if (!placedViaPlayerList) {
            addToWorld(worldServer);
        }

        this.tickTask = scheduling.repeatOn(bukkitPlayer, 1L, 1L, this::tickServerPlayer, () -> {});
    }

    /** Must run on the global/main thread. */
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
        try {
            Object minecraftServer = minecraftServer();
            Object playerList = invoke(method(minecraftServer.getClass(), "getPlayerList"), minecraftServer);
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
        bukkitPlayer = null;
    }

    /* ------------------------------------------------------------------ */
    /*  NMS bootstrap                                                      */
    /* ------------------------------------------------------------------ */

    private static ReflectionRemapper createRemapper(JavaPlugin plugin) {
        try {
            return ReflectionRemapper.forReobfMappingsInPaperJar();
        } catch (Throwable mojangMappedRuntime) {
            plugin.getLogger().info("No reobf mappings present — Mojang-mapped runtime, using identity remapper.");
            return ReflectionRemapper.noop();
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

        EmbeddedChannel channel = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
        if (channel.pipeline().get("decoder") == null) {
            channel.pipeline().addLast("decoder", new ChannelInboundHandlerAdapter());
        }
        if (channel.pipeline().get("encoder") == null) {
            channel.pipeline().addLast("encoder", new ChannelOutboundHandlerAdapter());
        }

        setField(connectionClass, connection, "channel", channel);
        setField(connectionClass, connection, "address", new InetSocketAddress("127.0.0.1", 9999));

        Object listener = createGamePacketListener(minecraftServer, connectionClass);
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
        Object playerList = invoke(method(minecraftServer.getClass(), "getPlayerList"), minecraftServer);
        Class<?> playerListClass = nmsClass("net.minecraft.server.players.PlayerList");
        String placeName = remapper.remapMethodName(playerListClass, "placeNewPlayer",
                connection.getClass(), serverPlayer.getClass());

        for (Method method : playerListClass.getMethods()) {
            if (!method.getName().equals(placeName) && !method.getName().equals("placeNewPlayer")) {
                continue;
            }
            Class<?>[] parameters = method.getParameterTypes();
            if (parameters.length == 3
                    && parameters[0].isAssignableFrom(connection.getClass())
                    && parameters[1].isAssignableFrom(serverPlayer.getClass())
                    && parameters[2].getSimpleName().equals("CommonListenerCookie")) {
                method.invoke(playerList, connection, serverPlayer, createListenerCookie(parameters[2]));
                return true;
            }
            if (parameters.length == 2
                    && parameters[0].isAssignableFrom(connection.getClass())
                    && parameters[1].isAssignableFrom(serverPlayer.getClass())) {
                method.invoke(playerList, connection, serverPlayer);
                return true;
            }
        }

        // Fallback: load player data and register in the list structures directly.
        Method load = Reflect.methodAssignable(playerListClass,
                remapper.remapMethodName(playerListClass, "load", serverPlayer.getClass()),
                serverPlayer.getClass());
        if (load == null) {
            load = Reflect.methodAssignable(playerListClass, "load", serverPlayer.getClass());
        }
        if (load == null) {
            throw new NoSuchMethodException("No compatible PlayerList registration on " + playerListClass);
        }
        load.invoke(playerList, serverPlayer);

        Field playersField = Reflect.field(playerListClass,
                remapper.remapFieldName(playerListClass, "players"));
        if (playersField != null) {
            @SuppressWarnings("unchecked")
            List<Object> players = (List<Object>) playersField.get(playerList);
            players.add(serverPlayer);
        }
        Field byUuid = mapField(playerListClass, serverPlayer.getClass());
        if (byUuid != null) {
            @SuppressWarnings("unchecked")
            Map<UUID, Object> map = (Map<UUID, Object>) byUuid.get(playerList);
            map.put(uuid, serverPlayer);
        }
        return false;
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

    private void tickServerPlayer() {
        try {
            Method tick = resolveTickMethod(serverPlayer.getClass());
            if (tick != null) {
                tick.invoke(serverPlayer);
            }
        } catch (Throwable ignored) {
            // Ticking is best-effort; physics-critical tests sync state explicitly.
        }
    }

    private Method cachedTick;

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
}
