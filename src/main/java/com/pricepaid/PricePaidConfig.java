package com.pricepaid;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Units;

@ConfigGroup(PricePaidConfig.GROUP)
public interface PricePaidConfig extends Config
{
	String GROUP = "pricepaid";

	@ConfigItem(
		keyName = "autoStar",
		name = "Autotrack expensive items",
		description = "Automatically track items bought above the price threshold, consumables excluded",
		position = 1
	)
	default boolean autoStar()
	{
		return true;
	}

	@ConfigItem(
		keyName = "autoStarThreshold",
		name = "Autotrack price",
		description = "Autotrack a purchase when the price per item is at least this much",
		position = 2
	)
	@Units(" gp")
	default int autoStarThreshold()
	{
		return 100_000;
	}

	@ConfigItem(
		keyName = "absoluteDates",
		name = "Exact dates",
		description = "Show purchase dates as full dates and times instead of relative time like '9 months ago'",
		position = 3
	)
	default boolean absoluteDates()
	{
		return false;
	}
}
