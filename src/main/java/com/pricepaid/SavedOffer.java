package com.pricepaid;

import lombok.Data;
import lombok.NoArgsConstructor;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;

/**
 * Snapshot of a GE slot's offer, saved per character so that offer events
 * replayed at login can be diffed against the last state we saw instead of
 * being counted twice. Fills that completed while logged out fall out of the
 * same diff.
 */
@Data
@NoArgsConstructor
public class SavedOffer
{
	private int itemId;
	private int quantitySold;
	private int totalQuantity;
	private int price;
	private int spent;
	private GrandExchangeOfferState state;

	public static SavedOffer from(GrandExchangeOffer offer)
	{
		SavedOffer saved = new SavedOffer();
		saved.itemId = offer.getItemId();
		saved.quantitySold = offer.getQuantitySold();
		saved.totalQuantity = offer.getTotalQuantity();
		saved.price = offer.getPrice();
		saved.spent = offer.getSpent();
		saved.state = offer.getState();
		return saved;
	}

	/**
	 * Whether the given offer is a later state of the offer this snapshot was
	 * taken from, rather than a new offer in the same slot.
	 */
	public boolean isSameOffer(GrandExchangeOffer offer)
	{
		return itemId == offer.getItemId()
			&& price == offer.getPrice()
			&& totalQuantity == offer.getTotalQuantity()
			&& quantitySold <= offer.getQuantitySold()
			&& spent <= offer.getSpent();
	}
}
