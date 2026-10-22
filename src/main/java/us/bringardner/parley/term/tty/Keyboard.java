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
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.ArrayDeque;

import us.bringardner.parley.term.line.LineEditor;
import us.bringardner.parley.term.tty.jna.JnaTty;

/**
 * The process's keyboard and terminal: keys typed, one at a time, without echo; Ctrl-C and Ctrl-Z
 * as keys or signals; lending the terminal to another program; the terminal's size.
 *
 * As an InputStream it gives a program the bytes a terminal sends, special keys as their escape
 * sequences. There is one terminal per process, so the state is static and every instance reads
 * the same keys.
 *
 * The work is done by a {@link TtyOps}: one an application installs ({@link #install}), else
 * {@link JnaTty}'s, made the first time it is needed.
 */
public class Keyboard extends InputStream {

	/** getChar() result at the end of input */
	public static final int KEY_EOF = TtyOps.KEY_EOF;
	/** getChar() result when nothing was typed (yet) */
	public static final int KEY_NONE = TtyOps.KEY_NONE;
	/** what a key that stops or interrupts a job is read as when the application has taken it */
	public static final int KEY_INTR = -3;

	public static final int CTRL_C = 3;
	public static final int CTRL_D = 4;
	public static final int CTRL_Z = 26;
	public static final int CTRL_BACKSLASH = 28;

	public static final int UP = TtyOps.KEY_UP;
	public static final int DN = TtyOps.KEY_DN;
	public static final int RT = TtyOps.KEY_RT;
	public static final int LF = TtyOps.KEY_LF;
	public static final int HOME = TtyOps.KEY_HOME;
	public static final int PAGE_UP = TtyOps.KEY_PAGE_UP;
	public static final int PAGE_DOWN = TtyOps.KEY_PAGE_DOWN;
	public static final int DELETE = TtyOps.KEY_DELETE;
	public static final int END = TtyOps.KEY_END;

	// the terminal: installed, or JNA's

	private static final Object opsLock = new Object();
	private static boolean resolved;
	private static TtyOps ops;

	/**
	 * Use ops for the terminal (null: there is none), instead of JNA's. Call it before anything
	 * reads the keyboard.
	 */
	public static void install(TtyOps ops) {
		synchronized (opsLock) {
			Keyboard.ops = ops;
			resolved = true;
		}
	}

	/** the terminal's functions, or null if there are none here */
	public static TtyOps ops() {
		synchronized (opsLock) {
			if( !resolved ) {
				ops = JnaTty.create();
				resolved = true;
			}
			return ops;
		}
	}

	/** there is a way to work the terminal here (raw mode, keys one at a time) */
	public static boolean isAvailable() {
		return ops() != null;
	}

	/** standard input and output are a terminal (and this class can work it) */
	public static boolean terminal() {
		TtyOps o = ops();
		if( o != null ) {
			try {
				return o.isTerminal();
			} catch (UnsatisfiedLinkError e) {
				// an older library
			}
		}
		return false;
	}

	/** the process's standard input is a terminal (without a TtyOps: there is a console) */
	public static boolean inputIsTerminal() {
		TtyOps o = ops();
		if( o != null ) {
			try {
				return o.isInputTerminal();
			} catch (UnsatisfiedLinkError e) {
				// an older library
			}
		}
		return System.console() != null;
	}

	/** standard input: 1 a pipe or socket, 2 a file, 0 something else, -1 not known */
	public static int inputKind() {
		TtyOps o = ops();
		if( o != null ) {
			try {
				return o.inputKind();
			} catch (UnsatisfiedLinkError e) {
				// an older library
			}
		}
		return -1;
	}

	/** the terminal's name without /dev/ (ttys003); "tty" if there is none */
	public static String ttyName() {
		TtyOps o = ops();
		if( o != null ) {
			try {
				String name = o.ttyName();
				if( name != null ) {
					return name.substring(name.lastIndexOf('/')+1);
				}
			} catch (UnsatisfiedLinkError e) {
				// an older library
			}
		}
		return "tty";
	}

	/** descriptor fd of this process is a terminal (false where that cannot be told) */
	public static boolean isatty(int fd) {
		TtyOps o = ops();
		if( o == null ) {
			return false;
		}
		try {
			return o.isatty(fd);
		} catch (UnsatisfiedLinkError e) {
			// (a library from before it had it: as before, input and output together)
			return terminal();
		}
	}

