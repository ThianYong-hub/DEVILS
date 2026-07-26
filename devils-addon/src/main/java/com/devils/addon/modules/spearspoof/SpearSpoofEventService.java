package com.devils.addon.modules.spearspoof;

import com.devils.addon.modules.SpearSpoof;
import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.entity.LivingEntity;

public final class SpearSpoofEventService {
    private final SpearSpoof module;
    private final SpearSpoofRuntime runtime;
    private final SpearSpoofCombatService combatService;
    private final SpearSpoofFlightService flightService;
    private final SpearSpoofDevDebugService devDebugService;
    private final SpearSpoofDebugLogger debugLogger;
    private final Setting<Boolean> devDebug;
    private final Setting<Boolean> debugPacketLog;
    private final Setting<Boolean> renderTarget;
    private final Setting<Double> nearRange;
    private final Setting<SettingColor> nearColor;
    private final Setting<SettingColor> farColor;

    public SpearSpoofEventService(
        SpearSpoof module,
        SpearSpoofRuntime runtime,
        SpearSpoofCombatService combatService,
        SpearSpoofFlightService flightService,
        SpearSpoofDevDebugService devDebugService,
        SpearSpoofDebugLogger debugLogger,
        Setting<Boolean> devDebug,
        Setting<Boolean> debugPacketLog,
        Setting<Boolean> renderTarget,
        Setting<Double> nearRange,
        Setting<SettingColor> nearColor,
        Setting<SettingColor> farColor
    ) {
        this.module = module;
        this.runtime = runtime;
        this.combatService = combatService;
        this.flightService = flightService;
        this.devDebugService = devDebugService;
        this.debugLogger = debugLogger;
        this.devDebug = devDebug;
        this.debugPacketLog = debugPacketLog;
        this.renderTarget = renderTarget;
        this.nearRange = nearRange;
        this.nearColor = nearColor;
        this.farColor = farColor;
    }

    public void onRender3DSafe(Render3DEvent event) {
        if (!renderTarget.get() || module.client().player == null || module.client().world == null) return;
        LivingEntity target = runtime.target;
        if (target == null || !target.isAlive()) return;

        double distanceSq = module.client().player.squaredDistanceTo(target);
        double farSq = 12.0 * 12.0;
        double nearSq = nearRange.get() * nearRange.get();

        if (distanceSq > farSq) return;

        SettingColor settingColor = distanceSq <= nearSq ? nearColor.get() : farColor.get();
        Color side = new Color(settingColor);
        Color line = new Color(settingColor).a(220);
        event.renderer.box(target.getBoundingBox().expand(0.1), side, line, ShapeMode.Both, 0);
    }

    public void onTickSafe() {
        if (devDebug.get()) {
            devDebugService.onTick();
            return;
        }

        devDebugService.onDisable();

        combatService.onTick();
        flightService.onTick();
    }

    public void onMoveSafe(PlayerMoveEvent event) {
        if (devDebug.get()) return;
        flightService.onMove(event);
    }

    public void onPacketSendSafe(PacketEvent.Send event) {
        if (event == null || event.packet == null) return;
        if (devDebug.get()) devDebugService.onPacketSend(event.packet);
        if (!SpearSpoofPacketInspector.isInterestingSendPacket(event.packet)) return;
        debugLogger.logPacketSend(event.packet, SpearSpoofPacketInspector.describeSendPacket(event.packet), runtime);
    }

    public void onPacketReceiveSafe(PacketEvent.Receive event) {
        if (event == null || event.packet == null) return;
        if (devDebug.get()) devDebugService.onPacketReceive(event.packet);
        else combatService.onPacketReceive(event.packet);
        boolean interesting = SpearSpoofPacketInspector.isInterestingReceivePacket(event.packet);
        if (!interesting && !debugPacketLog.get()) return;
        debugLogger.logPacketReceive(event.packet, SpearSpoofPacketInspector.describeReceivePacket(event.packet), runtime);
    }
}
