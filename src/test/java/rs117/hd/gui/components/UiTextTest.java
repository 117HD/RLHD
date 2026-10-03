package rs117.hd.gui.components;

import javax.swing.JLabel;
import org.junit.Assert;
import org.junit.Test;

public class UiTextTest {
	@Test
	public void disablesHtmlAndStripsTags() {
		JLabel label = new JLabel();
		UiText.setPlainText(label, "<html>Pack <img src='https://example.invalid/tracker.png'>name</html>");

		Assert.assertEquals("Pack name", label.getText());
		Assert.assertEquals(Boolean.TRUE, label.getClientProperty("html.disable"));
	}
}
