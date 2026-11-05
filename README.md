# PvP Mod (Fabric, Minecraft 1.21.11)

Client-side only. Open settings with /pvpmod in chat. Config: config/pvpmod.json

- Items: click any item for its own screen: colour picker, preview, size, and "Edit texture...".
- Texture editor: pixel editor with animation frames. "Apply + reload" writes the resource pack
  resourcepacks/PvPMod Textures (enable it once in Options > Resource Packs) and reloads.
- Armor Colours: tint a whole armor set (leather, chainmail, iron, gold, diamond, netherite, turtle, elytra).
- End crystal: 3D on/off, bedrock base on/off.
- Totem pop: hide the on-screen totem, hide/resize/thin out/recolour the particles.
- Particles: master switch, global size and amount, plus show/hide, size and amount per particle type.

Build: push to GitHub; the Actions tab builds the jar (artifact "pvpmod-jar", use the one without -sources).
The colour tints live in their own mixin config (pvpmod.tint.mixins.json). If one fails to apply,
the rest of the mod still loads.
