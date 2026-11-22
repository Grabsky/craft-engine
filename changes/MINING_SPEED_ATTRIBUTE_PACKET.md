# Mining speed attribute packets

Commit `74198dcfd`. Replacing `block_break_speed` modifiers before reading them left the packet cursor inside the original modifier list, so later attributes could be misread.

`UpdateAttributesListener` now decodes every modifier before applying the replacement. Preserve UUID modifier IDs on 1.20.5–1.20.6 and resource keys on 1.21+. When porting, test a modified mining-speed attribute followed by another attribute in the same packet.
