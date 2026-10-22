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
package us.bringardner.parley.term.tty.jna;

import java.util.Collections;

import com.sun.jna.Function;
import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;

import us.bringardner.parley.term.tty.TtyOps;

/**
 * The Win32 console stands in for the terminal, as in fsh's windows/NativeKeyboard.cpp.
 *
 * Raw mode is the console's input without ENABLE_LINE_INPUT and ENABLE_ECHO_INPUT; signal keys
 * are ENABLE_PROCESSED_INPUT. Keys are returned as a terminal sends them: Enter is '\n', Backspace
 * 127, Alt-x ESC x, text as UTF-8. Output gets VT100 escape sequences and UTF-8 while this runs.
 */
final class WindowsTty implements TtyOps {

	/**
	 * The options to load a Win32 library with. 32-bit Windows calls its functions as stdcall, which
	 * JNA calls ALT_CONVENTION; on 64-bit Windows there is one calling convention, and JNA refuses
	 * ALT_CONVENTION there ("Invalid calling convention 63"), so the default is the one to use.
	 */
	static java.util.Map<String, Object> win32Options() {
		if( Native.POINTER_SIZE == 4 ) {
			return Collections.<String, Object>singletonMap(Library.OPTION_CALLING_CONVENTION, Function.ALT_CONVENTION);
		}
		return Collections.<String, Object>emptyMap();
	}

	static final class K {
		static {
			Native.register(NativeLibrary.getInstance("kernel32", win32Options()));
		}
		static native Pointer GetStdHandle(int which);
		static native int GetConsoleMode(Pointer h, Pointer mode);
		static native int SetConsoleMode(Pointer h, int mode);
		static native int GetFileType(Pointer h);
		static native int PeekNamedPipe(Pointer h, Pointer buf, int size, Pointer read, Pointer avail, Pointer left);
		static native int ReadFile(Pointer h, Pointer buf, int size, Pointer read, Pointer overlapped);
		static native int WaitForSingleObject(Pointer h, int millis);
		static native int ReadConsoleInputW(Pointer h, Pointer records, int n, Pointer read);
		static native int PeekConsoleInputW(Pointer h, Pointer records, int n, Pointer read);
		static native int GetConsoleScreenBufferInfo(Pointer h, Pointer info);
		static native Pointer GetCurrentProcess();
		static native long GetTickCount64();
		static native void Sleep(int millis);
		static native int GetFileSizeEx(Pointer h, Pointer size);
		static native int SetFilePointerEx(Pointer h, long distance, Pointer newPosition, int method);
		static native int GetConsoleOutputCP();
		static native int SetConsoleOutputCP(int codePage);
	}

	private static final int STD_INPUT_HANDLE = -10;
	private static final int STD_OUTPUT_HANDLE = -11;
	private static final int STD_ERROR_HANDLE = -12;

	private static final int ENABLE_PROCESSED_INPUT = 0x1;
	private static final int ENABLE_LINE_INPUT = 0x2;
	private static final int ENABLE_ECHO_INPUT = 0x4;
	private static final int ENABLE_VIRTUAL_TERMINAL_INPUT = 0x200;
	private static final int ENABLE_PROCESSED_OUTPUT = 0x1;
	private static final int ENABLE_VIRTUAL_TERMINAL_PROCESSING = 0x4;

	private static final int FILE_TYPE_DISK = 1;
	private static final int FILE_TYPE_PIPE = 3;
	private static final int WAIT_TIMEOUT = 0x102;
	private static final int WAIT_OBJECT_0 = 0;

	private static final int LEFT_ALT_PRESSED = 0x2;
	private static final int RIGHT_ALT_PRESSED = 0x1;
	private static final int LEFT_CTRL_PRESSED = 0x8;
	private static final int RIGHT_CTRL_PRESSED = 0x4;

