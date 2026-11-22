package net.momirealms.craftengine.bukkit.plugin.network.listener.game;

import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import net.momirealms.craftengine.bukkit.block.BukkitBlockManager;
import net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine;
import net.momirealms.craftengine.bukkit.plugin.user.BukkitServerPlayer;
import net.momirealms.craftengine.bukkit.util.BlockStateUtils;
import net.momirealms.craftengine.bukkit.util.LocationUtils;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.plugin.config.Config;
import net.momirealms.craftengine.core.plugin.network.ConnectionState;
import net.momirealms.craftengine.core.plugin.network.NetWorkUser;
import net.momirealms.craftengine.core.plugin.network.event.ByteBufPacketEvent;
import net.momirealms.craftengine.core.plugin.network.listener.ByteBufferPacketListener;
import net.momirealms.craftengine.core.util.FriendlyByteBuf;
import net.momirealms.craftengine.core.util.VersionHelper;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.Vec3d;
import net.momirealms.craftengine.proxy.bukkit.craftbukkit.CraftWorldProxy;
import net.momirealms.craftengine.proxy.minecraft.server.MinecraftServerProxy;
import net.momirealms.craftengine.proxy.minecraft.sounds.SoundEventProxy;
import net.momirealms.craftengine.proxy.minecraft.world.level.BlockGetterProxy;
import net.momirealms.craftengine.proxy.minecraft.world.level.block.SoundTypeProxy;
import net.momirealms.craftengine.proxy.minecraft.world.level.block.state.BlockBehaviourProxy;
import org.bukkit.World;
import org.bukkit.entity.Player;

public final class PlayerActionListener implements ByteBufferPacketListener {
    public static final ByteBufferPacketListener INSTANCE = new PlayerActionListener();
    private static final AttributeKey<MiningCooldown> MINING_COOLDOWN = AttributeKey.valueOf("craftengine:mining_cooldown");
    private static final int MINING_COOLDOWN_TICKS = 5;
    private static final int START_DESTROY_BLOCK = 0;
    private static final int CHANGE_DESTROY_DIRECTION = 1;
    private static final int ABORT_DESTROY_BLOCK = VersionHelper.isOrAbove26_3 ? 2 : 1;
    private static final int STOP_DESTROY_BLOCK = VersionHelper.isOrAbove26_3 ? 3 : 2;

    private PlayerActionListener() {}

    public static void startMiningCooldown(BukkitServerPlayer player) {
        Channel channel = player.nettyChannel();
        if (channel == null || !channel.isOpen()) {
            return;
        }
        Player platformPlayer = player.platformPlayer();
        MiningCooldown cooldown = new MiningCooldown(platformPlayer.getWorld());
        Attribute<MiningCooldown> attribute = channel.attr(MINING_COOLDOWN);
        attribute.set(cooldown);
        BukkitCraftEngine.instance().scheduler().platform().runLater(
                () -> channel.eventLoop().execute(() -> {
                    if (attribute.compareAndSet(cooldown, null)
                            && cooldown.startPacket != null
                            && platformPlayer.getWorld() == cooldown.world
                            && player.decoderState() == ConnectionState.PLAY
                            && player.canInteractPoint(new Vec3d(cooldown.pos.x, cooldown.pos.y, cooldown.pos.z), 4)) {
                        player.simulatePacket(Unpooled.wrappedBuffer(cooldown.startPacket));
                        if (cooldown.directionPacket != null) {
                            player.simulatePacket(Unpooled.wrappedBuffer(cooldown.directionPacket));
                        }
                        if (cooldown.stopPacket != null) {
                            player.simulatePacket(Unpooled.wrappedBuffer(cooldown.stopPacket));
                        }
                    }
                }),
                () -> attribute.compareAndSet(cooldown, null),
                MINING_COOLDOWN_TICKS, player
        );
    }