	/** the terminal's width (else $COLUMNS, else 80) */
	public static int columns() {
		TtyOps o = ops();
		if( o != null ) {
			try {
				int c = o.columns();
				if( c > 0 ) {
					return c;
				}
			} catch (UnsatisfiedLinkError e) {
				// an older library
			}
		}
		return env("COLUMNS", 80);
	}

	/** the terminal's height (else $LINES, else 24) */
	public static int rows() {
		TtyOps o = ops();
		if( o != null ) {
			try {
				int r = o.rows();
				if( r > 0 ) {
					return r;
				}
			} catch (UnsatisfiedLinkError e) {
				// an older library
			}
		}
		return env("LINES", 24);
	}

	private static int env(String name, int otherwise) {
		try {
			return Integer.parseInt(System.getenv().getOrDefault(name, String.valueOf(otherwise)));
		} catch (NumberFormatException e) {
			return otherwise;
		}
	}

	// lending the terminal to another program

	/** the programs that have the terminal now (see lendTerminal) */
	private static final java.util.Set<Object> terminalUsers = new java.util.HashSet<>();

	/**
	 * A program reads the terminal itself: it gets it in the terminal's own mode (where it
	 * echoes, edits lines, and Ctrl-C and Ctrl-Z signal the program), until each user has given
	 * it back.
	 */
	public static void lendTerminal(Object user) {
		synchronized (terminalUsers) {
			if( terminalUsers.add(user) && terminalUsers.size() == 1 ) {
				programMode(true);
			}
		}
	}

	/** user is done with the terminal (it ended, or was stopped) */
	public static void reclaimTerminal(Object user) {
		synchronized (terminalUsers) {
			if( terminalUsers.remove(user) && terminalUsers.isEmpty()) {
				programMode(false);
			}
		}
	}

	/** a program has the terminal now */
	public static boolean isTerminalLent() {
		synchronized (terminalUsers) {
			return !terminalUsers.isEmpty();
		}
	}

	private static void programMode(boolean on) {
		TtyOps o = ops();
		if( o != null ) {
			try {
				o.setProgramMode(on);
			} catch (UnsatisfiedLinkError e) {
				// an older library
			}
		}
	}

	/** Ctrl-C, Ctrl-Z and Ctrl-\ as keys (on) or as signals (off) */
	public static void signalKeys(boolean on) {
		TtyOps o = ops();
		if( o != null ) {
			try {
				o.setSignalKeys(on);
			} catch (UnsatisfiedLinkError e) {
				// an older library: they stay signals
			}
		}
	}

	// keys

	/** told of Ctrl-C, Ctrl-Z and Ctrl-\; true if it took the key */
	public interface ControlKeys {
		boolean typed(int key);
	}

	/** set by an application (fsh's interactive shell) while a job runs in the foreground */
	public static volatile ControlKeys controlKeys;

	/** keys read while a job ran (to find Ctrl-C and Ctrl-Z) that a reader has not had yet */
	private static final ArrayDeque<Integer> typedAhead = new ArrayDeque<>();
	private static final Object keyLock = new Object();

	/** Ctrl-C at a prompt: the line typed so far is dropped */
	public static class LineCancelled extends InterruptedIOException {
		private static final long serialVersionUID = 1L;
	}

	private static int getChar() {
		TtyOps o = ops();
		return o == null ? KEY_EOF : o.getChar();
	}

	private static int ready() {
		TtyOps o = ops();
		return o == null ? 0 : o.ready();
	}

	/** the next key: what was typed ahead first; one the application took is KEY_INTR (Ctrl-C) or KEY_NONE */
	protected int key() {
		synchronized (keyLock) {
			Integer k = typedAhead.poll();
			if( k != null ) {
				return k;
			}
			int key = getChar();
			if( taken(key)) {
				return key == CTRL_C ? KEY_INTR : KEY_NONE;
			}
			return key;
		}
	}

	/** the next key, waiting until one is typed (or KEY_EOF) */
	protected int nextKey() {
		int key = key();
		while( key == KEY_NONE ) {
			key = key();
		}
		return key;
	}

	private static boolean taken(int key) {
		ControlKeys ck = controlKeys;
		return (key == CTRL_C || key == CTRL_Z || key == CTRL_BACKSLASH) && ck != null && ck.typed(key);
	}

	/**
	 * Read what has been typed, without waiting, so Ctrl-C and Ctrl-Z reach the application while
	 * a job that does not read the keyboard runs; the other keys are kept for whoever reads next.
	 */
	public static void pollTyped() {
		if( !isAvailable() || isTerminalLent()) {
			// a program reads the terminal itself
			return;
		}
		synchronized (keyLock) {
			while( ready() > 0 ) {
				int key = getChar();
				if( key == KEY_EOF || key == KEY_NONE ) {
					break;
				}
				if( !taken(key)) {
					typedAhead.add(key);
				}
			}
		}
	}

