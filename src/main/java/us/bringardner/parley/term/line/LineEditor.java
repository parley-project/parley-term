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
package us.bringardner.parley.term.line;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import us.bringardner.parley.term.tty.Keyboard;

/**
 * A line editor for an interactive prompt (fsh's), as bash's readline in its emacs mode: moving by
 * character and word, killing and yanking, undo, the history (with Ctrl-R / Ctrl-S search and
 * Alt-.), and Tab completion. It draws the line itself (lines longer than the terminal wrap).
 */
public class LineEditor {

	/** where the keys come from */
	public interface Keys {
		/** the next key (a byte, or one of NativeKeyboard's special keys), waiting for it; KEY_EOF at the end */
		int next() throws IOException;

		/** the next key if one comes within millis, else Keyboard.KEY_NONE */
		int nextWithin(long millis) throws IOException;
	}

	/** completion for Tab: what the word at the cursor can become */
	public interface Completer {
		Completions.Result complete(String line, int cursor);
	}

	public static final int KEY_EOF = Keyboard.KEY_EOF;
	public static final int KEY_NONE = Keyboard.KEY_NONE;

	// the keys that are not characters, after an escape sequence is read
	private static final int K_UP = Keyboard.UP, K_DOWN = Keyboard.DN, K_RIGHT = Keyboard.RT,
			K_LEFT = Keyboard.LF, K_HOME = Keyboard.HOME, K_END = Keyboard.END,
			K_DELETE = Keyboard.DELETE, K_PAGE_UP = Keyboard.PAGE_UP, K_PAGE_DOWN = Keyboard.PAGE_DOWN;
	/** Alt (Meta) and a key: META+c */
	private static final int META = 0x10000;
	private static final int K_CTRL_LEFT = 600, K_CTRL_RIGHT = 601;

	/** history-search-backward and -forward (no key of their own until bind gives them one) */
	private static final int K_HISTORY_BACK = 602, K_HISTORY_FORWARD = 603;

	// ------------------------------------------------------------------ bind: what the keys do

	/** the readline functions this editor does, and the key each is on by default (bind maps keys to them) */
	public static final Map<String, Integer> FUNCTIONS = new java.util.TreeMap<>();
	/** the keys and what they do by default, in the order bind -p lists them */
	private static final Map<Integer, String> DEFAULTS = new java.util.LinkedHashMap<>();
	static {
		Object [][] keys = {
			{1, "beginning-of-line"}, {K_HOME, "beginning-of-line"}, {5, "end-of-line"}, {K_END, "end-of-line"},
			{2, "backward-char"}, {K_LEFT, "backward-char"}, {6, "forward-char"}, {K_RIGHT, "forward-char"},
			{META+'b', "backward-word"}, {K_CTRL_LEFT, "backward-word"}, {META+'f', "forward-word"}, {K_CTRL_RIGHT, "forward-word"},
			{K_DELETE, "delete-char"}, {4, "delete-char"}, {127, "backward-delete-char"}, {8, "backward-delete-char"},
			{11, "kill-line"}, {21, "unix-line-discard"}, {23, "unix-word-rubout"}, {META+'d', "kill-word"},
			{META+127, "backward-kill-word"}, {META+8, "backward-kill-word"}, {25, "yank"}, {META+'y', "yank-pop"},
			{20, "transpose-chars"}, {META+'t', "transpose-words"}, {META+'u', "upcase-word"}, {META+'l', "downcase-word"},
			{META+'c', "capitalize-word"}, {31, "undo"}, {META+'r', "revert-line"}, {12, "clear-screen"},
			{16, "previous-history"}, {K_UP, "previous-history"}, {14, "next-history"}, {K_DOWN, "next-history"},
			{META+'<', "beginning-of-history"}, {META+'>', "end-of-history"}, {META+'.', "yank-last-arg"},
			{META+'_', "yank-last-arg"}, {18, "reverse-search-history"}, {19, "forward-search-history"},
			{22, "quoted-insert"}, {15, "operate-and-get-next"}, {(int) '\t', "complete"}, {7, "abort"}, {(int) '\n', "accept-line"}, {(int) '\r', "accept-line"},
		};
		for(Object [] k : keys) {
			DEFAULTS.put((Integer) k[0], (String) k[1]);
			FUNCTIONS.putIfAbsent((String) k[1], (Integer) k[0]);
		}
		FUNCTIONS.put("history-search-backward", K_HISTORY_BACK);
		FUNCTIONS.put("history-search-forward", K_HISTORY_FORWARD);
		FUNCTIONS.put("self-insert", -1);
		FUNCTIONS.put("do-nothing", KEY_NONE);
	}

	/** bind's: a key sequence (its characters) and what it does: a function, "\"text\"" (a macro), or "" (nothing) */
	private static final Map<String, String> BOUND = new java.util.concurrent.ConcurrentHashMap<>();
	/** bind -x: a key sequence and the shell command it runs */
	private static final Map<String, String> COMMANDS = new java.util.concurrent.ConcurrentHashMap<>();
	/** readline's variables (bind 'set name value'), with their defaults */
	public static final Map<String, String> VARIABLES = new java.util.concurrent.ConcurrentSkipListMap<>();
	static {
		for(String [] v : new String[][] {{"bell-style", "audible"}, {"bind-tty-special-chars", "on"},
				{"blink-matching-paren", "off"}, {"colored-completion-prefix", "off"}, {"colored-stats", "off"},
				{"completion-ignore-case", "off"}, {"completion-map-case", "off"}, {"completion-query-items", "100"},
				{"convert-meta", "off"}, {"disable-completion", "off"}, {"echo-control-characters", "on"},
				{"editing-mode", "emacs"}, {"enable-bracketed-paste", "on"}, {"enable-keypad", "off"},
				{"expand-tilde", "off"}, {"history-preserve-point", "off"}, {"history-size", "0"},
				{"horizontal-scroll-mode", "off"}, {"input-meta", "on"}, {"keymap", "emacs"},
				{"mark-directories", "on"}, {"mark-modified-lines", "off"}, {"mark-symlinked-directories", "off"},
				{"match-hidden-files", "on"}, {"menu-complete-display-prefix", "off"}, {"output-meta", "on"},
				{"page-completions", "on"}, {"print-completions-horizontally", "off"}, {"revert-all-at-newline", "off"},
				{"show-all-if-ambiguous", "off"}, {"show-all-if-unmodified", "off"}, {"show-mode-in-prompt", "off"},
				{"skip-completed-text", "off"}, {"visible-stats", "off"}}) {
			VARIABLES.put(v[0], v[1]);
		}
	}

	/** runs a bind -x command: the line and cursor (READLINE_LINE, READLINE_POINT) it leaves */
	public interface CommandRunner {
		Object [] run(String command, String line, int point);
	}

	private static volatile CommandRunner runner;

	public static void setCommandRunner(CommandRunner r) {
		runner = r;
	}

