package raccoonman.reterraforged.client.gui.screen.presetconfig;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import org.apache.commons.compress.utils.FileNameUtils;
import org.jetbrains.annotations.Nullable;

import com.google.gson.JsonParser;
import com.google.gson.stream.JsonWriter;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;

import io.netty.util.internal.StringUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.toasts.SystemToast.SystemToastId;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.GsonHelper;
import raccoonman.reterraforged.RTFCommon;
import raccoonman.reterraforged.client.data.RTFTranslationKeys;
import raccoonman.reterraforged.client.gui.Toasts;
import raccoonman.reterraforged.client.gui.screen.page.BisectedPage;
import raccoonman.reterraforged.client.gui.screen.page.LinkedPageScreen.Page;
import raccoonman.reterraforged.client.gui.widget.Label;
import raccoonman.reterraforged.client.gui.widget.WidgetList;
import raccoonman.reterraforged.client.gui.widget.WidgetList.Entry;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;
import raccoonman.reterraforged.data.worldgen.preset.settings.Presets;
import raccoonman.reterraforged.platform.ConfigUtil;

class PresetListPage extends BisectedPage<PresetConfigScreen, AbstractWidget, AbstractWidget> {
	private static final Path PRESET_PATH = ConfigUtil.rtf("presets");
	private static final Path EXPORT_PATH = ConfigUtil.rtf("exports");
	private static final Path LEGACY_PRESET_PATH = ConfigUtil.legacy("presets");

	private static final Predicate<String> IS_VALID = Pattern.compile("^[A-Za-z0-9\\-_ ]+$").asPredicate();
	private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm");

	private EditBox input;
	private Button createPreset;
	private Button renamePreset;
	private Button copyPreset;
	private Button deletePreset;
	private Button openPresetFolder;
	private Button openExportFolder;
	private Button exportAsDatapack;

	private WrappedLabel selectionDetails;

	public PresetListPage(PresetConfigScreen screen) {
		super(screen);

		try {
			if(!Files.exists(PRESET_PATH)) Files.createDirectory(PRESET_PATH);
			if(!Files.exists(EXPORT_PATH)) Files.createDirectory(EXPORT_PATH);
		} catch(IOException e) {
			e.printStackTrace();
		}
	}

	@Override
	public Component title() {
		return Component.translatable(RTFTranslationKeys.GUI_SELECT_PRESET_TITLE);
	}