	/** the keys typed, for the line editor */
	public LineEditor.Keys editorKeys() {
		return new LineEditor.Keys() {
			@Override
			public int next() {
				return nextKey();
			}

			@Override
			public int nextWithin(long millis) {
				long until = System.currentTimeMillis()+millis;
				while( true ) {
					synchronized (keyLock) {
						Integer k = typedAhead.poll();
						if( k != null ) {
							return k;
						}
						if( ready() > 0 ) {
							return getChar();
						}
					}
					if( System.currentTimeMillis() >= until ) {
						return KEY_NONE;
					}
					try {
						Thread.sleep(2);
					} catch (InterruptedException e) {
						return KEY_NONE;
					}
				}
			}
		};
	}

	// as an InputStream

	/** bytes of a special key's escape sequence still to be returned by read() */
	private final ArrayDeque<Integer> pending = new ArrayDeque<>();

	@Override
	public int read() throws IOException {
		if( !pending.isEmpty()) {
			return pending.poll();
		}
		if( !isAvailable() ) {
			return System.in.read();
		}
		while(true) {
			int key = key();
			if( key == KEY_INTR ) {
				return -1;
			} else if( key == KEY_NONE ) {
				if( Thread.currentThread().isInterrupted()) {
					throw new InterruptedIOException();
				}
			} else if( key == KEY_EOF ) {
				return -1;
			} else if( key > 255 ) {
				// give the program the escape sequence the terminal sent
				return expand(key);
			} else {
				return key;
			}
		}
	}

	/**
	 * A byte that was typed, without waiting: KEY_NONE if there is none (or the application took
	 * it, Ctrl-Z), KEY_EOF at the end of input. For a program's input, so a job that is stopped
	 * meanwhile does not wait in here for the next key.
	 */
	public int readTyped() {
		if( !pending.isEmpty()) {
			return pending.poll();
		}
		if( !isAvailable() ) {
			return KEY_NONE;
		}
		int key;
		synchronized (keyLock) {
			Integer k = typedAhead.poll();
			if( k != null ) {
				key = k;
			} else if( ready() <= 0 ) {
				return KEY_NONE;
			} else {
				key = getChar();
				if( taken(key)) {
					return KEY_NONE;
				}
			}
		}
		if( key > 255 ) {
			return expand(key);
		}
		return key;
	}

	private int expand(int key) {
		byte [] seq = escapeSequence(key);
		for(int idx=1; idx < seq.length; idx++ ) {
			pending.add(seq[idx] & 0xff);
		}
		return seq[0];
	}

	@Override
	public int available() throws IOException {
		if( !isAvailable() ) {
			return pending.size()+System.in.available();
		}
		synchronized (keyLock) {
			return pending.size()+typedAhead.size()+ready();
		}
	}

	private static byte [] escapeSequence(int key) {
		String seq;
		switch (key) {
		case UP: seq = "\033[A"; break;
		case DN: seq = "\033[B"; break;
		case RT: seq = "\033[C"; break;
		case LF: seq = "\033[D"; break;
		case HOME: seq = "\033[H"; break;
		case END: seq = "\033[F"; break;
		case DELETE: seq = "\033[3~"; break;
		case PAGE_UP: seq = "\033[5~"; break;
		case PAGE_DOWN: seq = "\033[6~"; break;
		default: seq = ""+((char)(key & 0xff));
		}
		return seq.getBytes();
	}

	@Override
	public void close() throws IOException {
		// the process's terminal stays open
	}

	// secrets

	private String prompt = "prompt";
	private char lineTerminator = '\n';

	public String getPrompt() {
		return prompt;
	}

	public void setPrompt(String prompt) {
		this.prompt = prompt;
	}

	public char getLineTerminator() {
		return lineTerminator;
	}

	public void setLineTerminator(char lineTerminator) {
		this.lineTerminator = lineTerminator;
	}

	/**
	 * Reads a secret: each character typed is shown as mask (a password prompt's *), Backspace
	 * removes the last one and Ctrl-U all of them; arrow and history keys are ignored. Ends at
	 * the line terminator. Typed bytes are read as UTF-8, so a character of several bytes shows
	 * one mask and is removed by one Backspace.
	 *
	 * @return the secret (the caller zeroes it), or null at end of input or Ctrl-C in a job
	 * @throws LineCancelled at Ctrl-C
	 */
	public char[] readMasked(char mask) throws LineCancelled {
		return readSecret(mask, false);
	}

