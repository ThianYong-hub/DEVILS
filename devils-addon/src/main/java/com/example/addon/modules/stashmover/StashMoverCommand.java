package com.example.addon.modules.stashmover;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.util.Locale;
import java.util.function.Function;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.command.CommandSource;

public class StashMoverCommand extends Command {
    public StashMoverCommand() {
        super("stashmover", "Configures StashMover world-bound positions and diagnostics.");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        for (CaptureAction action : CaptureAction.values()) {
            builder.then(literal(action.literal()).executes(ctx -> runCapture(action)));
        }

        builder.then(literal("status").executes(ctx -> runStatus()));
        builder.then(literal("clear")
            .then(argument("target", StringArgumentType.word()).executes(ctx -> runClear(StringArgumentType.getString(ctx, "target"))))
        );
    }

    private int runCapture(CaptureAction action) {
        StashMover module = module();
        if (module == null) {
            warnMissingModule();
            return SINGLE_SUCCESS;
        }

        info(action.execute(module));
        return SINGLE_SUCCESS;
    }

    private int runStatus() {
        StashMover module = module();
        if (module == null) {
            warnMissingModule();
            return SINGLE_SUCCESS;
        }

        info(module.runtimeStatusSummary());
        return SINGLE_SUCCESS;
    }

    private int runClear(String rawTarget) {
        StashMover module = module();
        if (module == null) {
            warnMissingModule();
            return SINGLE_SUCCESS;
        }

        ClearTarget target = resolveClearTarget(rawTarget);
        info(module.clearPosition(target.canonicalKey()));
        return SINGLE_SUCCESS;
    }

    private void warnMissingModule() {
        error("StashMover module is not available.");
    }

    private static ClearTarget resolveClearTarget(String rawTarget) {
        String normalized = normalizeTarget(rawTarget);
        for (ClearTarget target : ClearTarget.values()) {
            if (target.matches(normalized)) return target;
        }
        return ClearTarget.UNKNOWN;
    }

    private static String normalizeTarget(String rawTarget) {
        if (rawTarget == null) return "";
        String normalized = rawTarget.trim().toLowerCase(Locale.ROOT);
        return normalized.replace('_', '-');
    }

    private static StashMover module() {
        return Modules.get().get(StashMover.class);
    }

    private enum CaptureAction {
        PEARL_CHEST("pearlchest", StashMover::capturePearlChestFromCrosshair),
        LOOT_CHEST("lootchest", StashMover::captureLootChestFromCrosshair),
        CHAMBER("chamber", StashMover::captureChamberFromCrosshair),
        PEARL_TARGET("pearltarget", StashMover::capturePearlTargetFromCrosshair),
        WATER("water", StashMover::captureWaterFromPlayer);

        private final String literal;
        private final Function<StashMover, String> executor;

        CaptureAction(String literal, Function<StashMover, String> executor) {
            this.literal = literal;
            this.executor = executor;
        }

        String literal() {
            return literal;
        }

        String execute(StashMover module) {
            return executor.apply(module);
        }
    }

    private enum ClearTarget {
        PEARL_CHEST("pearlchest", "pearl-chest"),
        LOOT_CHEST("lootchest", "loot-chest"),
        CHAMBER("chamber"),
        PEARL_TARGET("pearltarget", "pearl-target"),
        WATER("water"),
        UNKNOWN("");

        private final String canonicalKey;
        private final String[] aliases;

        ClearTarget(String canonicalKey, String... aliases) {
            this.canonicalKey = canonicalKey;
            this.aliases = aliases;
        }

        String canonicalKey() {
            return canonicalKey;
        }

        boolean matches(String normalized) {
            if (canonicalKey.equals(normalized)) return true;
            for (String alias : aliases) {
                if (alias.equals(normalized)) return true;
            }
            return false;
        }
    }
}