	/** bind keyseq:function (or "macro", or "" for nothing); keyseq is its characters */
	public static void bind(String keyseq, String what) {
		String seq = normal(keyseq);
		COMMANDS.remove(seq);
		BOUND.put(seq, what);
	}

	/** the keys as they are at the start (bind's bindings gone) */
	public static void resetBindings() {
		BOUND.clear();
		COMMANDS.clear();
	}

	/** bind -x keyseq:command */
	public static void bindCommand(String keyseq, String command) {
		COMMANDS.put(normal(keyseq), command);
	}

	/** bind -r keyseq: the key does nothing */
	public static void unbindKey(String keyseq) {
		String seq = normal(keyseq);
		COMMANDS.remove(seq);
		BOUND.put(seq, "");
	}

	/** bind -u function: the keys it is on do nothing */
	public static void unbindFunction(String function) {
		for(Map.Entry<String, String> e : bindings().entrySet()) {
			if( e.getValue().equals(function)) {
				BOUND.put(e.getKey(), "");
			}
		}
	}

	/** every key sequence that does something and what it does (the defaults, then bind's) */
	public static Map<String, String> bindings() {
		Map<String, String> ret = new java.util.LinkedHashMap<>();
		for(Map.Entry<Integer, String> e : DEFAULTS.entrySet()) {
			String seq = sequenceOf(e.getKey());
			String b = BOUND.getOrDefault(seq, e.getValue());
			if( !b.isEmpty()) {
				ret.put(seq, b);
			}
		}
		for(Map.Entry<String, String> e : new java.util.TreeMap<>(BOUND).entrySet()) {
			if( !ret.containsKey(e.getKey()) && !e.getValue().isEmpty() && !isDefault(e.getKey())) {
				ret.put(e.getKey(), e.getValue());
			}
		}
		return ret;
	}

	private static boolean isDefault(String seq) {
		for(Integer k : DEFAULTS.keySet()) {
			if( sequenceOf(k).equals(seq)) {
				return true;
			}
		}
		return false;
	}

	/** bind -x's: key sequence and command */
	public static Map<String, String> commandBindings() {
		return new java.util.TreeMap<>(COMMANDS);
	}

	/** a key sequence as this editor reads it (ESC O A is an arrow, as ESC [ A is) */
	private static String normal(String seq) {
		if( seq.length() > 2 && seq.charAt(0) == 27 && (seq.charAt(1) == '[' || seq.charAt(1) == 'O')) {
			int k = decode(seq.substring(2, seq.length()-1), seq.charAt(seq.length()-1));
			if( k != KEY_NONE ) {
				return sequenceOf(k);
			}
		}
		return seq;
	}

	/** the characters of a key as this editor reads it: a character, ESC and one (Alt), or an arrow's sequence */
	static String sequenceOf(int key) {
		switch (key) {
		case K_UP: return "\u001b[A";
		case K_DOWN: return "\u001b[B";
		case K_RIGHT: return "\u001b[C";
		case K_LEFT: return "\u001b[D";
		case K_HOME: return "\u001b[H";
		case K_END: return "\u001b[F";
		case K_DELETE: return "\u001b[3~";
		case K_PAGE_UP: return "\u001b[5~";
		case K_PAGE_DOWN: return "\u001b[6~";
		case K_CTRL_LEFT: return "\u001b[1;5D";
		case K_CTRL_RIGHT: return "\u001b[1;5C";
		default:
		}
		if( key >= META ) {
			return "\u001b"+sequenceOf(key-META);
		}
		return String.valueOf((char) key);
	}

	/** a key sequence as bind writes it: "\\C-a", "\\eb", "\\e[A" */
	public static String keyText(String seq) {
		StringBuilder ret = new StringBuilder();
		for(char c : seq.toCharArray()) {
			if( c == 27 ) {
				ret.append("\\e");
			} else if( c == 127 ) {
				ret.append("\\C-?");
			} else if( c < 32 ) {
				ret.append("\\C-").append((char) (c == 31 ? '_' : c == 0 ? '@' : c+96));
			} else if( c == '\\' || c == '"' ) {
				ret.append('\\').append(c);
			} else {
				ret.append(c);
			}
		}
		return ret.toString();
	}

	/** the ways a key sequence comes, as bind -p and -q write them (an arrow both as ESC O A and ESC [ A) */
	public static List<String> keyTexts(String seq) {
		String t = keyText(seq);
		if( seq.length() == 3 && seq.charAt(0) == 27 && seq.charAt(1) == '[' && "ABCDHF".indexOf(seq.charAt(2)) >= 0 ) {
			return List.of("\\eO"+seq.charAt(2), t);
		}
		return List.of(t);
	}

	/** a bound sequence (bind's or -x's) longer than seq starts with it */
	private static boolean prefixOfBound(String seq) {
		for(String k : BOUND.keySet()) {
			if( k.length() > seq.length() && k.startsWith(seq) && !BOUND.get(k).isEmpty()) {
				return true;
			}
		}
		for(String k : COMMANDS.keySet()) {
			if( k.length() > seq.length() && k.startsWith(seq)) {
				return true;
			}
		}
		return false;
	}

	private final Keys keys;
	private final PrintStream out;
	private final List<String> history;
	private Completer completer;
	private int columns = 80;

	private StringBuilder buf = new StringBuilder();
	private int pos;
	private String prompt = "";
	/** the cursor's row below the line's first row, as last drawn */
	private int cursorRow;

	/** killed text, the most recent last */
	private static final List<String> killRing = new ArrayList<>();
	private boolean lastWasKill;
	/** the yank last made (for Alt-Y): where it is in the line */
	private int yankStart = -1, yankEnd = -1, yankIndex = -1;
	/** the line and cursor before each change, for undo */
	private final Deque<Object[]> undo = new ArrayDeque<>();
	private boolean lastWasInsert;
	private boolean lastWasTab;
	/** Alt-.: how far back, and what it put in */
	private int lastArgBack = -1, lastArgStart, lastArgEnd;

	/** the history line shown (history.size() is the new line), and edits of the lines visited */
	private int histIndex;
	private final Map<Integer, String> edited = new HashMap<>();

	public LineEditor(Keys keys, PrintStream out, List<String> history) {
		this.keys = keys;
		this.out = out;
		this.history = history;
	}

	/** Ctrl-O: the history line to edit next, as its distance from the end (once the line is added) and text */
	private static int operateFromEnd = -1;
	private static String operateText;

	/** set -o vi: vi's keys (insert mode at the start of a line, Escape for command mode) */
	private boolean vi;
	/** in vi's command mode */
	private boolean viCommand;

	public void setViMode(boolean on) {
		this.vi = on;
	}

	public void setCompleter(Completer completer) {
		this.completer = completer;
	}

	public void setColumns(int columns) {
		this.columns = Math.max(columns, 10);
	}

