package rs117.hd.resourcepacks;

import com.google.common.html.HtmlEscapers;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.LinkBrowser;
import net.runelite.client.util.SwingUtil;
import rs117.hd.gui.panel.HdPanel;
import rs117.hd.resourcepacks.data.Manifest;

import javax.swing.*;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.ScheduledExecutorService;

@Slf4j
public class ResourcePackComponent extends JPanel {

    private static final ImageIcon MISSING_ICON;
    private static final ImageIcon HELP_ICON;
    private static final ImageIcon HELP_ICON_HOVER;
    private static final int HEIGHT = 147;
    private static final int ICON_WIDTH = 224;
    private static final int BOTTOM_LINE_HEIGHT = 16;

    static {
        BufferedImage missingIcon = ImageUtil.loadImageResource(HdPanel.class, "missing.png");
        MISSING_ICON = new ImageIcon(missingIcon);

        BufferedImage helpIcon = ImageUtil.loadImageResource(HdPanel.class, "help.png");
        HELP_ICON = new ImageIcon(helpIcon);
        HELP_ICON_HOVER = new ImageIcon(ImageUtil.alphaOffset(helpIcon, -100));
    }

    ResourcePackComponent(Manifest manifest, ScheduledExecutorService executor, ResourcePackManager resourcePackManager) {

        setBackground(ColorScheme.DARKER_GRAY_COLOR);
        setOpaque(true);

        GroupLayout layout = new GroupLayout(this);
        setLayout(layout);

        JLabel pluginName = new JLabel(Constants.fromInternalName(manifest.getInternalName()));
        pluginName.setFont(FontManager.getRunescapeBoldFont());
        pluginName.setToolTipText(Constants.fromInternalName(manifest.getInternalName()));

        JLabel author = new JLabel(manifest.getAuthor());
        author.setFont(FontManager.getRunescapeSmallFont());
        author.setToolTipText(manifest.getAuthor());

        JLabel version = new JLabel(manifest.getVersion().isEmpty() ? "N/A" : manifest.getVersion());
        version.setFont(FontManager.getRunescapeSmallFont());
        version.setToolTipText(manifest.getVersion());

        String descriptionText = manifest.getDescription();
        if (!descriptionText.startsWith("<html>")) {
            descriptionText = "<html>" + HtmlEscapers.htmlEscaper().escape(descriptionText) + "</html>";
        }
        JLabel description = new JLabel(descriptionText);
        description.setVerticalAlignment(JLabel.TOP);
        description.setToolTipText(descriptionText);

        JLabel icon = new JLabel();
        icon.setHorizontalAlignment(JLabel.CENTER);
        icon.setIcon(MISSING_ICON);
        if (manifest.isHasIcon()) {
            executor.submit(() ->
            {
                try {
                    BufferedImage img = Constants.downloadIcon(manifest);

                    SwingUtilities.invokeLater(() ->
                    {
                        icon.setIcon(new ImageIcon(img));
                    });
                } catch (IOException e) {
                    log.info("Cannot download icon for pack \"{}\"", manifest.getInternalName(), e);
                }
            });
        }
        JButton help = new JButton(HELP_ICON);
        help.setRolloverIcon(HELP_ICON_HOVER);
        SwingUtil.removeButtonDecorations(help);
        help.setBorder(null);

        String support = manifest.getSupport().isEmpty() ? manifest.getLink() : manifest.getSupport();
        help.setToolTipText("Open help: " + support);
        help.addActionListener(ev -> LinkBrowser.browse(support));
        help.setBorder(null);

        if (manifest.getDev()) {
            help.setVisible(false);
        }

        JButton actionButton = new JButton();

        if (manifest.getDev()) {
            actionButton.setText("Locate");
            actionButton.setBackground(new Color(0x28BE28));
            actionButton.addActionListener(ev -> LinkBrowser.open(resourcePackManager.installedPacks.get(manifest.getInternalName()).getAbsolutePath()));
        } else {
            boolean install = !resourcePackManager.installedPacks.containsKey(manifest.getInternalName());
            if (install) {
                actionButton.setText("Install");
                actionButton.setBackground(new Color(0x28BE28));
                actionButton.addActionListener(l ->
                {
                    actionButton.setText("Installing");
                    actionButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
                    resourcePackManager.downloadResourcePack(manifest, executor);
                });
            } else {
                actionButton.setText("Remove");
                actionButton.setBackground(new Color(0xBE2828));
                actionButton.addActionListener(l ->
                {
                    actionButton.setText("Removing");
                    actionButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
                    resourcePackManager.panel.installedDropdown.removeItem(Constants.fromInternalName(manifest.getInternalName()));
                    resourcePackManager.uninstallPack(resourcePackManager.installedPacks.get(manifest.getInternalName()),manifest.getInternalName());
                    resourcePackManager.locateInstalledPacks();
                });
            }
        }

        actionButton.setBorder(new LineBorder(actionButton.getBackground().darker()));
        actionButton.setFocusPainted(false);


        layout.setHorizontalGroup(layout.createSequentialGroup()
                .addGap(5)
                .addGroup(layout.createParallelGroup()
                        .addGroup(layout.createSequentialGroup()
                                .addComponent(pluginName, 0, GroupLayout.PREFERRED_SIZE, Short.MAX_VALUE)
                                .addPreferredGap(LayoutStyle.ComponentPlacement.RELATED, GroupLayout.PREFERRED_SIZE, Short.MAX_VALUE)
                                .addComponent(author, 0, GroupLayout.PREFERRED_SIZE, Short.MAX_VALUE))
                        .addComponent(description, 0, GroupLayout.PREFERRED_SIZE, Short.MAX_VALUE)
                        .addGroup(layout.createSequentialGroup()
                                .addComponent(version, 0, GroupLayout.PREFERRED_SIZE, Short.MAX_VALUE)
                                .addPreferredGap(LayoutStyle.ComponentPlacement.RELATED, GroupLayout.PREFERRED_SIZE, 100)
                                .addComponent(help, 0, 24, 24)
                                .addComponent(actionButton, 0, 57, GroupLayout.PREFERRED_SIZE)
                                .addGap(5))));

        int lineHeight = description.getFontMetrics(description.getFont()).getHeight();
        layout.setVerticalGroup(layout.createParallelGroup()
                .addGroup(layout.createSequentialGroup()
                        .addGap(5)
                        .addGroup(layout.createParallelGroup(GroupLayout.Alignment.BASELINE)
                                .addComponent(pluginName)
                                .addComponent(author))
                        .addPreferredGap(LayoutStyle.ComponentPlacement.RELATED, GroupLayout.PREFERRED_SIZE, Short.MAX_VALUE)
                        .addComponent(description, lineHeight, GroupLayout.PREFERRED_SIZE, lineHeight * 2)
                        .addPreferredGap(LayoutStyle.ComponentPlacement.RELATED, GroupLayout.PREFERRED_SIZE, Short.MAX_VALUE)
                        .addGroup(layout.createParallelGroup(GroupLayout.Alignment.BASELINE)
                                .addComponent(version, BOTTOM_LINE_HEIGHT, BOTTOM_LINE_HEIGHT, BOTTOM_LINE_HEIGHT)
                                .addComponent(help, BOTTOM_LINE_HEIGHT, BOTTOM_LINE_HEIGHT, BOTTOM_LINE_HEIGHT)
                                .addComponent(actionButton, BOTTOM_LINE_HEIGHT, BOTTOM_LINE_HEIGHT, BOTTOM_LINE_HEIGHT))
                        .addGap(5)));

    }


}
