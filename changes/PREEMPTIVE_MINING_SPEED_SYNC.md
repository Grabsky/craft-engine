# Preemptive mining speed sync

Commit `3b1dbf82c`. Set `block.preemptive-mining-speed-sync: true` to sync mining speed before the first swing on 1.20.5+ Java survival clients. The option defaults to `false` and was added in config version 115.

`BukkitServerPlayer` uses its existing tick to cache aim, range, tool, and world. It checks loaded CraftEngine chunk sections for nearby custom blocks before tracing, reuses the mining trace when available, and staggers stationary checks every five ticks. Preserve the no-chunk-load path and restore vanilla speed when the aim-based override ends.

When porting, test the first crack stages when switching from vanilla to custom blocks, then check that vanilla mining speed returns when aiming away.
