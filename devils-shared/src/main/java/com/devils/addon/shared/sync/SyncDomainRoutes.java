package com.devils.addon.shared.sync;

public final class SyncDomainRoutes {
    public static final String CORE_PULL_PATH = "/v1/core/sync/pull";
    public static final String CORE_PUSH_PATH = "/v1/core/sync/push";
    public static final String CORE_STREAM_PATH = "/v1/core/sync/stream";

    public static final String GAME_PULL_PATH = "/v1/games/sync/pull";
    public static final String GAME_PUSH_PATH = "/v1/games/sync/push";
    public static final String GAME_STREAM_PATH = "/v1/games/sync/stream";

    public static final String LEGACY_PULL_PATH = "/pull";
    public static final String LEGACY_PUSH_PATH = "/push";
    public static final String LEGACY_STREAM_PATH = "/v1/sync/stream";

    private SyncDomainRoutes() {
    }
}
