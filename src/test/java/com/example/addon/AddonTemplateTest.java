package com.example.addon;

import meteordevelopment.meteorclient.addons.GithubRepo;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AddonTemplateTest {
    @Test
    void packageNameIsCorrect() {
        AddonTemplate addon = new AddonTemplate();
        assertEquals("com.example.addon", addon.getPackage());
    }

    @Test
    void repoContainsExpectedOwnerAndName() throws IllegalAccessException {
        GithubRepo repo = new AddonTemplate().getRepo();
        assertNotNull(repo);

        List<String> stringFields = new ArrayList<>();
        for (Field field : repo.getClass().getDeclaredFields()) {
            if (field.getType() != String.class) continue;
            field.setAccessible(true);
            Object value = field.get(repo);
            if (value instanceof String s) stringFields.add(s);
        }

        assertTrue(stringFields.contains("MeteorDevelopment"));
        assertTrue(stringFields.contains("meteor-addon-template"));
    }
}