	/**
	 * Read a line, after the prompt.
	 * @return the line, or null at the end of input (Ctrl-D on an empty line)
	 * @throws Keyboard.LineCancelled on Ctrl-C
	 */
	public String readLine(String prompt) throws IOException {
		this.prompt = prompt == null ? "" : prompt;
		buf = new StringBuilder();
		pos = 0;
		cursorRow = 0;
		histIndex = history.size();
		edited.clear();
		undo.clear();
		viCommand = false;
		viCount.setLength(0);
		viOperator = 0;
		if( operateFromEnd >= 0 ) {
			// after Ctrl-O: the history line after the one entered (counted from the end: the
			// entered one was added after it, and the oldest may have gone)
			int at = history.size()-1-operateFromEnd;
			if( at >= 0 && at < history.size() && !history.get(at).equals(operateText) && at+1 < history.size()
					&& history.get(at+1).equals(operateText)) {
				at++;
			}
			if( at >= 0 && at < history.size()) {
				histIndex = at;
				buf.append(history.get(at));
				pos = buf.length();
			}
			operateFromEnd = -1;
		}
		out.print(this.prompt);
		out.flush();
		int lastLine = this.prompt.lastIndexOf('\n');
		if( lastLine >= 0 ) {
			// redraws start from the prompt's last line
			this.prompt = this.prompt.substring(lastLine+1);
		}
		if( buf.length() > 0 ) {
			// (the line Ctrl-O left)
			redraw();
		}
		while( true ) {
			int key = readKey();
			if( key == KEY_EOF ) {
				if( buf.length() == 0 ) {
					return null;
				}
				return accept();
			}
			if( key == KEY_NONE ) {
				continue;
			}
			String seq = sequenceOf(key);
			if( !BOUND.containsKey(seq) && !COMMANDS.containsKey(seq) && prefixOfBound(seq)) {
				// the start of a bound sequence (C-x p): the keys after it, until it is one or is not
				StringBuilder more = new StringBuilder(seq);
				while( prefixOfBound(more.toString()) && !BOUND.containsKey(more.toString()) && !COMMANDS.containsKey(more.toString())) {
					int next = readKey();
					if( next == KEY_EOF || next == KEY_NONE ) {
						break;
					}
					more.append(sequenceOf(next));
				}
				seq = more.toString();
				if( !BOUND.containsKey(seq) && !COMMANDS.containsKey(seq)) {
					// (a sequence that is bound to nothing)
					bell();
					continue;
				}
			}
			String command = COMMANDS.get(seq);
			if( command != null ) {
				// bind -x: the shell command, with the line in READLINE_LINE and READLINE_POINT
				runBound(command);
				continue;
			}
			String bound = BOUND.get(seq);
			if( bound != null ) {
				if( bound.isEmpty()) {
					// (bind -r, -u: the key does nothing)
					continue;
				}
				if( bound.startsWith("\"")) {
					// a macro: its text, as if typed
					insert(bound.substring(1, bound.length()-1), false);
					continue;
				}
				Integer canonical = FUNCTIONS.get(bound);
				if( canonical == null || canonical == KEY_NONE ) {
					continue;
				}
				if( canonical >= 0 ) {
					key = canonical;
				} else {
					// self-insert: the key's character goes in (Tab too)
					if( key < META && seq.length() == 1 ) {
						insert(character(key), false);
					}
					continue;
				}
			}
			if( vi ) {
				if( !viCommand && (key == 27+META || key > META && key < META+256)) {
					// Escape: command mode, the cursor one back (Escape and a key at once: that key, there)
					viEnterCommand();
					if( key == 27+META ) {
						continue;
					}
					key -= META;
				}
				if( viCommand ) {
					int done = viKey(key);
					if( done == VI_ACCEPT ) {
						return accept();
					}
					if( done == VI_EOF ) {
						out.println();
						out.flush();
						return null;
					}
					if( done == VI_DONE ) {
						continue;
					}
					// (a control key: as in insert mode)
				}
			}
			boolean wasKill = lastWasKill;
			boolean wasTab = lastWasTab;
			boolean wasInsert = lastWasInsert;
			int wasArg = lastArgBack;
			lastWasKill = false;
			lastWasTab = false;
			lastWasInsert = false;
			lastArgBack = -1;
			boolean keepYank = false;
			switch (key) {
			case '\n':
			case '\r':
				return accept();
			case 1: // Ctrl-A
			case K_HOME:
				moveTo(0);
				break;
			case 5: // Ctrl-E
			case K_END:
				moveTo(buf.length());
				break;
			case 2: // Ctrl-B
			case K_LEFT:
				moveTo(pos-1);
				break;
			case 6: // Ctrl-F
			case K_RIGHT:
				moveTo(pos+1);
				break;
			case META+'b':
			case K_CTRL_LEFT:
				moveTo(wordStart(pos));
				break;
			case META+'f':
			case K_CTRL_RIGHT:
				moveTo(wordEnd(pos));
				break;
			case 4: // Ctrl-D: the end on an empty line, else delete the character at the cursor
				if( buf.length() == 0 ) {
					out.println();
					out.flush();
					return null;
				}
				deleteRange(pos, pos+1, false);
				break;
			case K_DELETE:
				deleteRange(pos, pos+1, false);
				break;
			case 8: // Ctrl-H
			case 127: // DEL
				deleteRange(pos-1, pos, false);
				break;
			case 11: // Ctrl-K
				kill(pos, buf.length(), wasKill, true);
				break;
			case 21: // Ctrl-U
				kill(0, pos, wasKill, false);
				break;
			case 23: // Ctrl-W: the word before, up to white space
				kill(unixWordStart(pos), pos, wasKill, false);
				break;
			case META+'d':
				kill(pos, wordEnd(pos), wasKill, true);
				break;
			case META+127:
			case META+8:
				kill(wordStart(pos), pos, wasKill, false);
				break;
			case 25: // Ctrl-Y
				yank();
				keepYank = true;
				break;
			case META+'y':
				keepYank = yankPop();
				break;
			case 20: // Ctrl-T
				transpose();
				break;
			case META+'t':
				transposeWords();
				break;
			case META+'u':
			case META+'l':
			case META+'c':
				changeCase(key-META);
				break;
			case 31: // Ctrl-_
				undo();
				break;
			case META+'r':
				revertLine();
				break;
			case 12: // Ctrl-L
				out.print("\033[H\033[2J");
				out.print(prompt);
				cursorRow = 0;
				redraw();
				break;
			case 16: // Ctrl-P
			case K_UP:
				historyMove(histIndex-1);
				break;
			case 14: // Ctrl-N
			case K_DOWN:
				historyMove(histIndex+1);
				break;
			case META+'<':
				historyMove(0);
				break;
			case META+'>':
				historyMove(history.size());
				break;
			case META+'.':
			case META+'_':
				lastArgument(wasArg);
				break;
			case 18: // Ctrl-R
				search(true);
				break;
			case 15: // Ctrl-O: operate-and-get-next
				if( histIndex+1 < history.size()) {
					operateFromEnd = history.size()-1-(histIndex+1)+1;
					operateText = history.get(histIndex+1);
				}
				return accept();
			case K_HISTORY_BACK:
				historySearch(-1);
				break;
			case K_HISTORY_FORWARD:
				historySearch(1);
				break;
			case 19: // Ctrl-S
				search(false);
				break;
			case 22: { // Ctrl-V: the next key as it is
				int next = keys.next();
				if( next >= 0 && next < 256 ) {
					insert(String.valueOf((char) next), false);
				}
				break;
			}
			case '\t':
				complete(wasTab);
				lastWasTab = true;
				break;
			case 7: // Ctrl-G
				bell();
				break;
			case Keyboard.CTRL_C:
				// as bash: the line is dropped
				moveTo(buf.length());
				out.print("^C\n");
				out.flush();
				throw new Keyboard.LineCancelled();
			case Keyboard.CTRL_Z:
			case Keyboard.CTRL_BACKSLASH:
			case K_PAGE_UP:
			case K_PAGE_DOWN:
				break;
			default:
				if( key >= 32 && key < META ) {
					insert(character(key), wasInsert);
					lastWasInsert = true;
				} else {
					bell();
				}
			}
			if( !keepYank ) {
				yankStart = yankEnd = -1;
			}
		}
	}

