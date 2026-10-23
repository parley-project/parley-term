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
package us.bringardner.parley.term.tty;

/** A key that was typed: a character (with Ctrl or Alt), or a named key. */
public final class Key {

	public enum Code {
		CHAR, ENTER, ESC, BACKSPACE, TAB, BACKTAB, UP, DOWN, LEFT, RIGHT, HOME, END, PAGE_UP, PAGE_DOWN,
		INSERT, DELETE, F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12
	}

	public final Code code;
	/** the character (CHAR), a lower-case letter for Ctrl-letter */
	public final int ch;
	public final boolean ctrl;
	public final boolean alt;
	/** Shift with a key that isn't a character (Shift-Up), as the terminal reports it */
	public final boolean shift;

	public Key(Code code, int ch, boolean ctrl, boolean alt) {
		this(code, ch, ctrl, alt, false);
	}

	public Key(Code code, int ch, boolean ctrl, boolean alt, boolean shift) {
		this.code = code;
		this.ch = ch;
		this.ctrl = ctrl;
		this.alt = alt;
		this.shift = shift;
	}

	public static Key of(Code code) {
		return new Key(code, 0, false, false);
	}

	/** this key with Shift (Shift-Up) */
	public static Key shift(Code code) {
		return new Key(code, 0, false, false, true);
	}

	public static Key ch(int c) {
		return new Key(Code.CHAR, c, false, false);
	}

	public static Key ctrl(char c) {
		return new Key(Code.CHAR, Character.toLowerCase(c), true, false);
	}

	public static Key alt(char c) {
		return new Key(Code.CHAR, c, false, true);
	}

	public boolean is(Code c) {
		return code == c;
	}

	/** This character, typed without Ctrl or Alt. */
	public boolean isChar(int c) {
		return code == Code.CHAR && !ctrl && !alt && ch == c;
	}

	/** A character typed without Ctrl or Alt. */
	public boolean isPlainChar() {
		return code == Code.CHAR && !ctrl && !alt;
	}

	/** this key with Shift held (Shift-Down); is(code) is true for it too */
	public boolean isShift(Code c) {
		return code == c && shift;
	}

	public boolean isCtrl(char c) {
		return code == Code.CHAR && ctrl && ch == Character.toLowerCase(c);
	}

	public boolean isAlt(char c) {
		return code == Code.CHAR && alt && Character.toLowerCase(ch) == Character.toLowerCase(c);
	}

	/** Esc or Ctrl-C: every way out of a dialog but OK counts as cancel. */
	public boolean isCancel() {
		return code == Code.ESC || isCtrl('c');
	}

	@Override
	public String toString() {
		return code == Code.CHAR ? (ctrl ? "Ctrl-" : "")+(alt ? "Alt-" : "")+new String(Character.toChars(ch)) : (shift ? "Shift-" : "")+code.name();
	}
}