	private static final int VK_BACK = 0x08;
	private static final int VK_MENU = 0x12;
	private static final int VK_PRIOR = 0x21;
	private static final int VK_NEXT = 0x22;
	private static final int VK_END = 0x23;
	private static final int VK_HOME = 0x24;
	private static final int VK_LEFT = 0x25;
	private static final int VK_UP = 0x26;
	private static final int VK_RIGHT = 0x27;
	private static final int VK_DOWN = 0x28;
	private static final int VK_DELETE = 0x2E;

	// INPUT_RECORD: WORD EventType, then (at 4) the KEY_EVENT_RECORD: BOOL bKeyDown, WORD
	// wRepeatCount, WORD wVirtualKeyCode, WORD wVirtualScanCode, WCHAR UnicodeChar, DWORD dwControlKeyState
	private static final int RECORD_SIZE = 20;
	private static final int KEY_EVENT = 0x1;

	private static final int BUFFER_SIZE = 300;
	private final byte [] buffer = new byte[BUFFER_SIZE];
	private final Memory fileBuffer = new Memory(BUFFER_SIZE);
	private int cnt;
	private int idx;
	/** the high half of a character outside the BMP, waiting for its low half */
	private char highSurrogate;

	private int origInMode;
	private boolean inSaved;
	private boolean rawMode;
	private boolean keysNotSignals;
	private boolean lentRaw;
	private boolean outSaved;
	private boolean errSaved;
	private int origOutMode;
	private int origErrMode;
	/** the console's output code page when the shell started (0: not changed) */
	private int origOutputCp;

	private static final int CP_UTF8 = 65001;

