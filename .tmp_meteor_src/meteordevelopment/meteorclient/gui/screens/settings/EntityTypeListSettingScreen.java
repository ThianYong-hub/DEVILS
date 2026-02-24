/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.gui.screens.settings;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.utils.Cell;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WSection;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WCheckbox;
import meteordevelopment.meteorclient.settings.EntityTypeListSetting;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.misc.Names;
import net.minecraft.class_1299;
import net.minecraft.class_3545;
import net.minecraft.class_7923;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

public class EntityTypeListSettingScreen extends WindowScreen {
    private final EntityTypeListSetting setting;

    private WVerticalList list;
    private final WTextBox filter;

    private String filterText = "";

    private WSection animals, waterAnimals, monsters, ambient, misc;
    private WTable animalsT, waterAnimalsT, monstersT, ambientT, miscT;
    int hasAnimal = 0, hasWaterAnimal = 0, hasMonster = 0, hasAmbient = 0, hasMisc = 0;

    public EntityTypeListSettingScreen(GuiTheme theme, EntityTypeListSetting setting) {
        super(theme, "Select entities");
        this.setting = setting;

        // Filter
        filter = super.add(theme.textBox("")).minWidth(400).expandX().widget();
        filter.setFocused(true);
        filter.action = () -> {
            filterText = filter.get().trim();

            list.clear();
            initWidgets();
        };

        list = super.add(theme.verticalList()).expandX().widget();

    }

    @Override
    public <W extends WWidget> Cell<W> add(W widget) {
        return list.add(widget);
    }

    @Override
    public void initWidgets() {
        hasAnimal = hasWaterAnimal = hasMonster = hasAmbient = hasMisc = 0;

        for (class_1299<?> entityType : setting.get()) {
            if (setting.filter == null || setting.filter.test(entityType)) {
                switch (entityType.method_5891()) {
                    case field_6294 -> hasAnimal++;
                    case field_24460, field_6300, field_30092, field_34447 -> hasWaterAnimal++;
                    case field_6302 -> hasMonster++;
                    case field_6303 -> hasAmbient++;
                    case field_17715 -> hasMisc++;
                }
            }
        }

        boolean first = animals == null;

        // Animals
        List<class_1299<?>> animalsE = new ArrayList<>();
        WCheckbox animalsC = theme.checkbox(hasAnimal > 0);

        animals = theme.section("Animals", animals != null && animals.isExpanded(), animalsC);
        animalsC.action = () -> tableChecked(animalsE, animalsC.checked);

        Cell<WSection> animalsCell = add(animals).expandX();
        animalsT = animals.add(theme.table()).expandX().widget();

        // Water animals
        List<class_1299<?>> waterAnimalsE = new ArrayList<>();
        WCheckbox waterAnimalsC = theme.checkbox(hasWaterAnimal > 0);

        waterAnimals = theme.section("Water Animals", waterAnimals != null && waterAnimals.isExpanded(), waterAnimalsC);
        waterAnimalsC.action = () -> tableChecked(waterAnimalsE, waterAnimalsC.checked);

        Cell<WSection> waterAnimalsCell = add(waterAnimals).expandX();
        waterAnimalsT = waterAnimals.add(theme.table()).expandX().widget();

        // Monsters
        List<class_1299<?>> monstersE = new ArrayList<>();
        WCheckbox monstersC = theme.checkbox(hasMonster > 0);

        monsters = theme.section("Monsters", monsters != null && monsters.isExpanded(), monstersC);
        monstersC.action = () -> tableChecked(monstersE, monstersC.checked);

        Cell<WSection> monstersCell = add(monsters).expandX();
        monstersT = monsters.add(theme.table()).expandX().widget();

        // Ambient
        List<class_1299<?>> ambientE = new ArrayList<>();
        WCheckbox ambientC = theme.checkbox(hasAmbient > 0);

        ambient = theme.section("Ambient", ambient != null && ambient.isExpanded(), ambientC);
        ambientC.action = () -> tableChecked(ambientE, ambientC.checked);

        Cell<WSection> ambientCell = add(ambient).expandX();
        ambientT = ambient.add(theme.table()).expandX().widget();

        // Misc
        List<class_1299<?>> miscE = new ArrayList<>();
        WCheckbox miscC = theme.checkbox(hasMisc > 0);

        misc = theme.section("Misc", misc != null && misc.isExpanded(), miscC);
        miscC.action = () -> tableChecked(miscE, miscC.checked);

        Cell<WSection> miscCell = add(misc).expandX();
        miscT = misc.add(theme.table()).expandX().widget();

        Consumer<class_1299<?>> entityTypeForEach = entityType -> {
            if (setting.filter == null || setting.filter.test(entityType)) {
                switch (entityType.method_5891()) {
                    case field_6294 -> {
                        animalsE.add(entityType);
                        addEntityType(animalsT, animalsC, entityType);
                    }
                    case field_24460, field_6300, field_30092, field_34447 -> {
                        waterAnimalsE.add(entityType);
                        addEntityType(waterAnimalsT, waterAnimalsC, entityType);
                    }
                    case field_6302 -> {
                        monstersE.add(entityType);
                        addEntityType(monstersT, monstersC, entityType);
                    }
                    case field_6303 -> {
                        ambientE.add(entityType);
                        addEntityType(ambientT, ambientC, entityType);
                    }
                    case field_17715 -> {
                        miscE.add(entityType);
                        addEntityType(miscT, miscC, entityType);
                    }
                }
            }
        };

        // Sort all entities
        if (filterText.isEmpty()) {
            class_7923.field_41177.forEach(entityTypeForEach);
        } else {
            List<class_3545<class_1299<?>, Integer>> entities = new ArrayList<>();
            class_7923.field_41177.forEach(entity -> {
                int words = Utils.searchInWords(Names.get(entity), filterText);
                int diff = Utils.searchLevenshteinDefault(Names.get(entity), filterText, false);

                if (words > 0 || diff < Names.get(entity).length() / 2) entities.add(new class_3545<>(entity, -diff));
            });
            entities.sort(Comparator.comparingInt(value -> -value.method_15441()));
            for (class_3545<class_1299<?>, Integer> pair : entities) entityTypeForEach.accept(pair.method_15442());
        }

        if (animalsT.cells.isEmpty()) list.cells.remove(animalsCell);
        if (waterAnimalsT.cells.isEmpty()) list.cells.remove(waterAnimalsCell);
        if (monstersT.cells.isEmpty()) list.cells.remove(monstersCell);
        if (ambientT.cells.isEmpty()) list.cells.remove(ambientCell);
        if (miscT.cells.isEmpty()) list.cells.remove(miscCell);

        if (first) {
            int totalCount = (hasWaterAnimal + waterAnimals.cells.size() + monsters.cells.size() + ambient.cells.size() + misc.cells.size()) / 2;

            if (totalCount <= 20) {
                if (!animalsT.cells.isEmpty()) animals.setExpanded(true);
                if (!waterAnimalsT.cells.isEmpty()) waterAnimals.setExpanded(true);
                if (!monstersT.cells.isEmpty()) monsters.setExpanded(true);
                if (!ambientT.cells.isEmpty()) ambient.setExpanded(true);
                if (!miscT.cells.isEmpty()) misc.setExpanded(true);
            }
            else {
                if (!animalsT.cells.isEmpty()) animals.setExpanded(false);
                if (!waterAnimalsT.cells.isEmpty()) waterAnimals.setExpanded(false);
                if (!monstersT.cells.isEmpty()) monsters.setExpanded(false);
                if (!ambientT.cells.isEmpty()) ambient.setExpanded(false);
                if (!miscT.cells.isEmpty()) misc.setExpanded(false);
            }
        }
    }