	// ------------------------------------------------------------------ vi's command mode

	private static final int VI_DONE = 0, VI_ACCEPT = 1, VI_EOF = 2, VI_OTHER = 3;
	/** the count typed before a command (3dw) */
	private final StringBuilder viCount = new StringBuilder();
	/** d, c or y waiting for its motion, or 0 */
	private int viOperator;
	/** the count typed before the operator (2d3w is 6 words) */
	private int viOperatorCount = 1;
	/** the last f, F, t or T and its character, for ; and , */
	private int viFindKind, viFindChar;
	/** the last / or ? and its text, for n and N */
	private String viSearchText;
	private boolean viSearchBack;

	private void viEnterCommand() {
		viCommand = true;
		viCount.setLength(0);
		viOperator = 0;
		// (what was typed is not undone by u, as with bash's readline)
		undo.clear();
		if( pos > 0 ) {
			moveTo(pos-1);
		}
	}

	private void viInsert(int at) {
		viCommand = false;
		moveTo(at);
	}

	/** the cursor in command mode is on a character (not after the last) */
	private void viClamp() {
		if( buf.length() > 0 && pos >= buf.length()) {
			moveTo(buf.length()-1);
		}
	}

	private static int viClass(char c, boolean big) {
		if( Character.isWhitespace(c)) {
			return 0;
		}
		if( big || Character.isLetterOrDigit(c) || c == '_' ) {
			return 1;
		}
		return 2;
	}

	/** w, W: the start of the next word (the line's end if there is none) */
	private int viNextWord(int at, boolean big) {
		int n = buf.length();
		if( at >= n ) {
			return n;
		}
		int c = viClass(buf.charAt(at), big);
		int i = at;
		if( c != 0 ) {
			while( i < n && viClass(buf.charAt(i), big) == c ) {
				i++;
			}
		}
		while( i < n && Character.isWhitespace(buf.charAt(i))) {
			i++;
		}
		return i;
	}

	/** b, B: the start of this word or the one before */
	private int viPrevWord(int at, boolean big) {
		int i = at-1;
		while( i > 0 && Character.isWhitespace(buf.charAt(i))) {
			i--;
		}
		if( i <= 0 ) {
			return 0;
		}
		int c = viClass(buf.charAt(i), big);
		while( i > 0 && viClass(buf.charAt(i-1), big) == c ) {
			i--;
		}
		return i;
	}

	/** e, E: the end (the last character) of this word or the next */
	private int viWordEnd(int at, boolean big) {
		int n = buf.length();
		int i = at+1;
		while( i < n && Character.isWhitespace(buf.charAt(i))) {
			i++;
		}
		if( i >= n ) {
			return Math.max(0, n-1);
		}
		int c = viClass(buf.charAt(i), big);
		while( i+1 < n && viClass(buf.charAt(i+1), big) == c ) {
			i++;
		}
		return i;
	}

	/** f F t T c from at: where it lands, or -1 */
	private int viFind(int kind, int ch, int at) {
		if( kind == 'f' || kind == 't' ) {
			int i = buf.indexOf(String.valueOf((char) ch), at+1);
			return i < 0 ? -1 : kind == 't' ? i-1 : i;
		}
		int i = at <= 0 ? -1 : buf.lastIndexOf(String.valueOf((char) ch), at-1);
		return i < 0 ? -1 : kind == 'T' ? i+1 : i;
	}

