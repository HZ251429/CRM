package com.hz.crm.whatsapp;

import java.util.List;

/**
 * A message from the assistant, limited to what WhatsApp can show: plain text, text with up to 3 reply buttons, or
 * text with a list of up to 10 choices. Long titles are shortened to WhatsApp's limits, so the same message can be
 * sent through the WhatsApp Cloud API later without changes.
 */
public record WaMessage(String text, List<Button> buttons, Choices list) {

	public record Button(String id, String title) {
	}

	public record Row(String id, String title, String description) {
	}

	/** buttonText = the label of the button that opens the list */
	public record Choices(String buttonText, List<Row> rows) {
	}

	/** what a person sends: typed text, or the id of a button / list choice they tapped */
	public record Incoming(String text, String replyId) {
	}

	private static String cut(String s, int max) {
		if (s == null) {
			return "";
		}
		return s.length() <= max ? s : s.substring(0, max - 1) + "…";
	}

	public static WaMessage text(String text) {
		return new WaMessage(cut(text, 4096), List.of(), null);
	}

	public static WaMessage buttons(String text, Button... buttons) {
		return new WaMessage(cut(text, 1024),
				List.of(buttons).stream().limit(3).map(b -> new Button(b.id(), cut(b.title(), 20))).toList(), null);
	}

	public static WaMessage list(String text, String buttonText, List<Row> rows) {
		return new WaMessage(cut(text, 1024), List.of(), new Choices(cut(buttonText, 20), rows.stream().limit(10)
				.map(r -> new Row(r.id(), cut(r.title(), 24), r.description() == null ? null : cut(r.description(), 72)))
				.toList()));
	}

	public static Button button(String id, String title) {
		return new Button(id, title);
	}

	public static Row row(String id, String title, String description) {
		return new Row(id, title, description);
	}
}
