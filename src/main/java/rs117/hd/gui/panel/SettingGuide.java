package rs117.hd.gui.panel;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.HashMap;
import org.commonmark.node.*;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.GroupLayout;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.border.EmptyBorder;
import lombok.Getter;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.DynamicGridLayout;
import rs117.hd.HdPlugin;
import rs117.hd.gui.panel.components.FixedWidthPanel;
import rs117.hd.gui.panel.components.Header;

public class SettingGuide extends JPanel
{

	@Getter
	private static List<File> updates = new ArrayList();

	private static final HashMap<Integer, String> contentCache = new HashMap<>();

	private final Header errorPanel = new Header();

	private JComboBox<String> environmentDropdown;

	private final JPanel topPanel = new JPanel();

	private final JLabel newsContent = new JLabel();


	public SettingGuide()
	{

		setBackground(ColorScheme.DARK_GRAY_COLOR);

		GroupLayout layout = new GroupLayout(this);
		setLayout(layout);
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setBorder(new EmptyBorder(0, 0, 0, 0));
		topPanel.setBorder(new EmptyBorder(2, 0, 25, 0));
		errorPanel.setContent("Loading", "Loading Latest Updates");
		topPanel.add(errorPanel);

		loadUpdates();
		JPanel mainPanel = new JPanel();
		mainPanel.setBorder(BorderFactory.createEmptyBorder(0, 7, 7, 7));
		mainPanel.setLayout(new DynamicGridLayout(0, 1, 0, 5));
		mainPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

		JPanel mainPanelWrapper = new FixedWidthPanel();
		mainPanelWrapper.setLayout(new BorderLayout());
		mainPanelWrapper.add(mainPanel, BorderLayout.NORTH);

		JScrollPane scrollPane = new JScrollPane();
		scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);
		// Can't use Short.MAX_VALUE like the docs say because of JDK-8079640
		scrollPane.setPreferredSize(new Dimension(0x7000, 0x7000));
		scrollPane.setViewportView(mainPanelWrapper);

		layout.setVerticalGroup(layout.createSequentialGroup()
			.addComponent(topPanel)
			.addGap(3).addComponent(scrollPane));

		layout.setHorizontalGroup(layout.createParallelGroup()
			.addComponent(topPanel, 0, Short.MAX_VALUE, Short.MAX_VALUE)
			.addGroup(layout.createSequentialGroup()
				.addComponent(scrollPane)));

		mainPanel.add(newsContent);
	}

	public void loadUpdates()
	{
		topPanel.removeAll();
		displayNews();
	}


	public void displayNews()
	{

		Parser parser = Parser.builder().build();

		try (InputStreamReader reader = new InputStreamReader(HdPlugin.class.getResource("pluginSettings.md").openStream(), Charset.forName("UTF-8")))
		{
			Node document = parser.parseReader(reader);
			HtmlRenderer renderer = HtmlRenderer.builder().build();
			String text = "<html>" + renderer.render(document) + "</html>";
			newsContent.setText("<html>" + renderer.render(document) + "</html>");
		}
		catch (IOException e)
		{
			errorPanel.setContent("Error", "Could not convert file to HTML");
			environmentDropdown.setVisible(false);
			errorPanel.setVisible(true);
			e.printStackTrace();
		}

	}


	public String getFormattedName(File file)
	{
		String[] info = file.getName().replace(".md", "").split("-");
		return info[1] + " - v" + info[0];
	}

}
