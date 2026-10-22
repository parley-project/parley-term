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

/** The styles the widgets use. */
public final class Theme {

	public static final Style NORMAL = Style.NORMAL;
	public static final Style BOLD = Style.NORMAL.bold();
	public static final Style HINT = Style.NORMAL.dim();
	public static final Style HIGHLIGHT = Style.NORMAL.reverse();
	public static final Style HEADER = Style.NORMAL.fg(Style.BLACK).bg(Style.CYAN);
	public static final Style FOCUS = Style.NORMAL.fg(Style.BLACK).bg(Style.CYAN);
	public static final Style FIELD = Style.NORMAL.fg(Style.WHITE).bg(Style.BLUE);
	public static final Style ERROR = Style.NORMAL.fg(Style.RED).bold();
	public static final Style MARKED = Style.NORMAL.fg(Style.YELLOW).bold();
	public static final Style DIRECTORY = Style.NORMAL.fg(Style.BLUE).bold();
	public static final Style GAUGE = Style.NORMAL.fg(Style.CYAN);

	private Theme() {
	}
}
