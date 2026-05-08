# ReTerraForged
- A Neoforge 1.21.1 release of https://github.com/TerraForged/TerraForged
  Continues the substantial Neoforge port work completed by Equalizer32 for NeoTerraForged in https://github.com/equalizer32/NeoTerraForged/tree/1.21.1
- Continued under the permissive MIT license as per the original repo.
- This is not an official release, do not ask for help with it on official channels. They will throw rocks at you and tell you to go away.
  This is me wanting a 1.21.1 Neoforge release and being willing to do the legwork to fix it up and compile it so it is stable enough for a 1.21.1 play through.

# Behavior
- Improved overworld terrain generation plugin with extremely customisable options

# Screenshots (full world gen stack not just bare noise)
<img width="2579" height="1381" alt="image" src="https://github.com/user-attachments/assets/1ebbc7d0-4701-4176-8d52-f964577cdf37" />
<img width="2301" height="942" alt="image" src="https://github.com/user-attachments/assets/9abae022-97bc-40c5-9b09-d2a37165e39c" />
<img width="2301" height="942" alt="image" src="https://github.com/user-attachments/assets/35af9f15-8481-4afc-af06-7ab6925761d2" />
<img width="2301" height="1799" alt="image" src="https://github.com/user-attachments/assets/ce9c453f-1b97-41bd-8b83-9a4ee186d974" />
<img width="2301" height="1799" alt="image" src="https://github.com/user-attachments/assets/b062f60d-f03c-4bde-92d4-e13fe60ceda3" />
<img width="2301" height="1799" alt="image" src="https://github.com/user-attachments/assets/f709fb91-ea64-473a-9354-181464cba558" />
+ heaps more. proper worldgen customization.

# Bugs
- Much more likely to be addressed if you raise an issue.

# Worldgen Adjacent Compatibility
- Newer versions of Lithostitched result in issues when loading, suspect they're trying a more aggressive registry intercept that RTF doesn't like so much. 
  v 1.5.7 works, 1.7.2 didn't when tested.
- Terralith is compatible if you get the datapack version, then extract it and delete the \data\minecraft\worldgen\density_function\overworld\sloped_cheese.json
  Functionally, this says "don't use Terralith noise as the base terrain, use RTFs". Without this you will get blocky square ridges.
  This change alone means you still get the nice Yellowstone biomes etc.
  I recommend using Paxi so that this just loads by default into every world you create (dont need to remember to add datapacks)
- Darker Depths works
- Dawn Of Time works
- Regions Unexplored works / RU Expansion works
- Croptopia works
- Biolith works
- Biomes O Plenty works
- No Mans Land works
- Oh The Biomes You'll Go / Wev've Gone works
- Create works
- Regenerating blocks works
- ThisIsStone works
- Deeper Darker works
- Hybrid Aquatic works
- Yung's everything seems to work.

Basically anything that doesn't aggressively intercept and override noise generation or density functions seems to play pretty nicely.

# Dev notes
- Biggest issues for a 1.21.1 release were many registries not including default cloners, and issues with the preset preview generator being cooked. Structure menu also went weird because of the registry issues. Otherwise actual generation is fine including old presets. Architectury is a huge pain in the arse causing issues with debug logging and language pack location. Could just be unfamiliarity.

