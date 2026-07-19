package com.pricepaid;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/**
 * Runs the full RuneLite client with the plugin loaded, for development.
 */
public class PricePaidPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(PricePaidPlugin.class);
		RuneLite.main(args);
	}
}
