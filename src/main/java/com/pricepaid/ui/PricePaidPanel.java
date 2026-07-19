package com.pricepaid.ui;

import com.pricepaid.PricePaidConfig;
import com.pricepaid.PurchaseDataManager;
import com.pricepaid.PurchaseRecord;
import com.pricepaid.RelativeTime;
import com.pricepaid.TrackedItem;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.CardLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.AsyncBufferedImage;

/**
 * The sidebar panel. Two tabs, Tracked and Recent, both rendered as lists of
 * cards.
 */
@Singleton
public class PricePaidPanel extends PluginPanel
{
	private static final Color UP_COLOR = ColorScheme.PROGRESS_COMPLETE_COLOR;
	private static final Color DOWN_SLIGHT_COLOR = new Color(255, 202, 40);
	private static final Color DOWN_MODERATE_COLOR = ColorScheme.PROGRESS_INPROGRESS_COLOR;
	private static final Color DOWN_HEAVY_COLOR = ColorScheme.PROGRESS_ERROR_COLOR;
	private static final Color STAR_COLOR = new Color(255, 184, 63);
	// soft alch gold for row labels
	private static final Color LABEL_COLOR = ColorScheme.GRAND_EXCHANGE_ALCH;
	private static final Color DIM_COLOR = ColorScheme.MEDIUM_GRAY_COLOR;

	// three shades of dark so the cards, the bars and the panel background
	// stay distinguishable
	private static final Color CARD_BG_COLOR = new Color(23, 23, 23);
	private static final Color CARD_HOVER_COLOR = new Color(30, 30, 30);
	private static final Color CARD_EDGE_COLOR = new Color(17, 17, 17);
	private static final Color INSET_BG_COLOR = new Color(27, 27, 27);

	private static final int MAX_DETAIL_PURCHASES = 10;

	private final ItemManager itemManager;
	private final ClientThread clientThread;
	private final PurchaseDataManager dataManager;
	private final PricePaidConfig config;

	private final CardLayout displayLayout = new CardLayout();
	private final JPanel display = new JPanel(displayLayout);
	private final JPanel trackedTab = new JPanel(new GridBagLayout());
	private final JPanel recentTab = new JPanel(new GridBagLayout());
	private TabButton trackedTabButton;
	private TabButton recentTabButton;

	// items with their purchase breakdown open, per tab
	private final Set<Integer> expandedItems = new HashSet<>();
	private final Set<Integer> expandedRecentItems = new HashSet<>();

	private boolean activeSectionCollapsed;
	private boolean soldSectionCollapsed;

	// unstarred items stay visible but dimmed until you leave the tab, so a
	// misclick can be undone
	private final Set<Integer> pendingUnstar = new HashSet<>();

	@Inject
	private PricePaidPanel(ItemManager itemManager, ClientThread clientThread,
		PurchaseDataManager dataManager, PricePaidConfig config)
	{
		// no wrap, we run our own scroll pane so the scrollbar can be styled
		super(false);
		this.itemManager = itemManager;
		this.clientThread = clientThread;
		this.dataManager = dataManager;
		this.config = config;

		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

		trackedTab.setOpaque(false);
		recentTab.setOpaque(false);
		display.setOpaque(false);

		display.add(trackedTab, "tracked");
		display.add(recentTab, "recent");

		trackedTabButton = new TabButton("Tracked", () -> selectTab(true));
		recentTabButton = new TabButton("Recent", () -> selectTab(false));
		trackedTabButton.setSelected(true);

		JPanel tabBar = new JPanel(new GridLayout(1, 2, 5, 0));
		tabBar.setOpaque(false);
		tabBar.setBorder(BorderFactory.createEmptyBorder(0, 0, 7, 0));
		tabBar.add(trackedTabButton);
		tabBar.add(recentTabButton);

		JScrollPane scrollPane = new JScrollPane(new ScrollableWrapper(display));
		scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scrollPane.setBorder(null);
		scrollPane.setBackground(ColorScheme.DARK_GRAY_COLOR);
		scrollPane.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
		JScrollBar scrollBar = scrollPane.getVerticalScrollBar();
		scrollBar.setUI(new ThinScrollBarUI());
		scrollBar.setPreferredSize(new Dimension(7, 0));
		scrollBar.setUnitIncrement(16);

		add(tabBar, BorderLayout.NORTH);
		add(scrollPane, BorderLayout.CENTER);
	}

	/**
	 * Keeps the content the same width as the viewport. A plain JPanel lays
	 * out at its preferred width inside a scroll pane and gets clipped.
	 */
	private static class ScrollableWrapper extends JPanel implements Scrollable
	{
		ScrollableWrapper(Component content)
		{
			super(new BorderLayout());
			setOpaque(false);
			add(content, BorderLayout.CENTER);
		}

		@Override
		public Dimension getPreferredScrollableViewportSize()
		{
			return getPreferredSize();
		}

		@Override
		public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction)
		{
			return 16;
		}

