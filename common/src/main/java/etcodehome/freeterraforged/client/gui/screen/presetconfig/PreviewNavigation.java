package etcodehome.freeterraforged.client.gui.screen.presetconfig;

import java.util.function.Supplier;

import org.lwjgl.glfw.GLFW;

import etcodehome.freeterraforged.world.worldgen.search.BiomeSearchTarget;
import etcodehome.freeterraforged.world.worldgen.search.TerrainSearchTargets;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;

/** Shared native input controls and command grammar for FTF preview navigation. */
final class PreviewNavigation {
    private static final String KEY = "freeterraforged.gui.preset_preview.";
    private static final int BUTTON_WIDTH = 42;
    private static final int GAP = 4;

    private final Handler handler;
	private final Context context;
    private final EditBox input;
    private final Button submit;
	private final PreviewNavigationSuggestions suggestions;
    private final Component help;

	static PreviewNavigation forPresetEditor(
		Font font,
		int x,
		int width,
		ColumnLayout layout,
		Supplier<RegistryAccess> registries,
		Handler handler
	) {
        return new PreviewNavigation(
            font,
            x,
            layout.navigationY(),
            width,
			Context.PRESET_EDITOR,
			registries,
            handler
        );
    }

	static PreviewNavigation forInWorldPreview(
		Font font,
		int x,
		int width,
		ColumnLayout layout,
		Supplier<RegistryAccess> registries,
		Handler handler
	) {
		return new PreviewNavigation(
			font,
			x,
			layout.navigationY(),
			width,
			Context.IN_WORLD,
			registries,
			handler
		);
	}

	private PreviewNavigation(
		Font font,
		int x,
		int y,
		int width,
		Context context,
		Supplier<RegistryAccess> registries,
		Handler handler
	) {
        this.handler = handler;
		this.context = context;
		this.help = Component.translatableWithFallback(
			KEY + (context == Context.IN_WORLD ? "navigation_help" : "editor_navigation_help"),
			context == Context.IN_WORLD
				? "Enter here, spawn, x z, terrain <name>, or biome <namespaced-id-or-#tag>. Press Enter or Go."
				: "Enter x z, terrain <name>, or biome <namespaced-id-or-#tag>. Press Enter or Go."
		);

        int inputWidth = Math.max(20, width - BUTTON_WIDTH - GAP);
        this.input = new EditBox(
            font, x, y, inputWidth, 20,
            Component.translatableWithFallback(KEY + "navigation", "Preview navigation")
        ) {
            @Override
            public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
				if (PreviewNavigation.this.suggestions.handleInputKey(keyCode)) {
					return true;
				}
                if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                    PreviewNavigation.this.submit();
                    return true;
                }
				boolean handled = super.keyPressed(keyCode, scanCode, modifiers);
				PreviewNavigation.this.suggestions.refresh();
				return handled;
            }

			@Override
			public boolean mouseClicked(double mouseX, double mouseY, int button) {
				boolean handled = super.mouseClicked(mouseX, mouseY, button);
				PreviewNavigation.this.suggestions.refresh();
				return handled;
			}

