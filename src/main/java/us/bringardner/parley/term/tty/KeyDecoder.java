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

import java.io.IOException;

/**
 * Bytes typed, as keys: UTF-8 characters, control characters, and the escape sequences
 * terminals send for the other keys (xterm, VT220 and the Linux console's, which cover macOS
 * Terminal, iTerm, Windows Terminal, GNOME Terminal and PuTTY). Esc alone is told from the start
 * of a sequence by the time to the next byte.
 */
public final class KeyDecoder {

	/** Where bytes come from: one byte, waiting at most timeoutMs; -2 if none came, -1 at the end. */
	public interface Bytes {
		int read(int timeoutMs) throws IOException;
	}

	/** How long the rest of an escape sequence may take to come. */
	static final int SEQUENCE_MS = 40;

	private final Bytes in;
	private int pushed = -2;

	public KeyDecoder(Bytes in) {
		this.in = in;
	}

	private int next(int timeoutMs) throws IOException {
		if( pushed != -2 ) {
			int b = pushed;
			pushed = -2;
			return b;
		}
		return in.read(timeoutMs);
	}

	/** The next key, waiting at most timeoutMs; null if none came. @throws java.io.EOFException at the end */
	public Key read(int timeoutMs) throws IOException {
		int b = next(timeoutMs);
		if( b == -2 ) {
			return null;
		}
		if( b == -1 ) {
			throw new java.io.EOFException("end of input");
		}
		if( b == 27 ) {
			return escape();
		}
		return single(b);
	}

	private Key single(int b) throws IOException {
		switch (b) {
			case '\r':
			case '\n':
				return Key.of(Key.Code.ENTER);
			case '\t':
				return Key.of(Key.Code.TAB);
			case 127:
			case 8:
				return Key.of(Key.Code.BACKSPACE);
			default:
				break;
		}
		if( b < 32 ) {
			return b == 0 ? Key.ctrl(' ') : Key.ctrl((char) ('a'+b-1));
		}
		if( b < 0x80 ) {
			return Key.ch(b);
		}
		return Key.ch(utf8(b));
	}

	private int utf8(int b) throws IOException {
		int more;
		int cp;
		if( (b & 0xE0) == 0xC0 ) {
			more = 1;
			cp = b & 0x1F;
		} else if( (b & 0xF0) == 0xE0 ) {
			more = 2;
			cp = b & 0x0F;
		} else if( (b & 0xF8) == 0xF0 ) {
			more = 3;
			cp = b & 0x07;
		} else {
			return 0xFFFD;
		}
		for(int i=0; i < more; i++) {
			int c = next(SEQUENCE_MS);
			if( c < 0 || (c & 0xC0) != 0x80 ) {
				if( c >= 0 ) {
					pushed = c;
				}
				return 0xFFFD;
			}
			cp = (cp << 6) | (c & 0x3F);
		}
		return cp;
	}

	private Key escape() throws IOException {
		int b = next(SEQUENCE_MS);
		if( b < 0 ) {
			return Key.of(Key.Code.ESC);
		}
		if( b == '[' ) {
			return csi();
		}
		if( b == 'O' ) {
			int c = next(SEQUENCE_MS);
			switch (c) {
				case 'A': return Key.of(Key.Code.UP);
				case 'B': return Key.of(Key.Code.DOWN);
				case 'C': return Key.of(Key.Code.RIGHT);
				case 'D': return Key.of(Key.Code.LEFT);
				case 'H': return Key.of(Key.Code.HOME);
				case 'F': return Key.of(Key.Code.END);
				case 'P': return Key.of(Key.Code.F1);
				case 'Q': return Key.of(Key.Code.F2);
				case 'R': return Key.of(Key.Code.F3);
				case 'S': return Key.of(Key.Code.F4);
				default: return Key.alt('O');
			}
		}
		if( b == 27 ) {
			pushed = 27;
			return Key.of(Key.Code.ESC);
		}
		// Alt and a key: ESC then the key
		Key k = single(b);
		return k.code == Key.Code.CHAR ? new Key(Key.Code.CHAR, k.ch, k.ctrl, true) : k;
	}

	/** CSI: ESC [ parameters final */
	private Key csi() throws IOException {
		StringBuilder params = new StringBuilder();
		int c;
		while(true) {
			c = next(SEQUENCE_MS);
			if( c < 0 ) {
				return Key.alt('[');
			}
			if( c >= 0x40 && c <= 0x7E ) {
				break;
			}
			params.append((char) c);
		}
		String p = params.toString();
		Key key = csiKey(c, p.contains(";") ? p.substring(0, p.indexOf(';')) : p);
		// modifiers (1;2A is Shift-Up, 1;5A Ctrl-Up): 1 + 1 for Shift + 2 for Alt + 4 for Ctrl. Only
		// Shift is kept, for keys that aren't characters (Shift and the arrows extend a selection)
		if( p.contains(";") && key.code != Key.Code.CHAR ) {
			try {
				int mod = Integer.parseInt(p.substring(p.indexOf(';')+1).split(";")[0])-1;
				if( (mod & 1) != 0 ) {
					return Key.shift(key.code);
				}
			} catch (NumberFormatException e) {
				// (not a modifier: the key as it is)
			}
		}
		return key;
	}

	private static Key csiKey(int c, String first) {
		switch (c) {
			case 'A': return Key.of(Key.Code.UP);
			case 'B': return Key.of(Key.Code.DOWN);
			case 'C': return Key.of(Key.Code.RIGHT);
			case 'D': return Key.of(Key.Code.LEFT);
			case 'H': return Key.of(Key.Code.HOME);
			case 'F': return Key.of(Key.Code.END);
			case 'Z': return Key.of(Key.Code.BACKTAB);
			case 'P': return Key.of(Key.Code.F1);
			case 'Q': return Key.of(Key.Code.F2);
			case 'R': return Key.of(Key.Code.F3);
			case 'S': return Key.of(Key.Code.F4);
			case '~':
				break;
			default:
				return Key.alt('[');
		}
		switch (first) {
			case "1": case "7": return Key.of(Key.Code.HOME);
			case "2": return Key.of(Key.Code.INSERT);
			case "3": return Key.of(Key.Code.DELETE);
			case "4": case "8": return Key.of(Key.Code.END);
			case "5": return Key.of(Key.Code.PAGE_UP);
			case "6": return Key.of(Key.Code.PAGE_DOWN);
			case "11": return Key.of(Key.Code.F1);
			case "12": return Key.of(Key.Code.F2);
			case "13": return Key.of(Key.Code.F3);
			case "14": return Key.of(Key.Code.F4);
			case "15": return Key.of(Key.Code.F5);
			case "17": return Key.of(Key.Code.F6);
			case "18": return Key.of(Key.Code.F7);
			case "19": return Key.of(Key.Code.F8);
			case "20": return Key.of(Key.Code.F9);
			case "21": return Key.of(Key.Code.F10);
			case "23": return Key.of(Key.Code.F11);
			case "24": return Key.of(Key.Code.F12);
			default: return Key.alt('[');
		}
	}
}
