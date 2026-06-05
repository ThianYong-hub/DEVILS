package com.devils.addon.modules;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoLoginSourceTest {
    @Test
    void autoLoginWidgetExposesManualLoginButton() throws IOException {
        String autoLoginSource = Files.readString(Path.of(
            "src", "main", "java", "com", "devils", "addon", "modules", "AutoLogin.java"
        ));
        String storeSource = Files.readString(Path.of(
            "src", "main", "java", "com", "devils", "addon", "modules", "autologin", "AutoLoginProfileStore.java"
        ));

        assertTrue(autoLoginSource.contains("this::loginSavedProfile"));
        assertTrue(autoLoginSource.contains("public boolean loginSavedProfile(AutoLoginProfile profile)"));
        assertTrue(storeSource.contains("theme.button(\"Login\")"));
        assertTrue(storeSource.contains("loginAction.accept(profile);"));
        assertTrue(storeSource.contains("fillWidget(theme, list, currentUsernameSupplier, currentServerKeySupplier, newEntryDelaySupplier, loginAction);"));
        assertTrue(storeSource.contains("private static final Color ACTIVE_ROW_BACKGROUND_COLOR = new Color(120, 18, 32, 52);"));
        assertTrue(storeSource.contains("new ProfileRow(currentSessionProfile)"));
        assertTrue(storeSource.contains("AutoLoginTextRules.matchesKey("));
        assertTrue(storeSource.contains("usernameLabel.color(new Color(ACTIVE_PRIMARY_TEXT_COLOR));"));
        assertTrue(storeSource.contains("serverLabel.color(new Color(ACTIVE_SECONDARY_TEXT_COLOR));"));
        assertTrue(storeSource.contains("new ActiveProfileButton(\"Login\", null)"));
        assertTrue(storeSource.contains("private static final class ActiveProfileMinus extends WMeteorMinus"));
    }
}
