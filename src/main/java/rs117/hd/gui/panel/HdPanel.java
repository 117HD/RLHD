/*
 * Copyright (c) 2023 Mark_ <https://github.com/Mark7625/>
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

import com.google.inject.Inject;
import lombok.Getter;

import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.materialtabs.MaterialTab;
import net.runelite.client.ui.components.materialtabs.MaterialTabGroup;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.LinkBrowser;
import net.runelite.client.util.SwingUtil;
import rs117.hd.HdPlugin;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.basic.BasicButtonUI;
import java.awt.*;
import java.awt.image.BufferedImage;

public class HdPanel extends PluginPanel
{

	private final BufferedImage DISCORD_ICON;
	public final BufferedImage GITHUB_ICON;

	private final MaterialTabGroup tabGroup;

	public static JPanel content;

	@Getter
	private static InstalledPacksPanel installedPacksPanel;

	@Getter
	private static PackHubPanel packHubPanel;

	@Inject
	HdPlugin plugin;

	@Inject
	private HdPanel(InstalledPacksPanel packsPanel,PackHubPanel hubPanel)
	{
		super(false);
		DISCORD_ICON = ImageUtil.resizeImage(ImageUtil.loadImageResource(getClass(), "discord.png"), 16, 16);
		GITHUB_ICON = ImageUtil.resizeImage(ImageUtil.loadImageResource(getClass(), "github.png"), 16, 16);

		installedPacksPanel = packsPanel;
		packHubPanel = hubPanel;

		tabGroup = new MaterialTabGroup();
		tabGroup.setLayout(new GridLayout(1, 0, 8, 4));
		tabGroup.setBorder(new EmptyBorder(4, 10, 0, 10));

		content = new JPanel();
		CardLayout layout = new CardLayout();
		content.setLayout(layout);
		content.setBorder(new EmptyBorder(6, 0, 0, 0));

		setLayout(new BorderLayout());
		add(setupHeader(), BorderLayout.NORTH);


		MaterialTab installedPacks = addTab(installedPacksPanel, "pack_icon.png", "Resource Packs");
		addTab(new JPanel(), "toolbox_icon.png", "Development Tools");

		installedPacks.select();

		JPanel container = new JPanel();
		container.setBorder(new EmptyBorder(4, 0, 8, 0));
		container.setLayout(new BorderLayout());
		container.add(tabGroup, BorderLayout.NORTH);
		container.add(content, BorderLayout.CENTER);

		add(container, BorderLayout.CENTER);

	}

	public static void switchPanel(JPanel panel) {
		content.removeAll();
		content.add(panel);
		content.revalidate();
		content.repaint();
	}

	private MaterialTab addTab(JPanel panel, String image, String tooltip)
	{
		MaterialTab mt = new MaterialTab(new ImageIcon(ImageUtil.loadImageResource(getClass(), image)), tabGroup, null);
		mt.setMaximumSize(new Dimension(55,28));
		mt.setToolTipText(tooltip);
		tabGroup.addTab(mt);

		content.add(image, panel);

		mt.setOnSelectEvent(() ->
		{
			//switchTo(image, panel, false);
			return true;
		});
		return mt;
	}

	private JPanel setupHeader()
	{

		JPanel container = new JPanel();
		container.setBorder(new EmptyBorder(10, 10, 2, 10));
		container.setLayout(new BorderLayout());

		JLabel title = new JLabel();
		title.setBorder(new EmptyBorder(1, 0, 0, 0));
		title.setText("117 HD");
		title.setForeground(Color.WHITE);
		container.add(title, BorderLayout.WEST);

		final JPanel buttons = new JPanel(new GridLayout(1, 3, 10, 0));
		buttons.setBackground(ColorScheme.DARK_GRAY_COLOR);

		buttons.add(titleButton(DISCORD_ICON, "Get help or make suggestions", "https://discord.gg/U4p6ChjgSE"));
		buttons.add(titleButton(GITHUB_ICON, "Report issues or contribute on GitHub", "https://github.com/117HD/RLHD"));

		container.add(buttons, BorderLayout.EAST);
		return container;
	}

	public JButton titleButton(BufferedImage image, String tooltip, String link)
	{
		JButton button = new JButton();
		SwingUtil.removeButtonDecorations(button);
		button.setIcon(new ImageIcon(image));
		button.setToolTipText(tooltip);
		button.setBackground(ColorScheme.DARK_GRAY_COLOR);
		button.setUI(new BasicButtonUI());
		button.addActionListener((ev) -> LinkBrowser.browse(link));
		return button;
	}

}