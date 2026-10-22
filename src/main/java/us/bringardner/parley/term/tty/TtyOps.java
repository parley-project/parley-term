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

/**
 * What a program needs from the operating system to read the keyboard and work the terminal:
 * reading a key at a time, raw mode, signal keys, lending the terminal to another program, and the
 * terminal's size.
 *
 * Two kinds of implementation: {@link us.bringardner.parley.term.tty.jna.JnaTty} (through JNA,
 * for Linux, macOS and the Windows console), and one an application provides (fsh's JNI library),
 * given to {@link Keyboard#install(TtyOps)}.
 *
 * A function a platform cannot do throws UnsatisfiedLinkError; {@link Keyboard} handles that.
 */
public interface TtyOps {

	/** getChar() at the end of input */
	int KEY_EOF = -1;
	/** getChar() when nothing was typed (yet) */
	int KEY_NONE = -2;

	// the special keys getChar() returns for a terminal's escape sequences
	int KEY_UP = 500;
	int KEY_DN = 501;
	int KEY_RT = 502;
	int KEY_LF = 503;
	int KEY_HOME = 504;
	int KEY_PAGE_UP = 505;
	int KEY_PAGE_DOWN = 506;
	int KEY_DELETE = 507;
	int KEY_END = 508;

	/** the next byte (0-255), a special key, KEY_NONE if nothing was typed within a second, or KEY_EOF */
	int getChar();

	/** the number of bytes that can be read without blocking */
	int ready();

	/** on: Ctrl-C, Ctrl-Z and Ctrl-\ are read as keys instead of signalling the process */
	void setSignalKeys(boolean on);

	/** on: a program gets the terminal in its own mode; off: this process has it back (raw) */
	void setProgramMode(boolean on);

	/** standard input and output are a terminal */
	boolean isTerminal();

	/** standard input is a terminal */
	boolean isInputTerminal();

	/** the name of standard input's terminal, or null */
	String ttyName();

	/** standard input: 1 a pipe or socket, 2 a file, 0 something else */
	int inputKind();

	/** the terminal's width in columns (standard output's), or 0 */
	int columns();

	/** the terminal's height in rows (standard output's), or 0 */
	int rows();

	/** descriptor fd is a terminal */
	boolean isatty(int fd);

	/**
	 * The special key of a terminal's escape sequence (ESC [ A ...) that is all of buf[0..cnt),
	 * or 0.
	 */
	static int escapeKey(byte [] buf, int cnt) {
		if( cnt == 3 && buf[0] == 27 && buf[1] == '[' ) {
			switch( buf[2] ) {
			case 'A': return KEY_UP;
			case 'B': return KEY_DN;
			case 'C': return KEY_RT;
			case 'D': return KEY_LF;
			case 'H': return KEY_HOME;
			case 'F': return KEY_END;
			}
		} else if( cnt == 4 && buf[0] == 27 && buf[1] == '[' && buf[3] == '~' ) {
			switch( buf[2] ) {
			case '3': return KEY_DELETE;
			case '5': return KEY_PAGE_UP;
			case '6': return KEY_PAGE_DOWN;
			}
		}
		return 0;
	}
}
