package rs117.hd.gui.panel.components;

import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.components.shadowlabel.JShadowedLabel;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

public class MessagePanel extends JPanel
{
    private final JLabel noResultsTitle = new JShadowedLabel();
    private final JLabel noResultsDescription = new JShadowedLabel();

    public MessagePanel(String title, String description) {
        setOpaque(false);
        setBorder(new EmptyBorder(10, 0, 7, 0));
        setLayout(new BorderLayout());

        noResultsTitle.setForeground(Color.WHITE);
        noResultsTitle.setHorizontalAlignment(SwingConstants.CENTER);

        noResultsDescription.setFont(FontManager.getRunescapeSmallFont());
        noResultsDescription.setForeground(Color.GRAY);
        noResultsDescription.setHorizontalAlignment(SwingConstants.CENTER);

        add(noResultsTitle, BorderLayout.NORTH);
        add(noResultsDescription, BorderLayout.CENTER);

        setVisible(false);
        if (!title.isEmpty() && !description.isEmpty()) {
            setContent(title,description);
        }
    }

    public MessagePanel() {
        new MessagePanel("","");
    }

    /**
     * Changes the content of the panel to the given parameters.
     * The description has to be wrapped in html so that its text can be wrapped.
     */
    public void setContent(String title, String description)
    {
        noResultsTitle.setText(title);
        noResultsDescription.setText("<html><body style='text-align:center'>" + description + "</body></html>");
        setVisible(true);
    }
}