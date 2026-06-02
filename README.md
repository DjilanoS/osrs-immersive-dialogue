# Immersive Dialogue

A RuneLite plugin that makes NPC ↔ player conversations more immersive — inspired by the
"Immersion" addon for World of Warcraft.

Instead of leaving dialogue in the chatbox (stuck in the far corner on large monitors), the
conversation is mirrored into a **translucent box at the bottom-center of the screen**, with the
**live animated chat-head** rendered beside it. The native dialogue stays active underneath, so all
interaction happens through the game's own input — see *Interaction* below.

## How it works

Every frame (`BeforeRender`), the plugin reads the currently-open dialogue and draws its own
replacement:

- **NPC lines** → interface `CHAT_LEFT` (231), head on the left
- **Player lines** → interface `CHAT_RIGHT` (217), head on the right
- **"Select an Option"** → interface `CHATMENU` (219)

The speaker name, body text and options are read from the native widgets and re-drawn on a
translucent backdrop (`OverlayLayer.UNDER_WIDGETS`) centered near the bottom of the screen. The
chat-head is mirrored into a `MODEL` widget the plugin creates on the top-level interface root (the
native head is clip-locked inside the chatbox and cannot be relocated).

To avoid showing the dialogue twice, the plugin hides only the native **display** widgets it
re-draws — the speaker name, body text and head. It deliberately leaves the **interactive** native
widgets (the "continue" prompt and the option list) untouched so the game keeps handling input.

## Interaction

All interaction is handled **natively by the game** — the plugin never synthesizes clicks, key
presses or menu actions:

- **Continue** a conversation with the **spacebar** (the native "click here to continue" in the
  chatbox also still works).
- **Select an option** with the **number keys 1–5**. The relocated box numbers each option so it's
  clear which key picks which.

Clicks that land on the relocated box are swallowed so you don't accidentally click the game world
behind it while reading.

> **Note:** OSRS shows one speaker at a time (NPC → continue → you), never both heads at once, so
> the layout shows the current speaker's head on its natural side per turn.

## Configuration

- **Position** — bottom margin and horizontal offset of the box.
- **Dialogue appearance** — backdrop color/opacity, padding, text/name color and size, title size,
  border color/width, corner radius, and the Quest Helper highlight color.
- **Avatar appearance** — optional colored panel behind the chat-head.
- **Transitions** — fade the box in/out, with a configurable duration.

If Quest Helper is installed, the option it marks as correct is highlighted in the relocated box
(in a legible color of your choice); Quest Helper's own color is only used to detect which option
to highlight.

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

## License

BSD 2-Clause — see [LICENSE](LICENSE).