	/**
	 * Reads a secret showing one spinner character (| / - \) that turns with every key,
	 * Backspace included: it shows the typing lands without showing how long the secret is.
	 */
	public char[] readSpinning() throws LineCancelled {
		return readSecret('|', true);
	}

	/** Reads a secret showing nothing, as sudo, ssh and passwd do. */
	public char[] readSilent() throws LineCancelled {
		return readSecret('\0', false);
	}

	private static final char[] SPINNER = {'|', '/', '-', '\\'};

	private char[] readSecret(char mask, boolean spin) throws LineCancelled {
		int frame = -1;
		System.out.print(prompt == null ? "" : prompt);
		System.out.flush();
		java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
		// where each character starts in bytes, so Backspace removes a whole one
		java.util.ArrayDeque<Integer> starts = new java.util.ArrayDeque<>();
		try {
			while( true ) {
				int key = nextKey();
				if( key == KEY_EOF || (key == CTRL_D && starts.isEmpty())) {
					System.out.print('\n');
					return starts.isEmpty() ? null : decodeSecret(bytes);
				}
				if( key == KEY_INTR ) {
					return null;
				}
				if( key == CTRL_C ) {
					System.out.print("^C\n");
					System.out.flush();
					throw new LineCancelled();
				}
				if( key == lineTerminator || key == '\r' || key == '\n' ) {
					if( spin && frame >= 0 ) {
						System.out.print("\b \b");
					}
					System.out.print('\n');
					System.out.flush();
					return decodeSecret(bytes);
				}
				if( key == 127 || key == 8 ) {
					if( !starts.isEmpty()) {
						truncate(bytes, starts.pop());
						if( !spin && mask != '\0' ) {
							System.out.print("\b \b");
						}
					}
					frame = turn(spin, frame);
				} else if( key == 21 ) {
					// Ctrl-U: erase everything typed
					if( !spin && mask != '\0' ) {
						for(int i = starts.size(); i > 0; i-- ) {
							System.out.print("\b \b");
						}
					}
					starts.clear();
					truncate(bytes, 0);
					frame = turn(spin, frame);
				} else if( key >= UP && key <= END || key < 32 ) {
					// arrows, Home, End, Delete, and other control keys: nothing to do in a secret
				} else if( key < 0x80 || (key & 0xC0) == 0xC0 && key < 0x100 ) {
					// ASCII, or the first byte of a UTF-8 character: one mask
					starts.push(bytes.size());
					bytes.write(key);
					frame = spin ? turn(true, frame) : show(mask);
				} else if( key < 0x100 ) {
					// a UTF-8 continuation byte
					bytes.write(key);
				} else {
					// already a character
					starts.push(bytes.size());
					byte[] b = new String(Character.toChars(key)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
					bytes.write(b, 0, b.length);
					frame = spin ? turn(true, frame) : show(mask);
				}
				System.out.flush();
			}
		} finally {
			truncate(bytes, 0);
		}
	}

	/** Turns the spinner one step (drawing it the first time); returns the new frame. */
	private static int turn(boolean spin, int frame) {
		if( !spin ) {
			return frame;
		}
		if( frame >= 0 ) {
			System.out.print('\b');
		}
		int next = (frame+1) % SPINNER.length;
		System.out.print(SPINNER[next]);
		return next;
	}

	private static int show(char mask) {
		if( mask != '\0' ) {
			System.out.print(mask);
		}
		return -1;
	}

	private static char[] decodeSecret(java.io.ByteArrayOutputStream bytes) {
		byte[] b = bytes.toByteArray();
		java.nio.CharBuffer cb = java.nio.charset.StandardCharsets.UTF_8.decode(java.nio.ByteBuffer.wrap(b));
		char[] ret = java.util.Arrays.copyOfRange(cb.array(), cb.position(), cb.limit());
		java.util.Arrays.fill(cb.array(), '\0');
		java.util.Arrays.fill(b, (byte) 0);
		return ret;
	}

	/** Cuts bytes back to size, zeroing what was cut. */
	private static void truncate(java.io.ByteArrayOutputStream bytes, int size) {
		byte[] keep = bytes.toByteArray();
		bytes.reset();
		bytes.write(keep, 0, Math.min(size, keep.length));
		java.util.Arrays.fill(keep, (byte) 0);
	}
}
