# Immersive Dialogue

A RuneLite plugin that makes NPC ↔ player conversations more immersive — inspired by the
"Immersion" addon for World of Warcraft.

Instead of leaving dialogue in the chatbox (stuck in the far corner on large monitors), the
conversation is relocated into a **translucent box at the bottom-center of the screen**, while
keeping RuneLite's real dialogue widgets — so the **live animated chat-head**, click-to-continue,
and option selection all keep working natively.

## How it works

The plugin re-applies a reposition + restyle to the native dialogue widgets every frame
(`BeforeRender`), because the client rebuilds these widgets via clientscripts:

- **NPC lines** → interface `CHAT_LEFT` (231), head on the left
- **Player lines** → interface `CHAT_RIGHT` (217), head on the right
- **"Select an option"** → interface `CHATMENU` (219)

Each open dialogue's root (`UNIVERSE`) is centered horizontally and anchored near the bottom
(`setXPositionMode`/`setYPositionMode` + `setOriginalX/Y` + `revalidate()`). A translucent
backdrop is drawn behind it (`OverlayLayer.UNDER_WIDGETS`), sized to the dialogue's live canvas
bounds so the two stay aligned. Body text and options are recolored for legibility on the dark
backdrop (option recoloring is conditional, preserving the native hover highlight).

> **Note:** OSRS shows one speaker at a time (NPC → click continue → you), never both heads at
> once, so the layout shows the current speaker's head on its natural side per turn.

## Configuration

- **Relocate dialogue** — master toggle (off = vanilla chatbox dialogue).
- **Bottom margin / Horizontal offset** — fine-tune the box position.
- **Backdrop color** — color + opacity of the translucent box.
- **Backdrop padding** — padding around the dialogue inside the box.
- **Recolor text / Text color** — keep dialogue legible on the dark backdrop.

## Building & running (development)

Requires JDK 11 (Eclipse Temurin) — the project targets Java 11.

```sh
# compile
./gradlew compileJava

# launch the RuneLite dev client with this plugin loaded
./gradlew run
```

To log in to the development client, follow
[Using Jagex Accounts](https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts).

> ⚠️ Never use automation/computer-use tools to interact with RuneScape — automating game input
> violates Jagex's third-party client guidelines and can get your account banned. Only test by
> playing manually.

## Status / known unknowns

- **Animated head relocation (the one open risk):** moving the model chat-head widget to a custom
  location is undocumented in the RuneLite API. It is expected to keep animating at the new
  position, but this must be confirmed in-game (talk to any NPC). If the head does not follow
  correctly, the fallback is to keep the head in place or render a static head image.
- Option **hover-highlight** is preserved via conditional recoloring; if option colors look off on
  some interfaces, tune **Text color** or disable **Recolor text**.

## License

BSD 2-Clause — see [LICENSE](LICENSE).
