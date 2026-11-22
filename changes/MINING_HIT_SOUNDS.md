# Mining hit sounds

Commit `cc5d80b2b`. Mining recovery could track a vanilla block and send a second hit sound over the client's own sound.

- `BukkitServerPlayer.tickBlockDestroy` sends hit sounds for custom states and for vanilla sounds muted by an enabled CraftEngine sound system.
- Hit packets use the block center. Custom hits use their configured volume and pitch. `BlockSounds.fromConfig` defaults omitted hit volume to `0.25` and pitch to `0.5`. Vanilla replacement hits retain `0.5` for both.
- Verify one hit on vanilla netherite, audible replacements for muted sounds, and centered custom hits. Final break sounds are handled separately.
