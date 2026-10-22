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
 * What's on the terminal, as cells: a character and a style each. The application draws a whole
 * frame into one ({@link Tui} keeps the previous frame and sends only the cells that changed).
 * Drawing outside it is clipped.
 */
public final class Screen {

	private final int width;
	private final int height;
	final int[] chars;
	final Style[] styles;
	private int cursorX = -1;
	private int cursorY = -1;

	public Screen(int width, int height) {
		this.width = width;
		this.height = height;
		chars = new int[width*height];
		styles = new Style[width*height];
		clear();
	}

	public int width() {
		return width;
	}

	public int height() {
		return height;
	}

	public Rect area() {
		return new Rect(0, 0, width, height);
	}

	public void clear() {
		java.util.Arrays.fill(chars, ' ');
		java.util.Arrays.fill(styles, Style.NORMAL);
		cursorX = cursorY = -1;
	}

	public int charAt(int x, int y) {
		return chars[y*width+x];
	}

	public Style styleAt(int x, int y) {
		return styles[y*width+x];
	}

	/** One row as text (trailing spaces kept). */
	public String line(int y) {
		StringBuilder b = new StringBuilder();
		for(int x=0; x < width; x++) {
			b.appendCodePoint(chars[y*width+x]);
		}
		return b.toString();
	}

	public void set(int x, int y, int ch, Style style) {
		if( x >= 0 && y >= 0 && x < width && y < height ) {
			chars[y*width+x] = ch;
			styles[y*width+x] = style;
		}
	}

	/** text at (x, y), cut at maxX; returns the column after it. Tabs and control characters show as spaces. */
	public int text(int x, int y, String text, Style style, int maxX) {
		int end = Math.min(maxX, width);
		for(int i=0; i < text.length() && x < end;) {
			int cp = text.codePointAt(i);
			i += Character.charCount(cp);
			set(x++, y, cp < ' ' ? ' ' : cp, style);
		}
		return x;
	}

	/** text in the area's first row, cut to its width. */
	public void text(Rect area, String text, Style style) {
		if( area.height > 0 ) {
			text(area.x, area.y, text, style, area.right());
		}
	}

	/** text padded with spaces to the area's width (a highlighted row). */
	public void line(Rect area, String text, Style style) {
		if( area.height > 0 ) {
			int x = text(area.x, area.y, text, style, area.right());
			while(x < area.right()) {
				set(x++, area.y, ' ', style);
			}
		}
	}

	public void fill(Rect area, Style style) {
		for(int y=area.y; y < area.bottom(); y++) {
			for(int x=area.x; x < area.right(); x++) {
				set(x, y, ' ', style);
			}
		}
	}

	/** A cleared box with a line border and a title; returns the inside. */
	public Rect box(Rect area, String title, Style style) {
		fill(area, style);
		for(int x=area.x+1; x < area.right()-1; x++) {
			set(x, area.y, '─', style);
			set(x, area.bottom()-1, '─', style);
		}
		for(int y=area.y+1; y < area.bottom()-1; y++) {
			set(area.x, y, '│', style);
			set(area.right()-1, y, '│', style);
		}
		set(area.x, area.y, '┌', style);
		set(area.right()-1, area.y, '┐', style);
		set(area.x, area.bottom()-1, '└', style);
		set(area.right()-1, area.bottom()-1, '┘', style);
		if( title != null && !title.isEmpty() && area.width > 4 ) {
			text(area.x+2, area.y, " "+title+" ", style.bold(), area.right()-2);
		}
		return area.inner();
	}

	/** Where the terminal's cursor goes (a field being edited); otherwise it's hidden. */
	public void cursor(int x, int y) {
		cursorX = x;
		cursorY = y;
	}

	int cursorX() {
		return cursorX;
	}

	int cursorY() {
		return cursorY;
	}
}
