package com.pricepaid;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A single GE buy, one fill delta of one offer.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PurchaseRecord
{
	private int itemId;
	private String itemName;
	private int quantity;
	private long spent;
	private long time;
	// true when the fill was only noticed at login, meaning it happened at
	// some unknown point while logged out and time is just the detection time
	private boolean approximate;

	public long unitPrice()
	{
		return quantity > 0 ? spent / quantity : 0;
	}
}
