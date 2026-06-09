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
		name = "General",
		description = "General behaviour for the relocated dialogue.",
		position = 0
	)
	String generalSection = "general";

	@ConfigSection(
		name = "Size",
		description = "Scale the entire dialogue (box, avatar and text) as one unit.",
		position = 1
	)
	String sizeSection = "size";

	@ConfigSection(
		name = "Position",
		description = "Where the dialogue box is placed on the screen.",
		position = 2
	)
	String positionSection = "position";

	@ConfigSection(
		name = "Dialogue appearance",
		description = "Backdrop, text and title styling for the dialogue box.",
		position = 3
	)
	String appearanceSection = "appearance";

	@ConfigSection(
		name = "Avatar appearance",
		description = "Backdrop behind the chat-head, plus the box border and corner shape.",
		position = 4
	)
	String frameSection = "frame";

	@ConfigSection(
		name = "Transitions",
		description = "Fade transitions when the dialogue box opens and closes.",
		position = 5
	)
	String animationSection = "animation";

	@ConfigItem(
		keyName = "animateHead",
		name = "Animate head",
		description = "Play the talking head animation instead of a static head.",
		section = generalSection,
		position = 0
	)
	default boolean animateHead()
	{
		return true;
	}

	@Range(min = 50, max = 150)
	@Units(Units.PERCENT)
	@ConfigItem(
		keyName = "scalePercent",
		name = "Dialogue scale",
		description = "Scale the whole dialogue - box, avatar and text - as one unit. 100% is the default.",
		section = sizeSection,
		position = 0
	)
	default int scalePercent()
	{
		return 100;
	}

	@ConfigItem(
		keyName = "dragMode",
		name = "Enable drag mode",
		description = "Hold ALT and drag the dialogue box to reposition it. The dragged position is saved into Bottom margin / Horizontal offset below, which control the box while drag mode is off.",
		section = positionSection,
		position = 1
	)
	default boolean dragMode()
	{
		return true;
	}

	@Range(min = 0, max = 2160)
	@Units(Units.PIXELS)
	@ConfigItem(
		keyName = "bottomMargin",
		name = "Bottom margin",
		description = "Distance of the dialogue box from the bottom edge of the screen.",
		section = positionSection,
		position = 2
	)
	default int bottomMargin()
	{
		return 150;
	}

	@Range(min = -1920, max = 1920)
	@Units(Units.PIXELS)
	@ConfigItem(
		keyName = "horizontalOffset",
		name = "Horizontal offset",
		description = "Shift the dialogue box left (negative) or right (positive) from the horizontal center.",
		section = positionSection,
		position = 3
	)
	default int horizontalOffset()
	{
		return 0;
	}

	@ConfigItem(
		keyName = "resetPosition",
		name = "Reset position",
		description = "Click to move the dialogue box back to its default position (re-centred, default bottom margin).",
		section = positionSection,
		position = 4
	)
	default boolean resetPosition()
	{
		return false;
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
		return 4;
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

	@Range(min = 12, max = 28)
	@Units(Units.PIXELS)
	@ConfigItem(
		keyName = "textSize",
		name = "Text size",
		description = "Font size of the dialogue body text and options.",
		section = appearanceSection,
		position = 4
	)
	default int textSize()
	{
		return 16;
	}

	@Alpha
	@ConfigItem(
		keyName = "nameColor",
		name = "Name color",
		description = "Color applied to the speaker's name.",
		section = appearanceSection,
		position = 5
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
		position = 6
	)
	default int titleFontSize()
	{
		return 19;
	}

	@ConfigItem(
		keyName = "showBorder",
		name = "Show border",
		description = "Draw a framed border around the dialogue box (and avatar panel), styled after the vanilla chatbox frame.",
		section = appearanceSection,
		position = 7
	)
	default boolean showBorder()
	{
		return true;
	}

	@Alpha
	@ConfigItem(
		keyName = "borderColor",
		name = "Border color",
		description = "Color of the dialogue box border.",
		section = appearanceSection,
		position = 8
	)
	default Color borderColor()
	{
		return new Color(116, 95, 60, 255);
	}

	@Range(min = 1, max = 8)
	@Units(Units.PIXELS)
	@ConfigItem(
		keyName = "borderWidth",
		name = "Border width",
		description = "Thickness of the dialogue box border.",
		section = appearanceSection,
		position = 9
	)
	default int borderWidth()
	{
		return 2;
	}

	@Range(min = 0, max = 40)
	@Units(Units.PIXELS)
	@ConfigItem(
		keyName = "cornerRadius",
		name = "Corner radius",
		description = "Rounding of the box corners. Set to 0 for square corners (no border-radius).",
		section = appearanceSection,
		position = 10
	)
	default int cornerRadius()
	{
		return 16;
	}

	@Alpha
	@ConfigItem(
		keyName = "questHelperHighlightColor",
		name = "Quest Helper highlight color",
		description = "Color used to highlight the option Quest Helper marks as correct. Kept legible on the dark backdrop; Quest Helper's own color is only used to detect which option to highlight. Has no effect without Quest Helper installed.",
		section = appearanceSection,
		position = 11
	)
	default Color questHelperHighlightColor()
	{
		return new Color(120, 180, 255);
	}

	// --- Avatar appearance -----------------------------------------------------

	@ConfigItem(
		keyName = "avatarBackground",
		name = "Avatar backdrop",
		description = "Draw a colored panel behind the chat-head, framing the avatar.",
		section = frameSection,
		position = 0
	)
	default boolean avatarBackground()
	{
		return true;
	}

	@Alpha
	@ConfigItem(
		keyName = "avatarBackgroundColor",
		name = "Avatar color",
		description = "Color and opacity of the panel drawn behind the chat-head.",
		section = frameSection,
		position = 1
	)
	default Color avatarBackgroundColor()
	{
		return new Color(45, 32, 22, 215);
	}

	// --- Transitions -----------------------------------------------------------

	@ConfigItem(
		keyName = "fade",
		name = "Fade in / out",
		description = "Fade the dialogue box in when it opens and out when it closes.",
		section = animationSection,
		position = 0
	)
	default boolean fade()
	{
		return true;
	}

	@Range(min = 250, max = 1000)
	@Units(Units.MILLISECONDS)
	@ConfigItem(
		keyName = "fadeDuration",
		name = "Fade duration",
		description = "How long the fade in / out takes.",
		section = animationSection,
		position = 1
	)
	default int fadeDuration()
	{
		return 150;
	}
}
