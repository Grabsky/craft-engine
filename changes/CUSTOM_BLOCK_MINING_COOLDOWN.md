# Custom block mining cooldown

Commit `01484e1bd`. Timed custom breaks finish on the server, so the client can begin mining the exposed block before vanilla's usual five-tick pause.

- `BukkitServerPlayer.tickBlockDestroy` starts the cooldown after a successful timed custom break. Instant and cancelled breaks bypass it.
- `PlayerActionListener` intercepts dig packets before vanilla handles them. It keeps byte copies of the latest start and its matching direction change and stop. A matching abort clears the queued attempt.
- After five server ticks, replay on the channel event loop in start, direction, stop order via `simulatePacket`. Check cooldown identity, world, protocol state, and interaction range before replay. Scheduler retirement clears the cooldown.

When porting, verify packet reinjection and action ordinals. Start/abort/stop are 0/1/2 through 26.2. On 26.3, direction change is 1 and abort/stop move to 2/3. Test held attack, release, target changes, and instant breaks with any protocol translators in use.
