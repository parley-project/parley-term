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

import java.io.IOException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;

/**
 * Runs a full-screen application on a terminal: one thread that reads keys, runs what other
 * threads hand it ({@link #ui}), and draws. The application draws a whole frame each time into
 * a {@link Screen}; only the cells that changed are sent. Keys are read only on this thread and
 * only while it runs, so nothing typed after it ends is taken from the terminal.
 */
public final class Tui {

	/** What runs on the terminal. */
	public interface App {
		/** Draw the whole screen. */
		void render(Screen screen);

		/** A key; true if the screen should be drawn again. */
		boolean key(Key key);

		/** Whether to stop. */
		boolean isDone();
	}

	static final String CSI = "\u001b[";
	private static final int KEY_WAIT_MS = 20;
	private static final int SIZE_CHECK_MS = 250;

	private final Terminal terminal;
	private final KeyDecoder keys;
	private final ConcurrentLinkedQueue<Runnable> posted = new ConcurrentLinkedQueue<>();
	private Screen shown;
	private volatile boolean dirty = true;
	private int width;
	private int height;
	private boolean full = true;

	public Tui(Terminal terminal) {
		this.terminal = terminal;
		this.keys = new KeyDecoder(terminal::read);
	}

	/** Runs r on the UI thread, then draws the screen again. Safe from any thread. */
	public final Executor ui = r->{
		posted.add(r);
	};

	/** Run app until it's done. The terminal is put back as it was, whatever happens. */
	public void run(App app) throws IOException {
		terminal.startScreen();
		terminal.write(CSI+"?1049h"+CSI+"?25l"+CSI+"2J");
		try {
			int[] s = terminal.size();
			width = s[0];
			height = s[1];
			long lastSizeCheck = System.currentTimeMillis();
			while(!app.isDone()) {
				Runnable r;
				while((r = posted.poll()) != null) {
					r.run();
					dirty = true;
				}
				if( app.isDone()) {
					break;
				}
				if( dirty ) {
					draw(app);
				}
				Key k;
				try {
					k = keys.read(KEY_WAIT_MS);
				} catch (java.io.EOFException e) {
					// the input is gone (or, in fsh, Ctrl-C ended the command): stop
					break;
				}
				if( k != null && app.key(k)) {
					dirty = true;
				}
				long now = System.currentTimeMillis();
				if( now-lastSizeCheck >= SIZE_CHECK_MS ) {
					lastSizeCheck = now;
					s = terminal.size();
					if( s[0] != width || s[1] != height ) {
						width = s[0];
						height = s[1];
						full = true;
						dirty = true;
					}
				}
			}
		} finally {
			terminal.write(CSI+"0m"+CSI+"?25h"+CSI+"?1049l");
			terminal.flush();
			terminal.endScreen();
		}
	}

	/** Draw everything again on the next frame (after another program had the terminal). */
	public void redrawAll() {
		full = true;
		dirty = true;
	}

	/**
	 * Lend the terminal to another program (an editor, a shell): the screen as it was before, and
	 * all of it back after. Call on the UI thread.
	 */
	public int lend(ProcessBuilder program) throws IOException {
		terminal.write(CSI+"0m"+CSI+"?25h"+CSI+"?1049l");
		terminal.flush();
		try {
			return terminal.lend(program);
		} finally {
			terminal.write(CSI+"?1049h"+CSI+"?25l");
			redrawAll();
		}
	}

	private void draw(App app) throws IOException {
		dirty = false;
		Screen next = new Screen(Math.max(1, width), Math.max(1, height));
		app.render(next);
		terminal.write(diff(full ? null : shown, next));
		terminal.flush();
		shown = next;
		full = false;
	}

	/** What to send to go from before (null: anything) to after: the changed cells, in runs. */
	static String diff(Screen before, Screen after) {
		StringBuilder out = new StringBuilder();
		out.append(CSI).append("?25l");
		if( before == null || before.width() != after.width() || before.height() != after.height()) {
			out.append(CSI).append("0m").append(CSI).append("2J");
			before = null;
		}
		Style current = null;
		int w = after.width();
		for(int y=0; y < after.height(); y++) {
			int x = 0;
			while(x < w) {
				int i = y*w+x;
				if( before != null && before.chars[i] == after.chars[i] && before.styles[i].equals(after.styles[i])) {
					x++;
					continue;
				}
				// a run of changed cells from here
				out.append(CSI).append(y+1).append(';').append(x+1).append('H');
				while(x < w) {
					i = y*w+x;
					if( before != null && before.chars[i] == after.chars[i] && before.styles[i].equals(after.styles[i])) {
						break;
					}
					if( !after.styles[i].equals(current)) {
						current = after.styles[i];
						out.append(current.sgr());
					}
					out.appendCodePoint(after.chars[i]);
					x++;
				}
			}
		}
		out.append(CSI).append("0m");
		if( after.cursorX() >= 0 ) {
			out.append(CSI).append(after.cursorY()+1).append(';').append(after.cursorX()+1).append('H').append(CSI).append("?25h");
		}
		return out.toString();
	}
}