	@Override
	public void init() {
		super.init();

		// Change screen "Next" button label to "Edit"
		this.screen.nextButton.setMessage(Component.literal("Edit"));

		// Input field
		this.input = PresetWidgets.createEditBox(this.screen.font, (text) -> {
			String trimmed = text.trim();
			boolean isValid = this.isValidPresetName(trimmed);
			final int white = 14737632;
			final int red = 0xFFFF3F30;

			this.createPreset.active = isValid;

			Entry<AbstractWidget> selected = this.left.getSelected();
			boolean isCustomSelected = selected != null && selected.getWidget() instanceof PresetEntry entry && !entry.isBuiltin();
			this.renamePreset.active = isCustomSelected && isValid;

			this.input.setTextColor(text.isEmpty() || isValid ? white : red);
		}, Component.translatable(RTFTranslationKeys.GUI_INPUT_PROMPT).withStyle(ChatFormatting.DARK_GRAY));

		// Action Buttons
		this.createPreset = PresetWidgets.createThrowingButton(RTFTranslationKeys.GUI_BUTTON_CREATE, () -> {
			String name = this.input.getValue().trim();
			PresetEntry newEntry = new PresetEntry(name, Component.literal(name).withStyle(ChatFormatting.GOLD), Presets.makeRTFDefault(), false, this);
			newEntry.save();
			this.input.setValue(StringUtil.EMPTY_STRING);
			this.rebuildPresets(name);
		});

		this.renamePreset = PresetWidgets.createThrowingButton("Rename Selected", () -> {
			Entry<AbstractWidget> selected = this.left.getSelected();
			if (selected != null && selected.getWidget() instanceof PresetEntry entry && !entry.isBuiltin()) {
				String newName = this.input.getValue().trim();
				if (this.isValidPresetName(newName)) {
					Path oldPath = entry.getPath();
					PresetEntry renamedEntry = new PresetEntry(newName, Component.literal(newName).withStyle(ChatFormatting.GOLD), entry.getPreset(), false, this);
					renamedEntry.save();
					if (Files.exists(oldPath)) {
						Files.delete(oldPath);
					}
					this.input.setValue(StringUtil.EMPTY_STRING);
					this.rebuildPresets(newName);
					Toasts.notify(
							RTFTranslationKeys.GUI_SELECT_PRESET_TITLE,
							Component.literal("Renamed to " + newName),
							SystemToastId.WORLD_BACKUP
					);
				}
			}
		});

		this.copyPreset = PresetWidgets.createThrowingButton(RTFTranslationKeys.GUI_BUTTON_COPY, () -> {
			if (this.left.getSelected() != null && this.left.getSelected().getWidget() instanceof PresetEntry preset) {
				String baseName = preset.getRawName();
				String timestamp = LocalDateTime.now().format(DATE_TIME_FORMATTER);
				String copyName = this.findUniqueName(baseName + " " + timestamp);
				new PresetEntry(copyName, Component.literal(copyName).withStyle(ChatFormatting.GOLD), preset.getPreset().copy(), false, this).save();
				this.rebuildPresets(copyName);
			}
		});

		this.deletePreset = PresetWidgets.createThrowingButton(RTFTranslationKeys.GUI_BUTTON_DELETE, () -> {
			if (this.left.getSelected() != null && this.left.getSelected().getWidget() instanceof PresetEntry preset && !preset.isBuiltin()) {
				Files.deleteIfExists(preset.getPath());
				this.rebuildPresets();
			}
		});

		this.openPresetFolder = PresetWidgets.createThrowingButton(RTFTranslationKeys.GUI_BUTTON_OPEN_PRESET_FOLDER, () -> {
			Util.getPlatform().openUri(PRESET_PATH.toUri());
			this.rebuildPresets();
		});

		this.openExportFolder = PresetWidgets.createThrowingButton(RTFTranslationKeys.GUI_BUTTON_OPEN_EXPORT_FOLDER, () -> {
			Util.getPlatform().openUri(EXPORT_PATH.toUri());
			this.rebuildPresets();
		});

		this.exportAsDatapack = PresetWidgets.createThrowingButton(RTFTranslationKeys.GUI_BUTTON_EXPORT_AS_DATAPACK, () -> {
			if (this.left.getSelected() != null && this.left.getSelected().getWidget() instanceof PresetEntry preset) {
				Path path = EXPORT_PATH.resolve(preset.getRawName() + ".zip");
				this.screen.exportAsDatapack(path, preset);
				this.rebuildPresets();
				Toasts.notify(RTFTranslationKeys.GUI_BUTTON_EXPORT_SUCCESS, Component.literal(path.toString()), SystemToastId.WORLD_BACKUP);
			}
		});

		// Information & Description Labels (At bottom of right panel)
		this.selectionDetails = new WrappedLabel(this.screen.font);

		// Right panel hierarchy: Controls at top, description at bottom
		this.right.addWidget(this.input);
		this.right.addWidget(this.createPreset);
		this.right.addWidget(this.renamePreset);
		this.right.addWidget(this.copyPreset);
		this.right.addWidget(this.deletePreset);
		this.right.addWidget(this.openPresetFolder);
		this.right.addWidget(this.openExportFolder);
		this.right.addWidget(this.exportAsDatapack);
		this.right.addWidget(this.selectionDetails);

		this.left.setRenderSelected(true);

		try {
			this.rebuildPresets();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	@Override
	public Optional<Page> previous() {
		return Optional.empty();
	}

	@Override
	public Optional<Page> next() {
		return Optional.ofNullable(this.left).map(WidgetList::getSelected).map(Entry::getWidget).filter(w -> w instanceof PresetEntry).map(w -> (PresetEntry) w).map((entry) -> {
			if(entry.isBuiltin()) {
				String timestamp = LocalDateTime.now().format(DATE_TIME_FORMATTER);
				String presetName = this.findUniqueName(entry.getRawName() + " " + timestamp);
				PresetEntry customEntry = new PresetEntry(
						presetName,
						Component.literal(presetName).withStyle(ChatFormatting.GOLD),
						entry.getPreset().copy(),
						false,
						this
				);
				try {
					customEntry.save();
					Toasts.notify(
							RTFTranslationKeys.GUI_SELECT_PRESET_TITLE,
							Component.literal("Created default preset: " + presetName),
							SystemToastId.WORLD_BACKUP
					);
				} catch (IOException e) {
					RTFCommon.LOGGER.error("Failed to auto-create preset from template", e);
				}
				return new WorldSettingsPage(this.screen, customEntry);
			}
			return new WorldSettingsPage(this.screen, entry);
		});
	}

	@Override
	public void onSave() {
		super.onSave();

		Entry<AbstractWidget> selected = this.left.getSelected();
		if(selected != null && selected.getWidget() instanceof PresetEntry presetEntry) {
			try {
				this.screen.applyPreset(presetEntry);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	private void selectPreset(@Nullable PresetEntry entry) {
		boolean active = entry != null;
		boolean isCustom = active && !entry.isBuiltin();

		this.screen.doneButton.active = active;
		this.screen.nextButton.active = active;
		this.copyPreset.active = active;
		this.exportAsDatapack.active = active;
		this.deletePreset.active = isCustom;

		String inputText = this.input != null ? this.input.getValue().trim() : "";
		boolean validName = !inputText.isEmpty() && this.isValidPresetName(inputText);
		if(this.createPreset != null) this.createPreset.active = validName;
		if(this.renamePreset != null) this.renamePreset.active = isCustom && validName;

		if(this.selectionDetails != null) {
			if(entry == null) {
				this.selectionDetails.setText(Component.literal("Select a preset from the left panel to use, modify or export.").withStyle(ChatFormatting.DARK_GRAY));
			} else {

				if(entry.isBuiltin()) {
					this.selectionDetails.setText(Component.literal("★ Template Preset (Read Only)\nEdit will duplicate this template to a fresh user preset and will save any settings edits to it.").withStyle(ChatFormatting.GRAY));
				} else {
					this.selectionDetails.setText(Component.literal("✎ Custom User Preset (Editable)\nChanges made when editing settings will be saved to this preset.").withStyle(ChatFormatting.GOLD));
				}
			}
		}
	}

	private void rebuildPresets() throws IOException {
		this.rebuildPresets(null);
	}

	private void rebuildPresets(@Nullable String selectPresetName) throws IOException {
		this.selectPreset(null);

		List<AbstractWidget> widgets = new ArrayList<>();

		// Section 1: User Presets (Your Presets)
		widgets.add(new CategoryHeader(Component.literal("Your Presets").withStyle(ChatFormatting.GOLD)));
		List<PresetEntry> userPresets = new ArrayList<>();
		userPresets.addAll(this.listPresets(PRESET_PATH));
		userPresets.addAll(this.listPresets(LEGACY_PRESET_PATH));

		if (userPresets.isEmpty()) {
			widgets.add(new EmptyNoticeLabel(Component.literal("No custom presets created yet").withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC)));
		} else {
			widgets.addAll(userPresets);
		}

		// Empty unselectable spacer entry between Sections
		widgets.add(new Spacer(12));

		// Section 2: Templates (Included Templates - Standard Light Grey)
		widgets.add(new CategoryHeader(Component.literal("Included Templates").withStyle(ChatFormatting.GRAY)));

		// Modern Patterns
		widgets.add(createTemplateEntry(Component.translatable(RTFTranslationKeys.GUI_RIVERS_PRESET_NAME), Presets.modernDefaultWithRivers(), ChatFormatting.GRAY));

		// Community Patterns
		widgets.add(createTemplateEntry(Component.translatable(RTFTranslationKeys.GUI_COMMUNITY1_PRESET_NAME), Presets.makeCommunityPreset1(), ChatFormatting.GRAY));

		// Legacy Patterns
		widgets.add(createTemplateEntry(Component.translatable(RTFTranslationKeys.GUI_DEFAULT_LEGACY_PRESET_NAME), Presets.makeLegacyDefault(), ChatFormatting.DARK_GRAY));
		widgets.add(createTemplateEntry(Component.translatable(RTFTranslationKeys.GUI_BEAUTIFUL_PRESET_NAME), Presets.makeLegacyBeautiful(), ChatFormatting.DARK_GRAY));
		widgets.add(createTemplateEntry(Component.translatable(RTFTranslationKeys.GUI_HUGE_BIOMES_PRESET_NAME), Presets.makeLegacyHugeBiomes(), ChatFormatting.DARK_GRAY));
		widgets.add(createTemplateEntry(Component.translatable(RTFTranslationKeys.GUI_LITE_PRESET_NAME), Presets.makeLegacyLite(), ChatFormatting.DARK_GRAY));
		widgets.add(createTemplateEntry(Component.translatable(RTFTranslationKeys.GUI_VANILLAISH_PRESET_NAME), Presets.makeLegacyVanillaish(), ChatFormatting.DARK_GRAY));

		this.left.replaceEntries(widgets.stream().map(WidgetList.Entry::new).toList());

		// Auto-select requested preset after rebuilding
		if (selectPresetName != null) {
			for (Entry<AbstractWidget> entry : this.left.children()) {
				if (entry.getWidget() instanceof PresetEntry presetEntry) {
					if (presetEntry.getRawName().equalsIgnoreCase(selectPresetName)) {
						this.left.setSelected(entry);
						this.selectPreset(presetEntry);
						break;
					}
				}
			}
		}
	}

	private PresetEntry createTemplateEntry(Component nameComponent, Preset preset, ChatFormatting nameColor) {
		String cleanName = nameComponent.getString();
		Component label = nameComponent.copy().withStyle(nameColor);
		return new PresetEntry(cleanName, label, preset, true, this);
	}

	private boolean isValidPresetName(String text) {
		return IS_VALID.test(text) && !this.hasPresetWithName(text);
	}

	private String findUniqueName(String baseName) {
		if (!this.hasPresetWithName(baseName) && !Files.exists(PRESET_PATH.resolve(baseName + ".json"))) {
			return baseName;
		}
		int counter = 1;
		String name;
		while (this.hasPresetWithName(name = baseName + " (" + counter + ")") || Files.exists(PRESET_PATH.resolve(name + ".json"))) {
			counter++;
		}
		return name;
	}

	private boolean hasPresetWithName(String name) {
		return this.left.children().stream().anyMatch((entry) -> {
			if (entry.getWidget() instanceof PresetEntry presetEntry) {
				return presetEntry.getRawName().equalsIgnoreCase(name);
			}
			return false;
		});
	}

	private List<PresetEntry> listPresets(Path path) throws IOException {
		List<PresetEntry> presets = new ArrayList<>();
		if(Files.exists(path)) {
			try (var stream = Files.list(path)) {
				for(Path presetPath : stream
						.filter(Files::isRegularFile)
						.filter(p -> p.toString().endsWith(".json"))
						.toList()
				) {
					try(Reader reader = Files.newBufferedReader(presetPath)) {
						String base = FileNameUtils.getBaseName(presetPath.toString());
						DataResult<Preset> result = Preset.DIRECT_CODEC.parse(JsonOps.INSTANCE, JsonParser.parseReader(reader));
						Preset preset = result.resultOrPartial(err -> {}).orElse(null);
						if(preset != null) {
							Component label = Component.literal(base).withStyle(ChatFormatting.GOLD);
							presets.add(new PresetEntry(base, label, preset, false, this));
						}
					} catch (Exception e) {
						// Silently ignore malformed files or reading hiccups
					}
				}
			}
		}
		return presets;
	}

	// Unselectable blank spacing widget
	public static class Spacer extends AbstractWidget {
		public Spacer(int height) {
			super(0, 0, 200, height, Component.empty());
			this.active = false;
		}

		@Override
		public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
			// Intentional no-op: invisible spacer
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {}
	}

	// Center-aligned category banner header without vertical marker bar
	public static class CategoryHeader extends AbstractWidget {
		private final Component title;

		public CategoryHeader(Component title) {
			super(0, 0, 200, 20, title);
			this.title = title;
			this.active = false;
		}

		@Override
		public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
			int x = this.getX();
			int y = this.getY();
			int w = this.getWidth();
			int h = this.getHeight();

			// Dark solid banner background
			graphics.fill(x, y, x + w, y + h, 0xFF141414);

			// Top/Bottom subtle divider lines
			graphics.fill(x, y, x + w, y + 1, 0xFF333333);
			graphics.fill(x, y + h - 1, x + w, y + h, 0xFF333333);

			// Center-aligned category title
			Font font = Minecraft.getInstance().font;
			graphics.drawCenteredString(font, this.title, x + w / 2, y + (h - 8) / 2, 0xFFFFFF);
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {}
	}

	// Center-aligned notice when user presets list is empty
	public static class EmptyNoticeLabel extends Label {
		public EmptyNoticeLabel(Component text) {
			super(-1, -1, -1, -1, (b) -> {}, text);
			this.active = false;
		}

		@Override
		public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
			Font font = Minecraft.getInstance().font;
			graphics.drawCenteredString(font, this.getMessage(), this.getX() + this.getWidth() / 2, this.getY() + (this.getHeight() - 8) / 2, 0x808080);
		}
	}

	// Wrapped label component that automatically wraps multiline description text to column width
	public static class WrappedLabel extends AbstractWidget {
		private final Font font;
		private Component text = Component.empty();

		public WrappedLabel(Font font) {
			super(0, 0, 100, 36, Component.empty());
			this.font = font;
			this.active = false;
		}

		public void setText(Component text) {
			this.text = text;
			int wrapWidth = Math.max(10, this.getWidth() - 8);
			List<FormattedCharSequence> lines = this.font.split(this.text, wrapWidth);
			this.setHeight(Math.max(24, lines.size() * 10 + 6));
		}

		@Override
		public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
			int wrapWidth = Math.max(10, this.getWidth() - 8);
			List<FormattedCharSequence> lines = this.font.split(this.text, wrapWidth);
			int y = this.getY() + 3;
			for (FormattedCharSequence line : lines) {
				graphics.drawString(this.font, line, this.getX() + 4, y, 0xFFFFFF);
				y += 10;
			}
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {}
	}

	// Center-aligned preset list entry with subtext tag underneath
	public static class PresetEntry extends Label {
		private String rawName;
		private Component displayName;
		private Preset preset;
		private boolean builtin;

		public PresetEntry(String rawName, Component displayName, Preset preset, boolean builtin, OnPress onPress) {
			super(-1, -1, -1, -1, onPress, displayName);
			this.rawName = rawName;
			this.displayName = displayName;
			this.preset = preset;
			this.builtin = builtin;
		}

		public PresetEntry(String rawName, Component displayName, Preset preset, boolean builtin, PresetListPage page) {
			this(rawName, displayName, preset, builtin, (b) -> {
				if(b instanceof PresetEntry entry) {
					page.selectPreset(entry);
				}
			});
		}

		public PresetEntry(Component displayName, Preset preset, boolean builtin, OnPress onPress) {
			this(displayName.getString(), displayName, preset, builtin, onPress);
		}

		public PresetEntry(Component displayName, Preset preset, boolean builtin, PresetListPage page) {
			this(displayName.getString(), displayName, preset, builtin, page);
		}

		public String getRawName() {
			return this.rawName;
		}

		public Component getName() {
			return this.displayName;
		}

		public Preset getPreset() {
			return this.preset;
		}

		public boolean isBuiltin() {
			return this.builtin;
		}

		public Path getPath() {
			return PRESET_PATH.resolve(this.rawName + ".json");
		}

		@Override
		public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
			Font font = Minecraft.getInstance().font;
			int x = this.getX();
			int y = this.getY();
			int w = this.getWidth();

			int textColor = this.isHoveredOrFocused() ? 0xFFFFFF : 0xE0E0E0;

			// 1. Main Title centered near top of entry slot
			graphics.drawCenteredString(font, this.getMessage(), x + w / 2, y + 2, textColor);

			// 2. Small subtext tag centered underneath
			Component subtext = this.builtin
					? Component.literal("Template Preset").withStyle(ChatFormatting.DARK_GRAY)
					: Component.literal("User Preset").withStyle(ChatFormatting.DARK_GRAY);

			graphics.pose().pushPose();
			graphics.pose().translate(x + w / 2.0f, y + 12.0f, 0.0f);
			graphics.pose().scale(0.6f, 0.6f, 0.8f);
			graphics.drawCenteredString(font, subtext, 0, 0, 0xFFFFFF);
			graphics.pose().popPose();
		}

		public void save() throws IOException {
			RTFCommon.LOGGER.info("Encoding Preset - {}", this.rawName);

			if (!this.builtin) {
				Path path = this.getPath();
				Path tempPath = path.resolveSibling(path.getFileName().toString() + ".tmp");

				Preset.DIRECT_CODEC.encodeStart(JsonOps.INSTANCE, this.preset)
						.resultOrPartial(error -> RTFCommon.LOGGER.error("Failed to encode preset: {}", error))
						.ifPresent(element -> {
							try (Writer writer = Files.newBufferedWriter(tempPath);
								 JsonWriter jsonWriter = new JsonWriter(writer)) {
								jsonWriter.setSerializeNulls(false);
								jsonWriter.setIndent("  ");
								GsonHelper.writeValue(jsonWriter, element, null);

								// Atomic move (if save succeeds, overwrite original)
								Files.move(tempPath, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
							} catch (IOException e) {
								RTFCommon.LOGGER.error("Failed to write preset to disk", e);
							}
						});
			}
		}
	}
}