	/**
	 * A key in command mode.
	 * @return VI_DONE, VI_ACCEPT (Enter), VI_EOF (Ctrl-D on an empty line), or VI_OTHER for a
	 *     key that does what it does in insert mode (Ctrl-L, Ctrl-R, Tab ...)
	 */
	private int viKey(int key) throws IOException {
		switch (key) {
		case K_LEFT: key = 'h'; break;
		case K_RIGHT: key = 'l'; break;
		case K_UP: key = 'k'; break;
		case K_DOWN: key = 'j'; break;
		case K_HOME: key = '0'; break;
		case K_END: key = '$'; break;
		case K_DELETE: key = 'x'; break;
		default:
		}
		if( key == '\n' || key == '\r' ) {
			return VI_ACCEPT;
		}
		if( key == 4 ) {
			return buf.length() == 0 ? VI_EOF : VI_DONE;
		}
		if( key == 27+META || key == 27 ) {
			// (Escape in command mode: what was typed of a command is dropped)
			viCount.setLength(0);
			viOperator = 0;
			return VI_DONE;
		}
		if( key < 32 || key >= META ) {
			return VI_OTHER;
		}
		if( key >= '1' && key <= '9' || key == '0' && viCount.length() > 0 ) {
			viCount.append((char) key);
			return VI_DONE;
		}
		int count = viCount.length() == 0 ? 1 : Integer.parseInt(viCount.toString());
		viCount.setLength(0);
		if( viOperator != 0 ) {
			int op = viOperator;
			viOperator = 0;
			count *= viOperatorCount;
			viOperate(op, key, count);
			return VI_DONE;
		}
		int n = buf.length();
		switch (key) {
		case 'd': case 'c': case 'y':
			viOperator = key;
			viOperatorCount = count;
			return VI_DONE;
		case 'i': viInsert(pos); return VI_DONE;
		case 'a': viInsert(Math.min(n, pos+(n > 0 ? 1 : 0))); return VI_DONE;
		case 'I': viInsert(0); return VI_DONE;
		case 'A': viInsert(n); return VI_DONE;
		case 'x':
			if( n > 0 ) {
				kill(pos, Math.min(n, pos+count), false, true);
				viClamp();
			}
			return VI_DONE;
		case 'X':
			if( pos > 0 ) {
				kill(Math.max(0, pos-count), pos, false, true);
			}
			return VI_DONE;
		case 'D':
			kill(pos, n, false, true);
			viClamp();
			return VI_DONE;
		case 'C':
			kill(pos, n, false, true);
			viInsert(buf.length());
			return VI_DONE;
		case 's':
			kill(pos, Math.min(n, pos+count), false, true);
			viInsert(pos);
			return VI_DONE;
		case 'S':
			kill(0, n, false, true);
			viInsert(0);
			return VI_DONE;
		case 'r': {
			int c = keys.next();
			if( c < 32 || pos+count > n ) {
				bell();
				return VI_DONE;
			}
			saveUndo();
			for (int i = 0; i < count; i++) {
				buf.setCharAt(pos+i, (char) c);
			}
			pos += count-1;
			redraw();
			return VI_DONE;
		}
		case '~': {
			if( n == 0 ) {
				return VI_DONE;
			}
			saveUndo();
			int end = Math.min(n, pos+count);
			for (int i = pos; i < end; i++) {
				char c = buf.charAt(i);
				buf.setCharAt(i, Character.isUpperCase(c) ? Character.toLowerCase(c) : Character.toUpperCase(c));
			}
			pos = Math.min(end, n-1);
			redraw();
			return VI_DONE;
		}
		case 'p': case 'P': {
			if( killRing.isEmpty()) {
				bell();
				return VI_DONE;
			}
			String text = killRing.get(killRing.size()-1).repeat(count);
			int at = key == 'p' && n > 0 ? pos+1 : pos;
			moveTo(at);
			insert(text, false);
			moveTo(pos-1);
			return VI_DONE;
		}
		case 'u':
			undo();
			viClamp();
			return VI_DONE;
		case 'k': case '-':
			historyMove(histIndex-count);
			moveTo(0);
			return VI_DONE;
		case 'j': case '+':
			historyMove(histIndex+count);
			moveTo(0);
			return VI_DONE;
		case '#':
			// insert-comment: the line as a comment, entered
			saveUndo();
			buf.insert(0, '#');
			redraw();
			return VI_ACCEPT;
		case '/': case '?':
			viSearch(key == '/');
			return VI_DONE;
		case 'n': case 'N':
			if( viSearchText == null ) {
				bell();
			} else {
				viSearchAgain(key == 'n' ? viSearchBack : !viSearchBack);
			}
			return VI_DONE;
		default:
		}
		int target = viMotion(key, count);
		if( target == Integer.MIN_VALUE ) {
			bell();
			return VI_DONE;
		}
		moveTo(target);
		viClamp();
		return VI_DONE;
	}

	/** a motion from the cursor: where it goes, or Integer.MIN_VALUE if key is none (or goes nowhere) */
	private int viMotion(int key, int count) throws IOException {
		int n = buf.length();
		int at = pos;
		switch (key) {
		case 'h': return Math.max(0, pos-count);
		case 'l': case ' ': return Math.min(n, pos+count);
		case '0': return 0;
		case '^': {
			int i = 0;
			while( i < n && Character.isWhitespace(buf.charAt(i))) {
				i++;
			}
			return i;
		}
		case '$': return n;
		case '|': return Math.min(n, count-1);
		case 'w': case 'W':
			for (int i = 0; i < count; i++) {
				at = viNextWord(at, key == 'W');
			}
			return at;
		case 'b': case 'B':
			for (int i = 0; i < count; i++) {
				at = viPrevWord(at, key == 'B');
			}
			return at;
		case 'e': case 'E':
			for (int i = 0; i < count; i++) {
				at = viWordEnd(at, key == 'E');
			}
			return at;
		case 'f': case 'F': case 't': case 'T': {
			int c = keys.next();
			viFindKind = key;
			viFindChar = c;
			return viRepeatFind(key, c, count);
		}
		case ';': case ',': {
			if( viFindKind == 0 ) {
				return Integer.MIN_VALUE;
			}
			int kind = viFindKind;
			if( key == ',' ) {
				kind = kind == 'f' ? 'F' : kind == 'F' ? 'f' : kind == 't' ? 'T' : 't';
			}
			return viRepeatFind(kind, viFindChar, count);
		}
		default:
			return Integer.MIN_VALUE;
		}
	}

	private int viRepeatFind(int kind, int c, int count) {
		int at = pos;
		for (int i = 0; i < count; i++) {
			// (t and T again: from past the character they stopped before)
			int from = kind == 't' && i > 0 ? at+1 : kind == 'T' && i > 0 ? at-1 : at;
			int next = viFind(kind, c, from);
			if( next < 0 ) {
				return Integer.MIN_VALUE;
			}
			at = next;
		}
		return at;
	}

	/** d, c or y with a motion (or doubled: the whole line) */
	private void viOperate(int op, int key, int count) throws IOException {
		int n = buf.length();
		int from;
		int to;
		if( key == op ) {
			from = 0;
			to = n;
		} else {
			boolean change = op == 'c';
			int motion = key;
			if( change && (key == 'w' || key == 'W') && pos < n && !Character.isWhitespace(buf.charAt(pos))) {
				// cw is ce, as in vi
				motion = key == 'w' ? 'e' : 'E';
			}
			int target = viMotion(motion, count);
			if( target == Integer.MIN_VALUE ) {
				bell();
				return;
			}
			boolean inclusive = motion == 'e' || motion == 'E' || motion == 'f' || motion == 't'
					|| motion == ';' || motion == ',';
			from = Math.min(pos, target);
			to = Math.max(pos, target)+(inclusive && target >= pos ? 1 : 0);
			to = Math.min(n, to);
		}
		String text = buf.substring(Math.max(0, from), Math.max(from, to));
		if( op == 'y' ) {
			killRing.add(text);
			moveTo(from);
			return;
		}
		kill(from, to, false, true);
		if( op == 'c' ) {
			viInsert(from);
		} else {
			viClamp();
		}
	}

	/** / and ?: the text to look for in the history, typed after the prompt's character */
	private void viSearch(boolean back) throws IOException {
		String saved = buf.toString();
		int savedPos = pos;
		StringBuilder text = new StringBuilder();
		while( true ) {
			buf = new StringBuilder((back ? "/" : "?")+text);
			pos = buf.length();
			redraw();
			int k = keys.next();
			if( k == '\n' || k == '\r' ) {
				break;
			}
			if( k == 27 || k == 7 || k == KEY_EOF ) {
				buf = new StringBuilder(saved);
				pos = savedPos;
				redraw();
				return;
			}
			if( k == 127 || k == 8 ) {
				if( text.length() == 0 ) {
					buf = new StringBuilder(saved);
					pos = savedPos;
					redraw();
					return;
				}
				text.setLength(text.length()-1);
				continue;
			}
			if( k >= 32 ) {
				text.append(character(k));
			}
		}
		buf = new StringBuilder(saved);
		pos = savedPos;
		if( text.length() > 0 ) {
			viSearchText = text.toString();
		}
		viSearchBack = back;
		viSearchAgain(back);
	}

