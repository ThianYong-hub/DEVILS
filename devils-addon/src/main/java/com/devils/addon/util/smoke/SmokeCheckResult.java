package com.devils.addon.util.smoke;

public record SmokeCheckResult(String id, boolean success, String detail) {
    public static SmokeCheckResult pass(String id, String detail) {
        return new SmokeCheckResult(id, true, detail == null ? "" : detail);
    }

    public static SmokeCheckResult fail(String id, String detail) {
        return new SmokeCheckResult(id, false, detail == null ? "" : detail);
    }
}
