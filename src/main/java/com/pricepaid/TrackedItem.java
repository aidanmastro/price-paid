package com.pricepaid;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A starred item. All of its purchases are kept until the item is unstarred.
 * Past the purchase cap the oldest records get collapsed into the carried
 * totals so the average cost stays correct.
 */
@Data
@NoArgsConstructor
public class TrackedItem
{
	private int itemId;
	private String itemName;
	private List<PurchaseRecord> purchases = new ArrayList<>();
	private long carriedQuantity;
	private long carriedSpent;
	// sells of this item while tracked, so a finished position can show what
	// it bought and sold for. soldReceived is net of GE tax
	private long soldQuantity;
	private long soldReceived;
	private long lastSaleTime;

	public TrackedItem(int itemId, String itemName)
	{
		this.itemId = itemId;
		this.itemName = itemName;
	}

	public long totalQuantity()
	{
		long qty = carriedQuantity;
		for (PurchaseRecord p : purchases)
		{
			qty += p.getQuantity();
		}
		return qty;
	}

	public long totalSpent()
	{
		long spent = carriedSpent;
		for (PurchaseRecord p : purchases)
		{
			spent += p.getSpent();
		}
		return spent;
	}

	public long averageUnitPrice()
	{
		long qty = totalQuantity();
		return qty > 0 ? totalSpent() / qty : 0;
	}

	public long lastPurchaseTime()
	{
		long last = 0;
		for (PurchaseRecord p : purchases)
		{
			last = Math.max(last, p.getTime());
		}
		return last;
	}

	/** Whether the whole position has been sold back to the GE. */
	public boolean fullySold()
	{
		long qty = totalQuantity();
		return qty > 0 && soldQuantity >= qty;
	}
}
