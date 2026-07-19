package com.pricepaid;

/**
 * Formats a timestamp relative to now, e.g. "9 months ago".
 */
public final class RelativeTime
{
	private RelativeTime()
	{
	}

	public static String format(long time)
	{
		long seconds = Math.max(0, (System.currentTimeMillis() - time) / 1000);

		if (seconds < 60)
		{
			return "just now";
		}

		long minutes = seconds / 60;
		if (minutes < 60)
		{
			return plural(minutes, "minute");
		}

		long hours = minutes / 60;
		if (hours < 24)
		{
			return plural(hours, "hour");
		}

		long days = hours / 24;
		if (days == 1)
		{
			return "yesterday";
		}
		if (days < 14)
		{
			return plural(days, "day");
		}

		long weeks = days / 7;
		if (days < 60)
		{
			return plural(weeks, "week");
		}

		long months = days / 30;
		if (months < 12)
		{
			return plural(months, "month");
		}

		long years = days / 365;
		return plural(Math.max(1, years), "year");
	}

	private static String plural(long n, String unit)
	{
		return n + " " + unit + (n == 1 ? "" : "s") + " ago";
	}
}