    private void tableChecked(List<class_1299<?>> entityTypes, boolean checked) {
        boolean changed = false;

        for (class_1299<?> entityType : entityTypes) {
            if (checked) {
                setting.get().add(entityType);
                changed = true;
            } else {
                if (setting.get().remove(entityType)) {
                    changed = true;
                }
            }
        }

        if (changed) {
            list.clear();
            initWidgets();
            setting.onChanged();
        }
    }

    private void addEntityType(WTable table, WCheckbox tableCheckbox, class_1299<?> entityType) {
        table.add(theme.label(Names.get(entityType)));

        WCheckbox a = table.add(theme.checkbox(setting.get().contains(entityType))).expandCellX().right().widget();
        a.action = () -> {
            if (a.checked) {
                setting.get().add(entityType);
                switch (entityType.method_5891()) {
                    case field_6294 -> {
                        if (hasAnimal == 0) tableCheckbox.checked = true;
                        hasAnimal++;
                    }
                    case field_24460, field_6300, field_30092, field_34447 -> {
                        if (hasWaterAnimal == 0) tableCheckbox.checked = true;
                        hasWaterAnimal++;
                    }
                    case field_6302 -> {
                        if (hasMonster == 0) tableCheckbox.checked = true;
                        hasMonster++;
                    }
                    case field_6303 -> {
                        if (hasAmbient == 0) tableCheckbox.checked = true;
                        hasAmbient++;
                    }
                    case field_17715 -> {
                        if (hasMisc == 0) tableCheckbox.checked = true;
                        hasMisc++;
                    }
                }
            } else {
                if (setting.get().remove(entityType)) {
                    switch (entityType.method_5891()) {
                        case field_6294 -> {
                            hasAnimal--;
                            if (hasAnimal == 0) tableCheckbox.checked = false;
                        }
                        case field_24460, field_6300, field_30092, field_34447 -> {
                            hasWaterAnimal--;
                            if (hasWaterAnimal == 0) tableCheckbox.checked = false;
                        }
                        case field_6302 -> {
                            hasMonster--;
                            if (hasMonster == 0) tableCheckbox.checked = false;
                        }
                        case field_6303 -> {
                            hasAmbient--;
                            if (hasAmbient == 0) tableCheckbox.checked = false;
                        }
                        case field_17715 -> {
                            hasMisc--;
                            if (hasMisc == 0) tableCheckbox.checked = false;
                        }
                    }
                }
            }

            setting.onChanged();
        };

        table.row();
    }
}
