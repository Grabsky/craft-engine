# Enderite placement flicker

In the FIREDOT pack, the client predicted Enderite placement as a brown mushroom block. Vanilla hides shared faces between adjacent blocks of that type. The resulting state could show a brown mushroom texture or turn a neighboring Lantern into Topaz Ore until the server corrected it.

Removing `client_bound_material` and `client_bound_data` from the Enderite item resolved the flicker in-game. The client sees the base item with the Enderite model and waits for the server to place the block.
