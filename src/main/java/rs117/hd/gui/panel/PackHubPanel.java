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

import com.google.common.html.HtmlEscapers;
import com.google.inject.Inject;
import lombok.extern.slf4j.Slf4j;
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
import rs117.hd.resourcepacks.data.Manifest;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class PackHubPanel extends JPanel {

	private static final ImageIcon FADE;

	static {
		BufferedImage fadeIcon = ImageUtil.loadImageResource(HdPanel.class, "fade.png");
		FADE = new ImageIcon(fadeIcon);

	}

	@Inject
	private ScheduledExecutorService executor;

	public JPanel packList = new JPanel();

	public HdPlugin plugin;

	JScrollPane scrollPane = new JScrollPane();

	MessagePanel errorMessage = new MessagePanel("Error ","Why not Install more packs by clicking<br>Download External Packs");

	@Inject
	public PackHubPanel(HdPlugin plugin) {
		this.plugin = plugin;
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

		JPanel bottomPanel = new JPanel();
		bottomPanel.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 3));
		bottomPanel.setLayout(new DynamicGridLayout(0, 1, 0, 5));
		JButton back = new JButton("Back");
		back.addActionListener(ev -> HdPanel.switchPanel(HdPanel.getInstalledPacksPanel()));
		bottomPanel.add(back);

		layout.setVerticalGroup(layout.createSequentialGroup().addGap(2).addComponent(scrollPane).addComponent(bottomPanel));

		layout.setHorizontalGroup(layout.createParallelGroup().addGroup(layout.createSequentialGroup().addComponent(scrollPane)).addComponent(bottomPanel));
	}

	public void load() {
		SwingUtilities.invokeLater(() -> {
			packList.removeAll();
			if (plugin.getResourcePackRepository().loadManifest()) {
				AtomicInteger index = new AtomicInteger();
				plugin.getResourcePackRepository().getManifestOnline().forEach((name , pack) -> {
					packList.add(createPackComponent(pack,index.get()));
					index.getAndIncrement();
				});
			} else {
				packList.add(errorMessage);
			}
		});
	}

	public JPanel createPackComponent(Manifest manifest, int index) {
		JPanel panel = new JPanel();

		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setOpaque(true);
		panel.setLayout(null);
		panel.setBounds(0, 0, 221,124);
		panel.setMinimumSize(new Dimension(221,124));
		panel.setPreferredSize(new Dimension(221,124));

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

		JLabel pluginName = new JLabel(Constants.fromInternalName(manifest.getInternalName()));
		pluginName.setFont(FontManager.getRunescapeBoldFont());
		pluginName.setToolTipText(Constants.fromInternalName(manifest.getInternalName()));
		pluginName.setBounds(5, 5, 105, 25);
		pluginName.setForeground(Color.WHITE);
		panel.add(pluginName);


		JLabel icon = new JLabel();
		icon.setHorizontalAlignment(JLabel.CENTER);

		JLabel blackBox = new JLabel();
		blackBox.setIcon(FADE);

		if (manifest.isHasIcon()) {
			executor.submit(() ->
			{
				try {
					BufferedImage img = Constants.downloadIcon(manifest);
					if (img != null) {
						SwingUtilities.invokeLater(() -> {
							icon.setIcon(new ImageIcon(img));
							icon.setVisible(true);
							blackBox.setVisible(true);
						});
						System.out.println("dfsdf");

					} else {
						log.warn("Received null icon for icon for pack \"{}\"", manifest.getInternalName());
					}
				} catch (IOException e) {
					log.warn("Cannot download icon for pack \"{}\"", manifest.getInternalName(), e);
					icon.setVisible(false);
					blackBox.setVisible(false);
				}
			});
		} else {
			icon.setVisible(false);
			blackBox.setVisible(false);
		}

		icon.setBounds(new Rectangle(new Point(0, 0), icon.getPreferredSize()));
		blackBox.setBounds(new Rectangle(new Point(0, 0), icon.getPreferredSize()));

		panel.add(blackBox);
		panel.add(icon);

		return panel;
	}

}
