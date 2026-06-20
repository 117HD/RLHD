/*
 * Copyright (c) 2021, Hooder <https://github.com/aHooder>
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
package rs117.hd.utils.devtools;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Toolkit;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.util.ArrayList;
import java.util.function.Consumer;
import javax.swing.JFormattedTextField;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.DocumentFilter;
import lombok.Getter;
import net.runelite.client.ui.ColorScheme;

public class Slider extends JPanel
{
	@Getter
	private float value;
	private final float min;
	private final float max;
	private final boolean allowWrapAround;
	private final RawSlider slider;

	private final ArrayList<Consumer<Float>> updateConsumers = new ArrayList<>();

	private JFormattedTextField input = null;

	public void addUpdateListener(Consumer<Float> consumer)
	{
		updateConsumers.add(consumer);
	}

	public void removeUpdateListener(Consumer<Float> consumer)
	{
		updateConsumers.remove(consumer);
	}

	/**
	 * Gets the whole string from the passed DocumentFilter replace.
	 */
	static String getReplacedText(DocumentFilter.FilterBypass fb, int offset, int length, String str)
		throws BadLocationException
	{
		Document doc = fb.getDocument();
		StringBuilder sb = new StringBuilder(doc.getText(0, doc.getLength()));
		sb.replace(offset, offset + length, str);

		return sb.toString();
	}

	public Slider(float value, float min, float max, boolean allowWrapAround)
	{
		this(null, value, min, max, allowWrapAround);
	}

	public Slider(String labelText, float value, float min, float max, boolean allowWrapAround)
	{
		this.value = value;
		this.min = min;
		this.max = max;
		this.allowWrapAround = allowWrapAround;

		this.slider = new RawSlider(allowWrapAround);

		setLayout(new BorderLayout(10, 0));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		if (labelText != null)
		{
			JLabel label = new JLabel(labelText);
			label.setPreferredSize(new Dimension(45, 0));
			label.setForeground(Color.WHITE);
			add(label, BorderLayout.WEST);
		}

		slider.setBackground(ColorScheme.DARK_GRAY_COLOR);
		slider.setBorder(new EmptyBorder(0, 0, 5, 0));
		slider.setPreferredSize(new Dimension(10, 30));
		slider.setOnValueChanged(ratio -> update(mapRatioToRange(ratio, min, max)));

		update(value);
		add(slider, BorderLayout.CENTER);

		SwingUtilities.invokeLater(this::updateInputBoxWidth);
	}

	public JFormattedTextField getInputTextField()
	{
		if (this.input != null)
			return this.input;
		this.input = new JFormattedTextField(this.value);

		input.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		input.setPreferredSize(new Dimension(35, 30));
		input.setBorder(new EmptyBorder(5, 5, 5, 5));
		input.setHorizontalAlignment(JTextField.RIGHT);
		Slider self = this;
		((AbstractDocument) input.getDocument()).setDocumentFilter(new DocumentFilter()
		{
			@Override
			public void replace(DocumentFilter.FilterBypass fb, int offset, int length, String str, AttributeSet attrs)
				throws BadLocationException
			{
				try
				{
					String text = getReplacedText(fb, offset, length, str);

					int value = Integer.parseInt(text);
					if (value < self.min || value > self.max)
					{
						Toolkit.getDefaultToolkit().beep();
						return;
					}

					super.replace(fb, offset, length, str, attrs);
				}
				catch (NumberFormatException e)
				{
					e.printStackTrace();
					Toolkit.getDefaultToolkit().beep();
				}
			}
		});

		input.addFocusListener(new FocusAdapter()
		{
			@Override
			public void focusLost(FocusEvent e)
			{
				update((int) input.getValue());
			}
		});

		input.addActionListener(a -> update((int) input.getValue()));

		input.addKeyListener(new KeyListener()
		{
			@Override
			public void keyTyped(KeyEvent keyEvent)
			{
			}

			@Override
			public void keyPressed(KeyEvent keyEvent)
			{
				if (keyEvent.getKeyCode() == KeyEvent.VK_UP)
				{
					update(self.value + 1);
				}
				else if (keyEvent.getKeyCode() == KeyEvent.VK_DOWN)
				{
					update(self.value - 1);
				}
			}

			@Override
			public void keyReleased(KeyEvent keyEvent)
			{
			}
		});

		return this.input;
	}

	private float mapRatioToRange(float ratio, float min, float max)
	{
		return min + ratio * (max - min);
	}

	public void update(float newValue)
	{
		value = newValue;

		if (allowWrapAround)
		{
			if (value != max) // wraps around to 0 correctly, but can look confusing when holding ctrl
			{
				value = (value - min) % (max - min) + min;
			}
		}
		else
		{
			value = Math.min(max, Math.max(min, value));
		}

		if (slider.ctrlPressed && value > min && value < max)
		{
			int roundToNearest = slider.shiftPressed ? 5 : 10;
			value = Math.round(value / roundToNearest) * roundToNearest;
		}

		for (Consumer<Float> c : updateConsumers)
		{
			c.accept(value);
		}

		slider.setRatio(value / max);
		if (this.input != null)
			input.setValue(value);

		updateInputBoxWidth();
	}

	private void updateInputBoxWidth()
	{
		if (input == null)
			return;
		String text = input.getText();
		if (slider.beingDragged)
		{
			// When being dragged, just accommodate the maximum number of symbols
			text = text.replaceAll("\\d", "");
			int numChars = (int) Math.log10(max) + 1;
			for (int i = 0; i < numChars; i++)
			{
				text += "0";
			}
		}
		int inputWidth = Math.max(35, input.getFontMetrics(input.getFont()).stringWidth(text) + 15);
		input.setPreferredSize(new Dimension(inputWidth, 30));
		updateUI();
	}
}
