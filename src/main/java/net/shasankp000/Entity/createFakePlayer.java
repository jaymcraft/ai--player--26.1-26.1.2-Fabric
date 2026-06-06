package net.shasankp000.Entity;

import com.google.gson.Gson;
import com.mojang.authlib.GameProfile;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.core.BlockPos;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import net.shasankp000.AIPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class createFakePlayer extends ServerPlayer {
    public static final Logger LOGGER = LoggerFactory.getLogger("ai-player");
    public boolean isAShadow;

    private createFakePlayer(MinecraftServer server, ServerLevel worldIn, GameProfile profile, ClientInformation cli,
            boolean shadow) {
        super(server, worldIn, profile, cli);
        isAShadow = shadow;
    }

    public static CompletableFuture<ServerPlayer> createFake(String username, MinecraftServer server, Vec3 pos, double yaw, double pitch,
            ResourceKey<Level> dimensionId, GameType gamemode, boolean flying) {
        ServerLevel worldIn = server.getLevel(dimensionId);
        if (worldIn == null) {
            LOGGER.error("Could not spawn fake player {}: dimension {} is not loaded", username, dimensionId);
            return CompletableFuture.completedFuture(null);
        }

        GameProfile gameProfile;
        boolean useMojangAuth = server.isDedicatedServer() && server.usesAuthentication();

        if (useMojangAuth) {
            gameProfile = server.services().profileResolver().fetchByName(username).orElse(null);
        } else {
            UUID offlineId = UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
            gameProfile = new GameProfile(offlineId, username);
        }

        Map<String, String> existingBotProfile = AIPlayer.CONFIG.getBotGameProfile();
        if (gameProfile == null) {
            if (existingBotProfile == null || !existingBotProfile.containsKey(username) || existingBotProfile.isEmpty()) {
                gameProfile = new GameProfile(UUID.randomUUID(), username);
            } else {
                UUID existingUUID = UUID.fromString(existingBotProfile.get(username));
                gameProfile = new GameProfile(existingUUID, username);
            }
        }

        if (useMojangAuth) {
            GameProfile finalGP = gameProfile;
            return fetchGameProfile(gameProfile.name()).thenCompose(p -> {
                CompletableFuture<ServerPlayer> spawnFuture = new CompletableFuture<>();
                GameProfile current = p.orElse(finalGP);
                server.execute(() -> {
                    try {
                        saveBotProfile(current);
                        spawnFuture.complete(spawnFake(server, worldIn, current, pos, yaw, pitch, gamemode, flying, dimensionId));
                    } catch (Exception e) {
                        spawnFuture.completeExceptionally(e);
                    }
                });
                return spawnFuture;
            });
        }

        saveBotProfile(gameProfile);
        return CompletableFuture.completedFuture(
                spawnFake(server, worldIn, gameProfile, pos, yaw, pitch, gamemode, flying, dimensionId)
        );
    }

    private static void saveBotProfile(GameProfile gameProfile) {
        if (gameProfile == null || gameProfile.name() == null || gameProfile.id() == null) {
            return;
        }

        Map<String, String> existingBotProfile = AIPlayer.CONFIG.getBotGameProfile();
        HashMap<String, String> botProfile = new HashMap<>();
        if (existingBotProfile != null) {
            botProfile.putAll(existingBotProfile);
        }
        String previousId = botProfile.putIfAbsent(gameProfile.name(), gameProfile.id().toString());
        if (previousId != null) {
            return;
        }

        try {
            AIPlayer.CONFIG.setBotGameProfile(botProfile);
            AIPlayer.CONFIG.save();
            LOGGER.info("Saved bot profile for {}", gameProfile.name());
        } catch (Exception e) {
            LOGGER.error("Could not save bot profile config: {}", e.getMessage());
        }
    }

    private static ServerPlayer spawnFake(MinecraftServer server, ServerLevel worldIn, GameProfile gameprofile, Vec3 pos,
            double yaw, double pitch, GameType gamemode, boolean flying, ResourceKey<Level> dimensionId) {
        createFakePlayer instance = new createFakePlayer(server, worldIn, gameprofile,
                ClientInformation.createDefault(), false);
        net.minecraft.network.Connection connection = new FakeClientConnection(PacketFlow.SERVERBOUND);
        server.getPlayerList().placeNewPlayer(connection, instance,
                new CommonListenerCookie(gameprofile, 0, instance.clientInformation(), false));
        instance.teleportTo(worldIn, pos.x, pos.y, pos.z, java.util.Set.of(), (float) yaw, (float) pitch, false);
        instance.setHealth(20.0F);
        instance.unsetRemoved();
        instance.gameMode.changeGameModeForPlayer(gamemode);
        server.getPlayerList().broadcastAll(
                new ClientboundRotateHeadPacket(instance, (byte) (instance.yHeadRot * 256 / 360)), dimensionId);
        instance.entityData.set(DATA_PLAYER_MODE_CUSTOMISATION, (byte) 0x7f);
        instance.getAbilities().flying = flying;
        return instance;
    }

    private static final class FakeClientConnection extends net.minecraft.network.Connection {
        private FakeClientConnection(PacketFlow receiving) {
            super(receiving);
            try {
                io.netty.channel.embedded.EmbeddedChannel embedded = new io.netty.channel.embedded.EmbeddedChannel();
                java.lang.reflect.Field chField = net.minecraft.network.Connection.class.getDeclaredField("channel");
                chField.setAccessible(true);
                chField.set(this, embedded);
            } catch (Exception e) {
                createFakePlayer.LOGGER.warn("Failed to setup embedded channel for FakeClientConnection", e);
            }
        }

        @Override
        public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> protocolInfo, T listener) {
        }

        @Override
        public void setupOutboundProtocol(ProtocolInfo<?> protocolInfo) {
        }

        @Override
        public void send(Packet<?> packet) {
        }

        @Override
        public void send(Packet<?> packet, ChannelFutureListener channelFutureListener) {
        }

        @Override
        public void send(Packet<?> packet, ChannelFutureListener channelFutureListener, boolean flush) {
        }


        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public void disconnect(Component reason) {
        }

        @Override
        public void disconnect(DisconnectionDetails disconnectionDetails) {
        }

        @Override
        public void handleDisconnection() {
        }
    }

    private static CompletableFuture<Optional<GameProfile>> fetchGameProfile(final String name) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                URL url = new URL("https://api.mojang.com/users/profiles/minecraft/" + name);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");

                if (connection.getResponseCode() == 200) {
                    LOGGER.info("Found player {} on Mojang's server", name);
                    try (InputStreamReader reader = new InputStreamReader(connection.getInputStream())) {
                        GameProfile profile = new Gson().fromJson(reader, GameProfile.class);
                        return Optional.ofNullable(profile);
                    }
                }
            } catch (Exception e) {
                LOGGER.error("Player {} was not found on Mojang's servers. {}", name, e.getMessage());
            }
            return Optional.empty();
        });
    }

    @Override
    public void onEquipItem(final EquipmentSlot slot, final ItemStack previous, final ItemStack stack) {
        if (!isUsingItem()) {
            super.onEquipItem(slot, previous, stack);
        }
    }

    @Override
    public void kill(ServerLevel level) {
        kill(Component.literal("Killed"));
    }

    public void kill(Component reason) {
        shakeOff();

        if (reason.getContents() instanceof TranslatableContents text
                && text.getKey().equals("multiplayer.disconnect.duplicate_login")) {
            this.connection.disconnect(reason);
        } else {
            ((ServerLevel) this.level()).getServer().addTickable(() -> this.connection.disconnect(reason));
        }
    }

    @Override
    public void tick() {
        if (Objects.requireNonNull(((ServerLevel) this.level()).getServer()).getTickCount() % 10 == 0) {
            this.connection.resetPosition();
            this.level().getChunkSource().move(this);
        }
        try {
            super.tick();
            this.doTick();
        } catch (NullPointerException ignored) {
            // Fake players can briefly tick with incomplete network state during login/removal.
        }
    }

    private void shakeOff() {
        if (getVehicle() instanceof Player) {
            stopRiding();
        }
        for (Entity passenger : getIndirectPassengers()) {
            if (passenger instanceof Player) {
                passenger.stopRiding();
            }
        }
    }

    @Override
    public void die(DamageSource cause) {
        shakeOff();
        super.die(cause);
        setHealth(20);
        this.foodData = new FoodData();
        kill(this.getCombatTracker().getDeathMessage());
    }

    @Override
    public String getIpAddress() {
        return "127.0.0.1";
    }

    @Override
    public boolean allowsListing() {
        return true;
    }

    @Override
    protected void checkFallDamage(double y, boolean onGround, BlockState state, BlockPos pos) {
        doCheckFallDamage(0.0, y, 0.0, onGround);
    }

    @Override
    public ServerPlayer teleport(TeleportTransition target) {
        ServerPlayer entity = super.teleport(target);
        if (wonGame) {
            ServerboundClientCommandPacket packet = new ServerboundClientCommandPacket(
                    ServerboundClientCommandPacket.Action.PERFORM_RESPAWN);
            connection.handleClientCommand(packet);
        }

        if (connection.player.isChangingDimension()) {
            connection.player.hasChangedDimension();
        }
        return connection.player;
    }

}