	WindowsTty() {
		// (loads kernel32 and fails here, not later, if it cannot)
		K.GetCurrentProcess();
		enableEscapes();
		utf8Output();
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			synchronized (WindowsTty.this) {
				restoreOutput();
			}
		}, "parley-term-restore-console"));
	}

	private static Pointer in() {
		return K.GetStdHandle(STD_INPUT_HANDLE);
	}

	private static Pointer out() {
		return K.GetStdHandle(STD_OUTPUT_HANDLE);
	}

	private static boolean valid(Pointer h) {
		return h != null && Pointer.nativeValue(h) != 0 && Pointer.nativeValue(h) != -1;
	}

	private static boolean isConsole(Pointer h) {
		if( !valid(h) ) {
			return false;
		}
		Memory mode = new Memory(4);
		return K.GetConsoleMode(h, mode) != 0;
	}

	private static int consoleMode(Pointer h) {
		Memory mode = new Memory(4);
		return K.GetConsoleMode(h, mode) != 0 ? mode.getInt(0) : -1;
	}

	private void disableRawMode() {
		if( rawMode ) {
			K.SetConsoleMode(in(), origInMode);
			rawMode = false;
		}
	}

	private void restoreOutput() {
		disableRawMode();
		if( outSaved ) {
			K.SetConsoleMode(out(), origOutMode);
		}
		if( errSaved ) {
			K.SetConsoleMode(K.GetStdHandle(STD_ERROR_HANDLE), origErrMode);
		}
		if( origOutputCp != 0 ) {
			K.SetConsoleOutputCP(origOutputCp);
			origOutputCp = 0;
		}
	}

	/**
	 * The shell writes UTF-8 bytes. A console decodes what is written with its output code page
	 * (437 or 850 in a new window), so "café" came out as "caf├⌐". The code page is made UTF-8
	 * for as long as the shell runs and put back when it ends. FSH_CONSOLE_UTF8=0 leaves it alone.
	 */
	private void utf8Output() {
		if( origOutputCp != 0 || "0".equals(System.getenv("FSH_CONSOLE_UTF8")) ) {
			return;
		}
		if( !isConsole(out()) && !isConsole(K.GetStdHandle(STD_ERROR_HANDLE)) ) {
			return;
		}
		int cp = K.GetConsoleOutputCP();
		if( cp != 0 && cp != CP_UTF8 && K.SetConsoleOutputCP(CP_UTF8) != 0 ) {
			origOutputCp = cp;
		}
	}

	/** Turn off echo and line buffering; the console's own settings are saved once. */
	private void enableRawMode() {
		Pointer h = in();
		if( rawMode || !isConsole(h) ) {
			return;
		}
		if( !inSaved ) {
			int mode = consoleMode(h);
			if( mode < 0 ) {
				return;
			}
			origInMode = mode;
			inSaved = true;
		}
		// key events, not escape sequences: the virtual key codes say which key it was
		int raw = origInMode & ~(ENABLE_LINE_INPUT | ENABLE_ECHO_INPUT | ENABLE_VIRTUAL_TERMINAL_INPUT);
		if( keysNotSignals ) {
			raw &= ~ENABLE_PROCESSED_INPUT;
		} else {
			raw |= ENABLE_PROCESSED_INPUT;
		}
		if( K.SetConsoleMode(h, raw) != 0 ) {
			rawMode = true;
		}
	}

	/**
	 * The shell writes VT100 escape sequences (cursor movement, clearing the line): the console
	 * acts on them only with ENABLE_VIRTUAL_TERMINAL_PROCESSING (Windows 10 and later).
	 */
	private void enableEscapes() {
		Pointer h = out();
		if( isConsole(h) ) {
			int mode = consoleMode(h);
			if( !outSaved ) {
				origOutMode = mode;
				outSaved = true;
			}
			K.SetConsoleMode(h, mode | ENABLE_PROCESSED_OUTPUT | ENABLE_VIRTUAL_TERMINAL_PROCESSING);
		}
		h = K.GetStdHandle(STD_ERROR_HANDLE);
		if( isConsole(h) ) {
			int mode = consoleMode(h);
			if( !errSaved ) {
				origErrMode = mode;
				errSaved = true;
			}
			K.SetConsoleMode(h, mode | ENABLE_PROCESSED_OUTPUT | ENABLE_VIRTUAL_TERMINAL_PROCESSING);
		}
	}

	@Override
	public synchronized void setSignalKeys(boolean on) {
		keysNotSignals = on;
		Pointer h = in();
		int mode = consoleMode(h);
		if( rawMode && mode >= 0 ) {
			// now, without dropping what was typed
			K.SetConsoleMode(h, on ? mode & ~ENABLE_PROCESSED_INPUT : mode | ENABLE_PROCESSED_INPUT);
		}
	}

	@Override
	public synchronized void setProgramMode(boolean on) {
		if( on ) {
			lentRaw = rawMode;
			if( rawMode ) {
				K.SetConsoleMode(in(), origInMode);
				rawMode = false;
			}
		} else {
			// the program may have changed the output mode too
			enableEscapes();
			if( lentRaw ) {
				lentRaw = false;
				enableRawMode();
			}
		}
	}

	@Override
	public int columns() {
		Memory info = new Memory(32);
		info.clear();
		if( K.GetConsoleScreenBufferInfo(out(), info) != 0 ) {
			// srWindow: Left at 10, Right at 14
			int cols = info.getShort(14) - info.getShort(10) + 1;
			if( cols > 0 ) {
				return cols;
			}
		}
		return 0;
	}

	@Override
	public int rows() {
		Memory info = new Memory(32);
		info.clear();
		if( K.GetConsoleScreenBufferInfo(out(), info) != 0 ) {
			// srWindow: Top at 12, Bottom at 16
			int rows = info.getShort(16) - info.getShort(12) + 1;
			if( rows > 0 ) {
				return rows;
			}
		}
		return 0;
	}

	@Override
	public String ttyName() {
		return isConsole(in()) ? "CON" : null;
	}

	@Override
	public int inputKind() {
		switch( K.GetFileType(in()) ) {
		case FILE_TYPE_PIPE: return 1;
		case FILE_TYPE_DISK: return 2;
		default: return 0;
		}
	}

	@Override
	public boolean isInputTerminal() {
		return isConsole(in());
	}

	@Override
	public boolean isTerminal() {
		return isConsole(in()) && isConsole(out());
	}

	@Override
	public boolean isatty(int fd) {
		switch( fd ) {
		case 0: return isConsole(in());
		case 1: return isConsole(out());
		case 2: return isConsole(K.GetStdHandle(STD_ERROR_HANDLE));
		default: return false;
		}
	}

	private static int specialKey(int vk) {
		switch( vk ) {
		case VK_UP: return KEY_UP;
		case VK_DOWN: return KEY_DN;
		case VK_RIGHT: return KEY_RT;
		case VK_LEFT: return KEY_LF;
		case VK_HOME: return KEY_HOME;
		case VK_END: return KEY_END;
		case VK_PRIOR: return KEY_PAGE_UP;
		case VK_NEXT: return KEY_PAGE_DOWN;
		case VK_DELETE: return KEY_DELETE;
		}
		return 0;
	}

	/** the record at offset of records is a key that getChar returns something for */
	private static boolean isKey(Pointer r, int offset) {
		if( (r.getShort(offset) & 0xffff) != KEY_EVENT ) {
			return false;
		}
		boolean down = r.getInt(offset+4) != 0;
		int vk = r.getShort(offset+10) & 0xffff;
		int ch = r.getShort(offset+14) & 0xffff;
		if( down ) {
			return ch != 0 || specialKey(vk) != 0;
		}
		// a character typed with Alt and the numeric keypad arrives when Alt is released
		return vk == VK_MENU && ch != 0;
	}

	private void putUtf8(int c) {
		if( cnt+4 > BUFFER_SIZE ) {
			return;
		}
		if( c < 0x80 ) {
			buffer[cnt++] = (byte) c;
		} else if( c < 0x800 ) {
			buffer[cnt++] = (byte) (0xC0 | (c >> 6));
			buffer[cnt++] = (byte) (0x80 | (c & 0x3F));
		} else if( c < 0x10000 ) {
			buffer[cnt++] = (byte) (0xE0 | (c >> 12));
			buffer[cnt++] = (byte) (0x80 | ((c >> 6) & 0x3F));
			buffer[cnt++] = (byte) (0x80 | (c & 0x3F));
		} else {
			buffer[cnt++] = (byte) (0xF0 | (c >> 18));
			buffer[cnt++] = (byte) (0x80 | ((c >> 12) & 0x3F));
			buffer[cnt++] = (byte) (0x80 | ((c >> 6) & 0x3F));
			buffer[cnt++] = (byte) (0x80 | (c & 0x3F));
		}
	}

	/**
	 * Put what the key record at the start of r types in the buffer, as a terminal would send it.
	 * @return a special key, or 0
	 */
	private int translate(Pointer r) {
		if( !isKey(r, 0) ) {
			return 0;
		}
		boolean down = r.getInt(4) != 0;
		int repeat = r.getShort(8) & 0xffff;
		int vk = r.getShort(10) & 0xffff;
		char w = (char) (r.getShort(14) & 0xffff);
		int state = r.getInt(16);
		int key = specialKey(vk);
		if( key != 0 && w == 0 ) {
			return key;
		}
		int c;
		if( Character.isHighSurrogate(w) ) {
			highSurrogate = w;
			return 0;
		} else if( Character.isLowSurrogate(w) ) {
			if( highSurrogate == 0 ) {
				return 0;
			}
			c = Character.toCodePoint(highSurrogate, w);
			highSurrogate = 0;
		} else {
			c = w;
		}
		if( c == '\r' ) {
			// as the terminal's ICRNL does
			c = '\n';
		} else if( c == 8 && vk == VK_BACK ) {
			// Backspace, as a terminal sends it
			c = 127;
		}
		// Alt-x is ESC x (AltGr, which is Ctrl and Alt, types a character of its own)
		boolean meta = down && (state & (LEFT_ALT_PRESSED | RIGHT_ALT_PRESSED)) != 0
				&& (state & (LEFT_CTRL_PRESSED | RIGHT_CTRL_PRESSED)) == 0;
		for(int i = 0, n = repeat > 0 ? repeat : 1; i < n; i++) {
			if( meta ) {
				putUtf8(27);
			}
			putUtf8(c);
		}
		return 0;
	}

	@Override
	public synchronized int ready() {
		if( idx < cnt ) {
			return cnt - idx;
		}
		enableRawMode();
		Pointer h = in();
		if( isConsole(h) ) {
			// the keys waiting (each is at least a byte); other events (mouse, focus, key up) are skipped
			Memory recs = new Memory(64L*RECORD_SIZE);
			Memory n = new Memory(4);
			n.setInt(0, 0);
			if( K.PeekConsoleInputW(h, recs, 64, n) == 0 ) {
				return 0;
			}
			int keys = 0;
			for(int i = 0, count = n.getInt(0); i < count; i++) {
				if( isKey(recs, i*RECORD_SIZE) ) {
					keys++;
				}
			}
			return keys;
		}
		switch( K.GetFileType(h) ) {
		case FILE_TYPE_PIPE: {
			Memory avail = new Memory(4);
			avail.setInt(0, 0);
			if( K.PeekNamedPipe(h, null, 0, null, avail, null) == 0 ) {
				return 0;
			}
			return avail.getInt(0);
		}
		case FILE_TYPE_DISK: {
			Memory size = new Memory(8);
			Memory pos = new Memory(8);
			if( K.GetFileSizeEx(h, size) == 0 || K.SetFilePointerEx(h, 0, pos, 1 /* FILE_CURRENT */) == 0 ) {
				return 0;
			}
			long n = size.getLong(0) - pos.getLong(0);
			return n <= 0 ? 0 : (int) Math.min(n, Integer.MAX_VALUE);
		}
		}
		return 0;
	}

	/** the next key from the console, KEY_NONE if nothing was typed within timeout milliseconds, or KEY_EOF */
	private int readConsoleKey(Pointer h, int timeout) {
		long until = K.GetTickCount64() + timeout;
		Memory rec = new Memory(RECORD_SIZE);
		Memory n = new Memory(4);
		while( true ) {
			long now = K.GetTickCount64();
			int left = now >= until ? 0 : (int) (until - now);
			int w = K.WaitForSingleObject(h, left);
			if( w == WAIT_TIMEOUT ) {
				return KEY_NONE;
			}
			if( w != WAIT_OBJECT_0 ) {
				return KEY_EOF;
			}
			n.setInt(0, 0);
			if( K.ReadConsoleInputW(h, rec, 1, n) == 0 ) {
				return KEY_EOF;
			}
			if( n.getInt(0) == 0 ) {
				continue;
			}
			cnt = idx = 0;
			int key = translate(rec);
			if( key != 0 ) {
				return key;
			}
			if( cnt > 0 ) {
				return buffer[idx++] & 0xff;
			}
			if( left == 0 ) {
				return KEY_NONE;
			}
		}
	}

	@Override
	public synchronized int getChar() {
		if( idx < cnt ) {
			return buffer[idx++] & 0xff;
		}

		enableRawMode();

		Pointer h = in();
		if( isConsole(h) ) {
			return readConsoleKey(h, 1000);
		}

		if( K.GetFileType(h) == FILE_TYPE_PIPE ) {
			// wait up to a second for something to read, as poll() does on a terminal
			long until = K.GetTickCount64() + 1000;
			Memory avail = new Memory(4);
			while( true ) {
				avail.setInt(0, 0);
				if( K.PeekNamedPipe(h, null, 0, null, avail, null) == 0 ) {
					// the writer closed the pipe
					cnt = idx = 0;
					return KEY_EOF;
				}
				if( avail.getInt(0) > 0 ) {
					break;
				}
				if( K.GetTickCount64() >= until ) {
					return KEY_NONE;
				}
				K.Sleep(10);
			}
		}

		Memory n = new Memory(4);
		n.setInt(0, 0);
		if( K.ReadFile(h, fileBuffer, BUFFER_SIZE, n, null) == 0 || n.getInt(0) == 0 ) {
			// 0 is end of file
			cnt = idx = 0;
			return KEY_EOF;
		}
		cnt = n.getInt(0);
		fileBuffer.read(0, buffer, 0, cnt);
		idx = 0;

		int key = TtyOps.escapeKey(buffer, cnt);
		if( key != 0 ) {
			cnt = 0;
			return key;
		}

		return buffer[idx++] & 0xff;
	}
}
