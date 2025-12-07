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

import com.google.common.html.HtmlEscapers;
import com.google.inject.Inject;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.DynamicGridLayout;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.IconTextField;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.LinkBrowser;
import net.runelite.client.util.SwingUtil;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import rs117.hd.HdPluginConfig;
import rs117.hd.gui.HdSidebar;
import rs117.hd.resourcepacks.AbstractResourcePack;
import rs117.hd.resourcepacks.PackEventType;
import rs117.hd.resourcepacks.ResourcePackManager;
import rs117.hd.resourcepacks.ResourcePackUpdate;
import rs117.hd.resourcepacks.data.Manifest;
import rs117.hd.resourcepacks.data.PackType;
import rs117.hd.resourcepacks.impl.DefaultResourcePack;

import static rs117.hd.resourcepacks.ResourcePackManager.RAW_GITHUB_URL;

@Slf4j
public class ResourcePackPanel extends JPanel {
	private static final ImageIcon FADE;
	private static final ImageIcon DEV_ICON;
	private static final ImageIcon ARROW_UP;
	private static final ImageIcon ARROW_DOWN;
	private static final ImageIcon FOLDER;

	static {
		FADE = new ImageIcon(ImageUtil.loadImageResource(HdSidebar.class, "fade.png"));
		DEV_ICON = new ImageIcon(ImageUtil.loadImageResource(HdSidebar.class, "dev_icon.png"));

		BufferedImage ARROW_UP_ICON = ImageUtil.loadImageResource(HdSidebar.class, "arrow_up.png");
		BufferedImage ARROW_DOWN_ICON = ImageUtil.loadImageResource(HdSidebar.class, "arrow_down.png");

		ARROW_UP = new ImageIcon(ARROW_UP_ICON);
		ARROW_DOWN = new ImageIcon(ARROW_DOWN_ICON);

		FOLDER = new ImageIcon(ImageUtil.loadImageResource(HdSidebar.class, "folder_icon.png"));
	}

	@Inject
	private OkHttpClient okHttpClient;

	@Inject
	private ResourcePackManager resourcePackManager;

	@Inject
	private EventBus eventBus;

	@Inject
	private HdPluginConfig config;

	// Map to track download progress bars for each pack
	private final Map<String, JProgressBar> downloadProgressBars = new HashMap<>();
	private final Map<String, JButton> downloadButtons = new HashMap<>();
	private final Map<String, JPanel> packPanels = new HashMap<>();

	private enum PanelState {SELECTION, DOWNLOAD}

	private PanelState currentState = null;

	private final JPanel list;
	private final JButton bottomButton;
	private final JComboBox<PackTypeFilter> packTypeFilter;
	private final JPanel filterPanel;
	private final IconTextField searchBar;

	private final MessagePanel installHint = new MessagePanel(
		"Looking for more? ",
		"You can install additional resource packs<br>by clicking the button below."
	);

	private enum PackTypeFilter {
		ALL("All"),
		RESOURCE("Resource"),
		ADDON("Addon");

		private final String displayName;

		PackTypeFilter(String displayName) {
			this.displayName = displayName;
		}

		@Override
		public String toString() {
			return displayName;
		}
	}