    @Override
    public void onPacketReceive(NetWorkUser user, ByteBufPacketEvent event) {
        BukkitServerPlayer player = (BukkitServerPlayer) user;
        Player platformPlayer = player.platformPlayer();
        World world = platformPlayer.getWorld();
        FriendlyByteBuf buf = event.getBuffer();
        int action = buf.readVarInt();
        BlockPos pos = buf.readBlockPos();
        MiningCooldown cooldown = player.nettyChannel().attr(MINING_COOLDOWN).get();
        if (cooldown != null && cooldown.world == world) {
            if (action == START_DESTROY_BLOCK) {
                // Keep only the latest target and preserve the original prediction sequence.
                cooldown.clearAttempt();
                cooldown.pos = pos;
                cooldown.startPacket = ByteBufUtil.getBytes(buf, 0, buf.writerIndex());
                event.setCancelled(true);
                return;
            }
            if (pos.equals(cooldown.pos)) {
                if (action == ABORT_DESTROY_BLOCK) {
                    cooldown.clearAttempt();
                } else if (action == STOP_DESTROY_BLOCK) {
                    cooldown.stopPacket = ByteBufUtil.getBytes(buf, 0, buf.writerIndex());
                    event.setCancelled(true);
                    return;
                } else if (VersionHelper.isOrAbove26_3 && action == CHANGE_DESTROY_DIRECTION) {
                    cooldown.directionPacket = ByteBufUtil.getBytes(buf, 0, buf.writerIndex());
                    event.setCancelled(true);
                    return;
                }
            }
        }
        if (!player.canInteractPoint(new Vec3d(pos.x, pos.y, pos.z), 4)) {
            return;
        }
        BukkitCraftEngine.instance().scheduler().platform().run(
                () -> handlePlayerActionPacketOnMainThread(player, world, pos, action),
                world, pos.x >> 4, pos.z >> 4
        );
    }

    private static void handlePlayerActionPacketOnMainThread(BukkitServerPlayer player, World world, BlockPos pos, int action) {
        if (action == START_DESTROY_BLOCK) {
            Object serverLevel = CraftWorldProxy.INSTANCE.getWorld(world);
            Object blockPos = LocationUtils.toBlockPos(pos);
            Object serverPlayer = player.minecraftPlayer();
            if (MinecraftServerProxy.INSTANCE.isUnderSpawnProtection(MinecraftServerProxy.INSTANCE.getServer(), serverLevel, blockPos, serverPlayer)) {
                player.setClientSideCanBreakBlock(false);
                return;
            }
            Object blockState = BlockGetterProxy.INSTANCE.getBlockState(serverLevel, blockPos);
            int stateId = BlockStateUtils.blockStateToId(blockState);
            // not a custom block
            if (BlockStateUtils.isVanillaBlock(stateId)) {
                if (Config.enableSoundSystem()) {
                    Object soundType = BlockBehaviourProxy.BlockStateBaseProxy.INSTANCE.getSoundType(blockState);
                    Object soundEvent = SoundTypeProxy.INSTANCE.getHitSound(soundType);
                    Object soundId = SoundEventProxy.INSTANCE.getLocation(soundEvent);
                    if (BukkitBlockManager.instance().isHitSoundMissing(soundId)) {
                        player.startMiningBlock(pos, blockState, null);
                        return;
                    }
                }
                if (player.isMiningBlock()) {
                    player.finishMiningBlock();
                }
                player.setClientSideCanBreakBlock(true);
                return;
            }
            if (player.isAdventureMode()) {
                if (Config.simplifyAdventureBreakCheck()) {
                    ImmutableBlockState state = BukkitBlockManager.instance().getImmutableBlockStateUnsafe(stateId);
                    if (!player.canBreak(pos, state.visualBlockState().minecraftState())) {
                        player.preventMiningBlock();
                        return;
                    }
                } else {
                    if (!player.canBreak(pos, null)) {
                        player.preventMiningBlock();
                        return;
                    }
                }
            }
            player.startMiningBlock(pos, blockState, BukkitBlockManager.instance().getImmutableBlockStateUnsafe(stateId));
        } else if (action == ABORT_DESTROY_BLOCK) {
            if (player.isMiningBlock()) {
                player.abortMiningBlock();
            }
        } else if (action == STOP_DESTROY_BLOCK) {
            if (player.isMiningBlock()) {
                player.finishMiningBlock();
            }
        }
    }

    private static final class MiningCooldown {
        private final World world;
        private BlockPos pos;
        private byte[] startPacket;
        private byte[] directionPacket;
        private byte[] stopPacket;

        private MiningCooldown(World world) {
            this.world = world;
        }

        private void clearAttempt() {
            this.pos = null;
            this.startPacket = null;
            this.directionPacket = null;
            this.stopPacket = null;
        }
    }
}
