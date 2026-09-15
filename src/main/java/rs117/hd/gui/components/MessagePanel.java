package rs117.hd.gui.components;

import java.awt.BorderLayout;
import java.awt.Color;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.JTextArea;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.components.shadowlabel.JShadowedLabel;

public class MessagePanel extends JPanel {
	private final JLabel titleLabel = new JShadowedLabel();
	private final JTextArea descriptionLabel = new JTextArea();

	public MessagePanel(String title, String description) {
		setOpaque(false);
		setBorder(new EmptyBorder(38, 0, 38, 0));
		setLayout(new BorderLayout());

		titleLabel.setForeground(Color.WHITE);
		titleLabel.setHorizontalAlignment(SwingConstants.CENTER);
		titleLabel.setBorder(new EmptyBorder(0, 0, 8, 0));

		descriptionLabel.setFont(FontManager.getRunescapeSmallFont());
		descriptionLabel.setForeground(Color.GRAY);
		descriptionLabel.setLineWrap(true);
		descriptionLabel.setWrapStyleWord(true);
		descriptionLabel.setRows(2);
		descriptionLabel.setEditable(false);
		descriptionLabel.setFocusable(false);
		descriptionLabel.setOpaque(false);
		descriptionLabel.setBorder(new EmptyBorder(0, 10, 0, 10));

		add(titleLabel, BorderLayout.NORTH);
		add(descriptionLabel, BorderLayout.CENTER);

		setVisible(false);
		if (!title.isEmpty() || !description.isEmpty())
			setContent(title, description);
	}

	/** Changes the content of the panel using wrapped, plain text only. */
	private void setContent(String title, String description) {
		UiText.setPlainText(titleLabel, title);
		UiText.setPlainText(descriptionLabel, description);
		setVisible(true);
	}
}
