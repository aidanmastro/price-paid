package com.pricepaid;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

/**
 * Owns the purchase data for the current character and persists it in profile
 * scoped config, which syncs between PCs through the runelite.net account.
 *
 * The "recent" key holds a rolling buffer of the last {@link #RECENT_CAP}
 * buys, "tracked" holds starred items which are kept until unstarred, and the
 * "geslot" keys hold offer snapshots used to diff replayed login events.
 */
@Slf4j
@Singleton
public class PurchaseDataManager
{
	// how many purchases the recent feed keeps before rolling over
	static final int RECENT_CAP = 200;
	// nobody sane hits this, purely a guard against a duplication bug
	static final int TRACKED_CAP = 500;
	// per tracked item, older records collapse into carried totals past this
	static final int PURCHASES_PER_ITEM_CAP = 100;

	private static final String KEY_RECENT = "recent";
	private static final String KEY_TRACKED = "tracked";
	private static final String KEY_SLOT_PREFIX = "geslot_";

	private static final Type RECENT_TYPE = new TypeToken<List<PurchaseRecord>>()
	{
	}.getType();
	private static final Type TRACKED_TYPE = new TypeToken<List<TrackedItem>>()
	{
	}.getType();

	private final ConfigManager configManager;
	private final Gson gson;

	private final Map<Integer, TrackedItem> tracked = new LinkedHashMap<>();
	// newest first
	private final List<PurchaseRecord> recent = new ArrayList<>();

	@Inject
	private PurchaseDataManager(ConfigManager configManager, Gson gson)
	{
		this.configManager = configManager;
		this.gson = gson;
	}

	public synchronized void load()
	{
		recent.clear();
		tracked.clear();

		String recentJson = configManager.getRSProfileConfiguration(PricePaidConfig.GROUP, KEY_RECENT);
		if (recentJson != null)
		{
			try
			{
				List<PurchaseRecord> loaded = gson.fromJson(recentJson, RECENT_TYPE);
				if (loaded != null)
				{
					recent.addAll(loaded);
				}
			}
			catch (JsonSyntaxException e)
			{
				log.warn("Corrupt recent purchase data, discarding", e);
			}
		}

		String trackedJson = configManager.getRSProfileConfiguration(PricePaidConfig.GROUP, KEY_TRACKED);
		if (trackedJson != null)
		{
			try
			{
				List<TrackedItem> loaded = gson.fromJson(trackedJson, TRACKED_TYPE);
				if (loaded != null)
				{
					for (TrackedItem item : loaded)
					{
						tracked.put(item.getItemId(), item);
					}
				}
			}
			catch (JsonSyntaxException e)
			{
				log.warn("Corrupt tracked item data, discarding", e);
			}
		}
	}

	public synchronized SavedOffer loadSlot(int slot)
	{
		String json = configManager.getRSProfileConfiguration(PricePaidConfig.GROUP, KEY_SLOT_PREFIX + slot);
		if (json == null)
		{
			return null;
		}
		try
		{
			return gson.fromJson(json, SavedOffer.class);
		}
		catch (JsonSyntaxException e)
		{
			return null;
		}
	}

	public synchronized void saveSlot(int slot, SavedOffer offer)
	{
		configManager.setRSProfileConfiguration(PricePaidConfig.GROUP, KEY_SLOT_PREFIX + slot, gson.toJson(offer));
	}

	public synchronized void clearSlot(int slot)
	{
		configManager.unsetRSProfileConfiguration(PricePaidConfig.GROUP, KEY_SLOT_PREFIX + slot);
	}

	/**
	 * Records a buy fill. Returns the record, or null if it was rejected by the
	 * duplicate guard.
	 */
	public synchronized PurchaseRecord recordPurchase(int itemId, String itemName, int quantity, long spent, boolean approximate)
	{
		long now = System.currentTimeMillis();

		// an identical fill of the same item in the same millisecond can only
		// be the same event firing twice
		for (PurchaseRecord existing : recent)
		{
			if (existing.getItemId() == itemId
				&& existing.getQuantity() == quantity
				&& existing.getSpent() == spent
				&& existing.getTime() == now)
			{
				return null;
			}
		}

		PurchaseRecord record = new PurchaseRecord(itemId, itemName, quantity, spent, now, approximate);

		recent.add(0, record);
		while (recent.size() > RECENT_CAP)
		{
			recent.remove(recent.size() - 1);
		}

		TrackedItem entry = tracked.get(itemId);
		if (entry != null)
		{
			appendToTracked(entry, record);
		}

		save();
		return record;
	}

	public synchronized boolean isTracked(int itemId)
	{
		return tracked.containsKey(itemId);
	}

	/**
	 * Stars an item, seeding the tracked entry with every purchase of it
	 * still in the recent buffer. Does nothing if already tracked.
	 */
	public synchronized void star(int itemId, String itemName)
	{
		if (tracked.containsKey(itemId) || tracked.size() >= TRACKED_CAP)
		{
			return;
		}

		TrackedItem entry = new TrackedItem(itemId, itemName);
		List<PurchaseRecord> matching = new ArrayList<>();
		for (PurchaseRecord record : recent)
		{
			if (record.getItemId() == itemId)
			{
				matching.add(record);
			}
		}
		// recent is newest first but tracked purchases are stored oldest first
		for (int i = matching.size() - 1; i >= 0; i--)
		{
			appendToTracked(entry, matching.get(i));
		}
		tracked.put(itemId, entry);
		save();
	}

	/**
	 * Records a GE sell fill of a tracked item. Does nothing for untracked
	 * items. netReceived is the coins actually collected after GE tax.
	 */
	public synchronized void recordSale(int itemId, int quantity, long netReceived)
	{
		TrackedItem entry = tracked.get(itemId);
		if (entry == null)
		{
			return;
		}
		entry.setSoldQuantity(entry.getSoldQuantity() + quantity);
		entry.setSoldReceived(entry.getSoldReceived() + netReceived);
		entry.setLastSaleTime(System.currentTimeMillis());
		save();
	}

	/** Unstars an item, discarding its tracked purchase history. */
	public synchronized void unstar(int itemId)
	{
		if (tracked.remove(itemId) != null)
		{
			save();
		}
	}

	public synchronized List<PurchaseRecord> getRecent()
	{
		return new ArrayList<>(recent);
	}

	/** Tracked items, most recently purchased first. */
	public synchronized List<TrackedItem> getTracked()
	{
		List<TrackedItem> items = new ArrayList<>(tracked.values());
		items.sort(Comparator.comparingLong(TrackedItem::lastPurchaseTime).reversed());
		return items;
	}

	private void appendToTracked(TrackedItem entry, PurchaseRecord record)
	{
		entry.getPurchases().add(record);
		while (entry.getPurchases().size() > PURCHASES_PER_ITEM_CAP)
		{
			PurchaseRecord oldest = entry.getPurchases().remove(0);
			entry.setCarriedQuantity(entry.getCarriedQuantity() + oldest.getQuantity());
			entry.setCarriedSpent(entry.getCarriedSpent() + oldest.getSpent());
		}
	}

	private void save()
	{
		configManager.setRSProfileConfiguration(PricePaidConfig.GROUP, KEY_RECENT, gson.toJson(recent, RECENT_TYPE));
		configManager.setRSProfileConfiguration(PricePaidConfig.GROUP, KEY_TRACKED,
			gson.toJson(new ArrayList<>(tracked.values()), TRACKED_TYPE));
	}
}