	private void viSearchAgain(boolean back) {
		if( viSearchText == null ) {
			bell();
			redraw();
			return;
		}
		int[] found = find(viSearchText, back, back ? histIndex-1 : histIndex+1, back);
		if( found == null ) {
			bell();
			redraw();
			return;
		}
		historyMove(found[0]);
		moveTo(0);
	}

	private String accept() {
		moveTo(buf.length());
		out.print("\n");
		out.flush();
		return buf.toString();
	}

	// ------------------------------------------------------------------ keys

	/** the next key, with escape sequences (arrows, Alt-x ...) read as one key */
	private int readKey() throws IOException {
		if( pushedBack != KEY_NONE ) {
			// a key a search ended with
			int k = pushedBack;
			pushedBack = KEY_NONE;
			return k;
		}
		int key = keys.next();
		if( key != 27 ) {
			return key;
		}
		int next = keys.nextWithin(50);
		if( next == KEY_NONE ) {
			// Escape alone
			return 27+META;
		}
		if( next == '[' || next == 'O' ) {
			return sequence(next);
		}
		if( next == 27 ) {
			return 27+META;
		}
		return META+next;
	}

	/** ESC [ ... or ESC O ...: the key it is */
	private int sequence(int intro) throws IOException {
		StringBuilder params = new StringBuilder();
		while( true ) {
			int c = keys.nextWithin(50);
			if( c == KEY_NONE || c == KEY_EOF ) {
				return KEY_NONE;
			}
			if( c >= 0x40 && c <= 0x7e ) {
				return decode(params.toString(), (char) c);
			}
			params.append((char) c);
		}
	}

	/** the key of ESC [ p c (or ESC O p c) */
	private static int decode(String p, char c) {
		switch (c) {
		case 'A': return K_UP;
		case 'B': return K_DOWN;
		case 'C': return p.endsWith(";5") ? K_CTRL_RIGHT : p.endsWith(";3") ? META+'f' : K_RIGHT;
		case 'D': return p.endsWith(";5") ? K_CTRL_LEFT : p.endsWith(";3") ? META+'b' : K_LEFT;
		case 'H': return K_HOME;
		case 'F': return K_END;
		case '~':
			switch (p) {
			case "1": case "7": return K_HOME;
			case "4": case "8": return K_END;
			case "3": return K_DELETE;
			case "5": return K_PAGE_UP;
			case "6": return K_PAGE_DOWN;
			default: return KEY_NONE;
			}
		default:
			return KEY_NONE;
		}
	}

	/** a typed character (UTF-8: a first byte and the bytes after it) */
	private String character(int first) throws IOException {
		int more = first >= 0xf0 ? 3 : first >= 0xe0 ? 2 : first >= 0xc0 ? 1 : 0;
		if( first < 0x80 || first > 255 ) {
			return String.valueOf((char) first);
		}
		byte [] bytes = new byte[more+1];
		bytes[0] = (byte) first;
		int n = 1;
		for(; n <= more; n++) {
			int b = keys.nextWithin(50);
			if( b < 0x80 || b > 0xbf ) {
				break;
			}
			bytes[n] = (byte) b;
		}
		return new String(bytes, 0, n, StandardCharsets.UTF_8);
	}

	// ------------------------------------------------------------------ editing

	private void saveUndo() {
		undo.push(new Object[] {buf.toString(), pos});
	}

	private void insert(String s, boolean continuing) {
		if( !continuing ) {
			saveUndo();
		}
		boolean atEnd = pos == buf.length();
		buf.insert(pos, s);
		pos += s.length();
		int end = width(prompt)+width(buf.toString());
		if( atEnd && end % columns != 0 && (end-width(s))/columns == end/columns && s.indexOf('\t') < 0 ) {
			// typed at the end: only what is new (as readline does)
			out.print(s);
			out.flush();
			cursorRow = end/columns;
			return;
		}
		redraw();
	}

	private void deleteRange(int from, int to, boolean save) {
		from = Math.max(0, from);
		to = Math.min(buf.length(), to);
		if( from >= to ) {
			return;
		}
		saveUndo();
		buf.delete(from, to);
		pos = from;
		redraw();
	}

	/** kill from..to; after another kill it adds to that one (after it, or before for back) */
	private void kill(int from, int to, boolean append, boolean forward) {
		from = Math.max(0, from);
		to = Math.min(buf.length(), to);
		if( from >= to ) {
			lastWasKill = append;
			return;
		}
		String text = buf.substring(from, to);
		if( append && !killRing.isEmpty()) {
			String last = killRing.remove(killRing.size()-1);
			killRing.add(forward ? last+text : text+last);
		} else {
			killRing.add(text);
			if( killRing.size() > 60 ) {
				killRing.remove(0);
			}
		}
		deleteRange(from, to, true);
		lastWasKill = true;
	}

	private void yank() {
		if( killRing.isEmpty()) {
			bell();
			return;
		}
		yankIndex = killRing.size()-1;
		yankStart = pos;
		insert(killRing.get(yankIndex), false);
		yankEnd = pos;
	}

	/** Alt-Y after a yank: the kill before the one yanked instead */
	private boolean yankPop() {
		if( yankStart < 0 || killRing.size() < 2 ) {
			bell();
			return false;
		}
		yankIndex = (yankIndex-1+killRing.size()) % killRing.size();
		buf.delete(yankStart, yankEnd);
		String text = killRing.get(yankIndex);
		buf.insert(yankStart, text);
		yankEnd = yankStart+text.length();
		pos = yankEnd;
		redraw();
		return true;
	}

	private void transpose() {
		if( buf.length() < 2 || pos == 0 ) {
			bell();
			return;
		}
		saveUndo();
		int at = pos == buf.length() ? pos-1 : pos;
		char a = buf.charAt(at-1);
		buf.setCharAt(at-1, buf.charAt(at));
		buf.setCharAt(at, a);
		pos = at+1;
		redraw();
	}

	private void transposeWords() {
		int end2 = wordEnd(pos);
		int start2 = wordStart(end2);
		int start1 = wordStart(start2);
		int end1 = wordEnd(start1);
		if( start1 >= start2 || end1 > start2 ) {
			bell();
			return;
		}
		saveUndo();
		String w1 = buf.substring(start1, end1);
		String w2 = buf.substring(start2, end2);
		buf.replace(start2, end2, w1);
		buf.replace(start1, end1, w2);
		pos = end2;
		redraw();
	}

	private void changeCase(int how) {
		int end = wordEnd(pos);
		int start = pos;
		while( start < end && !isWordChar(buf.charAt(start))) {
			start++;
		}
		if( start >= end ) {
			moveTo(end);
			return;
		}
		saveUndo();
		String w = buf.substring(start, end);
		String changed = how == 'u' ? w.toUpperCase()
				: how == 'l' ? w.toLowerCase()
				: w.substring(0, 1).toUpperCase()+w.substring(1).toLowerCase();
		buf.replace(start, end, changed);
		pos = end;
		redraw();
	}

