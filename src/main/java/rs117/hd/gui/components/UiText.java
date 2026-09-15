package rs117.hd.gui.components;

import java.util.regex.Pattern;
import javax.annotation.Nullable;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.text.JTextComponent;

/**
 * The sole boundary for arbitrary text displayed by resource-pack UI.
 * Swing otherwise treats strings beginning with HTML as markup.
 */
final class UiText {
	private static final Pattern HTML_TAG = Pattern.compile("<[^>]*>");

	private UiText() {
	}

	static void setPlainText(JLabel label, @Nullable String text) {
		label.putClientProperty("html.disable", true);
		label.setText(stripTags(text));
	}

	static void setPlainToolTip(JComponent component, @Nullable String text) {
		component.putClientProperty("html.disable", true);
		component.setToolTipText(stripTags(text));
	}

	static void setPlainText(JTextComponent component, @Nullable String text) {
		component.putClientProperty("html.disable", true);
		component.setText(stripTags(text));
	}

	static String stripTags(@Nullable String text) {
		return text == null ? "" : HTML_TAG.matcher(text).replaceAll("");
	}
}
