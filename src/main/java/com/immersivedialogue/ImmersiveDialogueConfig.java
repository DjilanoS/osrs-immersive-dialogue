package com.immersivedialogue;

import java.awt.Color;
import net.runelite.client.config.Alpha;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

@ConfigGroup(ImmersiveDialogueConfig.GROUP)
public interface ImmersiveDialogueConfig extends Config
{
	String GROUP = "immersivedialogue";

	@ConfigSection(
		name = "Position",
		description = "Where the dialogue box is placed on the screen.",
		position = 0
	)
	String positionSection = "position";

	@ConfigSection(
		name = "Appearance",
		description = "Backdrop and text styling.",
		position = 1
	)
	String appearanceSection = "appearance";

	@ConfigItem(
		keyName = "relocate",
		name = "Relocate dialogue",
		description = "Move NPC/player dialogue to a box at the bottom-center of the screen. Turn off to keep the vanilla chatbox dialogue.",
		section = positionSection,
		position = 0
	)
	default boolean relocate()
	{
		return true;
	}

	@Range(min = 0, max = 800)
	@Units(Units.PIXELS)
	@ConfigItem(
		keyName = "bottomMargin",
		name = "Bottom margin",
		description = "Distance of the dialogue box from the bottom edge of the screen.",
		section = positionSection,
		position = 1
	)
	default int bottomMargin()
	{
		return 24;
	}

	@Range(min = -960, max = 960)
	@Units(Units.PIXELS)
	@ConfigItem(
		keyName = "horizontalOffset",
		name = "Horizontal offset",
		description = "Shift the dialogue box left (negative) or right (positive) from the horizontal center.",
		section = positionSection,
		position = 2
	)
	default int horizontalOffset()
	{
		return 0;
	}

	@Alpha
	@ConfigItem(
		keyName = "backgroundColor",
		name = "Backdrop color",
		description = "Color and opacity of the translucent box drawn behind the dialogue.",
		section = appearanceSection,
		position = 0
	)
	default Color backgroundColor()
	{
		return new Color(60, 42, 28, 205);
	}

	@Range(min = 0, max = 64)
	@Units(Units.PIXELS)
	@ConfigItem(
		keyName = "backdropPadding",
		name = "Backdrop padding",
		description = "Padding drawn around the dialogue inside the backdrop.",
		section = appearanceSection,
		position = 1
	)
	default int backdropPadding()
	{
		return 12;
	}

	@ConfigItem(
		keyName = "restyleText",
		name = "Recolor text",
		description = "Recolor dialogue body text and options so they stay legible on the dark backdrop.",
		section = appearanceSection,
		position = 2
	)
	default boolean restyleText()
	{
		return true;
	}

	@Alpha
	@ConfigItem(
		keyName = "textColor",
		name = "Text color",
		description = "Color applied to the dialogue body text and options.",
		section = appearanceSection,
		position = 3
	)
	default Color textColor()
	{
		return Color.WHITE;
	}

	@Alpha
	@ConfigItem(
		keyName = "nameColor",
		name = "Name color",
		description = "Color applied to the speaker's name.",
		section = appearanceSection,
		position = 4
	)
	default Color nameColor()
	{
		return new Color(255, 200, 90);
	}

	@Range(min = 14, max = 28)
	@Units(Units.PIXELS)
	@ConfigItem(
		keyName = "titleFontSize",
		name = "Title size",
		description = "Font size of the speaker's name / title shown at the top of the dialogue box.",
		section = appearanceSection,
		position = 5
	)
	default int titleFontSize()
	{
		return 19;
	}

	@ConfigItem(
		keyName = "animateHead",
		name = "Animate head (experimental)",
		description = "Play the talking head animation. EXPERIMENTAL: some NPC heads can crash the client when animated; leave off for a stable static head.",
		section = appearanceSection,
		position = 6,
		warning = "Animating relocated chat-heads is experimental and can crash the game client for some NPCs. Enable at your own risk."
	)
	default boolean animateHead()
	{
		return false;
	}

	@ConfigItem(
		keyName = "debugOverlay",
		name = "Debug overlay",
		description = "Show diagnostic widget metrics (for troubleshooting only).",
		section = appearanceSection,
		position = 7
	)
	default boolean debugOverlay()
	{
		return false;
	}
}
