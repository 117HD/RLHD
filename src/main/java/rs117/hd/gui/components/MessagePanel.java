package rs117.hd.gui.components;

import java.awt.BorderLayout;
import java.awt.Color;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.components.shadowlabel.JShadowedLabel;

public class MessagePanel extends JPanel {
	private final JLabel titleLabel = new JShadowedLabel();
	private final JLabel descriptionLabel = new JShadowedLabel();

	public MessagePanel(String title, String description) {
		setOpaque(false);
		setBorder(new EmptyBorder(38, 0, 38, 0));
		setLayout(new BorderLayout());

		titleLabel.setForeground(Color.WHITE);
		titleLabel.setHorizontalAlignment(SwingConstants.CENTER);
		titleLabel.setBorder(new EmptyBorder(0, 0, 8, 0));

		descriptionLabel.setFont(FontManager.getRunescapeSmallFont());
		descriptionLabel.setForeground(Color.GRAY);
		descriptionLabel.setHorizontalAlignment(SwingConstants.CENTER);

		add(titleLabel, BorderLayout.NORTH);
		add(descriptionLabel, BorderLayout.CENTER);

		setVisible(false);
		if (!title.isEmpty() || !description.isEmpty())
			setContent(title, description);
	}

	/**
	 * Changes the content of the panel to the given parameters.
	 * The description has to be wrapped in html so that its text can be wrapped.
	 */
	public void setContent(String title, String description) {
		this.titleLabel.setText(title);
		this.descriptionLabel.setText("<html><body style='text-align:center'>" + description + "</body></html>");
		setVisible(true);
	}
}
