# Porting the custom block mining cooldown

Vanilla Java Edition starts a five-tick delay when the client completes a timed block break. CraftEngine suppresses client-side progress during timed custom mining and completes the break on the server. The client can then send a start-dig packet for the newly exposed block immediately. The existing `lastSuccessfulBreak` check in `BukkitServerPlayer.tick` delays CraftEngine's mining loop, while vanilla can still handle the inbound start packet and begin mining the next block.

## Implementation

1. In [BukkitServerPlayer.tickBlockDestroy](bukkit/src/main/java/net/momirealms/craftengine/bukkit/plugin/user/BukkitServerPlayer.java), call `PlayerActionListener.startMiningCooldown(this)` after `ServerPlayerGameMode.destroyBlock` reports success, when the block is custom and the player lacks instant-build ability. Instant breaks take a separate path through `startMiningBlock`, and cancelled breaks exit before the cooldown is installed.
2. In [PlayerActionListener](bukkit/src/main/java/net/momirealms/craftengine/bukkit/plugin/network/listener/game/PlayerActionListener.java), store the cooldown as a Netty channel attribute tied to the world where the break occurred. Intercept inbound player-action packets before the vanilla decoder. Keep the latest `START_DESTROY_BLOCK` and any matching direction change or stop packet. A matching `ABORT_DESTROY_BLOCK` clears the queued attempt and continues through normal handling. Keep a byte copy of each queued packet, including its packet ID, face, and prediction sequence, because the decoder releases the inbound buffer.
3. Use the player scheduler to wait five server ticks, then clear and replay on the channel's event loop. Compare the channel attribute with the scheduled cooldown instance so an older callback cannot clear a newer cooldown. Replay only for the original world, in the play protocol state, and within the listener's interaction range check. Send start, direction change, and stop in that order through `simulatePacket` so CraftEngine and vanilla both process them normally. The scheduler retirement callback clears the corresponding cooldown.

## Version and pipeline checks

| Server version | Start | Change direction | Abort | Stop |
| --- | ---: | ---: | ---: | ---: |
| 1.20 through 26.2 | 0 | — | 1 | 2 |
| 26.3 | 0 | 1 | 2 | 3 |

The listener uses `VersionHelper.isOrAbove26_3` for the ordinal change. When porting, check the current `ServerboundPlayerActionPacket` codec and action order, and confirm that the client's timed break still sets a five-tick delay. The original checks covered client bytecode for 1.20.1, 1.21.1, 26.2, and 26.3.

Check that [BukkitNetworkManager](bukkit/src/main/java/net/momirealms/craftengine/bukkit/plugin/network/BukkitNetworkManager.java) still cancels the original packet before vanilla handles it and that `simulatePacket` reinjects the saved bytes at the correct point for the negotiated protocol. Packet listeners can observe the replayed start later than the surrounding swings or movement. The server-side cooldown can also leave client-side swings or hit feedback visible during the pause.

## In-game verification

- Hold attack through a timed custom block with a vanilla block behind it, then repeat with another custom block behind it. The second block should begin mining after the pause.
- Release attack or switch targets during the pause. A released attempt should be discarded, and a changed target should replace the queued start. Check direction changes on 26.3.
- Check instant breaks, creative mode, and cancelled breaks. These should follow their usual paths.
- Repeat with the server's protocol translators and packet interception plugins enabled. On Folia, also check replay when the player crosses a region boundary or changes worlds during the pause.