	ResourcePackPanel() {
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setBorder(BorderFactory.createEmptyBorder());

		list = new JPanel();
		list.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 3));
		list.setLayout(new DynamicGridLayout(0, 1, 0, 5));
		list.setAlignmentX(Component.LEFT_ALIGNMENT);

		var scrollPane = new JScrollPane();
		scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);
		scrollPane.setPreferredSize(new Dimension(0x7000, 0x7000));
		scrollPane.addComponentListener(new ComponentAdapter() {
			public void componentResized(ComponentEvent e) {
				int visibleAmount = scrollPane.getVerticalScrollBar().getVisibleAmount();
				int maxSize = scrollPane.getVerticalScrollBar().getMaximum();
				boolean displayHint = visibleAmount == maxSize;
				if (displayHint) {
					list.add(installHint);
				} else {
					list.remove(installHint);
				}
			}
		});

		JPanel scrollContainer = new FixedWidthPanel();
		scrollContainer.setLayout(new BorderLayout());
		scrollContainer.add(list, BorderLayout.NORTH);
		scrollPane.setViewportView(scrollContainer);
		add(scrollPane);

		// Pack type filter dropdown
		packTypeFilter = new JComboBox<>(PackTypeFilter.values());
		packTypeFilter.setSelectedItem(PackTypeFilter.ALL);
		packTypeFilter.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		packTypeFilter.setForeground(Color.WHITE);
		packTypeFilter.addActionListener(e -> refreshPanel());

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

		JPanel bottomPanel = new JPanel();
		bottomPanel.setLayout(new BorderLayout());
		bottomPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 0, 10));
		bottomButton = new JButton("Initializing...");
		bottomButton.setFocusPainted(false);
		bottomButton.addActionListener(ev -> setState(currentState == PanelState.SELECTION ?
			PanelState.DOWNLOAD : PanelState.SELECTION));
		bottomPanel.add(bottomButton);
		add(bottomPanel);

		// Add filter panel at the top
		filterPanel = new JPanel();
		filterPanel.setLayout(new BorderLayout());
		filterPanel.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
		filterPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		
		JPanel filterControls = new JPanel();
		filterControls.setLayout(new BorderLayout());
		filterControls.setBackground(ColorScheme.DARK_GRAY_COLOR);
		
		// Make search bar bigger - take most of the space
		searchBar.setPreferredSize(new Dimension(0, 30));
		filterControls.add(searchBar, BorderLayout.CENTER);
		
		// Make dropdown smaller and put it on the right with small gap
		packTypeFilter.setPreferredSize(new Dimension(70, 30));
		packTypeFilter.setMaximumSize(new Dimension(70, 30));
		JPanel dropdownContainer = new JPanel(new BorderLayout());
		dropdownContainer.setBorder(BorderFactory.createEmptyBorder(0, 5, 0, 0));
		dropdownContainer.setBackground(ColorScheme.DARK_GRAY_COLOR);
		dropdownContainer.add(packTypeFilter, BorderLayout.CENTER);
		filterControls.add(dropdownContainer, BorderLayout.EAST);
		
		filterPanel.add(filterControls, BorderLayout.CENTER);
		filterPanel.setVisible(false);
		add(filterPanel, 0); // Add at the top

		setState(PanelState.SELECTION);
	}

	public void setState(PanelState state) {
		if (currentState == state)
			return;
		currentState = state;
		refreshPanel();
	}

	public void refreshPanel() {
		SwingUtilities.invokeLater(() -> {
			list.removeAll();

			switch (currentState) {
				case SELECTION: {
					bottomButton.setText("Download resource packs");
					filterPanel.setVisible(false);

					var packs = resourcePackManager.getInstalledPacks();
					for (int i = 0; i < packs.size(); i++) {
						list.add(createInstalledPackComponent(packs.get(i), i));
					}

					list.add(installHint);
					break;
				}
				case DOWNLOAD: {
					bottomButton.setText("Show installed resource packs");
					filterPanel.setVisible(true);

					resourcePackManager.checkForUpdates();

					var message = resourcePackManager.getStatusMessage();
					if (message != null) {
						list.add(message);
					} else {
						PackTypeFilter selectedFilter = (PackTypeFilter) packTypeFilter.getSelectedItem();
						String searchQuery = searchBar.getText().toLowerCase().trim();
						var allPacks = resourcePackManager.getDownloadablePacks().values();
						
						// Update search suggestions with tags from all packs
						updateSearchSuggestions(allPacks);
						
						for (var pack : allPacks) {
							// Filter by pack type
							boolean matchesType = selectedFilter == PackTypeFilter.ALL ||
								(selectedFilter == PackTypeFilter.RESOURCE && pack.isResourcePack()) ||
								(selectedFilter == PackTypeFilter.ADDON && pack.isAddonPack());
							
							if (!matchesType) {
								continue;
							}
							
							// Filter by search query (display name and tags)
							if (!searchQuery.isEmpty()) {
								boolean matchesSearch = false;
								
								// Check display name
								String displayName = pack.getDisplayName().toLowerCase();
								if (displayName.contains(searchQuery)) {
									matchesSearch = true;
								}
								
								// Check tags
								if (!matchesSearch && pack.getTags() != null) {
									for (String tag : pack.getTags()) {
										if (tag.toLowerCase().contains(searchQuery)) {
											matchesSearch = true;
											break;
										}
									}
								}
								
								// Check description
								if (!matchesSearch) {
									String description = pack.getDescription().toLowerCase();
									if (description.contains(searchQuery)) {
										matchesSearch = true;
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

			revalidate();
			repaint();
		});
	}

	public void movePack(int fromIndex, int toIndex) {
		var packs = resourcePackManager.getInstalledPacks();
		var pack = packs.get(fromIndex);
		
		// Prevent moving the default pack (it must always be at the bottom)
		if (pack instanceof DefaultResourcePack) {
			return;
		}
		
		// The default pack is always at the last index, so prevent moving past it
		int lastIndex = packs.size() - 1;
		if (toIndex >= lastIndex) {
			toIndex = lastIndex - 1;
		}
		
		Collections.swap(packs, fromIndex, toIndex);
		
		refreshPanel();
		eventBus.post(new ResourcePackUpdate(PackEventType.MOVED, pack));
	}

	public JPanel createInstalledPackComponent(AbstractResourcePack pack, int index) {
		JPanel panel = new JPanel();

		boolean isDefaultPack = pack instanceof DefaultResourcePack;
		boolean isTop = index == 0;
		int lastIndex = resourcePackManager.getInstalledPacks().size() - 1;
		// Disable down arrow if it's the last pack before the default pack (default is always at lastIndex)
		boolean isLastBeforeDefault = index == lastIndex - 1;
		boolean compactView = config.compactView();

		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setOpaque(true);
		panel.setLayout(null);
		
		// Adjust panel size for compact view
		int panelHeight = compactView ? 45 : 124;
		panel.setBounds(0, 0, 221, panelHeight);
		panel.setMinimumSize(new Dimension(221, panelHeight));
		panel.setPreferredSize(new Dimension(221, panelHeight));

		JButton moveDown = new JButton();
		moveDown.setText("");
		moveDown.setIcon(ARROW_DOWN);
		SwingUtil.removeButtonDecorations(moveDown);
		moveDown.setBounds(190, 5, 22, 22);
		panel.add(moveDown);
		moveDown.setEnabled(!isLastBeforeDefault && !isDefaultPack);
		moveDown.addActionListener(ev -> movePack(index, index + 1));

		JButton moveUp = new JButton();
		moveUp.setText("");
		moveUp.setIcon(ARROW_UP);
		SwingUtil.removeButtonDecorations(moveUp);
		panel.add(moveUp);
		moveUp.setBounds(165, 5, 22, 22);
		moveUp.setEnabled(!isTop && !isDefaultPack);
		moveUp.addActionListener(ev -> movePack(index, index - 1));

		boolean hasFolderButton = pack.isDevelopmentPack();
		if (hasFolderButton) {
			JButton openFolder = new JButton();
			openFolder.setIcon(FOLDER);
			SwingUtil.removeButtonDecorations(openFolder);
			openFolder.setToolTipText("Open folder");
			openFolder.setBounds(140, 5, 22, 22);
			panel.add(openFolder);
			openFolder.addActionListener(ev -> LinkBrowser.open(pack.path.toFile().getAbsolutePath()));
		}

		Manifest manifest = pack.getManifest();
		
		// Author is always shown, but positioned differently in compact view
		JLabel author = new JLabel(manifest.getAuthor());
		author.setFont(FontManager.getRunescapeSmallFont());
		author.setToolTipText(manifest.getAuthor());
		int authorY = compactView ? 28 : 105;
		author.setBounds(5, authorY, 65, author.getPreferredSize().height);
		author.setForeground(Color.WHITE);
		panel.add(author);
		
		if (!compactView) {
			String descriptionText = manifest.getDescription();
			if (!descriptionText.startsWith("<html>")) {
				descriptionText = "<html>" + HtmlEscapers.htmlEscaper().escape(descriptionText) + "</html>";
			}
			JLabel description = new JLabel(descriptionText);
			description.setVerticalAlignment(JLabel.TOP);
			description.setToolTipText(descriptionText);
			description.setBounds(5, 30, 210, 70);
			description.setForeground(Color.WHITE);
			panel.add(description);
		}


		if (pack.isDevelopmentPack()) {
			JLabel icon = new JLabel("");
			icon.setIcon(DEV_ICON);
			icon.setToolTipText("This is a development pack");
			icon.setBounds(3, 7, 18, 18);
			panel.add(icon);
		}

		int packNameShift = pack.isDevelopmentPack() ? 19 : 0;
		// Calculate width: stop before folder button (140) if present, otherwise before up arrow (165)
		int packNameEndX = hasFolderButton ? 140 : 165;
		int packNameWidth = packNameEndX - (5 + packNameShift);
		JLabel packName = new JLabel(manifest.getDisplayName());
		packName.setFont(FontManager.getRunescapeBoldFont());
		packName.setToolTipText(manifest.getInternalName());
		packName.setBounds(5 + packNameShift, 5, packNameWidth, 25);
		packName.setForeground(Color.WHITE);
		panel.add(packName);


		JLabel icon = new JLabel();
		icon.setHorizontalAlignment(JLabel.CENTER);

		JLabel blackBox = new JLabel();
		blackBox.setIcon(FADE);

		if (pack.hasPackImage(compactView)) {
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

		return panel;
	}

	public JPanel createDownloadablePackComponent(Manifest manifest) {
		JPanel panel = new JPanel();

		boolean compactView = config.compactView();
		int panelHeight = compactView ? 60 : 124;

		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setOpaque(true);
		panel.setLayout(null);
		panel.setBounds(0, 0, 221, panelHeight);
		panel.setMinimumSize(new Dimension(221, panelHeight));
		panel.setPreferredSize(new Dimension(221, panelHeight));

		// Author is always shown, but positioned differently in compact view
		JLabel author = new JLabel(manifest.getAuthor());
		author.setFont(FontManager.getRunescapeSmallFont());
		author.setToolTipText(manifest.getAuthor());
		int authorY = compactView ? 28 : 105;
		author.setBounds(5, authorY, 65, author.getPreferredSize().height);
		author.setForeground(Color.WHITE);
		panel.add(author);
		
		if (!compactView) {
			String descriptionText = manifest.getDescription();
			if (!descriptionText.startsWith("<html>")) {
				descriptionText = "<html>" + HtmlEscapers.htmlEscaper().escape(descriptionText) + "</html>";
			}
			JLabel description = new JLabel(descriptionText);
			description.setVerticalAlignment(JLabel.TOP);
			description.setToolTipText(descriptionText);
			description.setBounds(5, 30, 210, 70);
			description.setForeground(Color.WHITE);
			panel.add(description);
		}

		String displayName = manifest.getDisplayName();
		displayName = displayName.replace('_', ' ');
		String[] words = displayName.split("\\s+");
		StringBuilder formatted = new StringBuilder();
		for (String word : words) {
			if (formatted.length() > 0) {
				formatted.append(' ');
			}
			if (!word.isEmpty()) {
				formatted.append(Character.toUpperCase(word.charAt(0)));
				if (word.length() > 1) {
					formatted.append(word.substring(1).toLowerCase());
				}
			}
		}

		displayName = formatted.toString();
		
		JLabel packName = new JLabel(displayName);
		packName.setFont(FontManager.getRunescapeBoldFont());
		packName.setToolTipText(manifest.getInternalName());
		packName.setBounds(5, 5, 200, 25);
		packName.setForeground(Color.WHITE);
		panel.add(packName);

		String internalName = manifest.getInternalName();
		packPanels.put(internalName, panel);
		
		// Adjust button position for compact view
		int buttonY = compactView ? 28 : 97;
		
		JButton actionButton = new JButton();
		actionButton.setFocusPainted(false);
		boolean notInstalled = resourcePackManager.getInstalledPack(internalName) == null;
		if (notInstalled) {
			actionButton.setText("Install");
			actionButton.setBackground(new Color(0x28BE28));
			downloadButtons.put(internalName, actionButton);
			actionButton.addActionListener(l ->
			{
				replaceButtonWithProgressBar(internalName, panel, actionButton, buttonY);
				resourcePackManager.downloadResourcePack(manifest, (progress) -> {
					SwingUtilities.invokeLater(() -> {
						JProgressBar progressBar = downloadProgressBars.get(internalName);
						if (progressBar != null) {
							// Skip if progress is -1 (unknown file size)
							if (progress < 0) {
								progressBar.setString("Downloading...");
								panel.repaint();
								return;
							}
							// Clamp progress to valid range (0-100)
							int clampedProgress = Math.max(0, Math.min(100, progress));
							progressBar.setValue(clampedProgress);
							progressBar.setString(clampedProgress + "%");
							panel.repaint(); // Force repaint to show progress
						}
					});
				}, () -> {
					SwingUtilities.invokeLater(() -> {
						JProgressBar progressBar = downloadProgressBars.get(internalName);
						if (progressBar != null) {
							progressBar.setValue(100);
							progressBar.setString("100%");
							panel.repaint();
						}
					});
				}, () -> {
					SwingUtilities.invokeLater(() -> {
						JProgressBar progressBar = downloadProgressBars.get(internalName);
						if (progressBar != null) {
							progressBar.setString("Failed");
							panel.repaint();
						}
					});
				});
			});
		} else {
			actionButton.setText("Remove");
			actionButton.setBackground(new Color(0xBE2828));
			actionButton.addActionListener(l ->
			{
				actionButton.setText("Removing");
				actionButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
				resourcePackManager.removeResourcePack(internalName);
			});
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
					public void onResponse(Call call, Response res) throws IOException {
						byte[] bytes = res.body().bytes();
						BufferedImage img;
						synchronized (ImageIO.class) {
							img = ImageIO.read(new ByteArrayInputStream(bytes));
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

							BufferedImage finalImg = img;
							SwingUtilities.invokeLater(() -> {
								icon.setIcon(new ImageIcon(finalImg));
								icon.setVisible(true);
								blackBox.setVisible(true);
								panel.revalidate();
								panel.repaint();
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
				allTags.addAll(pack.getTags());
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
				public void onResponse(Call call, Response res) throws IOException {
					byte[] bytes = res.body().bytes();
					BufferedImage img;
					synchronized (ImageIO.class) {
						img = ImageIO.read(new ByteArrayInputStream(bytes));
					}

					if (img != null) {
						SwingUtilities.invokeLater(() -> {
							icon.setIcon(new ImageIcon(img));
							icon.setVisible(true);
							blackBox.setVisible(true);
							panel.revalidate();
							panel.repaint();
						});
					} else {
						log.warn("Received null regular icon for pack \"{}\"", manifest.getInternalName());
					}
				}
			});
	}
}