	private void undo() {
		if( undo.isEmpty()) {
			bell();
			return;
		}
		Object[] u = undo.pop();
		buf = new StringBuilder((String) u[0]);
		pos = (Integer) u[1];
		redraw();
	}

	private void revertLine() {
		if( undo.isEmpty()) {
			return;
		}
		Object[] first = undo.getLast();
		undo.clear();
		buf = new StringBuilder((String) first[0]);
		pos = buf.length();
		redraw();
	}

	// ------------------------------------------------------------------ words

	private static boolean isWordChar(char c) {
		return Character.isLetterOrDigit(c);
	}

	/** the start of the word before pos (readline's backward-word) */
	private int wordStart(int at) {
		int i = Math.min(at, buf.length());
		while( i > 0 && !isWordChar(buf.charAt(i-1))) {
			i--;
		}
		while( i > 0 && isWordChar(buf.charAt(i-1))) {
			i--;
		}
		return i;
	}

	/** the end of the word at or after pos (readline's forward-word) */
	private int wordEnd(int at) {
		int i = Math.max(at, 0);
		while( i < buf.length() && !isWordChar(buf.charAt(i))) {
			i++;
		}
		while( i < buf.length() && isWordChar(buf.charAt(i))) {
			i++;
		}
		return i;
	}

	/** Ctrl-W: back to white space */
	private int unixWordStart(int at) {
		int i = at;
		while( i > 0 && Character.isWhitespace(buf.charAt(i-1))) {
			i--;
		}
		while( i > 0 && !Character.isWhitespace(buf.charAt(i-1))) {
			i--;
		}
		return i;
	}

	// ------------------------------------------------------------------ history

	/**
	 * history-search-backward (-1) and -forward: the next line in that direction that starts with
	 * the text before the cursor, the cursor where it was
	 */
	private void historySearch(int direction) {
		String prefix = buf.substring(0, pos);
		int keep = pos;
		for (int i = histIndex+direction; i >= 0 && i <= history.size(); i += direction) {
			String line = i == history.size() ? (edited.containsKey(i) ? edited.get(i) : "") : history.get(i);
			if( i < history.size() && line.startsWith(prefix) && !line.equals(buf.toString())) {
				historyMove(i);
				pos = Math.min(keep, buf.length());
				redraw();
				return;
			}
		}
		bell();
	}

	/** bind -x: run the command; the line and cursor become what it leaves in READLINE_LINE and READLINE_POINT */
	private void runBound(String command) {
		CommandRunner r = runner;
		if( r == null ) {
			bell();
			return;
		}
		moveTo(buf.length());
		out.print("\n");
		out.flush();
		Object [] after = r.run(command, buf.toString(), pos);
		if( after != null ) {
			saveUndo();
			buf = new StringBuilder(String.valueOf(after[0]));
			pos = Math.max(0, Math.min(buf.length(), (Integer) after[1]));
		}
		out.print(prompt);
		cursorRow = 0;
		redraw();
	}

	private void historyMove(int to) {
		if( to < 0 || to > history.size()) {
			bell();
			return;
		}
		edited.put(histIndex, buf.toString());
		histIndex = to;
		String line = edited.containsKey(to) ? edited.get(to) : to == history.size() ? "" : history.get(to);
		buf = new StringBuilder(line);
		pos = buf.length();
		redraw();
	}

	/** Alt-.: the last word of the command before (again: of the one before that) */
	private void lastArgument(int back) {
		int n = back < 0 ? 1 : back+1;
		int index = history.size()-n;
		if( index < 0 ) {
			bell();
			lastArgBack = back;
			return;
		}
		List<String> words = words(history.get(index));
		String word = words.isEmpty() ? "" : words.get(words.size()-1);
		if( back >= 0 ) {
			buf.delete(lastArgStart, lastArgEnd);
			pos = lastArgStart;
		} else {
			saveUndo();
		}
		lastArgStart = pos;
		buf.insert(pos, word);
		pos += word.length();
		lastArgEnd = pos;
		lastArgBack = n;
		redraw();
	}

	/** a command's words, with their quotes (as history expansion splits them) */
	static List<String> words(String line) {
		List<String> ret = new ArrayList<>();
		StringBuilder w = new StringBuilder();
		char quote = 0;
		for (int i = 0; i < line.length(); i++) {
			char c = line.charAt(i);
			if( quote != 0 ) {
				w.append(c);
				if( c == quote ) {
					quote = 0;
				} else if( c == '\\' && quote == '"' && i+1 < line.length()) {
					w.append(line.charAt(++i));
				}
			} else if( c == '\'' || c == '"' ) {
				quote = c;
				w.append(c);
			} else if( c == '\\' && i+1 < line.length()) {
				w.append(c).append(line.charAt(++i));
			} else if( Character.isWhitespace(c)) {
				if( w.length() > 0 ) {
					ret.add(w.toString());
					w.setLength(0);
				}
			} else {
				w.append(c);
			}
		}
		if( w.length() > 0 ) {
			ret.add(w.toString());
		}
		return ret;
	}

	/**
	 * Ctrl-R (back) and Ctrl-S (forward): the history line with what is typed, shown as
	 * (reverse-i-search)`text': line. Enter runs it; Ctrl-G puts the line back; another
	 * editing key takes the line found and does what it does.
	 */
	private void search(boolean back) throws IOException {
		String saved = buf.toString();
		int savedPos = pos;
		int savedIndex = histIndex;
		StringBuilder text = new StringBuilder();
		int found = histIndex;
		int at = -1;
		boolean failed = false;
		String savedPrompt = prompt;
		while( true ) {
			prompt = (failed ? "(failed " : "(")+(back ? "reverse" : "i")+"-search)`"+text+"': ";
			redraw();
			int key = readKey();
			if( key == 18 || key == 19 ) {
				back = key == 18;
				if( text.length() == 0 ) {
					continue;
				}
				int[] next = find(text.toString(), back, back ? found-1 : found+1, back);
				failed = next == null;
				if( next != null ) {
					found = next[0];
					at = next[1];
					show(found, at);
				} else {
					bell();
				}
				continue;
			}
			if( key == 127 || key == 8 ) {
				if( text.length() > 0 ) {
					text.setLength(text.length()-1);
				}
				int[] next = text.length() == 0 ? null : find(text.toString(), back, savedIndex-(back ? 1 : 0), back);
				failed = text.length() > 0 && next == null;
				if( next != null ) {
					found = next[0];
					at = next[1];
					show(found, at);
				}
				continue;
			}
			if( key == 7 || key == Keyboard.CTRL_C ) {
				// back to the line as it was
				prompt = savedPrompt;
				buf = new StringBuilder(saved);
				pos = savedPos;
				histIndex = savedIndex;
				redraw();
				if( key == Keyboard.CTRL_C ) {
					out.print("^C\n");
					out.flush();
					throw new Keyboard.LineCancelled();
				}
				return;
			}
			if( key >= 32 && key < 127 || key >= 128 && key < 256 ) {
				text.append(character(key));
				int[] next = find(text.toString(), back, found, back);
				failed = next == null;
				if( next != null ) {
					found = next[0];
					at = next[1];
					show(found, at);
				} else {
					bell();
				}
				continue;
			}
			// another key: the line found stays, and the key does what it does
			prompt = savedPrompt;
			histIndex = found;
			redraw();
			if( key == '\n' || key == '\r' ) {
				pushBack('\n');
			} else if( key != 27+META ) {
				pushBack(key);
			}
			return;
		}
	}

