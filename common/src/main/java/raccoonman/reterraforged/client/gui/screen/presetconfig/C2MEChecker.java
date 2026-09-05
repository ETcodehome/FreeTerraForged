package raccoonman.reterraforged.client.gui.screen.presetconfig;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import raccoonman.reterraforged.client.data.RTFTranslationKeys;

public class C2MEChecker {

    public static boolean isDfcActive() {
        return !isDfcDisabledSafely();
    }

    public static void initIncompatibilityScreen(PresetConfigScreen screen, Screen parentScreen) {
        int centerX = screen.width / 2;
        int centerY = screen.height / 2;

        // Button 1: Fix Config & Quit Game
        screen.addWidgetToScreen(Button.builder(Component.translatable(RTFTranslationKeys.GUI_BUTTON_C2ME_AUTO_PATCH), button -> {
            button.active = false;
            if (applyInstantFix()) {
                Minecraft.getInstance().stop();
            } else {
                button.setMessage(Component.translatable(RTFTranslationKeys.GUI_BUTTON_C2ME_FIX_FAILED));
            }
        }).bounds(centerX - 100, centerY + 40, 200, 20).build());

        // Button 2: Open Config Folder
        screen.addWidgetToScreen(Button.builder(Component.translatable(RTFTranslationKeys.GUI_BUTTON_OPEN_CONFIG_FOLDER), button -> {
            Path configFolder = Path.of("config");
            Util.ioPool().execute(() -> Util.getPlatform().openUri(configFolder.toUri()));
        }).bounds(centerX - 100, centerY + 65, 200, 20).build());

        // Button 3: Return To Menu
        screen.addWidgetToScreen(Button.builder(Component.translatable(RTFTranslationKeys.GUI_BUTTON_RETURN_TO_MENU), button -> {
            Minecraft.getInstance().setScreen(parentScreen);
        }).bounds(centerX - 100, centerY + 90, 200, 20).build());
    }

    public static void renderIncompatibilityOverlay(GuiGraphics guiGraphics, Font screenFont, int width, int height) {
        guiGraphics.fillGradient(0, 0, width, height, 0xC0101010, 0xD0101010);

        int centerX = width / 2;
        int centerY = height / 2;

        Font font = screenFont != null ? screenFont : Minecraft.getInstance().font;

        // Shifted upward to leave ~40px of negative space between the text block and top button
        guiGraphics.drawCenteredString(font, Component.translatable(RTFTranslationKeys.GUI_C2ME_TITLE), centerX, centerY - 95, 0xFFFFFFFF);
        guiGraphics.drawCenteredString(font, Component.translatable(RTFTranslationKeys.GUI_C2ME_DESC_LINE_1), centerX, centerY - 70, 0xFFDDDDDD);
        guiGraphics.drawCenteredString(font, Component.translatable(RTFTranslationKeys.GUI_C2ME_DESC_LINE_2), centerX, centerY - 55, 0xFFDDDDDD);

        guiGraphics.drawCenteredString(font, Component.translatable(RTFTranslationKeys.GUI_C2ME_CHANGE_REQUIRED), centerX, centerY - 30, 0xFFFFFFFF);
        guiGraphics.drawCenteredString(font, Component.translatable(RTFTranslationKeys.GUI_C2ME_INSTRUCTION), centerX, centerY - 15, 0xFFFFFFFF);
        guiGraphics.drawCenteredString(font, Component.translatable(RTFTranslationKeys.GUI_C2ME_RESTART_REQUIRED), centerX, centerY - 2, 0xFFAAAAAA);
    }

    public static boolean applyInstantFix() {
        Path configPath = Path.of("config", "c2me.toml");
        try {
            if (!Files.exists(configPath)) {
                Files.createDirectories(configPath.getParent());
                String defaultContent = "[vanillaWorldGenOptimizations]\nuseDensityFunctionCompiler = false\n";
                Files.writeString(configPath, defaultContent);
                return true;
            }

            List<String> lines = Files.readAllLines(configPath);
            List<String> newLines = new ArrayList<>();
            boolean inTargetSection = false;
            boolean keyFound = false;
            boolean sectionFound = false;

            for (String line : lines) {
                String trimmed = line.trim();

                if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                    if (inTargetSection && !keyFound) {
                        newLines.add("useDensityFunctionCompiler = false");
                        keyFound = true;
                    }
                    inTargetSection = trimmed.equalsIgnoreCase("[vanillaWorldGenOptimizations]");
                    if (inTargetSection) {
                        sectionFound = true;
                    }
                }

                if (inTargetSection && trimmed.startsWith("useDensityFunctionCompiler")) {
                    newLines.add("useDensityFunctionCompiler = false");
                    keyFound = true;
                    continue;
                }

                newLines.add(line);
            }

            if (inTargetSection && !keyFound) {
                newLines.add("useDensityFunctionCompiler = false");
                keyFound = true;
            }

            if (!sectionFound) {
                newLines.add("");
                newLines.add("[vanillaWorldGenOptimizations]");
                newLines.add("useDensityFunctionCompiler = false");
            }

            Files.write(configPath, newLines);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isDfcDisabledSafely() {
        try {
            String value = getC2MEDensityCompilerSetting();
            return "false".equalsIgnoreCase(value);
        } catch (Exception e) {
            return false;
        }
    }

    private static String getC2MEDensityCompilerSetting() {
        try {
            Path configFolder = Path.of("config");
            Path configPath = configFolder.resolve("c2me.toml");

            if (!Files.exists(configPath)) {
                return "false";
            }

            try (BufferedReader reader = Files.newBufferedReader(configPath)) {
                String line;
                boolean inTargetSection = false;

                while ((line = reader.readLine()) != null) {
                    line = line.trim();

                    if (line.startsWith("[") && line.endsWith("]")) {
                        inTargetSection = line.equalsIgnoreCase("[vanillaWorldGenOptimizations]");
                        continue;
                    }

                    if (inTargetSection && line.startsWith("useDensityFunctionCompiler")) {
                        String[] parts = line.split("=", 2);
                        if (parts.length == 2) {
                            return parts[1].split("#")[0].trim().replace("\"", "").replace("'", "").toLowerCase();
                        }
                    }
                }
            }
        } catch (Exception e) {
            return "error_fallback";
        }
        return "false";
    }
}