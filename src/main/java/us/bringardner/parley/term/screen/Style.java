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

/**
 * How a cell looks: a foreground and background color (the 8 ANSI colors, or the terminal's
 * default) and bold, dim, reverse, underline. Immutable; each method returns a new style.
 */
public final class Style {

	public static final int DEFAULT = -1;
	public static final int BLACK = 0;
	public static final int RED = 1;
	public static final int GREEN = 2;
	public static final int YELLOW = 3;
	public static final int BLUE = 4;
	public static final int MAGENTA = 5;
	public static final int CYAN = 6;
	public static final int WHITE = 7;

	public static final Style NORMAL = new Style(DEFAULT, DEFAULT, 0);

	private static final int BOLD = 1;
	private static final int DIM = 2;
	private static final int REVERSE = 4;
	private static final int UNDERLINE = 8;

	private final int fg;
	private final int bg;
	private final int attrs;

	private Style(int fg, int bg, int attrs) {
		this.fg = fg;
		this.bg = bg;
		this.attrs = attrs;
	}

	public Style fg(int color) {
		return new Style(color, bg, attrs);
	}

	public Style bg(int color) {
		return new Style(fg, color, attrs);
	}

	public Style bold() {
		return new Style(fg, bg, attrs | BOLD);
	}

	public Style dim() {
		return new Style(fg, bg, attrs | DIM);
	}

	public Style reverse() {
		return new Style(fg, bg, attrs | REVERSE);
	}

	public Style underline() {
		return new Style(fg, bg, attrs | UNDERLINE);
	}

	/** The SGR sequence that sets this style from a reset. */
	String sgr() {
		StringBuilder b = new StringBuilder("\u001b[0");
		if( (attrs & BOLD) != 0 ) {
			b.append(";1");
		}
		if( (attrs & DIM) != 0 ) {
			b.append(";2");
		}
		if( (attrs & UNDERLINE) != 0 ) {
			b.append(";4");
		}
		if( (attrs & REVERSE) != 0 ) {
			b.append(";7");
		}
		if( fg != DEFAULT ) {
			b.append(';').append(30+fg);
		}
		if( bg != DEFAULT ) {
			b.append(';').append(40+bg);
		}
		return b.append('m').toString();
	}

	@Override
	public boolean equals(Object o) {
		if( !(o instanceof Style)) {
			return false;
		}
		Style s = (Style) o;
		return s.fg == fg && s.bg == bg && s.attrs == attrs;
	}

	@Override
	public int hashCode() {
		return (fg+1)*1000+(bg+1)*100+attrs;
	}
}
