/*
 * Copyright (c) 2025, Hooder <ahooder@protonmail.com>
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
package rs117.hd.overlays;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.components.ComponentConstants;
import net.runelite.client.ui.overlay.components.PanelComponent;

@Slf4j
public abstract class HdOverlayPanel extends HdOverlay {
	protected final PanelComponent panelComponent = new PanelComponent();
	@Setter
	private boolean clearChildren = true;
	@Setter
	private boolean dynamicFont = false;
	@Setter
	private Color preferredColor = null;

	protected HdOverlayPanel(Plugin plugin) {
		super(plugin);
		this.setResizable(true);
	}

	public Dimension render(Graphics2D graphics) {
		Dimension oldSize = this.panelComponent.getPreferredSize();
		if (this.getPreferredSize() != null) {
			this.panelComponent.setPreferredSize(this.getPreferredSize());
			if (this.dynamicFont) {
				if ((double)this.getPreferredSize().width >= 167.70000000000002) {
					graphics.setFont(FontManager.getRunescapeBoldFont());
				} else if ((double)this.getPreferredSize().width <= 103.2) {
					graphics.setFont(FontManager.getRunescapeSmallFont());
				}
			}
		}

		Color oldBackgroundColor = this.panelComponent.getBackgroundColor();
		if (this.getPreferredColor() != null && ComponentConstants.STANDARD_BACKGROUND_COLOR.equals(oldBackgroundColor)) {
			this.panelComponent.setBackgroundColor(this.getPreferredColor());
		}

		Dimension dimension;
		try {
			dimension = this.panelComponent.render(graphics);
		} finally {
			if (this.clearChildren) {
				this.panelComponent.getChildren().clear();
			}

		}

		this.panelComponent.setPreferredSize(oldSize);
		this.panelComponent.setBackgroundColor(oldBackgroundColor);
		return dimension;
	}

	public PanelComponent getPanelComponent() {
		return this.panelComponent;
	}

	public boolean isClearChildren() {
		return this.clearChildren;
	}

	public boolean isDynamicFont() {
		return this.dynamicFont;
	}

	public Color getPreferredColor() {
		return this.preferredColor;
	}

}