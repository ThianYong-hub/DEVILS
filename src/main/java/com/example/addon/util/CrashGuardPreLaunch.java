package com.example.addon.util;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

public final class CrashGuardPreLaunch implements PreLaunchEntrypoint {
    @Override
    public void onPreLaunch() {
        EarlyLogSpamFilter.install();
    }
}
