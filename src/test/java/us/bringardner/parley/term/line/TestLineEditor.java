package us.bringardner.parley.term.line;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import us.bringardner.parley.term.tty.Keyboard;

/**
 * The line editor's keys, as bash's readline (emacs mode) does them: each case types keys and
 * checks the line it gives (the same keys checked against bash 5.3 on a terminal).
 */
public class TestLineEditor {

	private static final int ESC = 27;

	/** the keys: text as typed, and ints as keys */
	private static int[] keys(Object ... parts) {
		List<Integer> ret = new ArrayList<>();
		for(Object p : parts) {
			if( p instanceof String ) { String s = (String) p;
				for(int k : LineEditor.typed(s)) {
					ret.add(k);
				}
			} else {
				ret.add((Integer) p);
			}
		}
		ret.add((int) '\n');
		return ret.stream().mapToInt(Integer::intValue).toArray();
	}

	private static int ctrl(char c) {
		return Character.toLowerCase(c) & 0x1f;
	}

	private static String edit(List<String> history, Object ... parts) throws Exception {
		LineEditor e = new LineEditor(LineEditor.keys(keys(parts)), new PrintStream(new ByteArrayOutputStream()), history);
		return e.readLine("$ ");
	}

	private static String edit(Object ... parts) throws Exception {
		return edit(new ArrayList<>(), parts);
	}

	@Test
	public void movingAndDeleting() throws Exception {
		assertEquals("echo 1", edit("cho 1", ctrl('a'), "e"));
		assertEquals("echo 2", edit("echo 2 extra", ctrl('w'), 127));
		assertEquals("echo 3 aaa zz", edit("echo 3 aaa bbb", ESC, (int) 'b', ctrl('k'), "zz"));
		assertEquals("echo ba", edit("echo ab", ctrl('t')));
		assertEquals("echo FOO bar", edit("echo foo bar", ESC, (int) 'b', ESC, (int) 'b', ESC, (int) 'u'));
		assertEquals("echo ac", edit("echo abc", ctrl('b'), ctrl('b'), ctrl('d')));
		assertEquals("echo xyz", edit("echo xz", ESC, (int) '[', (int) 'D', "y"));
		assertEquals("Xecho abY", edit("echo ab", ESC, (int) '[', (int) 'H', "X", ESC, (int) '[', (int) 'F', "Y"));
		assertEquals("echo é", edit("echo é"));
	}

	@Test
	public void killAndYank() throws Exception {
		assertEquals("echo 7b echo 7 kill", edit("echo 7 kill", ctrl('u'), "echo 7b ", ctrl('y')));
		assertEquals("echo bbbbbb", edit("echo aaa", ctrl('w'), "bbb", ctrl('w'), ctrl('y'), ctrl('y')));
		// two kills in a row are one: yank gives both
		assertEquals("one two", edit("one two", ctrl('w'), ctrl('w'), ctrl('y')));
		// Alt-Y: the kill before
		assertEquals("a b first", edit("first", ctrl('u'), "second", ctrl('u'), "a b ", ctrl('y'), ESC, (int) 'y'));
	}

	@Test
	public void undo() throws Exception {
		assertEquals("", edit("echo abc", 31));
		assertEquals("echo abc", edit("echo abc", ctrl('w'), 31));
	}

	@Test
	public void history() throws Exception {
		List<String> h = new ArrayList<>(List.of("echo one", "echo two", "ls -l /tmp"));
		assertEquals("ls -l /tmp", edit(h, ctrl('p')));
		assertEquals("echo two", edit(h, ESC, (int) '[', (int) 'A', ESC, (int) '[', (int) 'A'));
		assertEquals("echo one", edit(h, ESC, (int) '<'));
		assertEquals("", edit(h, ctrl('p'), ctrl('n')));
		// Alt-.: the last word of the command before, then the one before that
		assertEquals("cat /tmp", edit(h, "cat ", ESC, (int) '.'));
		assertEquals("cat two", edit(h, "cat ", ESC, (int) '.', ESC, (int) '.'));
		// Ctrl-R: the latest line with the text; Ctrl-R again: an earlier one
		assertEquals("echo two", edit(h, ctrl('r'), "echo"));
		assertEquals("echo one", edit(h, ctrl('r'), "echo", ctrl('r')));
		// an editing key keeps the line found
		assertEquals("echo two!", edit(h, ctrl('r'), "two", ctrl('e'), "!"));
		// Ctrl-G puts the line back
		assertEquals("x", edit(h, "x", ctrl('r'), "echo", ctrl('g')));
	}

	@Test
	public void endAndCancel() throws Exception {
		LineEditor e = new LineEditor(LineEditor.keys(ctrl('d')), new PrintStream(new ByteArrayOutputStream()), List.of());
		assertNull(e.readLine("$ "));
		// Ctrl-D with text deletes
		assertEquals("ab", edit("abc", ctrl('b'), ctrl('d')));
		LineEditor c = new LineEditor(LineEditor.keys(LineEditor.typed("abc\u0003")), new PrintStream(new ByteArrayOutputStream()), List.of());
		assertThrows(Keyboard.LineCancelled.class, () -> c.readLine("$ "));
	}

