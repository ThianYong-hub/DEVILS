package com.devils.addon.modules.games;

import com.devils.addon.games.DevilsGameAddon;
import com.devils.addon.shared.sync.AbstractSyncConfigModule;

public final class GameSyncHub extends AbstractSyncConfigModule {
    public GameSyncHub() {
        super(
            DevilsGameAddon.GAMES_CATEGORY,
            "game-sync-hub",
            "Dedicated sync configuration for Devils Game sessions and presence."
        );
    }
}
