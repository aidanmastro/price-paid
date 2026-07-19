package com.pricepaid;

import com.pricepaid.ui.PricePaidPanel;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.ItemComposition;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.RuneScapeProfileChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import java.awt.image.BufferedImage;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import com.google.inject.Provides;

@Slf4j
@PluginDescriptor(
	name = "Price Paid",
	description = "Tracks what you paid for items on the GE and how their value has changed since",
	tags = {"ge", "grand", "exchange", "tracker", "tracking", "item", "paid", "cost", "purchase", "purchases", "price", "value", "gear"}
)
public class PricePaidPlugin extends Plugin
{
	// offer events within a couple ticks of login are replays of state we
	// already know about, same window the core GE plugin uses
	private static final int GE_LOGIN_BURST_WINDOW = 2;

	// GE sell tax is 2% per item rounded down, so items under 50 gp pay
	// nothing. Everything on the exemption list is cheap enough that the
	// rounding already covers it, except bonds
	private static final long GE_TAX_CAP_PER_ITEM = 5_000_000L;
	private static final int OLD_SCHOOL_BOND_ITEM_ID = 13190;

	@Inject
	private Client client;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ItemManager itemManager;

	@Inject
	private PurchaseDataManager dataManager;

	@Inject
	private PricePaidConfig config;

	@Inject
	private PricePaidPanel panel;

	private NavigationButton navButton;
	private int lastLoginTick = -1;

	@Provides
	PricePaidConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(PricePaidConfig.class);
	}

	@Override
	protected void startUp()
	{
		// coin stack sprite, scaled down to fit the toolbar
		BufferedImage icon = ImageUtil.resizeImage(
			ImageUtil.loadImageResource(PricePaidPlugin.class, "icon.png"), 16, 11);
		navButton = NavigationButton.builder()
			.tooltip("Price Paid")
			.icon(icon)
			.priority(5)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		if (client.getGameState() == GameState.LOGGED_IN)
		{
			dataManager.load();
			panel.refresh();
		}
	}

	@Override
	protected void shutDown()
	{
		panel.commitPendingUnstars();
		clientToolbar.removeNavigation(navButton);
		navButton = null;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			lastLoginTick = client.getTickCount();
		}
	}

	@Subscribe
	public void onRuneScapeProfileChanged(RuneScapeProfileChanged event)
	{
		// switched character, or profile data just became available at login
		dataManager.load();
		panel.refresh();
	}

	@Subscribe
	public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event)
	{
		final int slot = event.getSlot();
		final GrandExchangeOffer offer = event.getOffer();
		final GrandExchangeOfferState state = offer.getState();

		if (state == GrandExchangeOfferState.EMPTY && client.getGameState() != GameState.LOGGED_IN)
		{
			// login fires EMPTY for every slot before the real offers come
			// in, acting on it would wipe the snapshots we diff against
			return;
		}

		if (state == GrandExchangeOfferState.EMPTY)
		{
			// offer collected, the slot is actually free now
			dataManager.clearSlot(slot);
			return;
		}

		if (state == GrandExchangeOfferState.SELLING
			|| state == GrandExchangeOfferState.SOLD
			|| state == GrandExchangeOfferState.CANCELLED_SELL)
		{
			// sells of tracked items get recorded so a finished position can
			// show its outcome. Other sells still snapshot the slot so a sell
			// replacing a buy is never misread as a continuation of it
			final SavedOffer savedSell = dataManager.loadSlot(slot);
			final int soldDelta;
			final long receivedDelta;
			if (savedSell != null && savedSell.isSameOffer(offer))
			{
				soldDelta = offer.getQuantitySold() - savedSell.getQuantitySold();
				receivedDelta = (long) offer.getSpent() - savedSell.getSpent();
			}
			else
			{
				soldDelta = offer.getQuantitySold();
				receivedDelta = offer.getSpent();
			}
			dataManager.saveSlot(slot, SavedOffer.from(offer));

			if (soldDelta > 0 && receivedDelta > 0 && dataManager.isTracked(offer.getItemId()))
			{
				// the offer reports the gross sale value but the GE keeps its
				// tax, record what actually reaches the coffer
				long tax = geTax(offer.getItemId(), receivedDelta / soldDelta) * soldDelta;
				dataManager.recordSale(offer.getItemId(), soldDelta, receivedDelta - tax);
				panel.refresh();
			}
			return;
		}

		// down here we have a buy, a cancelled buy still keeps whatever
		// partially filled
		final SavedOffer saved = dataManager.loadSlot(slot);

		final int quantityDelta;
		final long spentDelta;
		if (saved != null && saved.isSameOffer(offer))
		{
			quantityDelta = offer.getQuantitySold() - saved.getQuantitySold();
			spentDelta = (long) offer.getSpent() - saved.getSpent();
		}
		else
		{
			quantityDelta = offer.getQuantitySold();
			spentDelta = offer.getSpent();
		}

		dataManager.saveSlot(slot, SavedOffer.from(offer));

		if (quantityDelta <= 0 || spentDelta <= 0)
		{
			return;
		}

		// inside the login burst this is a fill that happened while logged
		// out, so the timestamp we record is really just when we noticed
		final boolean approximate = lastLoginTick >= 0
			&& client.getTickCount() <= lastLoginTick + GE_LOGIN_BURST_WINDOW;

		final ItemComposition composition = itemManager.getItemComposition(offer.getItemId());
		final String itemName = composition.getName();

		PurchaseRecord record = dataManager.recordPurchase(
			offer.getItemId(), itemName, quantityDelta, spentDelta, approximate);
		if (record == null)
		{
			return;
		}

		log.debug("Recorded purchase: {} x{} for {} gp (approximate={})",
			itemName, quantityDelta, spentDelta, approximate);

		if (config.autoStar()
			&& !dataManager.isTracked(offer.getItemId())
			&& record.unitPrice() >= config.autoStarThreshold()
			&& !isConsumable(composition))
		{
			dataManager.star(offer.getItemId(), itemName);
			log.debug("Autotracked {}", itemName);
		}

		panel.refresh();
	}

	/** Sell tax per item, 2% rounded down with the cap. Bonds are exempt. */
	private static long geTax(int itemId, long unitPrice)
	{
		if (itemId == OLD_SCHOOL_BOND_ITEM_ID)
		{
			return 0;
		}
		return Math.min(unitPrice * 2 / 100, GE_TAX_CAP_PER_ITEM);
	}

	/**
	 * Food and potions never get autotracked no matter the price. Checking
	 * the item's own menu actions covers both without any name lists.
	 */
	private static boolean isConsumable(ItemComposition composition)
	{
		String[] actions = composition.getInventoryActions();
		if (actions == null)
		{
			return false;
		}
		for (String action : actions)
		{
			if ("Eat".equalsIgnoreCase(action) || "Drink".equalsIgnoreCase(action))
			{
				return true;
			}
		}
		return false;
	}

}
