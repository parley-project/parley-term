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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;

import org.junit.jupiter.api.Test;

/** The pieces under the browser: keys from bytes, and what's sent for a frame. */
public class TermTest {

	/** keys from these bytes, as a terminal would send them all at once */
	private static KeyDecoder decoder(String bytes) {
		Deque<Integer> q = new ArrayDeque<>();
		for(byte b : bytes.getBytes(StandardCharsets.UTF_8)) {
			q.add(b & 0xff);
		}
		return new KeyDecoder(t->q.isEmpty() ? -2 : q.poll());
	}

	private static Key one(String bytes) throws IOException {
		KeyDecoder d = decoder(bytes);
		Key k = d.read(0);
		assertNull(d.read(0), "more than one key in "+bytes);
		return k;
	}

	@Test
	public void decodesTheKeysTerminalsSend() throws IOException {
		assertTrue(one("a").isChar('a'));
		assertTrue(one("é").isChar('é'));
		assertTrue(one("\r").is(Key.Code.ENTER));
		assertTrue(one("\n").is(Key.Code.ENTER));
		assertTrue(one("\t").is(Key.Code.TAB));
		assertTrue(one("\u007f").is(Key.Code.BACKSPACE));
		assertTrue(one("\u0003").isCtrl('c'));
		assertTrue(one("\u0012").isCtrl('r'));
		assertTrue(one("\u001b").is(Key.Code.ESC));
		assertTrue(one("\u001bx").isAlt('x'));
		assertTrue(one("\u001b[A").is(Key.Code.UP));
		assertTrue(one("\u001bOB").is(Key.Code.DOWN));
		assertTrue(one("\u001b[1;5C").is(Key.Code.RIGHT));
		assertTrue(one("\u001b[H").is(Key.Code.HOME));
		assertTrue(one("\u001b[4~").is(Key.Code.END));
		assertTrue(one("\u001b[5~").is(Key.Code.PAGE_UP));
		assertTrue(one("\u001b[6~").is(Key.Code.PAGE_DOWN));
		assertTrue(one("\u001b[2~").is(Key.Code.INSERT));
		assertTrue(one("\u001b[3~").is(Key.Code.DELETE));
		assertTrue(one("\u001b[Z").is(Key.Code.BACKTAB));
		assertTrue(one("\u001bOP").is(Key.Code.F1));
		assertTrue(one("\u001b[13~").is(Key.Code.F3));
		assertTrue(one("\u001b[15~").is(Key.Code.F5));
		assertTrue(one("\u001b[21~").is(Key.Code.F10));
	}

	@Test
	public void keysTypedQuicklyComeOneAtATime() throws IOException {
		KeyDecoder d = decoder("\u001b[Bq\u001b");
		assertTrue(d.read(0).is(Key.Code.DOWN));
		assertTrue(d.read(0).isChar('q'));
		assertTrue(d.read(0).is(Key.Code.ESC));
		assertNull(d.read(0));
	}

	@Test
	public void sendsOnlyWhatChanged() {
		Screen a = new Screen(20, 3);
		a.text(a.area().row(0), "hello", Style.NORMAL);
		String full = Tui.diff(null, a);
		assertTrue(full.contains("\u001b[2J") && full.contains("hello"), full);

		Screen b = new Screen(20, 3);
		b.text(b.area().row(0), "help!", Style.NORMAL);
		String change = Tui.diff(a, b);
		assertFalse(change.contains("\u001b[2J"), change);
		// "hel" is the same: only from column 4 on is sent (after the style)
		assertTrue(change.contains("\u001b[1;4H") && change.contains("mp!"), change);
		assertFalse(change.contains("hel"), change);

		assertEquals(Tui.diff(b, b), Tui.diff(a, a));
	}

	/** Shift with an arrow (ESC [ 1 ; 2 A) is that key with shift; other modifiers give the plain key. */
	@Test
	public void decodesShiftWithArrows() throws IOException {
		assertTrue(one("\u001b[1;2A").isShift(Key.Code.UP));
		assertTrue(one("\u001b[1;2B").isShift(Key.Code.DOWN));
		assertTrue(one("\u001b[1;2B").is(Key.Code.DOWN));
		assertTrue(one("\u001b[1;6B").isShift(Key.Code.DOWN));
		assertTrue(one("\u001b[6;2~").isShift(Key.Code.PAGE_DOWN));
		assertTrue(one("\u001b[1;5B").is(Key.Code.DOWN));
		assertFalse(one("\u001b[1;5B").shift);
		assertFalse(one("\u001b[B").shift);
		assertEquals("Shift-UP", one("\u001b[1;2A").toString());
	}

	/**
	 * While a full-screen program's screen is up, Ctrl-C and Ctrl-Z are its keys: the shell's
	 * handler of them (fsh's, which ends the command at Ctrl-C) is set aside, and back after.
	 */
	@Test
	public void theScreenTakesCtrlCFromTheShell() throws IOException {
		Keyboard.ControlKeys shell = key -> true;
		Keyboard.controlKeys = shell;
		try {
			NativeTerminal t = new NativeTerminal(false);
			t.startScreen();
			assertNull(Keyboard.controlKeys);
			t.endScreen();
			assertTrue(Keyboard.controlKeys == shell);
		} finally {
			Keyboard.controlKeys = null;
		}
	}

	/** In a text field, Ctrl-W deletes back a folder (or word) and Ctrl-U clears it. */
	@Test
	public void textFieldDeletesBackAFolderAndClears() {
		Field.Text f = new Field.Text("/Volumes/Data/eclipse-git/fsh", false, false);
		f.key(Key.ctrl('w'));
		assertEquals("/Volumes/Data/eclipse-git/", f.value());
		f.key(Key.ctrl('w'));
		assertEquals("/Volumes/Data/", f.value());
		f.key(Key.ctrl('w'));
		f.key(Key.ctrl('w'));
		assertEquals("/", f.value());
		f.key(Key.ctrl('w'));
		assertEquals("", f.value());
		f.key(Key.ctrl('w'));
		assertEquals("", f.value());

		f = new Field.Text("one two  three", false, false);
		f.key(Key.ctrl('w'));
		assertEquals("one two  ", f.value());
		f.key(Key.ctrl('w'));
		assertEquals("one ", f.value());
		// from the middle: only what's before the cursor goes
		f = new Field.Text("/a/bb/c", false, false);
		f.key(Key.of(Key.Code.LEFT));
		f.key(Key.of(Key.Code.LEFT));
		f.key(Key.ctrl('w'));
		assertEquals("/a//c", f.value());

		f.key(Key.ctrl('u'));
		assertEquals("", f.value());
	}
}
