package com.example.addon.commands;

import com.example.addon.modules.AutoAnvilRename;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.command.CommandSource;

public class AutoAnvilRenameCommand extends Command {
    public AutoAnvilRenameCommand() {
        super("autoraname", "Sets AutoAnvilRename options");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        builder.then(literal("setname").then(argument("name", StringArgumentType.greedyString()).executes(ctx -> {
            String name = StringArgumentType.getString(ctx, "name");
            AutoAnvilRename module = Modules.get().get(AutoAnvilRename.class);
            if (module != null) {
                module.setRenameText(name);
                info("Set rename text to: " + name);
            } else info("AutoAnvilRename module not found");
            return SINGLE_SUCCESS;
        })));
        builder.then(literal("selectid").then(argument("id", StringArgumentType.word()).executes(ctx -> {
            String id = StringArgumentType.getString(ctx, "id");
            AutoAnvilRename module = Modules.get().get(AutoAnvilRename.class);
            if (module != null) {
                module.setSelectId(id);
                info("Set selective id to: " + id);
            } else info("AutoAnvilRename module not found");
            return SINGLE_SUCCESS;
        })));
    }
}