	@org.junit.jupiter.api.condition.DisabledOnOs(value = org.junit.jupiter.api.condition.OS.WINDOWS, disabledReason = "completion of $HOME depends on HOME being a directory")
	/** bind: keys bound to functions, macros, sequences of keys, a key that does nothing, and -x */
	@Test
	public void boundKeys() throws Exception {
		try {
			// history-search-backward on Alt-p: the lines that start with what is before the cursor
			LineEditor.bind("\u001bp", "history-search-backward");
			List<String> history = new ArrayList<>(List.of("echo one", "ls -l", "echo two"));
			assertEquals("echo two", edit(history, "ec", ESC, (int) 'p'));
			assertEquals("echo one", edit(history, "ec", ESC, (int) 'p', ESC, (int) 'p'));
			// a macro on a sequence of two keys (C-x p)
			LineEditor.bind("\u0018p", "\"hello\"");
			assertEquals("say hello", edit("say ", ctrl('x'), "p"));
			// a key bound to another function: C-a to end-of-line
			LineEditor.bind("\u0001", "end-of-line");
			assertEquals("abX", edit("ab", ctrl('b'), ctrl('b'), ctrl('a'), "X"));
			// bind -r: the key does nothing
			LineEditor.unbindKey("\u0002");
			assertEquals("abX", edit("ab", ctrl('b'), "X"));
			// bind -x: the command gets the line and cursor, and what it leaves is the line
			LineEditor.setCommandRunner((command, line, point) -> new Object[] {line.toUpperCase()+"!"+command, 2});
			LineEditor.bindCommand("\u0018x", "cmd");
			assertEquals("ABZ!cmd", edit("abz", ctrl('x'), "x"));
		} finally {
			LineEditor.resetBindings();
			LineEditor.setCommandRunner(null);
		}
	}

	private static String editVi(Object ... parts) throws Exception {
		LineEditor e = new LineEditor(LineEditor.keys(keys(parts)), new PrintStream(new ByteArrayOutputStream()), new ArrayList<>());
		e.setViMode(true);
		return e.readLine("$ ");
	}

	/** set -o vi: command mode's motions and changes (each checked against bash 5.3's readline) */
	@Test
	public void viMode() throws Exception {
		String[][] cases = {
			{"echo hello world\u001bbdwi", "echo hello "},
			{"echo one two three\u001b0wcwXX\u001b", "echo XX two three"},
			{"echo abc\u001bhhx", "echo bc"},
			{"echo abcdef\u001b02dl", "ho abcdef"},
			{"echo foo bar\u001bFbd$", "echo foo "},
			{"echo foo bar\u001b0fod;", "echo bar"},
			{"echo aaa\u001b0yyP", "echo aaaecho aaa"},
			{"echo xyz\u001br1", "echo xy1"},
			{"echo case\u001b0~~~", "ECHo case"},
			{"echo one two\u001b0wDiend", "echoend "},
			{"echo q w e\u001b02wiZ \u001b", "echo q Z w e"},
			{"echo t1\u001bIa \u001b", "a echo t1"},
			{"echo xx\u001buAyy", "echo xxyy"},
			{"echo 12345\u001b0ft3x", "o 12345"},
			{"echo abc def\u001b0wdeAZ", "echo  defZ"},
			{"echo abc def\u001b02bcbQ\u001b", "Qecho abc def"},
			{"echo abcdef\u001b0w3x", "echo def"},
			{"echo abcdef\u001bXi", "echo abcdf"},
			{"echo abc\u001b0wsZ", "echo Zbc"},
			{"echo abc def\u001b0wSecho new", "echo new"},
			{"echo abc def\u001b0wCnew", "echo new"},
			{"echo abc\u001bccecho cc", "echo cc"},
			{"echo zzz\u001bddiecho dd", "echo dd"},
			{"echo 1234567\u001b0t5d0", "4567"},
			{"echo 1234567\u001bT2x", "echo 124567"},
			{"echo abcdef\u001b8|x", "echo abdef"},
			{"echo abc def\u001b0wywP", "echo abc abc def"},
			{"echo abcdef\u001b0w3rZ", "echo ZZZdef"},
			{"echo one two three\u001b0w2dw", "echo three"},
			{"echo one two three\u001b0wd2w", "echo three"},
			{"echo aXbXc\u001b0fX;x", "echo aXbc"},
			{"echo aXbXc\u001b$FX,x", "echo aXbc"},
			{"echo hello\u001b0wea!", "echo hello!"},
		};
		for(String [] c : cases) {
			List<Object> parts = new ArrayList<>();
			for(String piece : c[0].split("(?<=\u001b)|(?=\u001b)")) {
				if( piece.equals("\u001b")) {
					// (Escape alone: nothing comes right after it)
					parts.add(27);
					parts.add(LineEditor.KEY_NONE);
				} else {
					parts.add(piece);
				}
			}
			assertEquals(c[1], editVi(parts.toArray()), c[0]);
		}
		// the history: k and j, / and n (as bash's readline in vi mode)
		List<String> history = new ArrayList<>(List.of("echo first", "echo second", "echo second"));
		assertEquals("echo second", editViHistory(history, 27, LineEditor.KEY_NONE, "k"));
		assertEquals("echo second", editViHistory(history, 27, LineEditor.KEY_NONE, "kk"));
		assertEquals("echo first", editViHistory(history, 27, LineEditor.KEY_NONE, "/first\n"));
		assertEquals("echo second", editViHistory(history, 27, LineEditor.KEY_NONE, "kkkj"));
	}

	private static String editViHistory(List<String> history, Object ... parts) throws Exception {
		LineEditor e = new LineEditor(LineEditor.keys(keys(parts)), new PrintStream(new ByteArrayOutputStream()), history);
		e.setViMode(true);
		return e.readLine("$ ");
	}
}
