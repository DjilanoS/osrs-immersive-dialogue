package com.immersivedialogue;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class ImmersiveDialoguePluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(ImmersiveDialoguePlugin.class);
		RuneLite.main(args);
	}
}
