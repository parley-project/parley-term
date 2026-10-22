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

import java.util.List;

/** A scrolling list with a cursor: the state (where the cursor is, what's at the top) and drawing. */
public final class ListView {

	private int selected;
	private int top;

	public int selected() {
		return selected;
	}

	public void select(int index, int count) {
		selected = Math.max(0, Math.min(count-1, index));
	}

	/** Up, Down, PgUp, PgDn, Home, End; true if it was one of them. */
	public boolean key(Key k, int count, int page) {
		if( k.is(Key.Code.UP)) {
			select(selected-1, count);
		} else if( k.is(Key.Code.DOWN)) {
			select(selected+1, count);
		} else if( k.is(Key.Code.PAGE_UP)) {
			select(selected-page, count);
		} else if( k.is(Key.Code.PAGE_DOWN)) {
			select(selected+page, count);
		} else if( k.is(Key.Code.HOME)) {
			select(0, count);
		} else if( k.is(Key.Code.END)) {
			select(count-1, count);
		} else {
			return false;
		}
		return true;
	}

	/** The first item shown, so the cursor is on the screen. */
	public int top(int rows) {
		if( selected < top ) {
			top = selected;
		} else if( selected >= top+rows ) {
			top = selected-rows+1;
		}
		return Math.max(0, top);
	}

	public void render(Screen screen, Rect area, List<String> items, boolean focused) {
		int first = top(area.height);
		for(int i=0; i < area.height; i++) {
			int idx = first+i;
			String text = idx < items.size() ? items.get(idx) : "";
			screen.line(area.row(i), text, idx == selected && idx < items.size() && focused ? Theme.HIGHLIGHT : Theme.NORMAL);
		}
	}
}