	private int pushedBack = KEY_NONE;

	private void pushBack(int key) {
		pushedBack = key;
	}

	/** the history line (from index on, going back or forward) with text: {index, where}; null if none */
	private int[] find(String text, boolean back, int from, boolean backward) {
		if( from > history.size()) {
			from = history.size();
		}
		for(int i = from; back ? i >= 0 : i < history.size(); i += back ? -1 : 1) {
			String line = i == history.size() ? "" : history.get(i);
			int at = back ? line.lastIndexOf(text) : line.indexOf(text);
			if( at >= 0 && i < history.size()) {
				return new int[] {i, at};
			}
		}
		return null;
	}

	private void show(int index, int at) {
		buf = new StringBuilder(history.get(index));
		pos = at;
	}

	// ------------------------------------------------------------------ completion

	private void complete(boolean again) {
		if( completer == null ) {
			insert("\t", false);
			return;
		}
		Completions.Result r = completer.complete(buf.toString(), pos);
		if( r == null || r.candidates.isEmpty()) {
			bell();
			return;
		}
		String current = buf.substring(r.start, pos);
		if( r.candidates.size() == 1 ) {
			Completions.Candidate c = r.candidates.get(0);
			replaceWord(r.start, c.text+c.suffix);
			return;
		}
		String common = r.commonPrefix();
		if( common.length() > current.length() ) {
			replaceWord(r.start, common);
			return;
		}
		if( !again ) {
			bell();
			return;
		}
		list(r);
	}

	private void replaceWord(int start, String text) {
		saveUndo();
		buf.replace(start, pos, text);
		pos = start+text.length();
		redraw();
	}

	/** the second Tab: the possibilities, in columns, then the line again */
	private void list(Completions.Result r) {
		moveTo(buf.length());
		int query = 100;
		try {
			query = Integer.parseInt(VARIABLES.getOrDefault("completion-query-items", "100").trim());
		} catch (NumberFormatException e) {
		}
		if( query > 0 && r.candidates.size() >= query ) {
			// as readline asks before it shows that many (completion-query-items)
			out.print("\nDisplay all "+r.candidates.size()+" possibilities? (y or n)");
			out.flush();
			while( true ) {
				int k;
				try {
					k = keys.next();
				} catch (IOException e) {
					k = -1;
				}
				if( k == 'y' || k == 'Y' || k == ' ' ) {
					break;
				}
				if( k == 'n' || k == 'N' || k == 127 || k == 8 || k == 3 || k < 0 ) {
					out.print("\n");
					out.print(prompt);
					cursorRow = 0;
					redraw();
					return;
				}
				bell();
			}
		}
		out.print("\n");
		List<String> shown = new ArrayList<>();
		int width = 0;
		for(Completions.Candidate c : r.candidates) {
			shown.add(c.display);
			width = Math.max(width, c.display.length());
		}
		width += 2;
		int perRow = Math.max(1, (columns+2)/width);
		int rows = (shown.size()+perRow-1)/perRow;
		for (int row = 0; row < rows; row++) {
			StringBuilder line = new StringBuilder();
			for (int col = 0; col < perRow; col++) {
				// down the columns, as bash lists them
				int i = col*rows+row;
				if( i < shown.size()) {
					String s = shown.get(i);
					line.append(s);
					if( (col+1)*rows+row < shown.size()) {
						line.append(" ".repeat(width-s.length()));
					}
				}
			}
			out.print(line.toString().stripTrailing()+"\n");
		}
		out.print(prompt);
		cursorRow = 0;
		redraw();
	}

	// ------------------------------------------------------------------ drawing

	private void bell() {
		String style = VARIABLES.getOrDefault("bell-style", "audible");
		if( style.equals("none") || style.equals("visible")) {
			// (bind 'set bell-style none')
			return;
		}
		out.print((char) 7);
		out.flush();
	}

	private void moveTo(int to) {
		to = Math.max(0, Math.min(buf.length(), to));
		if( to != pos ) {
			pos = to;
			redraw();
		}
	}

	/** the columns text takes on the terminal (escape sequences take none) */
	static int width(String text) {
		int ret = 0;
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if( c == 27 ) {
				// ESC [ ... letter
				i++;
				if( i < text.length() && text.charAt(i) == '[' ) {
					while( i+1 < text.length() && !(text.charAt(i+1) >= 0x40 && text.charAt(i+1) <= 0x7e)) {
						i++;
					}
					i++;
				}
				continue;
			}
			if( Character.isLowSurrogate(c) || c < 32 ) {
				continue;
			}
			ret++;
		}
		return ret;
	}

	/** draw the prompt's last line and the line again, and put the cursor where it is */
	private void redraw() {
		StringBuilder s = new StringBuilder();
		if( cursorRow > 0 ) {
			s.append("\033[").append(cursorRow).append('A');
		}
		s.append('\r');
		s.append(prompt).append(buf);
		s.append("\033[J");
		int start = width(prompt);
		int end = start+width(buf.toString());
		if( end > 0 && end % columns == 0 ) {
			// the cursor stays on the last column until something is written: go to the next row
			s.append(" \r");
		}
		int endRow = end/columns;
		int at = start+width(buf.substring(0, pos));
		int row = at/columns;
		int col = at%columns;
		if( endRow > row ) {
			s.append("\033[").append(endRow-row).append('A');
		}
		s.append('\r');
		if( col > 0 ) {
			s.append("\033[").append(col).append('C');
		}
		cursorRow = row;
		out.print(s);
		out.flush();
	}

	/** keys from a list, for tests (and keys pushed back) */
	public static Keys keys(int ... list) {
		return new Keys() {
			int i;

			@Override
			public int next() {
				return i < list.length ? list[i++] : KEY_EOF;
			}

			@Override
			public int nextWithin(long millis) {
				return i < list.length ? list[i++] : KEY_NONE;
			}
		};
	}

	/** the keys of text (its bytes), for tests */
	public static int[] typed(String text) {
		byte [] b = text.getBytes(StandardCharsets.UTF_8);
		int [] ret = new int[b.length];
		for (int i = 0; i < b.length; i++) {
			ret[i] = b[i] & 0xff;
		}
		return ret;
	}
}
