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

import java.awt.*;
import java.awt.event.ItemEvent;
import java.util.concurrent.ScheduledExecutorService;
import javax.swing.*;
import javax.swing.border.EmptyBorder;

import com.google.inject.Inject;
import lombok.Getter;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.DynamicGridLayout;
import net.runelite.client.util.ImageUtil;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import rs117.hd.gui.panel.components.FixedWidthPanel;
import rs117.hd.gui.panel.components.Header;
import rs117.hd.resourcepacks.ResourcePackManager;

import static net.runelite.client.ui.PluginPanel.PANEL_WIDTH;
import static rs117.hd.resourcepacks.Constants.fromInternalName;

public class ResourcePackPanel extends JPanel
{

	@Getter
	private final HdPlugin plugin;

	public final Header messagePanel = new Header();

	public JComboBox<String> installedDropdown;
	public JButton refreshButton;
	public final JPanel topPanel = new JPanel();

	public JPanel packList = new JPanel();

	public JPanel dropdownPanel;
	public JPanel progressPanel;

	public JProgressBar progressBar = new JProgressBar(0, 100);

	public final ScheduledExecutorService executor;

	@Inject
	private ResourcePackPanel(HdPlugin plugin, ScheduledExecutorService executor)
	{
		super();

		this.plugin = plugin;
		this.executor = executor;

		setBackground(ColorScheme.DARK_GRAY_COLOR);

		GroupLayout layout = new GroupLayout(this);
		setLayout(layout);
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setBorder(new EmptyBorder(0, 0, 0, 0));

		dropdownPanel = new JPanel();
		dropdownPanel.setLayout(new BorderLayout());
		dropdownPanel.setMinimumSize(new Dimension(PANEL_WIDTH, 0));

		progressPanel = new JPanel();
		progressPanel.setLayout(new BorderLayout());
		progressPanel.setMinimumSize(new Dimension(PANEL_WIDTH, 0));
		progressPanel.setVisible(false);


		progressBar.setStringPainted(true);
		progressBar.setPreferredSize(new Dimension(200, 30));
		progressBar.setForeground(Color.WHITE);
		progressBar.updateUI();
		progressPanel.add(progressBar, BorderLayout.WEST);

		topPanel.setBorder(new EmptyBorder(2, 0, 25, 0));
		messagePanel.setContent("Loading..","Loading Manifest");
		topPanel.add(messagePanel);
		topPanel.add(progressPanel);
		packList.setBorder(BorderFactory.createEmptyBorder(0, 7, 7, 7));
		packList.setLayout(new DynamicGridLayout(0, 1, 0, 5));
		packList.setAlignmentX(Component.LEFT_ALIGNMENT);

		JPanel mainPanelWrapper = new FixedWidthPanel();
		mainPanelWrapper.setLayout(new BorderLayout());
		mainPanelWrapper.add(packList, BorderLayout.NORTH);

		topPanel.add(createDropdown());

		JScrollPane scrollPane = new JScrollPane();
		scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);

		scrollPane.setPreferredSize(new Dimension(0x7000, 0x7000));
		scrollPane.setViewportView(mainPanelWrapper);

		layout.setVerticalGroup(layout.createSequentialGroup()
				.addComponent(topPanel)
		.addGap(3).addComponent(scrollPane));

		layout.setHorizontalGroup(layout.createParallelGroup()
				.addComponent(topPanel, 0, Short.MAX_VALUE, Short.MAX_VALUE)
				.addGroup(layout.createSequentialGroup()
		.addComponent(scrollPane)));

		plugin.setResourcePackManager(new ResourcePackManager(this));
		plugin.getResourcePackManager().startup();
	}

	public JPanel createDropdown()
	{

		installedDropdown = new JComboBox<>();
		installedDropdown.setPreferredSize(new Dimension(200, 30));
		installedDropdown.setForeground(Color.WHITE);
		installedDropdown.setFocusable(false);
		installedDropdown.addItem("Default");
		installedDropdown.addItemListener(e -> {
			if (e.getStateChange() == ItemEvent.SELECTED)
			{
				plugin.getResourcePackManager().setActivePack(e.getItem().toString());
			}
		});

		dropdownPanel.add(installedDropdown, BorderLayout.WEST);

		refreshButton = new JButton();
		refreshButton.setPreferredSize(new Dimension(30,30));
		refreshButton.setMinimumSize(new Dimension(30,30));
		refreshButton.setIcon(new ImageIcon(ImageUtil.resizeImage(ImageUtil.loadImageResource(HdPanel.class, "refresh.png"), 16, 16)));
		refreshButton.setToolTipText("Refresh");
		refreshButton.addActionListener((ev) -> {
			plugin.getPanel().getResourcePackPanel().installedDropdown.removeAllItems();
			plugin.getPanel().getResourcePackPanel().installedDropdown.addItem("Default");
			plugin.getResourcePackManager().loadManifest();
		});

		dropdownPanel.add(refreshButton, BorderLayout.EAST);

		return dropdownPanel;
	}



}
