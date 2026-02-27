package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;

public class VClip extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> distance = sgGeneral.add(new DoubleSetting.Builder()
        .name("distance")
        .description("Vertical distance to clip. Negative = down.")
        .defaultValue(-5.0)
        .min(-100.0)
        .max(100.0)
        .sliderRange(-20.0, 20.0)
        .build()
    );

    public VClip() {
        super(AddonTemplate.CATEGORY, "v-clip", "Instantly clips you vertically by a configured distance.");
    }

    @Override
    public void onActivate() {
        if (mc.player == null) {
            toggle();
            return;
        }

        mc.player.setPosition(mc.player.getX(), mc.player.getY() + distance.get(), mc.player.getZ());
        toggle();
    }
}
