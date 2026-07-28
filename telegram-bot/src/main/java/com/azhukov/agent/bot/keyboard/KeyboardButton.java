package com.azhukov.agent.bot.keyboard;

/**
 * A button for an inline keyboard. {@code text} is the label shown to the
 * user; {@code callbackData} is the data sent back on press.
 *
 * @param text          button label
 * @param callbackData  data sent on press (format: "command:value")
 */
public record KeyboardButton(String text, String callbackData) {
}