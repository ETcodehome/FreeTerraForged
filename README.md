# FreeTerraForged
A community driven fork of the Legendary ReTerraForged project for modern minecraft providing heavily customizable overworld terrain generation.
Additional feature contributions are welcomed via forking and raising a merge PR.

### We stand on the shoulders of giants
- Original project https://github.com/TerraForged/TerraForged
- Builds on the substantial post v1.19+ work of Racoonman2 in https://github.com/racoonman2/ReTerraForged
- Finishes the Neoforge port work started by Equalizer32 in https://github.com/equalizer32/NeoTerraForged/tree/1.21.1

### Licensing 
- Continued under the permissive MIT license as per all historic contributions, with the exception of `Erosion.java` which is licensed under the Mozilla Public License 2.0 (MPL-2.0). See [LICENSE](LICENSE) for details.

***

# Getting started

### Customize a new world
<img width="856" height="526" alt="image" src="https://github.com/user-attachments/assets/941bc4b1-9334-4c08-9197-9ff729876369" />

---

### Select a preset 
<img width="1579" height="743" alt="image" src="https://github.com/user-attachments/assets/fb9fc15b-dd2c-4970-bc11-b4d873fe6830" />

- Use template settings directly by clicking [Done]
- Customize the selected preset settings by clicking [Edit]
- Manage preset modifications using the right hand menu options.

---

### Customize your world
<img width="2345" height="1244" alt="image" src="https://github.com/user-attachments/assets/93e99f98-da19-4718-a283-3c9f5dee8362" />
<img width="2345" height="1244" alt="image" src="https://github.com/user-attachments/assets/3d033148-aa9c-44c0-bbdb-971aef581267" />

- There are many pages of settings accessible via paging through using [<<] and [>>]
- Click Done to save your edits.
- Click Cancel to abandon your edits.

---

## Biome replacement

FreeTerraForged creates and reads its biome replacement configuration at:

```text
config/freeterraforged/biome_replacements.properties
```

On first startup:

- if `config/biome_replacer.properties` exists, FTF atomically copies its rules into the native file
  and enables compatibility validation for that imported content;
- otherwise, FTF creates a documented native template; and
- after the native file has been created, it is authoritative. Later changes to the legacy Biome
  Replacer file are not merged or copied over it.

The native format supports global rules, dimension sections, source-side biome tags, and explicit
removal:

```properties
format_version=1
validation=strict
placement=preserve

minecraft:snowy_plains > minecraft:desert
#minecraft:is_forest > minecraft:cherry_grove
minecraft:deep_dark > null

[minecraft:the_nether]
minecraft:basalt_deltas > minecraft:warped_forest

[]
minecraft:old_growth_birch_forest > minecraft:forest
```

Rules are atomic and one-hop. For example, with `a > b` and `b > c`, an original `a` becomes `b`
and an original `b` becomes `c`; FTF does not recursively chase the chain. Swaps are therefore
well-defined. Tag rules fill sources not already claimed in their scope, direct rules overwrite in
the same scope, and rules in the selected dimension overlay global rules.

Replacement changes only the final biome. The source biome's climate point, terrain, hydrology, and
placement envelope are preserved. Removal filters the biome from every FTF provider table; if a
selection decorator produces a removed biome, FTF falls back to that query's provider-base biome.
Configurations that would empty a required provider/fallback table or remove from an unsupported
direct custom source are rejected without replacing the previous live worldgen plan.

Use Minecraft's resource reload or reopen the world after editing the file. A failed reload leaves
the previous immutable plan active. In the preset biome preview, replaced cells use the target
biome's color with a world-anchored crosshatch. Hovering shows the source, target, rule line, and
placement-preservation note. The **Show Biome Overrides** control toggles this annotation.

Biome Replacer remains available to non-FTF generators when both mods are installed. FTF does not
call its mutable resolver; FTF-owned plans use the native immutable rules and suppress only
Lithostitched injectors carrying explicit `biome_replacer` provenance. Biolith's captured range API
does not expose caller provenance, so FTF emits a warning rather than claiming source-proven
exact-once coexistence for an overlapping Biome Replacer + Biolith stack.

---

# Bugs
- Any issues encountered should be raised as Github issues with as much supporting documentation as you can provide, ideally latest.log and screenshots at a minimum.
