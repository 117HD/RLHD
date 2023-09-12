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
package rs117.hd.gui.panel;

import javax.swing.*;
import javax.swing.border.EmptyBorder;

import com.google.common.html.HtmlEscapers;
import com.google.inject.Inject;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.DynamicGridLayout;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.LinkBrowser;
import net.runelite.client.util.SwingUtil;
import rs117.hd.HdPlugin;
import rs117.hd.gui.panel.components.FixedWidthPanel;
import rs117.hd.gui.panel.components.MessagePanel;
import rs117.hd.resourcepacks.AbstractResourcePack;
import rs117.hd.resourcepacks.Constants;
import rs117.hd.resourcepacks.IResourcePack;
import rs117.hd.resourcepacks.data.Manifest;

import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

public class InstalledPacksPanel extends JPanel {

	private static final ImageIcon FADE;

	private static final ImageIcon DEV_ICON;
	private static final ImageIcon ARROW_UP;
	private static final ImageIcon ARROW_DOWN;
	private static final ImageIcon ARROW_UP_HOVER;
	private static final ImageIcon ARROW_DOWN_HOVER;
	private static final ImageIcon FOLDER;
	static {
		BufferedImage fadeIcon = ImageUtil.loadImageResource(HdPanel.class, "fade.png");
		FADE = new ImageIcon(fadeIcon);
		DEV_ICON = new ImageIcon(ImageUtil.loadImageResource(HdPanel.class, "dev_icon.png"));

		BufferedImage ARROW_UP_ICON = ImageUtil.loadImageResource(HdPanel.class, "arrow_up.png");
		BufferedImage ARROW_DOWN_ICON = ImageUtil.loadImageResource(HdPanel.class, "arrow_down.png");

		ARROW_UP = new ImageIcon(ARROW_UP_ICON);
		ARROW_DOWN = new ImageIcon(ARROW_DOWN_ICON);

		ARROW_UP_HOVER = new ImageIcon(ImageUtil.alphaOffset(ARROW_UP_ICON, -100));
		ARROW_DOWN_HOVER = new ImageIcon(ImageUtil.alphaOffset(ARROW_DOWN_ICON, -100));

		FOLDER = new ImageIcon(ImageUtil.loadImageResource(HdPanel.class, "folder_icon.png"));
	}

	@Inject
	private ScheduledExecutorService executor;

	public JPanel packList = new JPanel();

	public HdPlugin plugin;

	JScrollPane scrollPane = new JScrollPane();

	MessagePanel installHint = new MessagePanel("Looking Empty? ","Why not Install more packs by clicking<br>Download External Packs");

	@Inject
	private InstalledPacksPanel(HdPlugin plugin) {
		this.plugin = plugin;
		plugin.getResourcePackRepository().setPackPanel(this);
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		GroupLayout layout = new GroupLayout(this);
		setLayout(layout);
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setBorder(BorderFactory.createEmptyBorder());

		packList.setBorder(BorderFactory.createEmptyBorder(0, 10, 8, 3));
		packList.setLayout(new DynamicGridLayout(0, 1, 0, 5));
		packList.setAlignmentX(Component.LEFT_ALIGNMENT);

		JPanel scrollContainer = new FixedWidthPanel();
		scrollContainer.setLayout(new BorderLayout());
		scrollContainer.add(packList, BorderLayout.NORTH);

		scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);

		scrollPane.setPreferredSize(new Dimension(0x7000, 0x7000));
		scrollPane.setViewportView(scrollContainer);

		scrollPane.addComponentListener(new ComponentAdapter(){
			public void componentResized(ComponentEvent e) {
				int visibleAmount = scrollPane.getVerticalScrollBar().getVisibleAmount();
				int maxSize = scrollPane.getVerticalScrollBar().getMaximum();
				boolean displayHint = visibleAmount == maxSize;
				if (displayHint) {
					packList.add(installHint);
				} else {
					packList.remove(installHint);
				}
			}
		});

		//JButton packManagerButton = new JButton("Pack Manager");

