package com.example.addon.modules.games;

import com.example.addon.games.DevilsGameAddon;
import com.example.addon.shared.sync.AbstractSyncConfigModule;

public final class GameSyncHub extends AbstractSyncConfigModule {
    public GameSyncHub() {
        super(
            DevilsGameAddon.GAMES_CATEGORY,
            "game-sync-hub",
            "Dedicated sync configuration for Devils Game sessions and presence."
        );
    }
}
