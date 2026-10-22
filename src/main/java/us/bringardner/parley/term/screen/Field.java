/**
 * <PRE>
 *
 * Copyright Tony Bringardner 2026 <A href="http://bringardner.com/tony">Tony Bringardner</A>
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       <A href="http://www.apache.org/licenses/LICENSE-2.0">http://www.apache.org/licenses/LICENSE-2.0</A>
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *  </PRE>
 *
 *	@author Tony Bringardner
 */
package us.bringardner.parley.term.screen;

import us.bringardner.parley.term.tty.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The editable parts of a form: a text field (masked for a password, digits only for a
 * number), several lines, a check box, a choice, a button. Each draws itself and handles the
 * keys it uses while it has the keyboard.
 */
public abstract class Field {

	/** What the line under the form says while this field has the keyboard (may be null). */
	public String help;

	public int height() {
		return 1;
	}

	public boolean focusable() {
		return true;
	}

	/** Enter in this field submits the form (else the field uses it). */
	public boolean submitsOnEnter() {
		return true;
	}

	public abstract void render(Screen screen, Rect area, boolean focused);

	/** A key while this field has the keyboard; true if it used it. */
	public abstract boolean key(Key k);

	public abstract String value();

	/** One line of text. */
	public static class Text extends Field {
		private final StringBuilder text;
		private int cursor;
		private int scroll;
		private final boolean masked;
		private final boolean digits;

		public Text(String value, boolean masked, boolean digits) {
			text = new StringBuilder(value == null ? "" : value);
			cursor = text.length();
			this.masked = masked;
			this.digits = digits;
		}

		@Override
		public void render(Screen screen, Rect area, boolean focused) {
			Style style = focused ? Theme.FOCUS : Theme.FIELD;
			if( cursor < scroll ) {
				scroll = cursor;
			} else if( cursor >= scroll+area.width ) {
				scroll = cursor-area.width+1;
			}
			String shown = masked ? "*".repeat(text.length()) : text.toString();
			screen.line(area, shown.substring(Math.min(scroll, shown.length())), style);
			if( focused ) {
				screen.cursor(area.x+cursor-scroll, area.y);
			}
		}

		@Override
		public boolean key(Key k) {
			if( k.isPlainChar()) {
				if( digits && !(Character.isDigit(k.ch) || (k.ch == '-' && cursor == 0))) {
					return true;
				}
				text.insert(cursor, new String(Character.toChars(k.ch)));
				cursor += Character.charCount(k.ch);
			} else if( k.is(Key.Code.BACKSPACE)) {
				if( cursor > 0 ) {
					text.deleteCharAt(--cursor);
				}
			} else if( k.is(Key.Code.DELETE)) {
				if( cursor < text.length()) {
					text.deleteCharAt(cursor);
				}
			} else if( k.is(Key.Code.LEFT)) {
				cursor = Math.max(0, cursor-1);
			} else if( k.is(Key.Code.RIGHT)) {
				cursor = Math.min(text.length(), cursor+1);
			} else if( k.is(Key.Code.HOME) || k.isCtrl('a')) {
				cursor = 0;
			} else if( k.is(Key.Code.END) || k.isCtrl('e')) {
				cursor = text.length();
			} else if( k.isCtrl('u')) {
				text.setLength(0);
				cursor = 0;
			} else {
				return false;
			}
			return true;
		}

		@Override
		public String value() {
			return text.toString();
		}

		public void setValue(String value) {
			text.setLength(0);
			text.append(value);
			cursor = text.length();
		}
	}

	/** Several lines (a private key pasted in): Enter is a new line, Tab leaves. */
	public static class MultiLine extends Field {
		private final List<StringBuilder> lines = new ArrayList<>();
		private int row;
		private int col;
		private int top;

		public MultiLine(String value) {
			for(String l : (value == null ? "" : value).split("\n", -1)) {
				lines.add(new StringBuilder(l));
			}
		}

		@Override
		public int height() {
			return 4;
		}

		@Override
		public boolean submitsOnEnter() {
			return false;
		}

		@Override
		public void render(Screen screen, Rect area, boolean focused) {
			Style style = focused ? Theme.FOCUS : Theme.FIELD;
			if( row < top ) {
				top = row;
			} else if( row >= top+area.height ) {
				top = row-area.height+1;
			}
			for(int i=0; i < area.height; i++) {
				int r = top+i;
				screen.line(area.row(i), r < lines.size() ? lines.get(r).toString() : "", style);
			}
			if( focused ) {
				screen.cursor(area.x+Math.min(col, area.width-1), area.y+row-top);
			}
		}

