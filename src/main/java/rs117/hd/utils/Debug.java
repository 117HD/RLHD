package rs117.hd.utils;

import java.awt.Color;
import java.util.IllegalFormatException;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.helpers.FormattingTuple;
import org.slf4j.helpers.MessageFormatter;
import rs117.hd.overlays.DebugPrintOverlay;

@Slf4j
public class Debug {
	// ---------------------------------------------------------------------------------------------
	// Single frame, default color
	// ---------------------------------------------------------------------------------------------

	public static final Color DEFAULT_COLOR = Color.CYAN;

	public static void Print(String message) {
		DebugPrintOverlay.Submit(0f, DEFAULT_COLOR, message);
	}

	public static void Print(String format, Object arg) {
		DebugPrintOverlay.Submit(0f, DEFAULT_COLOR, formatMessage(format, arg));
	}

	public static void Print(String format, Object arg1, Object arg2) { DebugPrintOverlay.Submit(0f, DEFAULT_COLOR, formatMessage(format, arg1, arg2)); }

	public static void Print(String format, Object... args) {
		DebugPrintOverlay.Submit(0f, DEFAULT_COLOR, formatMessage(format, args));
	}

	// ---------------------------------------------------------------------------------------------
	// Single frame, with color
	// ---------------------------------------------------------------------------------------------

	public static void Print(Color color, String message) {
		DebugPrintOverlay.Submit(0f, color, message);
	}

	public static void Print(Color color, String format, Object arg) {
		DebugPrintOverlay.Submit(0f, color, formatMessage(format, arg));
	}

	public static void Print(Color color, String format, Object arg1, Object arg2) { DebugPrintOverlay.Submit(0f, color, formatMessage(format, arg1, arg2)); }

	public static void Print(Color color, String format, Object... args) {
		DebugPrintOverlay.Submit(0f, color, formatMessage(format, args));
	}

	// ---------------------------------------------------------------------------------------------
	// Timed, default color
	// ---------------------------------------------------------------------------------------------

	public static void Print(float elapsedTime, String message) {
		DebugPrintOverlay.Submit(elapsedTime, DEFAULT_COLOR, message);
	}

	public static void Print(float elapsedTime, String format, Object arg) { DebugPrintOverlay.Submit(elapsedTime, DEFAULT_COLOR, formatMessage(format, arg)); }

	public static void Print(float elapsedTime, String format, Object arg1, Object arg2) { DebugPrintOverlay.Submit(elapsedTime, DEFAULT_COLOR, formatMessage(format, arg1, arg2)); }

	public static void Print(float elapsedTime, String format, Object... args) { DebugPrintOverlay.Submit(elapsedTime, DEFAULT_COLOR, formatMessage(format, args)); }

	// ---------------------------------------------------------------------------------------------
	// Timed, with color
	// ---------------------------------------------------------------------------------------------

	public static void Print(float elapsedTime, Color color, String message) {
		DebugPrintOverlay.Submit(elapsedTime, color, message);
	}

	public static void Print(float elapsedTime, Color color, String format, Object arg) { DebugPrintOverlay.Submit(elapsedTime, color, formatMessage(format, arg)); }

	public static void Print(float elapsedTime, Color color, String format, Object arg1, Object arg2) { DebugPrintOverlay.Submit(elapsedTime, color, formatMessage(format, arg1, arg2)); }

	public static void Print(float elapsedTime, Color color, String format, Object... args) { DebugPrintOverlay.Submit(elapsedTime, color, formatMessage(format, args)); }

	public static void PrintF(String format, Object... args) { DebugPrintOverlay.Submit(0f, DEFAULT_COLOR, formatString(format, args)); }

	public static void PrintF(Color color, String format, Object... args) {
		DebugPrintOverlay.Submit(0f, color, formatString(format, args));
	}

	public static void PrintF(float elapsedTime, String format, Object... args) { DebugPrintOverlay.Submit(elapsedTime, DEFAULT_COLOR, formatString(format, args)); }

	public static void PrintF(float elapsedTime, Color color, String format, Object... args) { DebugPrintOverlay.Submit(elapsedTime, color, formatString(format, args)); }

	private static String formatMessage(String format, Object... args) {
		if (format == null)
			return null;

		FormattingTuple ft = MessageFormatter.arrayFormat(format, args);
		Throwable t = ft.getThrowable();
		return t == null ? ft.getMessage() : ft.getMessage() + '\n' + t;
	}

	private static String formatString(String format, Object... args) {
		if (format == null)
			return null;

		try {
			return String.format(format, args);
		} catch (IllegalFormatException e) {
			return "[PrintF failed: " + e.getMessage() + "] " + format;
		}
	}
}
