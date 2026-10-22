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

/** A rectangle of cells: the area a widget draws in. */
public final class Rect {

	public final int x;
	public final int y;
	public final int width;
	public final int height;

	public Rect(int x, int y, int width, int height) {
		this.x = x;
		this.y = y;
		this.width = Math.max(0, width);
		this.height = Math.max(0, height);
	}

	/** One row of it (y counted from its top). */
	public Rect row(int y) {
		return new Rect(x, this.y+y, width, y < height ? 1 : 0);
	}

	/** Rows from y on (to the bottom), or height rows from y. */
	public Rect rows(int y, int height) {
		return new Rect(x, this.y+y, width, Math.min(height, this.height-y));
	}

	/** Columns from x on, width wide. */
	public Rect cols(int x, int width) {
		return new Rect(this.x+x, y, Math.min(width, this.width-x), height);
	}

	/** Inside a border of one cell. */
	public Rect inner() {
		return new Rect(x+1, y+1, width-2, height-2);
	}

	/** A width x height rectangle in its middle (no bigger than it). */
	public Rect centered(int width, int height) {
		int w = Math.min(width, this.width);
		int h = Math.min(height, this.height);
		return new Rect(x+(this.width-w)/2, y+(this.height-h)/2, w, h);
	}

	public int bottom() {
		return y+height;
	}

	public int right() {
		return x+width;
	}
}
