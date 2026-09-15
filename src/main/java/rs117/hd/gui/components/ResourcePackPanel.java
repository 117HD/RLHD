/*
 * Copyright (c) 2021 Mark_ <https://github.com/Mark7625/>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package rs117.hd.gui.components;

import com.google.inject.Inject;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.awt.event.MouseAdapter;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.JTextArea;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.plaf.basic.BasicButtonUI;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.screenmarkers.ScreenMarkerPlugin;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.components.DragAndDropReorderPane;
import net.runelite.client.ui.components.IconTextField;
import net.runelite.client.ui.components.MouseDragEventForwarder;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.LinkBrowser;
import net.runelite.client.util.SwingUtil;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import rs117.hd.HdPluginConfig;
import rs117.hd.gui.HdSidebar;
import rs117.hd.resourcepacks.AbstractResourcePack;
import rs117.hd.resourcepacks.PackEventType;
import rs117.hd.resourcepacks.ResourcePackManager;
import rs117.hd.resourcepacks.ResourcePackStatus;
import rs117.hd.resourcepacks.ResourcePackUpdate;
import rs117.hd.resourcepacks.data.Manifest;
import rs117.hd.resourcepacks.impl.DefaultResourcePack;
import rs117.hd.utils.PopupUtils;


@Slf4j
public class ResourcePackPanel extends JPanel {
	private static final ImageIcon FADE;
	private static final ImageIcon DEV_ICON;
	private static final ImageIcon ARROW_UP;
	private static final ImageIcon ARROW_DOWN;
	private static final ImageIcon FOLDER;
	private static final ImageIcon REFRESH;
	private static final ImageIcon ADD_ICON;
	private static final ImageIcon BACK;
	private static final Color DISABLED_PACK_COLOR = new Color(0x252525);
	private static final BasicButtonUI SHADOW_TEXT_BUTTON_UI = new BasicButtonUI() {
		@Override
		protected void paintText(Graphics graphics, AbstractButton button, Rectangle textRect, String text) {
			graphics.setColor(Color.BLACK);
			graphics.drawString(text, textRect.x + 1, textRect.y + graphics.getFontMetrics().getAscent() + 1);
			super.paintText(graphics, button, textRect, text);
		}
	};
	private static final HttpUrl RAW_GITHUB_URL = HttpUrl.get("https://raw.githubusercontent.com/");
	private static final String MOVE_UP_BUTTON = "moveUpButton";
	private static final String MOVE_DOWN_BUTTON = "moveDownButton";
	private static final String DOWNLOAD_CARD_ID = "downloadCardId";
	private static final String DOWNLOAD_MANIFEST = "downloadManifest";
	private static final String DOWNLOAD_ACTION_BUTTON = "downloadActionButton";
	private static final int MAX_REMOTE_ICON_BYTES = 1024 * 1024;
	private static final long MAX_REMOTE_ICON_PIXELS = 1024L * 1024;

	static {
		FADE = new ImageIcon(ImageUtil.loadImageResource(HdSidebar.class, "fade.png"));
		DEV_ICON = new ImageIcon(ImageUtil.loadImageResource(HdSidebar.class, "dev_icon.png"));

		BufferedImage ARROW_UP_ICON = ImageUtil.loadImageResource(HdSidebar.class, "arrow_up.png");
		BufferedImage ARROW_DOWN_ICON = ImageUtil.loadImageResource(HdSidebar.class, "arrow_down.png");

		ARROW_UP = new ImageIcon(ARROW_UP_ICON);
		ARROW_DOWN = new ImageIcon(ARROW_DOWN_ICON);

		FOLDER = new ImageIcon(ImageUtil.loadImageResource(HdSidebar.class, "folder_icon.png"));
		REFRESH = new ImageIcon(ImageUtil.resizeImage(ImageUtil.loadImageResource(HdSidebar.class, "refresh.png"), 16, 16));
		ADD_ICON = new ImageIcon(ImageUtil.resizeImage(
			ImageUtil.loadImageResource(ScreenMarkerPlugin.class, "add_icon.png"), 16, 16));
		BACK = new ImageIcon(ImageUtil.flipImage(
			ImageUtil.loadImageResource(HdSidebar.class, "arrow_right.png"), true, false));
	}

	@Inject
	private OkHttpClient okHttpClient;

	@Inject
	private ResourcePackManager resourcePackManager;

	@Inject
	private Client client;

	@Inject
	private HdPluginConfig config;

	// Map to track download progress bars for each pack
	private final Map<String, JProgressBar> downloadProgressBars = new HashMap<>();
	private final Map<String, ImageIcon> packIcons = new ConcurrentHashMap<>();
	private final AtomicBoolean refreshQueued = new AtomicBoolean();

	private AbstractResourcePack draggedMovePack;
	private int draggedMoveFromIndex;
	private int draggedMoveToIndex;

	private enum PanelState { SELECTION, DOWNLOAD }

	private PanelState currentState = null;

	private final DragAndDropReorderPane list;
	private final Map<Component, AbstractResourcePack> draggablePacks = new IdentityHashMap<>();
	private Component draggedPackCard;
	private final JButton officialPacksButton;
	private final MessagePanel installHint = new MessagePanel(
		"Looking for more?",
		"Browse officially recognized resource packs with the green + button above."
	);
	private final JPanel filterPanel;
	private final IconTextField searchBar;

	ResourcePackPanel() {
		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setBorder(BorderFactory.createEmptyBorder());
		installHint.setBorder(BorderFactory.createEmptyBorder(20, 0, 38, 0));
		JPanel topControls = new JPanel();
		topControls.setLayout(new BoxLayout(topControls, BoxLayout.Y_AXIS));
		topControls.setBackground(ColorScheme.DARK_GRAY_COLOR);
		add(topControls, BorderLayout.NORTH);

		list = new DragAndDropReorderPane();
		list.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 3));
		list.setAlignmentX(Component.LEFT_ALIGNMENT);
		list.addDragListener(this::onPackDragged);
		installDragPadding();

		var scrollPane = new JScrollPane();
		scrollPane.setBorder(BorderFactory.createEmptyBorder(5, 0, 0, 0));
		scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);
		scrollPane.setPreferredSize(new Dimension(0x7000, 0x7000));

		JPanel scrollContainer = new FixedWidthPanel();
		scrollContainer.setLayout(new BorderLayout());
		JPanel listContent = new JPanel(new BorderLayout());
		listContent.setOpaque(false);
		listContent.add(list, BorderLayout.NORTH);
		JPanel hints = new JPanel();
		hints.setLayout(new BoxLayout(hints, BoxLayout.Y_AXIS));
		hints.setOpaque(false);
		hints.add(installHint);
		listContent.add(hints, BorderLayout.SOUTH);
		scrollContainer.add(listContent, BorderLayout.NORTH);
		scrollPane.setViewportView(scrollContainer);
		add(scrollPane, BorderLayout.CENTER);

		JPanel actions = new JPanel(new GridLayout(1, 2, 5, 0));
		actions.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
		actions.setBackground(ColorScheme.DARK_GRAY_COLOR);
		actions.setMaximumSize(new Dimension(Integer.MAX_VALUE, 35));
		officialPacksButton = new JButton();
		officialPacksButton.setFocusPainted(false);
		officialPacksButton.setMargin(new Insets(2, 4, 2, 4));
		officialPacksButton.setHorizontalTextPosition(SwingConstants.RIGHT);
		officialPacksButton.setIconTextGap(4);
		officialPacksButton.setToolTipText("Browse officially recognized resource packs");
		officialPacksButton.addActionListener(ev -> setState(
			currentState == PanelState.SELECTION ? PanelState.DOWNLOAD : PanelState.SELECTION));
		actions.add(officialPacksButton);
		JButton openFolderButton = new JButton("Open folder");
		openFolderButton.setFocusPainted(false);
		openFolderButton.setMargin(new Insets(2, 4, 2, 4));
		openFolderButton.setToolTipText("Open the local resource-pack folder");
		openFolderButton.addActionListener(ev -> LinkBrowser.open(resourcePackManager.getPackDirectory().getAbsolutePath()));
		actions.add(openFolderButton);
		topControls.add(actions);

		// Search bar
		searchBar = new IconTextField();
		searchBar.setIcon(IconTextField.Icon.SEARCH);
		searchBar.setPreferredSize(new Dimension(0, 30));
		searchBar.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		searchBar.setHoverBackgroundColor(ColorScheme.DARK_GRAY_HOVER_COLOR);
		searchBar.getDocument().addDocumentListener(new DocumentListener() {
			@Override
			public void insertUpdate(DocumentEvent e) {
				onSearchBarChanged();
			}

			@Override
			public void removeUpdate(DocumentEvent e) {
				onSearchBarChanged();
			}

			@Override
			public void changedUpdate(DocumentEvent e) {
				onSearchBarChanged();
			}
		});

		// Add common tags to search suggestions
		// Tags will be dynamically added from manifests when packs are loaded

		// Add filter panel at the top
		filterPanel = new JPanel();
		filterPanel.setLayout(new BorderLayout());
		filterPanel.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 11));
		filterPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel filterControls = new JPanel();
		filterControls.setLayout(new BorderLayout());
		filterControls.setBackground(ColorScheme.DARK_GRAY_COLOR);

		filterControls.add(searchBar, BorderLayout.CENTER);

		filterPanel.add(filterControls, BorderLayout.CENTER);
		filterPanel.setVisible(false);
		topControls.add(filterPanel);

		setState(PanelState.SELECTION);
	}

	private void setState(PanelState state) {
		if (currentState == state)
			return;
		currentState = state;
		officialPacksButton.setText(state == PanelState.SELECTION ? "Browse packs" : "My packs");
		officialPacksButton.setIcon(state == PanelState.SELECTION ? ADD_ICON : BACK);
		refreshPanel();
	}

	@Subscribe
	public void onResourcePackUpdate(ResourcePackUpdate event) {
		if (SwingUtilities.isEventDispatchThread())
			refreshForPackUpdate(event);
		else
			SwingUtilities.invokeLater(() -> refreshForPackUpdate(event));
	}

	private void refreshForPackUpdate(ResourcePackUpdate event) {
		if (currentState == PanelState.SELECTION && event.stateIs(PackEventType.ADDED, PackEventType.REMOVED)
			&& applyInstalledPackChange(event))
			return;
		if (currentState == PanelState.DOWNLOAD && event.stateIs(PackEventType.ADDED)
			&& downloadProgressBars.containsKey(event.getInternalName()))
			return;
		if (currentState == PanelState.DOWNLOAD && event.stateIs(PackEventType.REMOVED)
			&& configureDownloadablePackInstallAction(event.getInternalName()))
			return;
		if (currentState == PanelState.DOWNLOAD && event.stateIs(PackEventType.ADDED)
			&& replaceDownloadablePackCard(event.getInternalName()))
			return;
		refreshPanel();
	}

	private boolean configureDownloadablePackInstallAction(String internalName) {
		for (Component component : list.getComponents()) {
			if (!(component instanceof JComponent)
				|| !internalName.equals(((JComponent) component).getClientProperty(DOWNLOAD_CARD_ID)))
				continue;
			Manifest manifest = (Manifest) ((JComponent) component).getClientProperty(DOWNLOAD_MANIFEST);
			JButton actionButton = (JButton) ((JComponent) component).getClientProperty(DOWNLOAD_ACTION_BUTTON);
			if (manifest == null || actionButton == null)
				return false;
			configureInstallAction(actionButton, manifest, (JPanel) component, actionButton.getY());
			component.repaint();
			return true;
		}
		return false;
	}

	private boolean applyInstalledPackChange(ResourcePackUpdate event) {
		if (event.stateIs(PackEventType.ADDED)) {
			AbstractResourcePack pack = event.getPack();
			int index = resourcePackManager.getInstalledPacks().indexOf(pack);
			if (pack == null || index < 0)
				return false;
			JPanel component = wrapPackCard(createInstalledPackComponent(pack, index));
			list.add(component, index);
			draggablePacks.put(component, pack);
			attachDragEventForwarder(component);
		} else {
			Component component = draggablePacks.entrySet().stream()
				.filter(entry -> entry.getValue() == event.getPack())
				.map(Map.Entry::getKey)
				.findFirst()
				.orElse(null);
			if (component == null)
				return false;
			list.remove(component);
			draggablePacks.remove(component);
		}
		updateMoveButtons();
		list.setMaximumSize(new Dimension(Integer.MAX_VALUE, list.getPreferredSize().height));
		list.revalidate();
		list.repaint();
		return true;
	}

	private void refreshPanel() {
		if (!refreshQueued.compareAndSet(false, true))
			return;
		SwingUtilities.invokeLater(() -> {
			refreshQueued.set(false);
			list.removeAll();
			draggablePacks.clear();

			switch (currentState) {
				case SELECTION: {
					filterPanel.setVisible(false);

					var packs = resourcePackManager.getInstalledPacks();
					installHint.setVisible(packs.size() <= 1);
					for (int i = 0; i < packs.size(); i++) {
						AbstractResourcePack pack = packs.get(i);
						JPanel card = createInstalledPackComponent(pack, i);
						JPanel component = wrapPackCard(card);
						list.add(component);
						draggablePacks.put(component, pack);
						attachDragEventForwarder(component);
					}

					break;
				}
				case DOWNLOAD: {
					filterPanel.setVisible(true);
					installHint.setVisible(false);

					resourcePackManager.checkForUpdates();

					ResourcePackStatus status = resourcePackManager.getStatus();
					if (status != null) {
						list.add(new MessagePanel(status.getTitle(), status.getDescription()));
					} else {
						String searchQuery = searchBar.getText().toLowerCase(Locale.ROOT).trim();
						var allPacks = resourcePackManager.getDownloadablePacks();

						// Update search suggestions with tags from all packs
						updateSearchSuggestions(allPacks);

						for (var pack : allPacks) {
							// Filter by search query (display name and tags)
							if (!searchQuery.isEmpty()) {
								boolean matchesSearch = false;

								String displayName = pack.getDisplayName().toLowerCase(Locale.ROOT);
								if (displayName.contains(searchQuery)) {
									matchesSearch = true;
								}

								if (!matchesSearch && pack.getTags() != null) {
									for (String tag : pack.getTags()) {
										if (tag.toLowerCase(Locale.ROOT).contains(searchQuery)) {
											matchesSearch = true;
											break;
										}
									}
								}

								if (!matchesSearch) {
									continue;
								}
							}

							list.add(createDownloadablePackComponent(pack));
						}
					}
					break;
				}
			}
			list.setMaximumSize(new Dimension(Integer.MAX_VALUE, list.getPreferredSize().height));

			revalidate();
			repaint();
		});
	}

	private boolean replaceDownloadablePackCard(String internalName) {
		if (internalName.isEmpty())
			return false;
		Manifest manifest = resourcePackManager.getDownloadablePacks().stream()
			.filter(pack -> internalName.equals(pack.getInternalName()))
			.findFirst()
			.orElse(null);
		if (manifest == null)
			return false;
		for (int index = 0; index < list.getComponentCount(); index++) {
			Component component = list.getComponent(index);
			if (!(component instanceof JComponent)
				|| !internalName.equals(((JComponent) component).getClientProperty(DOWNLOAD_CARD_ID)))
				continue;
			list.remove(index);
			list.add(createDownloadablePackComponent(manifest), index);
			list.revalidate();
			list.repaint();
			return true;
		}
		return false;
	}

	private void onPackDragged(Component component) {
		AbstractResourcePack pack = draggablePacks.get(component);
		if (pack == null)
			return;

		int fromIndex = resourcePackManager.getInstalledPacks().indexOf(pack);
		if (fromIndex >= 0)
			movePack(fromIndex, list.getPosition(component), false);
	}

	private JPanel wrapPackCard(JPanel card) {
		JPanel wrapper = new JPanel(new BorderLayout());
		wrapper.setOpaque(false);
		wrapper.setBorder(BorderFactory.createEmptyBorder(0, 0, 5, 1));
		wrapper.setAlignmentX(Component.LEFT_ALIGNMENT);
		wrapper.add(card, BorderLayout.CENTER);
		return wrapper;
	}

	private void installDragPadding() {
		list.addMouseListener(new MouseAdapter() {
			@Override
			public void mousePressed(MouseEvent event) {
				for (Component component : list.getComponents()) {
					if (component.contains(event.getX() - component.getX(), event.getY() - component.getY())) {
						draggedPackCard = component;
						return;
					}
				}
			}

			@Override
			public void mouseReleased(MouseEvent event) {
				commitDraggedMove();
				draggedPackCard = null;
			}
		});
		list.addMouseMotionListener(new MouseAdapter() {
			@Override
			public void mouseDragged(MouseEvent event) {
				if (draggedPackCard != null) {
					int y = Math.max(list.getInsets().top, draggedPackCard.getY());
					draggedPackCard.setLocation(list.getInsets().left, y);
					updateMoveButtons();
				}
			}
		});
	}

	private void updateMoveButtons() {
		ArrayList<Component> cards = new ArrayList<>(draggablePacks.keySet());
		cards.sort(Comparator.comparingInt(Component::getY));
		for (int index = 0; index < cards.size(); index++) {
			JPanel wrapper = (JPanel) cards.get(index);
			JPanel card = (JPanel) wrapper.getComponent(0);
			JButton moveUp = (JButton) card.getClientProperty(MOVE_UP_BUTTON);
			JButton moveDown = (JButton) card.getClientProperty(MOVE_DOWN_BUTTON);
			moveUp.setEnabled(index > 0);
			moveDown.setEnabled(index < cards.size() - 1);
		}
	}

	private void movePack(int fromIndex, int toIndex) {
		movePack(fromIndex, toIndex, true);
	}

	private void movePack(int fromIndex, int toIndex, boolean commitImmediately) {
		var packs = resourcePackManager.getInstalledPacks();
		if (fromIndex < 0 || fromIndex >= packs.size())
			return;
		var pack = packs.get(fromIndex);
		int finalToIndex = resourcePackManager.movePack(fromIndex, toIndex);
		if (finalToIndex < 0)
			return;

		if (commitImmediately) {
			resourcePackManager.commitPackMove(pack, fromIndex, finalToIndex);
			return;
		}
		if (draggedMovePack != pack) {
			draggedMovePack = pack;
			draggedMoveFromIndex = fromIndex;
		}
		draggedMoveToIndex = finalToIndex;
	}

	private void commitDraggedMove() {
		if (draggedMovePack == null)
			return;

		resourcePackManager.commitPackMove(draggedMovePack, draggedMoveFromIndex, draggedMoveToIndex);
		draggedMovePack = null;
	}

	private JPanel createInstalledPackComponent(AbstractResourcePack pack, int index) {
		boolean compactView = config.compactView();
		boolean packEnabled = resourcePackManager.isPackEnabled(pack);
		boolean hasPackImage = pack.hasPackImage(compactView);

		int panelHeight = compactView ? 45 : 124;

		JPanel panel = new JPanel() {
			@Override
			public Point getToolTipLocation(MouseEvent event) {
				return new Point(5, panelHeight + 5);
			}
		};

		boolean isDefaultPack = pack instanceof DefaultResourcePack;
		boolean isTop = index == 0;
		int lastIndex = resourcePackManager.getInstalledPacks().size() - 1;

		panel.setBackground(packEnabled ? ColorScheme.DARKER_GRAY_COLOR : DISABLED_PACK_COLOR);
		panel.setOpaque(true);
		panel.setLayout(null);

		panel.setBounds(0, 0, 221, panelHeight);
		panel.setMinimumSize(new Dimension(221, panelHeight));
		panel.setPreferredSize(new Dimension(221, panelHeight));

		JButton moveDown = new JButton();
		moveDown.setText("");
		moveDown.setIcon(ARROW_DOWN);
		SwingUtil.removeButtonDecorations(moveDown);
		moveDown.setBounds(165, 5, 22, 22);
		moveDown.setToolTipText("Deprioritize this pack, or drag and drop to reorder");
		panel.add(moveDown);
		moveDown.setEnabled(index < lastIndex);
		moveDown.addActionListener(ev -> movePack(
			resourcePackManager.getInstalledPacks().indexOf(pack),
			resourcePackManager.getInstalledPacks().indexOf(pack) + 1
		));

		JButton moveUp = new JButton();
		moveUp.setText("");
		moveUp.setIcon(ARROW_UP);
		SwingUtil.removeButtonDecorations(moveUp);
		moveUp.setToolTipText("Prioritize this pack, or drag and drop to reorder");
		panel.add(moveUp);
		moveUp.setBounds(140, 5, 22, 22);
		moveUp.setEnabled(!isTop);
		moveUp.addActionListener(ev -> movePack(
			resourcePackManager.getInstalledPacks().indexOf(pack),
			resourcePackManager.getInstalledPacks().indexOf(pack) - 1
		));
		panel.putClientProperty(MOVE_UP_BUTTON, moveUp);
		panel.putClientProperty(MOVE_DOWN_BUTTON, moveDown);

		boolean hasFolderButton = pack.isDevelopmentPack();
		if (hasFolderButton) {
			JButton openFolder = new JButton();
			openFolder.setIcon(FOLDER);
			SwingUtil.removeButtonDecorations(openFolder);
			openFolder.setToolTipText("Open folder");
			openFolder.setBounds(115, 5, 22, 22);
			panel.add(openFolder);
			openFolder.addActionListener(ev -> LinkBrowser.open(pack.path.toFile().getAbsolutePath()));
		}

		Manifest manifest = pack.getManifest();
		Color textColor = packEnabled ? Color.WHITE : Color.GRAY;
		boolean modified = pack.isModified();
		boolean updateAvailable = resourcePackManager.hasUpdate(pack);
		boolean custom = !isDefaultPack && !resourcePackManager.isTrackedOfficialPack(pack);
		boolean invalid = !isDefaultPack && !pack.hasContent();
		String descriptionText = getDescription(pack, manifest, custom, invalid);

		// Author is always shown, but positioned differently in compact view
		JLabel author = new JLabel();
		UiText.setPlainText(author, manifest.getAuthor());
		author.setFont(FontManager.getRunescapeSmallFont());
		author.setToolTipText(null); // Don't override panel tooltip
		int authorY = compactView ? 28 : 105;
		author.setBounds(5, authorY, 65, author.getPreferredSize().height);
		author.setForeground(textColor);
		panel.add(author);

		JCheckBox enabled = new JCheckBox();
		enabled.setOpaque(false);
		enabled.setFocusPainted(false);
		enabled.setSelected(packEnabled);
		enabled.setEnabled(!(pack instanceof DefaultResourcePack));
		enabled.setToolTipText(pack instanceof DefaultResourcePack
			? "The built-in pack is always enabled"
			: packEnabled ? "Disable this pack" : "Enable this pack");
		enabled.setMargin(new Insets(0, 0, 0, 0));
		enabled.setBounds(190, 4, 25, 22);
		enabled.addActionListener(ev -> resourcePackManager.setPackEnabled(pack, enabled.isSelected()));
		panel.add(enabled);
		if (resourcePackManager.hasSettingsConflict(pack)) {
			JLabel settingsConflict = new JLabel("!");
			settingsConflict.setFont(FontManager.getRunescapeBoldFont());
			settingsConflict.setForeground(new Color(0xE0A13A));
			settingsConflict.setToolTipText("A setting applied by this pack was changed manually and will not be reverted automatically.");
			settingsConflict.setBounds(122, authorY, 12, settingsConflict.getPreferredSize().height);
			panel.add(settingsConflict);
		}

		if (!descriptionText.isEmpty()) {
			UiText.setPlainToolTip(panel, descriptionText);
			UiText.setPlainToolTip(author, descriptionText);
		}

		if (!compactView && !descriptionText.isEmpty()) {
			JTextArea description = createDescription(descriptionText);
			description.setToolTipText(null); // Don't override panel tooltip
			description.setBounds(5, 30, 210, 70);
			description.setForeground(textColor);
			panel.add(description);
		}

		// Stop before the checkbox, or before the preceding folder/repair button when present.
		int packNameEndX = modified || updateAvailable || hasFolderButton ? 115 : 140;
		int packNameWidth = packNameEndX - 5;
		JLabel packName = new JLabel();
		UiText.setPlainText(packName, manifest.getDisplayName());
		packName.setFont(FontManager.getRunescapeBoldFont());
		packName.setToolTipText(null); // Don't override panel tooltip
		packName.setBounds(5, 5, packNameWidth, 25);
		packName.setForeground(textColor);
		if (!descriptionText.isEmpty())
			UiText.setPlainToolTip(packName, descriptionText);
		panel.add(packName);
		if (modified || custom || invalid) {
			JLabel integrity = new JLabel(modified ? "Modified" : invalid ? "Invalid" : "Custom");
			integrity.setFont(FontManager.getRunescapeSmallFont());
			integrity.setForeground(modified || invalid ? new Color(0xE0A13A) : Color.GRAY);
			UiText.setPlainToolTip(
				integrity, modified
					? "This official pack has been modified. Re-download it to restore the official archive."
					: invalid ? "This pack contains no usable resource files." : "This is a locally installed custom pack."
			);
			int integrityWidth = integrity.getPreferredSize().width;
			integrity.setBounds(215 - integrityWidth, authorY, integrityWidth, integrity.getPreferredSize().height);
			panel.add(integrity);
		}
		if (updateAvailable) {
			JButton update = new JButton("↓");
			update.setFont(FontManager.getRunescapeBoldFont());
			update.setForeground(new Color(0x28BE28));
			SwingUtil.removeButtonDecorations(update);
			update.setToolTipText("An official update is available. Download and install it.");
			update.setBounds(115, 5, 22, 22);
			update.addActionListener(ev -> resourcePackManager.updateResourcePack(pack));
			panel.add(update);
		} else if (modified) {
			JButton repair = new JButton(REFRESH);
			SwingUtil.removeButtonDecorations(repair);
			repair.setToolTipText("This official pack has been modified. Re-download the official archive.");
			repair.setBounds(115, 5, 22, 22);
			repair.addActionListener(ev -> resourcePackManager.redownloadResourcePack(pack));
			panel.add(repair);
		}


		JLabel icon = new JLabel();
		icon.setHorizontalAlignment(JLabel.CENTER);

		JLabel blackBox = new JLabel();
		blackBox.setIcon(FADE);

		if (hasPackImage) {
			icon.setIcon(new ImageIcon(pack.getPackImage(compactView)));
			icon.setVisible(true);
			blackBox.setVisible(true);
		} else {
			icon.setVisible(false);
			blackBox.setVisible(false);
		}
		icon.setBounds(new Rectangle(new Point(0, 0), icon.getPreferredSize()));
		blackBox.setBounds(0, 0, 221, panelHeight);

		panel.add(blackBox);
		panel.add(icon);
		if (pack.isDevelopmentPack() && !hasPackImage) {
			JLabel developmentMarker = new JLabel(DEV_ICON);
			developmentMarker.setToolTipText("This pack is loaded from a local development path.");
			int markerWidth = DEV_ICON.getIconWidth();
			int markerHeight = DEV_ICON.getIconHeight();
			developmentMarker.setBounds(30, (panelHeight - markerHeight) / 2, markerWidth, markerHeight);
			panel.add(developmentMarker);
			panel.setComponentZOrder(developmentMarker, panel.getComponentCount() - 1);
		}
		if (!isDefaultPack)
			addPackMenu(panel, pack, resourcePackManager.isTrackedOfficialPack(pack));
		return panel;
	}

	private void addPackMenu(JPanel panel, AbstractResourcePack pack, boolean official) {
		JPopupMenu menu = new JPopupMenu();
		if (official && resourcePackManager.hasUpdate(pack)) {
			JMenuItem update = new JMenuItem("Update");
			update.addActionListener(event -> resourcePackManager.updateResourcePack(pack));
			menu.add(update);
		}
		JMenuItem remove = new JMenuItem(official ? "Uninstall" : "Delete");
		remove.addActionListener(event -> confirmPackRemoval(panel, pack, official));
		menu.add(remove);
		panel.setComponentPopupMenu(menu);
		for (Component component : panel.getComponents()) {
			if (component instanceof JComponent && !(component instanceof AbstractButton))
				((JComponent) component).setComponentPopupMenu(menu);
		}
	}

	private void confirmPackRemoval(Component anchor, AbstractResourcePack pack, boolean official) {
		String packName = UiText.stripTags(pack.getManifest().getDisplayName());
		PopupUtils.displayPopupMessage(
			client,
			anchor,
			official ? "Uninstall resource pack" : "Delete local resource pack",
			official
				? "Do you really want to uninstall &quot;" + packName + "&quot;?"
				: "Do you really want to delete the local resource pack &quot;" + packName + "&quot;?<br><br>" +
				  "The associated files will be permanently deleted from your computer.",
			new String[] { "Cancel", official ? "Uninstall" : "Delete" },
			buttonIndex -> {
				if (buttonIndex == 1)
					resourcePackManager.removeResourcePack(pack.getManifest().getInternalName());
				return true;
			}
		);
	}

	private static String getDescription(AbstractResourcePack pack, Manifest manifest, boolean custom, boolean invalid) {
		String description = manifest.getDescription();
		if (description != null && !description.trim().isEmpty())
			return description;
		if (!custom)
			return "";

		String source = pack.path.isFileSystemResource() && pack.path.toFile().isDirectory() ? "folder" : "archive";
		if (!pack.isValid())
			return invalid
				? "Empty custom resource pack from a local " + source + " without a manifest."
				: "Custom resource pack from a local " + source + " without a manifest.";
		if (pack.isDevelopmentPack())
			return "Local development resource pack.";
		return invalid
			? "Empty custom resource pack from a local " + source + "."
			: "Custom resource pack from a local " + source + ".";
	}

	private void attachDragEventForwarder(Component component) {
		if (component instanceof JButton)
			return;
		MouseDragEventForwarder forwarder = new MouseDragEventForwarder(list);
		component.addMouseListener(forwarder);
		component.addMouseMotionListener(forwarder);
		if (component instanceof JPanel)
			for (Component child : ((JPanel) component).getComponents())
				attachDragEventForwarder(child);
	}

	private JPanel createDownloadablePackComponent(Manifest manifest) {
		boolean compactView = config.compactView();
		int panelHeight = compactView ? 60 : 124;
		final int finalPanelHeight = panelHeight;

		JPanel panel = new JPanel() {
			@Override
			public Point getToolTipLocation(MouseEvent event) {
				return new Point(5, finalPanelHeight + 5);
			}
		};

		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setOpaque(true);
		panel.putClientProperty(DOWNLOAD_CARD_ID, manifest.getInternalName());
		panel.putClientProperty(DOWNLOAD_MANIFEST, manifest);
		panel.setLayout(null);
		panel.setBounds(0, 0, 221, panelHeight);
		panel.setMinimumSize(new Dimension(221, panelHeight));
		panel.setPreferredSize(new Dimension(221, panelHeight));

		JLabel author = new JLabel();
		UiText.setPlainText(author, manifest.getAuthor());
		author.setFont(FontManager.getRunescapeSmallFont());
		author.setToolTipText(null);
		int authorY = compactView ? 28 : 105;
		author.setBounds(5, authorY, 65, author.getPreferredSize().height);
		author.setForeground(Color.WHITE);
		panel.add(author);

		String descriptionText = manifest.getDescription();
		if (descriptionText != null && !descriptionText.isEmpty()) {
			UiText.setPlainToolTip(panel, descriptionText);
		}
		if (!compactView && descriptionText != null && !descriptionText.isEmpty()) {
			JTextArea description = createDescription(descriptionText);
			description.setToolTipText(null); // Don't override panel tooltip
			description.setBounds(5, 30, 210, 70);
			description.setForeground(Color.WHITE);
			panel.add(description);
		}

		JLabel packName = new JLabel();
		UiText.setPlainText(packName, manifest.getDisplayName());
		packName.setFont(FontManager.getRunescapeBoldFont());
		packName.setToolTipText(null);
		packName.setBounds(5, 5, 200, 25);
		packName.setForeground(Color.WHITE);
		panel.add(packName);

		String internalName = manifest.getInternalName();
		int buttonY = compactView ? 28 : 97;

		JButton actionButton = new JButton();
		actionButton.setUI(SHADOW_TEXT_BUTTON_UI);
		actionButton.setFocusPainted(false);
		actionButton.setMargin(new Insets(2, 4, 2, 4));
		actionButton.setToolTipText(null);
		panel.putClientProperty(DOWNLOAD_ACTION_BUTTON, actionButton);
		boolean notInstalled = resourcePackManager.getInstalledPack(internalName) == null;
		if (notInstalled) {
			configureInstallAction(actionButton, manifest, panel, buttonY);
		} else if (resourcePackManager.hasUpdate(resourcePackManager.getInstalledPack(internalName))) {
			actionButton.setText("Update");
			actionButton.setBackground(new Color(0x28BE28));
			actionButton.setToolTipText("An official update is available.");
			actionButton.addActionListener(event -> {
				AbstractResourcePack installedPack = resourcePackManager.getInstalledPack(internalName);
				if (installedPack != null)
					resourcePackManager.updateResourcePack(installedPack);
			});
		} else {
			configureUninstallAction(actionButton, internalName);
		}
		actionButton.setBounds(115, buttonY, 105, 25);

		JLabel icon = new JLabel();
		icon.setHorizontalAlignment(JLabel.CENTER);

		JLabel blackBox = new JLabel();
		blackBox.setIcon(FADE);

		icon.setVisible(false);
		blackBox.setVisible(false);
		icon.setBounds(0, 0, 221, panelHeight);
		blackBox.setBounds(0, 0, 221, panelHeight);

		if (manifest.hasIcon()) {
			String iconFileName = compactView ? "compact-icon.png" : "icon.png";
			String iconKey = manifest.getInternalName() + ':' + manifest.getCommit() + ':' + iconFileName;
			ImageIcon cachedIcon = packIcons.get(iconKey);
			if (cachedIcon != null) {
				showIcon(cachedIcon, icon, blackBox, panel);
				panel.add(actionButton);
				panel.add(blackBox);
				panel.add(icon);
				return panel;
			}

			okHttpClient
				.newCall(new Request.Builder()
					.url(RAW_GITHUB_URL
						.newBuilder()
						.addPathSegment(manifest.getLink().replace("https://github.com/", ""))
						.addPathSegment(manifest.getCommit())
						.addPathSegment(iconFileName)
						.build())
					.build())
				.enqueue(new Callback() {
					@Override
					public void onFailure(Call call, IOException ex) {
						if (compactView) {
							downloadRegularIcon(manifest, icon, blackBox, panel);
						} else {
							log.warn("Unable to download icon for pack \"{}\"", manifest.getInternalName(), ex);
						}
					}

					@Override
					public void onResponse(Call call, Response res) {
						byte[] bytes;
						try (Response ignored = res) {
							if (!res.isSuccessful() || res.body() == null)
								throw new IOException("Unexpected icon response: " + res.code());
							bytes = res.body().bytes();
						} catch (IOException ex) {
							if (compactView)
								downloadRegularIcon(manifest, icon, blackBox, panel);
							else
								log.warn("Unable to download icon for pack \"{}\"", manifest.getInternalName(), ex);
							return;
						}
						BufferedImage img;
						try {
							synchronized (ImageIO.class) {
								img = ImageIO.read(new ByteArrayInputStream(bytes));
							}
						} catch (IOException ex) {
							if (compactView)
								downloadRegularIcon(manifest, icon, blackBox, panel);
							else
								log.warn("Unable to decode icon for pack \"{}\"", manifest.getInternalName(), ex);
							return;
						}

						if (img != null) {
							if (compactView) {
								// Scale image to match panel height
								int originalWidth = img.getWidth();
								int originalHeight = img.getHeight();
								int targetWidth = (originalWidth * panelHeight) / originalHeight;
								Image scaled = img.getScaledInstance(targetWidth, panelHeight, Image.SCALE_SMOOTH);
								img = new BufferedImage(targetWidth, panelHeight, BufferedImage.TYPE_INT_ARGB);
								Graphics2D g2d = img.createGraphics();
								g2d.drawImage(scaled, 0, 0, null);
								g2d.dispose();
							}

							ImageIcon imageIcon = new ImageIcon(img);
							packIcons.putIfAbsent(iconKey, imageIcon);
							SwingUtilities.invokeLater(() -> {
								if (panel.getParent() != null)
									showIcon(imageIcon, icon, blackBox, panel);
							});
						} else {
							if (compactView) {
								downloadRegularIcon(manifest, icon, blackBox, panel);
							} else {
								log.warn("Received null icon for pack \"{}\"", manifest.getInternalName());
							}
						}
					}
				});
		}

		panel.add(actionButton);
		panel.add(blackBox);
		panel.add(icon);

		return panel;
	}

	private void configureUninstallAction(JButton actionButton, String internalName) {
		for (var listener : actionButton.getActionListeners())
			actionButton.removeActionListener(listener);
		actionButton.setText("Uninstall");
		actionButton.setBackground(new Color(0xBE2828));
		actionButton.setToolTipText(null);
		actionButton.addActionListener(event -> {
			AbstractResourcePack installedPack = resourcePackManager.getInstalledPack(internalName);
			if (installedPack != null)
				resourcePackManager.removeResourcePack(installedPack.getManifest().getInternalName());
		});
	}

	private void configureInstallAction(JButton actionButton, Manifest manifest, JPanel panel, int buttonY) {
		String internalName = manifest.getInternalName();
		for (var listener : actionButton.getActionListeners())
			actionButton.removeActionListener(listener);
		actionButton.setText("Install");
		actionButton.setBackground(new Color(0x28BE28));
		actionButton.setToolTipText(null);
		actionButton.addActionListener(event -> {
			replaceButtonWithProgressBar(internalName, panel, actionButton, buttonY);
			resourcePackManager.downloadResourcePack(
				manifest,
				progress -> updateDownloadProgress(internalName, panel, progress),
				() -> finishDownload(internalName, panel, actionButton),
				failureMessage -> failDownload(internalName, panel, actionButton, failureMessage),
				false
			);
		});
	}

	private void updateDownloadProgress(String internalName, JPanel panel, int progress) {
		JProgressBar progressBar = downloadProgressBars.get(internalName);
		if (progressBar == null)
			return;
		if (progress == -2) {
			progressBar.setString("Waiting...");
			progressBar.setToolTipText("Waiting for confirmation");
		} else if (progress < 0) {
			progressBar.setString("Downloading...");
			progressBar.setToolTipText(null);
		} else {
			int clampedProgress = Math.min(100, progress);
			progressBar.setValue(clampedProgress);
			progressBar.setString(clampedProgress + "%");
			progressBar.setToolTipText(null);
		}
		panel.repaint();
	}

	private void finishDownload(String internalName, JPanel panel, JButton actionButton) {
		JProgressBar progressBar = downloadProgressBars.remove(internalName);
		if (progressBar == null)
			return;
		panel.remove(progressBar);
		configureUninstallAction(actionButton, internalName);
		panel.add(actionButton);
		panel.setComponentZOrder(actionButton, 0);
		panel.revalidate();
		panel.repaint();
	}

	private void failDownload(String internalName, JPanel panel, JButton actionButton, String failureMessage) {
		JProgressBar progressBar = downloadProgressBars.remove(internalName);
		if (progressBar == null)
			return;
		if (failureMessage == null) {
			configureInstallAction(actionButton, (Manifest) panel.getClientProperty(DOWNLOAD_MANIFEST), panel, actionButton.getY());
			panel.remove(progressBar);
			panel.add(actionButton);
			panel.revalidate();
			panel.repaint();
			return;
		}
		panel.remove(progressBar);
		actionButton.setText("Failed. Retry?");
		actionButton.setBackground(new Color(0xFFFF00));
		UiText.setPlainToolTip(actionButton, failureMessage + " Click to retry.");
		panel.add(actionButton);
		panel.setComponentZOrder(actionButton, 0);
		panel.revalidate();
		panel.repaint();
	}

	private static void showIcon(ImageIcon imageIcon, JLabel icon, JLabel blackBox, JPanel panel) {
		icon.setIcon(imageIcon);
		icon.setVisible(true);
		blackBox.setVisible(true);
		panel.revalidate();
		panel.repaint();
	}

	private static JTextArea createDescription(String text) {
		JTextArea description = new JTextArea();
		UiText.setPlainText(description, text);
		description.setFont(FontManager.getRunescapeSmallFont());
		description.setForeground(Color.WHITE);
		description.setLineWrap(true);
		description.setWrapStyleWord(true);
		description.setEditable(false);
		description.setFocusable(false);
		description.setOpaque(false);
		description.setBorder(null);
		return description;
	}

	private void replaceButtonWithProgressBar(String internalName, JPanel panel, JButton button, int y) {
		panel.remove(button);

		JProgressBar progressBar = new JProgressBar(0, 100);
		progressBar.setStringPainted(true);
		progressBar.setString("0%");
		progressBar.setValue(0);
		progressBar.setBounds(115, y, 105, 25);
		progressBar.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		progressBar.setForeground(new Color(0x28BE28));
		progressBar.setOpaque(true);
		progressBar.setVisible(true);
		progressBar.setBorderPainted(true);

		downloadProgressBars.put(internalName, progressBar);

		panel.add(progressBar);
		panel.setComponentZOrder(progressBar, 0);
		panel.revalidate();
		panel.repaint();
	}

	private void onSearchBarChanged() {
		if (currentState == PanelState.DOWNLOAD) {
			refreshPanel();
		}
	}

	private void updateSearchSuggestions(java.util.Collection<Manifest> packs) {
		var suggestionModel = searchBar.getSuggestionListModel();
		suggestionModel.clear();

		// Collect all unique tags from all packs
		Set<String> allTags = new HashSet<>();
		for (var pack : packs) {
			if (pack.getTags() != null) {
				for (String tag : pack.getTags())
					allTags.add(UiText.stripTags(tag));
			}
		}

		// Add tags to suggestions
		allTags.stream()
			.sorted()
			.forEach(suggestionModel::addElement);
	}

	private void downloadRegularIcon(Manifest manifest, JLabel icon, JLabel blackBox, JPanel panel) {
		okHttpClient
			.newCall(new Request.Builder()
				.url(RAW_GITHUB_URL
					.newBuilder()
					.addPathSegment(manifest.getLink().replace("https://github.com/", ""))
					.addPathSegment(manifest.getCommit())
					.addPathSegment("icon.png")
					.build())
				.build())
			.enqueue(new Callback() {
				@Override
				public void onFailure(Call call, IOException ex) {
					log.warn("Unable to download regular icon for pack \"{}\"", manifest.getInternalName(), ex);
				}

				@Override
				public void onResponse(Call call, Response res) {
					byte[] bytes;
					try (Response ignored = res) {
						if (!res.isSuccessful() || res.body() == null)
							throw new IOException("Unexpected icon response: " + res.code());
						if (res.body().contentLength() > MAX_REMOTE_ICON_BYTES)
							throw new IOException("Icon response exceeds " + MAX_REMOTE_ICON_BYTES + " bytes");
						bytes = readLimited(res.body().byteStream(), MAX_REMOTE_ICON_BYTES);
					} catch (IOException ex) {
						log.warn("Unable to download regular icon for pack \"{}\"", manifest.getInternalName(), ex);
						return;
					}
					BufferedImage img;
					try {
						img = decodeIcon(bytes);
					} catch (IOException ex) {
						log.warn("Unable to decode regular icon for pack \"{}\"", manifest.getInternalName(), ex);
						return;
					}

					if (img != null) {
						SwingUtilities.invokeLater(() -> {
							if (panel.getParent() == null)
								return;
							icon.setIcon(new ImageIcon(img));
							icon.setVisible(true);
							panel.revalidate();
							panel.repaint();
						});
					} else {
						log.warn("Received null regular icon for pack \"{}\"", manifest.getInternalName());
					}
				}
			});
	}

	private static byte[] readLimited(InputStream input, int limit) throws IOException {
		try (InputStream ignored = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			byte[] buffer = new byte[4096];
			for (int read; (read = input.read(buffer)) != -1;) {
				if (output.size() + read > limit)
					throw new IOException("Icon response exceeds " + limit + " bytes");
				output.write(buffer, 0, read);
			}
			return output.toByteArray();
		}
	}

	private static BufferedImage decodeIcon(byte[] bytes) throws IOException {
		try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
			if (input == null)
				throw new IOException("Unable to read icon data");

			Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
			if (!readers.hasNext())
				throw new IOException("Icon is not a supported image");

			ImageReader reader = readers.next();
			try {
				reader.setInput(input, true, true);
				int width = reader.getWidth(0);
				int height = reader.getHeight(0);
				if (width <= 0 || height <= 0 || (long) width * height > MAX_REMOTE_ICON_PIXELS)
					throw new IOException("Icon dimensions exceed " + MAX_REMOTE_ICON_PIXELS + " pixels");
				return reader.read(0);
			} finally {
				reader.dispose();
			}
		}
	}
}
