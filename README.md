# Cobblemon Custom Mounts (`cobble_mounts`)

Fabric sidemod for **Cobblemon 1.7.x** (Minecraft **1.21.1**) that lets you assign Pokémon from your **PC** as personal mounts — without removing them from storage.

- **Author:** Soleysus  
- **Mod ID:** `cobble_mounts`  
- **Version:** 1.0.0  
- **Repository:** [jalexss/sidemod-monturas](https://github.com/jalexss/sidemod-monturas)

## Features

- Assign up to **3 slots** per mount style: **Land**, **Water**, **Air**, and **Teleport**
- Assigned Pokémon stay in the **PC** (soft UUID references); party Pokémon cannot be used as mounts
- Assignments are stored **per player** and dual-persisted (Cobblemon store + world SavedData) so they survive multiplayer disconnect/rejoin
- Optional **Mega** toggle when the Pokémon has a compatible mega stone
- **Teleport** style integrates with [Waystones](https://modrinth.com/mod/waystones) (soft dependency)
- PC slots used as mounts appear locked/dimmed while assigned
- Locales: English (`en_us`), Spanish Spain (`es_es`), Spanish Mexico (`es_mx`)

## Requirements

| Dependency | Notes |
|---|---|
| Minecraft `1.21.1` | |
| Fabric Loader `>=0.17.2` | |
| Java `21` | Cobblemon 1.7.3 expects Java 21 |
| Fabric API | |
| Fabric Language Kotlin | Same stack as Cobblemon 1.7.3 |
| Cobblemon `>=1.7.3 <1.8.0` | Required |
| Waystones (optional) | Needed only for **Teleport** assign/use |

Client and server both need this mod (and Cobblemon). Install Waystones on both sides if you want Teleport.

## How to use

1. Move the Pokémon you want as a mount into the **PC** (not the party).
2. Open the mount menu with **`H`** (default; rebind under *Options → Controls → Cobble Mounts*).
3. Pick a style tab, select an empty slot, then click a PC candidate to assign.
4. **Ride** summons the mount; **Dismount** recalls it without unassigning.
5. **Remove** clears the assignment from all styles that shared that Pokémon.

### Teleport (Abra / Ralts lines + Waystones)

Teleport is only available when **Waystones is installed**. Eligible species:

- Abra line: `abra`, `kadabra`, `alakazam`
- Ralts line: `ralts`, `kirlia`, `gardevoir`, `gallade`

You do **not** need a placed waystone block just to assign the Pokémon. To actually travel, you need destinations discovered in Waystones as usual.

If Waystones is missing, the Teleport tab is greyed out and assignment is blocked.

### Commands

| Command | Description |
|---|---|
| `/cobblemounts` or `/cobblemounts open` | Open the mount menu |
| `/cobblemounts assign <style> <slot> <pcIndex>` | Assign by PC index (`style`: `land` / `liquid` / `air` / `teleport`, slot `1–3`) |
| `/cobblemounts remove <style> <slot>` | Unassign a slot |
| `/cobblemounts summon <style> <slot>` | Summon / ride / teleport |
| `/cobblemounts dismount` | Dismount and recall |

## Install (players)

1. Install Fabric Loader for Minecraft 1.21.1.
2. Install Fabric API, Fabric Language Kotlin, and Cobblemon 1.7.3+.
3. Drop the built `cobble_mounts-*.jar` into the `mods` folder (client and server).
4. Optionally add Waystones for Teleport.

Build output after `./gradlew build` is under `build/libs/`.

## Develop

```bash
# Requires JDK 21 on PATH (or JAVA_HOME). Do not commit machine-specific JDK paths.
./gradlew runClient   # dev client
./gradlew runServer   # dev server
./gradlew build       # produce jars in build/libs/
```

Cobblemon is resolved from the [official Cobblemon Maven](https://artefacts.cobblemon.com/releases/). Local jars under `libs/` are for optional tooling only and are **gitignored**.

If Gradle picks the wrong Java version, set `JAVA_HOME` or put a local override in your user Gradle config (`~/.gradle/gradle.properties`), for example:

```properties
org.gradle.java.home=/path/to/jdk-21
```

Never commit that path into this repository.

## License

This project is released under **[CC0 1.0 Universal](LICENSE)** (public-domain dedication), the same license as the Fabric example-mod template it started from.

**Do you need to change it?** Only if you want stronger copyright control:

| License | When it fits |
|---|---|
| **CC0** (current) | Anyone may use, modify, and redistribute with almost no restrictions — including no required attribution |
| **MIT / Apache-2.0** | Still open source, but keep copyright notice / attribution |
| **All rights reserved / custom** | If you want to restrict commercial use or redistribution |

Because the repo is already public under CC0, keeping **CC0** is the simplest and consistent choice unless you deliberately want to relicense future contributions. Changing the license later does not retroactively revoke what was already published under CC0.

## Credits

- Built on [Fabric](https://fabricmc.net/) and [Cobblemon](https://cobblemon.com/)
- Teleport UI integration targets [Waystones](https://modrinth.com/mod/waystones) (soft dependency via reflection)
- Originally scaffolded from [Fabric example mod](https://github.com/FabricMC/fabric-example-mod)