		JPanel bottomPanel = new JPanel();
		bottomPanel.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 3));
		bottomPanel.setLayout(new DynamicGridLayout(0, 1, 0, 5));
		JButton downloadPacks = new JButton("Download External Packs");
		downloadPacks.addActionListener(ev -> {
			HdPanel.switchPanel(HdPanel.getPackHubPanel());
			HdPanel.getPackHubPanel().load();
		});
		//bottomPanel.add(packManagerButton);
		bottomPanel.add(downloadPacks);
		layout.setVerticalGroup(layout.createSequentialGroup().addGap(2).addComponent(scrollPane).addComponent(bottomPanel));

		layout.setHorizontalGroup(layout.createParallelGroup().addGroup(layout.createSequentialGroup().addComponent(scrollPane)).addComponent(bottomPanel));
	}

	public void populatePacks() {
		SwingUtilities.invokeLater(() -> {
			packList.removeAll();
			AtomicInteger index = new AtomicInteger();
			plugin.getResourcePackRepository().getRepository().forEach(pack -> {
				System.out.println(pack.getPackName());
				packList.add(createPackComponent(pack,index.get()));
				index.getAndIncrement();
			});

			packList.add(installHint);
		});
	}

	public JPanel createPackComponent(AbstractResourcePack pack, int index) {
		JPanel panel = new JPanel();
		Manifest manifest = pack.getPackMetadata();

		boolean isTop = index == 0;
		boolean isBottom = index == (plugin.getResourcePackRepository().getRepository().size() - 1);

		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setOpaque(true);
		panel.setLayout(null);
		panel.setBounds(0, 0, 221,124);
		panel.setMinimumSize(new Dimension(221,124));
		panel.setPreferredSize(new Dimension(221,124));

		JButton moveDown = new JButton();
		moveDown.setText("");
		moveDown.setIcon(ARROW_DOWN);
		SwingUtil.removeButtonDecorations(moveDown);
		moveDown.setBounds(190, 5, 22, 22);
		panel.add(moveDown);
		moveDown.setEnabled(!isBottom);
		moveDown.addActionListener(ev -> movePack(index,false));

		JButton moveUp = new JButton();
		moveUp.setText("");
		moveUp.setIcon(ARROW_UP);
		SwingUtil.removeButtonDecorations(moveUp);
		panel.add(moveUp);
		moveUp.setBounds(165, 5, 22, 22);
		moveUp.setEnabled(!isTop);
		moveUp.addActionListener(ev -> movePack(index,true));

		if (pack.isDevelopmentPack()) {
			JButton openFolder = new JButton();
			openFolder.setIcon(FOLDER);
			SwingUtil.removeButtonDecorations(openFolder);
			openFolder.setToolTipText("Open Pack Location");
			openFolder.setBounds(140, 5, 22, 22);
			panel.add(openFolder);
			openFolder.addActionListener(ev -> LinkBrowser.open(pack.resourcePackFile.toFile().getAbsolutePath()));
		}

		JLabel author = new JLabel(manifest.getAuthor());
		author.setFont(FontManager.getRunescapeSmallFont());
		author.setToolTipText(manifest.getAuthor());
		author.setBounds(5, 105, 65, author.getPreferredSize().height);
		author.setForeground(Color.WHITE);
		panel.add(author);

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


		if (pack.isDevelopmentPack()) {
			JLabel devicon = new JLabel("");
			devicon.setIcon(DEV_ICON);
			devicon.setToolTipText("This is a development Pack");
			devicon.setBounds(3, 7, 18, 18);
			panel.add(devicon);
		}

		int pluginNameShift = pack.isDevelopmentPack() ? 19 : 0;
		JLabel pluginName = new JLabel(Constants.fromInternalName(manifest.getInternalName()));
		pluginName.setFont(FontManager.getRunescapeBoldFont());
		pluginName.setToolTipText(Constants.fromInternalName(manifest.getInternalName()));
		pluginName.setBounds(5 + pluginNameShift, 5, 105, 25);
		pluginName.setForeground(Color.WHITE);
		panel.add(pluginName);


		JLabel icon = new JLabel();
		icon.setHorizontalAlignment(JLabel.CENTER);

		JLabel blackBox = new JLabel();
		blackBox.setIcon(FADE);

		if (pack.hasPackImage()) {
			icon.setIcon(new ImageIcon(pack.getPackImage()));
			icon.setVisible(true);
			blackBox.setVisible(true);
		} else {
			icon.setVisible(false);
			blackBox.setVisible(false);
		}
		icon.setBounds(new Rectangle(new Point(0, 0), icon.getPreferredSize()));
		blackBox.setBounds(0, 0, 221, 124);

		panel.add(blackBox);
		panel.add(icon);

		return panel;
	}

	public void movePack(int index, boolean up) {
		Collections.swap(plugin.getResourcePackRepository().getRepository(), index, up ? index-1 : index+1);
		packList.removeAll();
		populatePacks();
		packList.revalidate();
		packList.repaint();
	}

}