		@Override
		public boolean key(Key k) {
			StringBuilder line = lines.get(row);
			col = Math.min(col, line.length());
			if( k.is(Key.Code.ENTER)) {
				StringBuilder rest = new StringBuilder(line.substring(col));
				line.setLength(col);
				lines.add(++row, rest);
				col = 0;
			} else if( k.isPlainChar()) {
				line.insert(col, new String(Character.toChars(k.ch)));
				col += Character.charCount(k.ch);
			} else if( k.is(Key.Code.BACKSPACE)) {
				if( col > 0 ) {
					line.deleteCharAt(--col);
				} else if( row > 0 ) {
					StringBuilder prev = lines.get(row-1);
					col = prev.length();
					prev.append(line);
					lines.remove(row--);
				}
			} else if( k.is(Key.Code.LEFT)) {
				col = Math.max(0, col-1);
			} else if( k.is(Key.Code.RIGHT)) {
				col = Math.min(line.length(), col+1);
			} else if( k.is(Key.Code.UP) && row > 0 ) {
				row--;
			} else if( k.is(Key.Code.DOWN) && row < lines.size()-1 ) {
				row++;
			} else {
				return false;
			}
			return true;
		}

		@Override
		public String value() {
			List<String> out = new ArrayList<>();
			for(StringBuilder l : lines) {
				out.add(l.toString());
			}
			return String.join("\n", out);
		}
	}

	/** Yes / no: Space switches. */
	public static class Check extends Field {
		private boolean checked;
		private final String text;

		public Check(boolean checked, String text) {
			this.checked = checked;
			this.text = text;
		}

		public boolean isChecked() {
			return checked;
		}

		@Override
		public void render(Screen screen, Rect area, boolean focused) {
			screen.text(area, (checked ? "[x]" : "[ ]")+(text == null ? "" : " "+text), focused ? Theme.FOCUS : Theme.NORMAL);
		}

		@Override
		public boolean key(Key k) {
			if( k.isChar(' ')) {
				checked = !checked;
				return true;
			}
			return false;
		}

		@Override
		public String value() {
			return Boolean.toString(checked);
		}
	}

	/** One of a list: Left / Right (or Space) change it. */
	public static class Choice extends Field {
		private final List<String> choices;
		private int index;

		public Choice(List<String> choices, String value) {
			this.choices = choices;
			this.index = Math.max(0, choices.indexOf(value));
		}

		@Override
		public void render(Screen screen, Rect area, boolean focused) {
			screen.text(area, "< "+choices.get(index)+" >", focused ? Theme.FOCUS : Theme.FIELD);
		}

		@Override
		public boolean key(Key k) {
			if( k.is(Key.Code.RIGHT) || k.isChar(' ')) {
				index = (index+1) % choices.size();
			} else if( k.is(Key.Code.LEFT)) {
				index = (index+choices.size()-1) % choices.size();
			} else {
				return false;
			}
			return true;
		}

		@Override
		public String value() {
			return choices.get(index);
		}
	}

	/** A button: Enter or Space does it. */
	public static class Button extends Field {
		private final String label;
		private final Runnable action;

		public Button(String label, Runnable action) {
			this.label = label;
			this.action = action;
		}

		public String label() {
			return label;
		}

		public int width() {
			return label.length()+4;
		}

		@Override
		public boolean submitsOnEnter() {
			return false;
		}

		@Override
		public void render(Screen screen, Rect area, boolean focused) {
			screen.text(area, "[ "+label+" ]", focused ? Theme.FOCUS : Theme.BOLD);
		}

		@Override
		public boolean key(Key k) {
			if( k.is(Key.Code.ENTER) || k.isChar(' ')) {
				action.run();
				return true;
			}
			return false;
		}

		@Override
		public String value() {
			return null;
		}
	}

	/** A label in a form (a heading): never has the keyboard. */
	public static class Label extends Field {
		@Override
		public boolean focusable() {
			return false;
		}

		@Override
		public void render(Screen screen, Rect area, boolean focused) {
		}

		@Override
		public boolean key(Key k) {
			return false;
		}

		@Override
		public String value() {
			return null;
		}
	}

	static List<String> split(String text) {
		return Arrays.asList(text.replace("\t", "    ").split("\n", -1));
	}
}
