/*
 * Copyright (c) 2025 Mark_ <https://github.com/Mark7625/>
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
package rs117.hd.utils.tooling.ui;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridLayout;
import javax.inject.Inject;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.basic.BasicButtonUI;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.materialtabs.MaterialTab;
import net.runelite.client.ui.components.materialtabs.MaterialTabGroup;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.LinkBrowser;
import net.runelite.client.util.SwingUtil;
import rs117.hd.HdPlugin;
import rs117.hd.utils.tooling.DeveloperToolManager;
import rs117.hd.utils.tooling.ui.impl.DeveloperPanel;

public class HdSidebar extends PluginPanel {
	@Inject
	private ClientToolbar clientToolbar;

	private final NavigationButton navigationButton;
	private final MaterialTabGroup tabGroup;
	private final JPanel tabPanel;

	@Inject
	public HdSidebar(ClientToolbar clientToolbar, DeveloperToolManager developerTools) {
		super(false);
		setLayout(new BorderLayout());

		addHeader();

		JPanel container = new JPanel();
		container.setBorder(new EmptyBorder(4, 0, 8, 0));
		container.setLayout(new BorderLayout());

		tabGroup = new MaterialTabGroup();
		tabGroup.setLayout(new GridLayout(1, 0, 8, 4));
		tabGroup.setBorder(new EmptyBorder(0, 10, 10, 10));
		container.add(tabGroup, BorderLayout.NORTH);

		tabPanel = new JPanel();
		tabPanel.setLayout(new CardLayout());
		tabPanel.setBorder(new EmptyBorder(0, 0, 0, 0));
		container.add(tabPanel, BorderLayout.CENTER);

		add(container, BorderLayout.CENTER);

		navigationButton = NavigationButton
			.builder()
			.tooltip("117 HD")
			.priority(3)
			.icon(ImageUtil.loadImageResource(HdPlugin.class, "icon.png"))
			.panel(this)
			.build();
		clientToolbar.addNavigation(navigationButton);

		DeveloperPanel developerPanel = new DeveloperPanel(developerTools);
		MaterialTab resourcePackTab = addTab(developerPanel, "toolbox_icon.png", "Developer Tools");
		resourcePackTab.select();
	}

	public void destroy() {
		clientToolbar.removeNavigation(navigationButton);
		tabGroup.removeAll();
		tabPanel.removeAll();
	}

	private MaterialTab addTab(JPanel tabContent, String image, String tooltip) {
		MaterialTab tab = new MaterialTab(new ImageIcon(ImageUtil.loadImageResource(getClass(), image)), tabGroup, null);
		tab.setMaximumSize(new Dimension(55, 28));
		tab.setToolTipText(tooltip);
		tabGroup.addTab(tab);

		tabPanel.add(image, tabContent);

		tab.setOnSelectEvent(() -> {
			tabPanel.removeAll();
			tabPanel.add(tabContent);
			tabPanel.revalidate();
			tabPanel.repaint();
			return true;
		});
		return tab;
	}

	private void addHeader() {
		JPanel container = new JPanel();
		container.setBorder(new EmptyBorder(10, 10, 5, 10));
		container.setLayout(new BorderLayout());

		JLabel title = new JLabel();
		title.setBorder(new EmptyBorder(1, 0, 0, 0));
		title.setText("117 HD");
		title.setForeground(Color.WHITE);
		container.add(title, BorderLayout.WEST);

		JPanel buttons = new JPanel(new GridLayout(1, 3, 10, 0));
		buttons.setBackground(ColorScheme.DARK_GRAY_COLOR);
		buttons.add(titleButton("discord.png", "Get help or make suggestions", "https://discord.gg/U4p6ChjgSE"));
		buttons.add(titleButton("github.png", "Report issues or contribute on GitHub", "https://github.com/117HD/RLHD"));
		container.add(buttons, BorderLayout.EAST);

		add(container, BorderLayout.NORTH);
	}

	private JButton titleButton(String icon, String tooltip, String link) {
		JButton button = new JButton();
		SwingUtil.removeButtonDecorations(button);
		button.setIcon(new ImageIcon(ImageUtil.resizeImage(ImageUtil.loadImageResource(getClass(), icon), 16, 16)));
		button.setToolTipText(tooltip);
		button.setBackground(ColorScheme.DARK_GRAY_COLOR);
		button.setUI(new BasicButtonUI());
		button.addActionListener((ev) -> LinkBrowser.browse(link));
		return button;
	}
}