			@Override
			public void setFocused(boolean focused) {
				super.setFocused(focused);
				if (PreviewNavigation.this.suggestions != null) {
					if (focused) {
						PreviewNavigation.this.suggestions.refresh();
					} else if (!PreviewNavigation.this.suggestions.isFocused()) {
						PreviewNavigation.this.suggestions.hide();
					}
				}
			}
        };
		this.input.setHint(ellipsize(
			font,
			Component.translatableWithFallback(KEY + "short_hint", "Search or enter x z…"),
			inputWidth - 8
		));
		this.input.setTooltip(Tooltip.create(this.help));
        this.input.setMaxLength(128);
		this.suggestions = new PreviewNavigationSuggestions(font, this.input, context, registries);
        this.input.setResponder(ignored -> {
            this.input.setTextColor(0xFFFFFF);
			this.input.setTooltip(Tooltip.create(this.help));
            this.handler.edited();
			this.suggestions.refresh();
        });
        this.submit = Button.builder(
            Component.translatableWithFallback(KEY + "go", "Go"), ignored -> this.submit()
        ).bounds(x + inputWidth + GAP, y, BUTTON_WIDTH, 20).build();
    }

    EditBox input() {
        return this.input;
    }

    Button submitButton() {
        return this.submit;
    }

	PreviewNavigationSuggestions suggestionsWidget() {
		return this.suggestions;
	}

	boolean mouseClicked(Screen screen, double mouseX, double mouseY, int button) {
		if (this.suggestions.mouseClicked(mouseX, mouseY, button)) {
			screen.setFocused(this.suggestions);
			if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
				screen.setDragging(true);
			}
			return true;
		}
		if (this.suggestions.dismissIfOutside(mouseX, mouseY)
			&& (screen.getFocused() == this.input || screen.getFocused() == this.suggestions)) {
			screen.clearFocus();
		}
		return false;
	}

	boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		return this.suggestions.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

    void pending() {
		this.feedback(0xFFFF55, "search_pending", "Searching the seed…");
    }

    void succeeded() {
		this.feedback(0x55FF55, "search_succeeded", "Match found; preview recentered.");
    }

	void searchSucceeded() {
		if (this.context == Context.PRESET_EDITOR) {
			this.feedback(0x55FF55, "search_spawn_selected", "Match found; spawn selected.");
		} else {
			this.succeeded();
		}
	}

    void failed() {
		this.feedback(0xFF5555, "search_failed", "No match was found before the search ended.");
	}

	void failed(Component status) {
		this.input.setTextColor(0xFF5555);
		this.input.setTooltip(Tooltip.create(Component.empty().append(status).append("\n").append(this.help)));
	}

	private void feedback(int color, String key, String fallback) {
		this.input.setTextColor(color);
		Component status = Component.translatableWithFallback(KEY + key, fallback);
		this.input.setTooltip(Tooltip.create(Component.empty().append(status).append("\n").append(this.help)));
    }

	private static Component ellipsize(Font font, Component text, int maxWidth) {
		String value = text.getString();
		if (font.width(value) <= maxWidth) {
			return text;
		}
		String ellipsis = "…";
		return Component.literal(
			font.plainSubstrByWidth(value, Math.max(0, maxWidth - font.width(ellipsis))) + ellipsis
		);
	}

    private void submit() {
        String value = this.input.getValue().trim();
		this.suggestions.hide();
		if (this.context.supportsHere() && value.equalsIgnoreCase("here")) {
            this.handler.here();
            return;
        }
		if (this.context.supportsSpawn() && value.equalsIgnoreCase("spawn")) {
            this.handler.spawn();
            return;
        }

        String[] operation = value.split("\\s+", 2);
        if (operation.length == 2 && operation[0].equalsIgnoreCase("terrain")) {
			String target = operation[1].trim();
			if (TerrainSearchTargets.get(target) == null) {
				this.reject(Component.literal("Unknown terrain type: " + target));
				return;
			}
			this.handler.terrain(target);
            return;
        }
        if (operation.length == 2 && operation[0].equalsIgnoreCase("biome")) {
			String target = operation[1].trim();
			try {
				BiomeSearchTarget.parse(target);
			} catch (IllegalArgumentException invalid) {
				this.reject(Component.literal(invalid.getMessage()));
				return;
			}
			this.handler.biome(target);
            return;
        }

        String[] coordinates = value.replace(',', ' ').trim().split("\\s+");
        if (coordinates.length == 2) {
            try {
                this.handler.coordinates(Integer.parseInt(coordinates[0]), Integer.parseInt(coordinates[1]));
                return;
            } catch (NumberFormatException ignored) {
            }
        }
		this.reject(Component.translatableWithFallback(
			KEY + (this.context == Context.IN_WORLD ? "invalid_navigation" : "editor_invalid_navigation"),
			this.context == Context.IN_WORLD
				? "Use: here, spawn, <x> <z>, terrain <name>, or biome <namespaced-id-or-#tag>"
				: "Use: <x> <z>, terrain <name>, or biome <namespaced-id-or-#tag>"
		));
    }

	private void reject(Component reason) {
		this.failed(reason);
		this.handler.invalid(reason);
	}

    interface Handler {
        default void edited() {
        }

        default void here() {
        }

		default void spawn() {
		}

        void coordinates(int x, int z);

        void terrain(String name);

        void biome(String id);

		default void invalid(Component reason) {
        }
    }

	enum Context {
		PRESET_EDITOR(false, false),
		IN_WORLD(true, true);

		private final boolean supportsHere;
		private final boolean supportsSpawn;

		Context(boolean supportsHere, boolean supportsSpawn) {
			this.supportsHere = supportsHere;
			this.supportsSpawn = supportsSpawn;
		}

		boolean supportsHere() {
			return this.supportsHere;
		}

		boolean supportsSpawn() {
			return this.supportsSpawn;
		}
	}

    record ColumnLayout(int navigationY, int zoomY, int modeY, int previewY) {
        private static final int ROW_STEP = 24;

        static ColumnLayout fromTop(int top) {
            return new ColumnLayout(top, top + ROW_STEP, top + ROW_STEP * 2, top + ROW_STEP * 3);
        }
    }
}
