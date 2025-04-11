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

import com.google.inject.Inject;
import lombok.Getter;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.materialtabs.MaterialTab;
import net.runelite.client.ui.components.materialtabs.MaterialTabGroup;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.LinkBrowser;
import net.runelite.client.util.SwingUtil;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.basic.BasicButtonUI;
import java.awt.*;
import java.awt.image.BufferedImage;

public class HdPanel extends PluginPanel
{

	private final BufferedImage DISCORD_ICON;
	public final BufferedImage GITHUB_ICON;
	private final BufferedImage PATREON_ICON;

	private final JPanel display = new JPanel();

	private final MaterialTabGroup tabGroup = new MaterialTabGroup(display);

	@Getter
	private final ResourcePackPanel resourcePackPanel;

	@Inject
	private HdPanel(ResourcePackPanel resourcePackPanel)
	{
		super(false);

		DISCORD_ICON = ImageUtil.resizeImage(ImageUtil.loadImageResource(getClass(), "discord.png"), 16, 16);
		GITHUB_ICON = ImageUtil.resizeImage(ImageUtil.loadImageResource(getClass(), "github.png"), 16, 16);
		PATREON_ICON = ImageUtil.resizeImage(ImageUtil.loadImageResource(getClass(), "patreon.png"), 16, 16);

		this.resourcePackPanel = resourcePackPanel;

		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setLayout(new BorderLayout());

		setup();

	}

	public void setup() {
		add(setupHeader(), BorderLayout.NORTH);
		add(setupTabs(), BorderLayout.CENTER);
	}


	private JPanel setupTabs()
	{

		MaterialTab packs = new MaterialTab("Resource Packs", tabGroup, resourcePackPanel);
		JPanel container = new JPanel();
		container.setBorder(new EmptyBorder(10, 0, 5, 0));
		container.setLayout(new BorderLayout());

		tabGroup.addTab(packs);
		tabGroup.select(packs);

		container.add(tabGroup, BorderLayout.NORTH);
		container.add(display, BorderLayout.CENTER);

		return container;
	}

	private JPanel setupHeader()
	{

		JPanel container = new JPanel();
		container.setBorder(new EmptyBorder(10, 10, 2, 10));
		container.setLayout(new BorderLayout());

		JLabel title = new JLabel();
		title.setText("117 HD");
		title.setForeground(Color.WHITE);
		container.add(title, BorderLayout.WEST);

		final JPanel buttons = new JPanel(new GridLayout(1, 3, 10, 0));
		buttons.setBackground(ColorScheme.DARK_GRAY_COLOR);

		buttons.add(titleButton(DISCORD_ICON, "Get help or make suggestions", "https://discord.gg/U4p6ChjgSE"));
		buttons.add(titleButton(GITHUB_ICON, "Report issues or contribute on GitHub", "https://github.com/117HD/RLHD"));
		buttons.add(titleButton(PATREON_ICON, "Support the original creator of 117HD", "https://www.patreon.com/RS_117"));

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