		@Override
		public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction)
		{
			return 64;
		}

		@Override
		public boolean getScrollableTracksViewportWidth()
		{
			return true;
		}

		@Override
		public boolean getScrollableTracksViewportHeight()
		{
			return false;
		}
	}

	/** Slim dark scrollbar without the arrow buttons. */
	private static class ThinScrollBarUI extends BasicScrollBarUI
	{
		@Override
		protected void configureScrollBarColors()
		{
			thumbColor = new Color(61, 61, 61);
			trackColor = ColorScheme.DARK_GRAY_COLOR;
		}

		@Override
		protected JButton createDecreaseButton(int orientation)
		{
			return zeroButton();
		}

		@Override
		protected JButton createIncreaseButton(int orientation)
		{
			return zeroButton();
		}

		private static JButton zeroButton()
		{
			JButton button = new JButton();
			button.setPreferredSize(new Dimension(0, 0));
			button.setMinimumSize(new Dimension(0, 0));
			button.setMaximumSize(new Dimension(0, 0));
			return button;
		}
	}

	private void selectTab(boolean tracked)
	{
		commitPendingUnstars();
		trackedTabButton.setSelected(tracked);
		recentTabButton.setSelected(!tracked);
		displayLayout.show(display, tracked ? "tracked" : "recent");
		refresh();
	}

	/** Tab button styled like a card, with an orange strip when selected. */
	private static class TabButton extends JPanel
	{
		private final JLabel label;
		private final JPanel strip;

		TabButton(String text, Runnable onSelect)
		{
			super(new BorderLayout());
			setBackground(CARD_BG_COLOR);
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

			label = new JLabel(text, SwingConstants.CENTER);
			label.setFont(FontManager.getRunescapeBoldFont());
			label.setBorder(new EmptyBorder(5, 0, 5, 0));
			add(label, BorderLayout.CENTER);

			strip = new JPanel();
			strip.setPreferredSize(new Dimension(0, 3));
			strip.setBackground(ColorScheme.BRAND_ORANGE);
			add(strip, BorderLayout.SOUTH);

			addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseClicked(MouseEvent e)
				{
					onSelect.run();
				}

				@Override
				public void mouseEntered(MouseEvent e)
				{
					setBackground(CARD_HOVER_COLOR);
				}

				@Override
				public void mouseExited(MouseEvent e)
				{
					setBackground(CARD_BG_COLOR);
				}
			});
		}

		void setSelected(boolean selected)
		{
			label.setForeground(selected ? Color.WHITE : ColorScheme.MEDIUM_GRAY_COLOR);
			// strip goes transparent when unselected so it doesn't fight the
			// hover background
			strip.setOpaque(selected);
			strip.repaint();
		}
	}

	@Override
	public void onActivate()
	{
		refresh();
	}

	@Override
	public void onDeactivate()
	{
		commitPendingUnstars();
	}

	/** Actually removes anything that was soft unstarred. */
	public void commitPendingUnstars()
	{
		if (pendingUnstar.isEmpty())
		{
			return;
		}
		for (int itemId : pendingUnstar)
		{
			dataManager.unstar(itemId);
			expandedItems.remove(itemId);
		}
		pendingUnstar.clear();
		refresh();
	}

	/**
	 * Rebuilds the panel. Prices and icons are fetched on the client thread,
	 * then the Swing work happens on the EDT. Safe to call from anywhere.
	 */
	public void refresh()
	{
		final List<TrackedItem> trackedItems = dataManager.getTracked();
		final List<List<PurchaseRecord>> recentGroups = groupByItem(dataManager.getRecent());

		clientThread.invokeLater(() ->
		{
			Map<Integer, Long> unitPrices = new HashMap<>();
			Map<Integer, AsyncBufferedImage> trackedIcons = new HashMap<>();
			for (TrackedItem item : trackedItems)
			{
				int quantity = (int) Math.min(Integer.MAX_VALUE, item.totalQuantity());
				unitPrices.put(item.getItemId(), (long) itemManager.getItemPrice(item.getItemId()));
				trackedIcons.put(item.getItemId(), itemManager.getImage(item.getItemId(), quantity, quantity > 1));
			}
			Map<Integer, AsyncBufferedImage> recentIcons = new HashMap<>();
			for (List<PurchaseRecord> group : recentGroups)
			{
				long quantity = 0;
				for (PurchaseRecord record : group)
				{
					quantity += record.getQuantity();
				}
				int itemId = group.get(0).getItemId();
				int iconQuantity = (int) Math.min(Integer.MAX_VALUE, quantity);
				recentIcons.put(itemId, itemManager.getImage(itemId, iconQuantity, iconQuantity > 1));
			}

			SwingUtilities.invokeLater(() -> rebuild(trackedItems, recentGroups, unitPrices, trackedIcons, recentIcons));
		});
	}

	/**
	 * Groups the recent feed by item. Groups keep recency order and the
	 * records inside a group are newest first.
	 */
	private static List<List<PurchaseRecord>> groupByItem(List<PurchaseRecord> records)
	{
		Map<Integer, List<PurchaseRecord>> groups = new LinkedHashMap<>();
		for (PurchaseRecord record : records)
		{
			groups.computeIfAbsent(record.getItemId(), k -> new ArrayList<>()).add(record);
		}
		return new ArrayList<>(groups.values());
	}

	private void rebuild(List<TrackedItem> trackedItems, List<List<PurchaseRecord>> recentGroups,
		Map<Integer, Long> unitPrices, Map<Integer, AsyncBufferedImage> trackedIcons,
		Map<Integer, AsyncBufferedImage> recentIcons)
	{
		trackedTab.removeAll();
		recentTab.removeAll();

		GridBagConstraints constraints = listConstraints();
		if (trackedItems.isEmpty())
		{
			trackedTab.add(emptyLabel("<html><center>Nothing tracked yet.<br>"
				+ "Star a purchase on the Recent tab<br>to track it.</center></html>"), constraints);
		}
		else
		{
			// sold positions get their own section so they don't mix with
			// current holdings
			List<TrackedItem> activeItems = new ArrayList<>();
			List<TrackedItem> soldItems = new ArrayList<>();
			for (TrackedItem item : trackedItems)
			{
				(item.fullySold() ? soldItems : activeItems).add(item);
			}

			// no section headers until something has actually been sold
			boolean sectioned = !soldItems.isEmpty();
			if (sectioned)
			{
				trackedTab.add(sectionHeader("Tracked", activeItems.size(), activeSectionCollapsed, () ->
				{
					activeSectionCollapsed = !activeSectionCollapsed;
					refresh();
				}), constraints);
				constraints.gridy++;
			}
			if (!sectioned || !activeSectionCollapsed)
			{
				JPanel summary = buildActiveSummary(activeItems, unitPrices);
				if (summary != null)
				{
					// bit of extra space between the totals and the entries
					constraints.insets = new Insets(0, 0, 14, 0);
					trackedTab.add(summary, constraints);
					constraints.insets = new Insets(0, 0, 6, 0);
					constraints.gridy++;
				}
				for (TrackedItem item : activeItems)
				{
					trackedTab.add(buildTrackedCard(item,
						unitPrices.getOrDefault(item.getItemId(), 0L),
						trackedIcons.get(item.getItemId())), constraints);
					constraints.gridy++;
				}
			}
			if (sectioned)
			{
				// gap above the sold section so the two areas read separately
				constraints.insets = new Insets(10, 0, 6, 0);
				trackedTab.add(sectionHeader("Sold", soldItems.size(), soldSectionCollapsed, () ->
				{
					soldSectionCollapsed = !soldSectionCollapsed;
					refresh();
				}), constraints);
				constraints.insets = new Insets(0, 0, 6, 0);
				constraints.gridy++;
				if (!soldSectionCollapsed)
				{
					constraints.insets = new Insets(0, 0, 14, 0);
					trackedTab.add(buildSoldSummary(soldItems), constraints);
					constraints.insets = new Insets(0, 0, 6, 0);
					constraints.gridy++;
					for (TrackedItem item : soldItems)
					{
						trackedTab.add(buildTrackedCard(item,
							unitPrices.getOrDefault(item.getItemId(), 0L),
							trackedIcons.get(item.getItemId())), constraints);
						constraints.gridy++;
					}
				}
			}
		}
		addListFiller(trackedTab, constraints);

		constraints = listConstraints();
		if (recentGroups.isEmpty())
		{
			recentTab.add(emptyLabel("<html><center>No GE purchases<br>recorded yet.</center></html>"), constraints);
		}
		else
		{
			for (List<PurchaseRecord> group : recentGroups)
			{
				recentTab.add(buildRecentCard(group, recentIcons.get(group.get(0).getItemId())), constraints);
				constraints.gridy++;
			}
		}
		addListFiller(recentTab, constraints);

		trackedTab.revalidate();
		trackedTab.repaint();
		recentTab.revalidate();
		recentTab.repaint();
	}

	/**
	 * Totals for open positions. Sold items get their own summary inside the
	 * Sold section.
	 */
	private JPanel buildActiveSummary(List<TrackedItem> activeItems, Map<Integer, Long> unitPrices)
	{
		long paid = 0;
		long now = 0;
		boolean any = false;
		for (TrackedItem item : activeItems)
		{
			long unitPrice = unitPrices.getOrDefault(item.getItemId(), 0L);
			if (unitPrice <= 0 || item.totalSpent() <= 0)
			{
				continue;
			}
			paid += item.totalSpent();
			now += unitPrice * item.totalQuantity();
			any = true;
		}
		if (!any)
		{
			return null;
		}

		long diff = now - paid;
		double pct = paid > 0 ? 100.0 * diff / paid : 0;
		Color color = changeColor(pct);

		JPanel card = cardShell();
		card.setBackground(INSET_BG_COLOR);
		JPanel content = summaryContent("Tracked totals");
		content.add(row("Paid", valueLabel(formatGp(paid), Color.WHITE), LABEL_COLOR));
		content.add(row("Now", valueLabel(formatGp(now), Color.WHITE), LABEL_COLOR));
		content.add(row("Change", valueLabel(changeText(diff, pct), color), LABEL_COLOR));

		card.add(content, BorderLayout.CENTER);
		card.add(new StatusStrip(color), BorderLayout.SOUTH);
		return card;
	}

	/** Totals for the Sold section. Received is already net of GE tax. */
	private JPanel buildSoldSummary(List<TrackedItem> soldItems)
	{
		long bought = 0;
		long received = 0;
		for (TrackedItem item : soldItems)
		{
			bought += item.totalSpent();
			received += item.getSoldReceived();
		}

		long profit = received - bought;
		double pct = bought > 0 ? 100.0 * profit / bought : 0;
		Color color = changeColor(pct);

		JPanel card = cardShell();
		card.setBackground(INSET_BG_COLOR);
		JPanel content = summaryContent("Sold totals");
		content.add(row("Bought", valueLabel(formatGp(bought), Color.WHITE), LABEL_COLOR));
		content.add(row("Received", valueLabel(formatGp(received), Color.WHITE), LABEL_COLOR));
		content.add(row("Profit", valueLabel(changeText(profit, pct), color), LABEL_COLOR));

		card.add(content, BorderLayout.CENTER);
		card.add(new StatusStrip(color), BorderLayout.SOUTH);
		return card;
	}

	private static JPanel summaryContent(String title)
	{
		JPanel content = new JPanel();
		content.setOpaque(false);
		content.setLayout(new BoxLayout(content, BoxLayout.PAGE_AXIS));
		content.setBorder(new EmptyBorder(6, 8, 6, 8));
		if (title != null)
		{
			// full width wrapper so the title centers over the whole card
			JPanel titleRow = new JPanel(new BorderLayout());
			titleRow.setOpaque(false);
			titleRow.setAlignmentX(Component.LEFT_ALIGNMENT);
			JLabel titleLabel = new JLabel(title, SwingConstants.CENTER);
			titleLabel.setFont(FontManager.getRunescapeBoldFont());
			titleLabel.setForeground(Color.WHITE);
			titleRow.add(titleLabel, BorderLayout.CENTER);
			content.add(titleRow);
		}
		return content;
	}

	/** Collapsible section header bar, e.g. "Sold (2)". */
	private JPanel sectionHeader(String title, int count, boolean collapsed, Runnable onToggle)
	{
		JPanel header = new JPanel(new BorderLayout());
		header.setBackground(INSET_BG_COLOR);
		header.setBorder(new CompoundBorder(
			new MatteBorder(1, 1, 1, 1, CARD_EDGE_COLOR),
			new EmptyBorder(4, 8, 4, 8)));
		header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

		JLabel text = new JLabel(title + " (" + count + ")");
		text.setFont(FontManager.getRunescapeBoldFont());
		text.setForeground(Color.WHITE);
		header.add(text, BorderLayout.CENTER);

		JLabel arrow = new JLabel(collapsed ? "▼" : "▲");
		arrow.setFont(FontManager.getRunescapeSmallFont());
		arrow.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		header.add(arrow, BorderLayout.EAST);

		header.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				onToggle.run();
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				header.setBackground(CARD_HOVER_COLOR);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				header.setBackground(INSET_BG_COLOR);
			}
		});
		return header;
	}

	private JPanel buildTrackedCard(TrackedItem item, long currentUnitPrice, AsyncBufferedImage icon)
	{
		final int itemId = item.getItemId();
		final long quantity = item.totalQuantity();
		final long paid = item.totalSpent();
		final boolean sold = item.fullySold();
		final long now = currentUnitPrice > 0 ? currentUnitPrice * quantity : 0;
		final boolean hasPrice = currentUnitPrice > 0 && paid > 0;
		final boolean pending = pendingUnstar.contains(itemId);

		final long diff;
		final double pct;
		if (sold)
		{
			diff = item.getSoldReceived() - paid;
			pct = paid > 0 ? 100.0 * diff / paid : 0;
		}
		else
		{
			diff = now - paid;
			pct = hasPrice ? 100.0 * diff / paid : 0;
		}

		// a card pending removal gets dulled entirely
		final Color labelColor = pending ? DIM_COLOR : LABEL_COLOR;
		final Color valueColor = pending ? DIM_COLOR : Color.WHITE;
		final Color subValueColor = pending ? DIM_COLOR : ColorScheme.LIGHT_GRAY_COLOR;
		final Color changeColor = pending ? DIM_COLOR
			: ((sold || hasPrice) ? changeColor(pct) : ColorScheme.MEDIUM_GRAY_COLOR);

		final JPanel card = cardShell();

		JPanel header = new JPanel(new BorderLayout(6, 0));
		header.setOpaque(false);
		header.setBorder(new EmptyBorder(4, 4, 2, 6));
		header.add(iconLabel(icon), BorderLayout.WEST);

		JLabel nameLabel = new JLabel(item.getItemName());
		nameLabel.setFont(FontManager.getRunescapeBoldFont());
		nameLabel.setForeground(valueColor);
		header.add(nameLabel, BorderLayout.CENTER);
		header.add(trackedStar(itemId, item.getItemName(), pending), BorderLayout.EAST);
		card.add(header, BorderLayout.NORTH);

		JPanel content = new JPanel();
		content.setOpaque(false);
		content.setLayout(new BoxLayout(content, BoxLayout.PAGE_AXIS));
		content.setBorder(new EmptyBorder(0, 8, 4, 8));

		String quantitySuffix = quantity > 1 ? " (×" + formatQty(quantity) + ")" : "";
		if (sold)
		{
			content.add(row("Bought", valueLabel(formatGp(paid) + quantitySuffix, valueColor), labelColor));
			content.add(row("Sold", valueLabel(formatGp(item.getSoldReceived()), valueColor), labelColor));
			content.add(row("Outcome", valueLabel(changeText(diff, pct), changeColor), labelColor));
			if (item.getLastSaleTime() > 0)
			{
				content.add(row("Sold", valueLabel(timeText(item.getLastSaleTime(), false), subValueColor), labelColor));
			}
		}
		else
		{
			content.add(row("Paid", valueLabel(formatGp(paid) + quantitySuffix, valueColor), labelColor));
			content.add(row("Now", valueLabel(hasPrice
					? formatGp(now) + (quantity > 1 ? " (" + formatGp(currentUnitPrice) + " ea)" : "")
					: "no price data",
				hasPrice ? valueColor : DIM_COLOR), labelColor));
			if (hasPrice)
			{
				content.add(row("Change", valueLabel(changeText(diff, pct), changeColor), labelColor));
			}
			PurchaseRecord latest = item.getPurchases().isEmpty()
				? null
				: item.getPurchases().get(item.getPurchases().size() - 1);
			if (latest != null)
			{
				content.add(row("Bought", valueLabel(timeText(latest.getTime(), latest.isApproximate()),
					subValueColor), labelColor));
			}
			if (item.getSoldQuantity() > 0)
			{
				content.add(row("Sold so far", valueLabel("×" + formatQty(item.getSoldQuantity())
					+ " for " + formatGp(item.getSoldReceived()), subValueColor), labelColor));
			}
		}

		card.add(content, BorderLayout.CENTER);

		// footer: breakdown when open, expand bar, then the status strip
		JPanel bottom = new JPanel();
		bottom.setLayout(new BoxLayout(bottom, BoxLayout.PAGE_AXIS));
		bottom.setOpaque(false);

		final boolean expanded = expandedItems.contains(itemId);
		final int purchaseCount = item.getPurchases().size();
		final boolean expandable = purchaseCount > 1 || item.getCarriedQuantity() > 0;
		if (expandable)
		{
			final JPanel expandBar = new JPanel(new BorderLayout());
			expandBar.setBackground(INSET_BG_COLOR);
			expandBar.setBorder(new CompoundBorder(
				new MatteBorder(1, 0, 0, 0, CARD_EDGE_COLOR),
				new EmptyBorder(4, 8, 4, 8)));
			expandBar.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

			JLabel expandText = new JLabel(expanded
				? "Hide purchases"
				: "View " + purchaseCount + " purchases", SwingConstants.CENTER);
			expandText.setFont(FontManager.getRunescapeSmallFont());
			expandText.setForeground(pending ? DIM_COLOR : Color.WHITE);
			expandBar.add(expandText, BorderLayout.CENTER);

			JLabel expandArrow = new JLabel(expanded ? "▲" : "▼");
			expandArrow.setFont(FontManager.getRunescapeSmallFont());
			expandArrow.setForeground(pending ? DIM_COLOR : ColorScheme.LIGHT_GRAY_COLOR);
			expandBar.add(expandArrow, BorderLayout.EAST);

			expandBar.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseClicked(MouseEvent e)
				{
					toggleExpanded(itemId);
				}

				@Override
				public void mouseEntered(MouseEvent e)
				{
					expandBar.setBackground(CARD_HOVER_COLOR);
				}

				@Override
				public void mouseExited(MouseEvent e)
				{
					expandBar.setBackground(INSET_BG_COLOR);
				}
			});

			if (expanded)
			{
				// grid with a shared date column so the dates line up, the
				// rs font digits are not all the same width
				JPanel purchasesPanel = new JPanel(new GridBagLayout());
				purchasesPanel.setBackground(INSET_BG_COLOR);
				purchasesPanel.setBorder(new CompoundBorder(
					new MatteBorder(1, 0, 0, 0, CARD_EDGE_COLOR),
					new EmptyBorder(4, 8, 6, 8)));

				Color priceColor = pending ? DIM_COLOR : LABEL_COLOR;
				GridBagConstraints priceConstraints = new GridBagConstraints();
				priceConstraints.gridx = 0;
				priceConstraints.weightx = 1;
				priceConstraints.anchor = GridBagConstraints.LINE_START;
				priceConstraints.fill = GridBagConstraints.HORIZONTAL;
				priceConstraints.insets = new Insets(1, 0, 1, 6);
				GridBagConstraints dateConstraints = new GridBagConstraints();
				dateConstraints.gridx = 1;
				dateConstraints.anchor = GridBagConstraints.LINE_START;
				dateConstraints.insets = new Insets(1, 0, 1, 0);

				List<PurchaseRecord> purchases = item.getPurchases();
				int shown = Math.min(purchases.size(), MAX_DETAIL_PURCHASES);
				int gridy = 0;
				for (int i = 0; i < shown; i++)
				{
					PurchaseRecord p = purchases.get(purchases.size() - 1 - i);
					String priceText = formatGp(p.unitPrice())
						+ (p.getQuantity() > 1 ? " (×" + formatQty(p.getQuantity()) + ")" : "");
					priceConstraints.gridy = gridy;
					dateConstraints.gridy = gridy;
					gridy++;

					JLabel priceLabel = new JLabel(priceText);
					priceLabel.setFont(FontManager.getRunescapeSmallFont());
					priceLabel.setForeground(priceColor);
					purchasesPanel.add(priceLabel, priceConstraints);

					JLabel dateLabel = new JLabel(timeText(p.getTime(), p.isApproximate()));
					dateLabel.setFont(FontManager.getRunescapeSmallFont());
					dateLabel.setForeground(subValueColor);
					purchasesPanel.add(dateLabel, dateConstraints);
				}
				int remaining = purchases.size() - shown;
				if (remaining > 0 || item.getCarriedQuantity() > 0)
				{
					long earlierQuantity = item.getCarriedQuantity();
					long earlierSpent = item.getCarriedSpent();
					for (int i = 0; i < remaining; i++)
					{
						PurchaseRecord p = purchases.get(i);
						earlierQuantity += p.getQuantity();
						earlierSpent += p.getSpent();
					}
					priceConstraints.gridy = gridy;
					dateConstraints.gridy = gridy;
					JLabel earlierLabel = new JLabel("+" + formatQty(earlierQuantity) + " earlier");
					earlierLabel.setFont(FontManager.getRunescapeSmallFont());
					earlierLabel.setForeground(priceColor);
					purchasesPanel.add(earlierLabel, priceConstraints);
					JLabel earlierValue = new JLabel(formatGp(earlierSpent));
					earlierValue.setFont(FontManager.getRunescapeSmallFont());
					earlierValue.setForeground(subValueColor);
					purchasesPanel.add(earlierValue, dateConstraints);
				}
				bottom.add(purchasesPanel);
			}
			bottom.add(expandBar);
		}

		bottom.add(new StatusStrip(pending ? DIM_COLOR
			: ((sold || hasPrice) ? changeColor : null)));
		card.add(bottom, BorderLayout.SOUTH);

		card.setToolTipText(trackedTooltip(item, hasPrice, now));
		card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		card.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (SwingUtilities.isLeftMouseButton(e) && expandable)
				{
					toggleExpanded(itemId);
				}
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				card.setBackground(CARD_HOVER_COLOR);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				card.setBackground(CARD_BG_COLOR);
			}
		});

		// right click to untrack, same soft removal as the star
		JPopupMenu menu = new JPopupMenu();
		JMenuItem untrack = new JMenuItem(pending
			? "Keep tracking " + item.getItemName()
			: "Untrack " + item.getItemName());
		untrack.addActionListener(e -> togglePendingUnstar(itemId));
		menu.add(untrack);
		card.setComponentPopupMenu(menu);
		inheritPopupMenuRecursively(card);

		return card;
	}

	private void toggleExpanded(int itemId)
	{
		if (!expandedItems.remove(itemId))
		{
			expandedItems.add(itemId);
		}
		refresh();
	}

	private void togglePendingUnstar(int itemId)
	{
		if (!pendingUnstar.remove(itemId))
		{
			pendingUnstar.add(itemId);
		}
		refresh();
	}

	/** Star on a tracked card. Unstarring is soft so it can be undone. */
	private JLabel trackedStar(int itemId, String itemName, boolean pending)
	{
		JLabel star = new JLabel(pending ? "☆" : "★");
		star.setFont(FontManager.getRunescapeBoldFont());
		star.setForeground(pending ? DIM_COLOR : STAR_COLOR);
		star.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		star.setToolTipText(pending ? "Keep tracking " + itemName : "Stop tracking " + itemName);
		star.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				togglePendingUnstar(itemId);
			}
		});
		return star;
	}

	private String trackedTooltip(TrackedItem item, boolean hasPrice, long now)
	{
		StringBuilder tip = new StringBuilder("<html>").append(item.getItemName())
			.append("<br>Paid: ").append(exactGp(item.totalSpent()));
		if (item.fullySold())
		{
			tip.append("<br>Sold: ").append(exactGp(item.getSoldReceived()));
		}
		else if (hasPrice)
		{
			tip.append("<br>Now: ").append(exactGp(now));
		}
		PurchaseRecord latest = item.getPurchases().isEmpty()
			? null
			: item.getPurchases().get(item.getPurchases().size() - 1);
		if (latest != null)
		{
			tip.append("<br>Last bought: ").append(timeText(latest.getTime(), latest.isApproximate()));
		}
		return tip.append("</html>").toString();
	}

	/**
	 * One card per item. Separate purchases of the same item are grouped with
	 * an expandable breakdown, while a single bulk offer is just one card.
	 */
	private JPanel buildRecentCard(List<PurchaseRecord> group, AsyncBufferedImage icon)
	{
		final PurchaseRecord newest = group.get(0);
		final int itemId = newest.getItemId();
		final String itemName = newest.getItemName();

		long totalQuantity = 0;
		long totalSpent = 0;
		for (PurchaseRecord record : group)
		{
			totalQuantity += record.getQuantity();
			totalSpent += record.getSpent();
		}
		long unitPrice = totalQuantity > 0 ? totalSpent / totalQuantity : 0;

		JPanel card = cardShell();

		JPanel header = new JPanel(new BorderLayout(6, 0));
		header.setOpaque(false);
		header.setBorder(new EmptyBorder(4, 4, 6, 6));
		header.add(iconLabel(icon), BorderLayout.WEST);

		JPanel lines = new JPanel();
		lines.setOpaque(false);
		lines.setLayout(new BoxLayout(lines, BoxLayout.PAGE_AXIS));

		JLabel nameLabel = new JLabel(itemName);
		nameLabel.setFont(FontManager.getRunescapeBoldFont());
		nameLabel.setForeground(Color.WHITE);
		nameLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		lines.add(nameLabel);

		// flag the unit price as an average when the purchases were made at
		// different prices
		boolean mixedPrices = false;
		for (PurchaseRecord record : group)
		{
			if (record.unitPrice() != newest.unitPrice())
			{
				mixedPrices = true;
				break;
			}
		}
		String info = totalQuantity > 1
			? (mixedPrices ? "avg " : "") + formatGp(unitPrice)
				+ " (×" + formatQty(totalQuantity) + ") = " + formatGp(totalSpent)
			: formatGp(totalSpent);
		JLabel infoLabel = new JLabel(info);
		infoLabel.setFont(FontManager.getRunescapeSmallFont());
		infoLabel.setForeground(LABEL_COLOR);
		infoLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		lines.add(infoLabel);

		lines.add(Box.createVerticalStrut(4));

		JLabel dateLabel = new JLabel(timeText(newest.getTime(), newest.isApproximate()));
		dateLabel.setFont(FontManager.getRunescapeSmallFont());
		dateLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		dateLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		lines.add(dateLabel);

		header.add(lines, BorderLayout.CENTER);

		JLabel star = recentStar(itemId, itemName);
		star.setVerticalAlignment(SwingConstants.TOP);
		header.add(star, BorderLayout.EAST);
		card.add(header, BorderLayout.CENTER);

		card.setToolTipText("<html>" + itemName
			+ "<br>Bought: " + formatQty(totalQuantity) + " × " + exactGp(unitPrice)
			+ "<br>Total: " + exactGp(totalSpent) + "</html>");

		if (group.size() > 1)
		{
			final boolean expanded = expandedRecentItems.contains(itemId);
			JPanel bottom = new JPanel();
			bottom.setLayout(new BoxLayout(bottom, BoxLayout.PAGE_AXIS));
			bottom.setOpaque(false);

			if (expanded)
			{
				JPanel purchasesPanel = new JPanel(new GridBagLayout());
				purchasesPanel.setBackground(INSET_BG_COLOR);
				purchasesPanel.setBorder(new CompoundBorder(
					new MatteBorder(1, 0, 0, 0, CARD_EDGE_COLOR),
					new EmptyBorder(4, 8, 6, 8)));

				GridBagConstraints priceConstraints = new GridBagConstraints();
				priceConstraints.gridx = 0;
				priceConstraints.weightx = 1;
				priceConstraints.anchor = GridBagConstraints.LINE_START;
				priceConstraints.fill = GridBagConstraints.HORIZONTAL;
				priceConstraints.insets = new Insets(1, 0, 1, 6);
				GridBagConstraints dateConstraints = new GridBagConstraints();
				dateConstraints.gridx = 1;
				dateConstraints.anchor = GridBagConstraints.LINE_START;
				dateConstraints.insets = new Insets(1, 0, 1, 0);

				int shown = Math.min(group.size(), MAX_DETAIL_PURCHASES);
				int gridy = 0;
				for (int i = 0; i < shown; i++)
				{
					PurchaseRecord record = group.get(i);
					priceConstraints.gridy = gridy;
					dateConstraints.gridy = gridy;
					gridy++;

					String priceText = formatGp(record.unitPrice())
						+ (record.getQuantity() > 1 ? " (×" + formatQty(record.getQuantity()) + ")" : "");
					JLabel priceLabel = new JLabel(priceText);
					priceLabel.setFont(FontManager.getRunescapeSmallFont());
					priceLabel.setForeground(LABEL_COLOR);
					purchasesPanel.add(priceLabel, priceConstraints);

					JLabel entryDate = new JLabel(timeText(record.getTime(), record.isApproximate()));
					entryDate.setFont(FontManager.getRunescapeSmallFont());
					entryDate.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
					purchasesPanel.add(entryDate, dateConstraints);
				}
				int remaining = group.size() - shown;
				if (remaining > 0)
				{
					long earlierQuantity = 0;
					long earlierSpent = 0;
					for (int i = shown; i < group.size(); i++)
					{
						PurchaseRecord record = group.get(i);
						earlierQuantity += record.getQuantity();
						earlierSpent += record.getSpent();
					}
					priceConstraints.gridy = gridy;
					dateConstraints.gridy = gridy;
					JLabel earlierLabel = new JLabel("+" + formatQty(earlierQuantity) + " earlier");
					earlierLabel.setFont(FontManager.getRunescapeSmallFont());
					earlierLabel.setForeground(LABEL_COLOR);
					purchasesPanel.add(earlierLabel, priceConstraints);
					JLabel earlierValue = new JLabel(formatGp(earlierSpent));
					earlierValue.setFont(FontManager.getRunescapeSmallFont());
					earlierValue.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
					purchasesPanel.add(earlierValue, dateConstraints);
				}
				bottom.add(purchasesPanel);
			}

			final JPanel expandBar = new JPanel(new BorderLayout());
			expandBar.setBackground(INSET_BG_COLOR);
			expandBar.setBorder(new CompoundBorder(
				new MatteBorder(1, 0, 0, 0, CARD_EDGE_COLOR),
				new EmptyBorder(4, 8, 4, 8)));
			expandBar.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

			JLabel expandText = new JLabel(expanded
				? "Hide purchases"
				: "View " + group.size() + " purchases", SwingConstants.CENTER);
			expandText.setFont(FontManager.getRunescapeSmallFont());
			expandText.setForeground(Color.WHITE);
			expandBar.add(expandText, BorderLayout.CENTER);

			JLabel expandArrow = new JLabel(expanded ? "▲" : "▼");
			expandArrow.setFont(FontManager.getRunescapeSmallFont());
			expandArrow.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			expandBar.add(expandArrow, BorderLayout.EAST);

			expandBar.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseClicked(MouseEvent e)
				{
					toggleRecentExpanded(itemId);
				}

				@Override
				public void mouseEntered(MouseEvent e)
				{
					expandBar.setBackground(CARD_HOVER_COLOR);
				}

				@Override
				public void mouseExited(MouseEvent e)
				{
					expandBar.setBackground(INSET_BG_COLOR);
				}
			});
			bottom.add(expandBar);
			card.add(bottom, BorderLayout.SOUTH);
		}

		return card;
	}

	private void toggleRecentExpanded(int itemId)
	{
		if (!expandedRecentItems.remove(itemId))
		{
			expandedRecentItems.add(itemId);
		}
		refresh();
	}

	/**
	 * Star toggle on a recent card. Starring applies to the item, so later
	 * buys attach to the same tracked entry.
	 */
	private JLabel recentStar(int itemId, String itemName)
	{
		final boolean tracked = dataManager.isTracked(itemId) && !pendingUnstar.contains(itemId);
		JLabel star = new JLabel(tracked ? "★" : "☆");
		star.setFont(FontManager.getRunescapeBoldFont());
		star.setHorizontalAlignment(SwingConstants.RIGHT);
		star.setForeground(tracked ? STAR_COLOR : DIM_COLOR);
		star.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		star.setToolTipText(tracked ? "Stop tracking " + itemName : "Track " + itemName);
		star.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (pendingUnstar.remove(itemId))
				{
					// was pending removal, keep it instead
					refresh();
					return;
				}
				if (dataManager.isTracked(itemId))
				{
					pendingUnstar.add(itemId);
				}
				else
				{
					dataManager.star(itemId, itemName);
				}
				refresh();
			}
		});
		return star;
	}

	/** Formats a purchase timestamp per the exact dates setting. */
	private String timeText(long time, boolean approximate)
	{
		if (config.absoluteDates())
		{
			Date date = new Date(time);
			return new SimpleDateFormat("yyyy-MM-dd").format(date)
				+ " (" + new SimpleDateFormat("HH:mm").format(date) + ")";
		}
		return RelativeTime.format(time);
	}

	private static JPanel cardShell()
	{
		JPanel card = new JPanel(new BorderLayout());
		card.setBackground(CARD_BG_COLOR);
		card.setBorder(new CompoundBorder(
			new MatteBorder(1, 1, 1, 1, CARD_EDGE_COLOR),
			new EmptyBorder(2, 0, 0, 0)));
		return card;
	}

	private static JPanel row(String labelText, JLabel value, Color labelColor)
	{
		JPanel row = new JPanel(new BorderLayout());
		row.setOpaque(false);
		row.setBorder(new EmptyBorder(2, 0, 2, 0));
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		JLabel label = new JLabel(labelText + ":");
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(labelColor);
		row.add(label, BorderLayout.WEST);
		row.add(value, BorderLayout.EAST);
		return row;
	}

	private static JLabel valueLabel(String text, Color color)
	{
		JLabel label = new JLabel(text);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(color);
		label.setHorizontalAlignment(SwingConstants.RIGHT);
		return label;
	}

	private static String changeText(long diff, double pct)
	{
		String arrow = diff > 0 ? "▲" : (diff < 0 ? "▼" : "•");
		return String.format("%s %s%s (%.1f%%)", arrow, diff > 0 ? "+" : "", formatGp(diff), Math.abs(pct));
	}

	/** Green when up or even, then yellow, orange, red as the loss grows. */
	private static Color changeColor(double pct)
	{
		if (pct >= 0)
		{
			return UP_COLOR;
		}
		if (pct >= -5)
		{
			return DOWN_SLIGHT_COLOR;
		}
		if (pct >= -15)
		{
			return DOWN_MODERATE_COLOR;
		}
		return DOWN_HEAVY_COLOR;
	}

	/** Colored strip along the bottom of a card. */
	private static class StatusStrip extends JPanel
	{
		private final Color color;

		StatusStrip(Color color)
		{
			this.color = color;
			setPreferredSize(new Dimension(0, 3));
			setOpaque(false);
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			super.paintComponent(g);
			g.setColor(color != null ? color : ColorScheme.DARK_GRAY_COLOR);
			g.fillRect(0, 0, getWidth(), getHeight());
		}
	}

	private static JLabel iconLabel(AsyncBufferedImage icon)
	{
		JLabel label = new JLabel();
		label.setPreferredSize(new Dimension(40, 36));
		label.setHorizontalAlignment(SwingConstants.CENTER);
		if (icon != null)
		{
			icon.addTo(label);
		}
		return label;
	}

	private static void inheritPopupMenuRecursively(JPanel panel)
	{
		for (Component child : panel.getComponents())
		{
			if (child instanceof JComponent)
			{
				((JComponent) child).setInheritsPopupMenu(true);
			}
			if (child instanceof JPanel)
			{
				inheritPopupMenuRecursively((JPanel) child);
			}
		}
	}

	private static GridBagConstraints listConstraints()
	{
		GridBagConstraints constraints = new GridBagConstraints();
		constraints.fill = GridBagConstraints.HORIZONTAL;
		constraints.weightx = 1;
		constraints.gridx = 0;
		constraints.gridy = 0;
		constraints.insets = new Insets(0, 0, 6, 0);
		return constraints;
	}

	private static void addListFiller(JPanel list, GridBagConstraints constraints)
	{
		// soak up leftover vertical space so cards stack from the top
		GridBagConstraints fillerConstraints = new GridBagConstraints();
		fillerConstraints.gridx = 0;
		fillerConstraints.gridy = constraints.gridy + 1;
		fillerConstraints.weighty = 1;
		JPanel filler = new JPanel();
		filler.setOpaque(false);
		list.add(filler, fillerConstraints);
	}

	private static JLabel emptyLabel(String html)
	{
		JLabel label = new JLabel(html, SwingConstants.CENTER);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		label.setBorder(BorderFactory.createEmptyBorder(20, 0, 20, 0));
		return label;
	}

	private static String formatGp(long gp)
	{
		long abs = Math.abs(gp);
		String formatted;
		if (abs >= 1_000_000_000L)
		{
			formatted = String.format("%.2fB", abs / 1_000_000_000.0);
		}
		else if (abs >= 1_000_000L)
		{
			formatted = String.format("%.1fM", abs / 1_000_000.0);
		}
		else if (abs >= 10_000L)
		{
			formatted = String.format("%.1fK", abs / 1_000.0);
		}
		else
		{
			// small values get a gp suffix so they read as money
			formatted = NumberFormat.getIntegerInstance().format(abs) + " gp";
		}
		return (gp < 0 ? "-" : "") + formatted;
	}

	private static String exactGp(long gp)
	{
		return NumberFormat.getIntegerInstance().format(gp) + " gp";
	}

	/** 10,000 rather than 10000. */
	private static String formatQty(long qty)
	{
		return NumberFormat.getIntegerInstance().format(qty);
	}
}
