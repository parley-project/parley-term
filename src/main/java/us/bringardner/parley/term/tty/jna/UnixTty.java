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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;

import us.bringardner.parley.term.tty.TtyOps;

/**
 * The terminal through libc, for macOS and Linux (glibc and musl, 64-bit).
 *
 * What differs between the two (the layout of termios, the numbers of the ioctl requests and
 * flags) is in the constants below, from their headers; everything else is the same calls as
 * fsh's JNI library (macos/ and linux/ NativeKeyboard.cpp) makes.
 */
final class UnixTty implements TtyOps {

	/** libc's functions with fixed arguments (JNA direct mapping: no proxies, quick to start) */
	static final class C {
		static {
			Native.register("c");
		}
		static native int isatty(int fd);
		static native String ttyname(int fd);
		static native int tcgetattr(int fd, Pointer t);
		static native int tcsetattr(int fd, int action, Pointer t);
		static native int tcgetpgrp(int fd);
		static native int tcsetpgrp(int fd, int group);
		static native int getpgrp();
		static native int poll(Pointer fds, long n, int timeout);
		static native long read(int fd, Pointer buf, long n);
		static native int sigemptyset(Pointer set);
		static native int sigaddset(Pointer set, int signal);
		static native int pthread_sigmask(int how, Pointer set, Pointer old);
	}

	/**
	 * The variadic functions: through an interface, because a call of one must say which arguments
	 * are the variable ones (on Apple's arm64 they are passed on the stack, not in registers).
	 */
	interface V extends Library {
		int ioctl(int fd, NativeLong request, Object... args);
		int fcntl(int fd, int cmd, Object... args);
	}

	private static final int STDIN = 0;
	private static final int STDOUT = 1;
	private static final int EINTR = 4;
	private static final int SIGTTOU = 22;


	private final boolean mac;
	private final V v;

	// termios (see <termios.h>): where the local flags and the control characters are
	private final int lflagOffset;
	private final int ccOffset;
	private final int vmin;
	private final int vtime;
	private final long iCanon;
	private final long iSig;
	private static final long ECHO = 0x8;
	private static final int TCSANOW = 0;
	private static final int TCSADRAIN = 1;
	private static final int TCSAFLUSH = 2;

	private final long tiocgwinsz;
	private final long fionread;
	private final int sigBlock;
	private final int sigSetmask;

	/** the terminal's own settings, saved once (a program that leaves another mode does not change them) */
	private Memory orig;
	private boolean rawMode;
	private boolean keysNotSignals;
	private boolean lentRaw;
	private boolean hookAdded;

	private static final int BUFFER_SIZE = 300;
	private final byte [] buffer = new byte[BUFFER_SIZE];
	private final Memory readBuffer = new Memory(BUFFER_SIZE);
	private int cnt;
	private int idx;

	UnixTty(boolean mac) {
		this.mac = mac;
		// (loading the library and the first call: if it cannot, JnaTty.create() has none)
		C.isatty(STDIN);
		v = Native.load("c", V.class);
		if( mac ) {
			lflagOffset = 24;
			ccOffset = 32;
			vmin = 16;
			vtime = 17;
			iCanon = 0x100;
			iSig = 0x80;
			tiocgwinsz = 0x40087468L;
			fionread = 0x4004667fL;
			sigBlock = 1;
			sigSetmask = 3;
		} else {
			lflagOffset = 12;
			ccOffset = 17;
			vmin = 6;
			vtime = 5;
			iCanon = 0x2;
			iSig = 0x1;
			tiocgwinsz = 0x5413L;
			fionread = 0x541BL;
			sigBlock = 0;
			sigSetmask = 2;
		}
	}

	private long lflag(Pointer t) {
		return mac ? t.getLong(lflagOffset) : t.getInt(lflagOffset) & 0xffffffffL;
	}

	private void setLflag(Pointer t, long value) {
		if( mac ) {
			t.setLong(lflagOffset, value);
		} else {
			t.setInt(lflagOffset, (int) value);
		}
	}

	private void disableRawMode() {
		if( rawMode ) {
			C.tcsetattr(STDIN, TCSAFLUSH, orig);
			rawMode = false;
		}
	}

	/** Turn off echo and line buffering. */
	private void enableRawMode() {
		if( rawMode || C.isatty(STDIN) == 0 ) {
			return;
		}
		if( orig == null ) {
			Memory m = new Memory(128);
			m.clear();
			if( C.tcgetattr(STDIN, m) == -1 ) {
				return;
			}
			orig = m;
		}
		if( !hookAdded ) {
			hookAdded = true;
			// (what atexit does in the library: the terminal is as it was when the shell ends)
			Runtime.getRuntime().addShutdownHook(new Thread(() -> {
				synchronized (UnixTty.this) {
					disableRawMode();
				}
			}, "parley-term-restore-terminal"));
		}
		Memory raw = new Memory(128);
		raw.write(0, orig.getByteArray(0, 128), 0, 128);
		long flags = lflag(raw) & ~(ECHO | iCanon);
		if( keysNotSignals ) {
			flags &= ~iSig;
		}
		setLflag(raw, flags);
		raw.setByte(ccOffset + vmin, (byte) 1);
		raw.setByte(ccOffset + vtime, (byte) 0);
		if( C.tcsetattr(STDIN, TCSADRAIN, raw) == 0 ) {
			rawMode = true;
		}
	}

	@Override
	public synchronized void setSignalKeys(boolean on) {
		keysNotSignals = on;
		Memory t = new Memory(128);
		t.clear();
		if( rawMode && C.tcgetattr(STDIN, t) == 0 ) {
			// now, without dropping what was typed
			long flags = lflag(t);
			setLflag(t, on ? flags & ~iSig : flags | iSig);
			C.tcsetattr(STDIN, TCSANOW, t);
		}
	}

	@Override
	public synchronized void setProgramMode(boolean on) {
		if( on ) {
			lentRaw = rawMode;
			if( rawMode ) {
				C.tcsetattr(STDIN, TCSADRAIN, orig);
				rawMode = false;
			}
		} else {
			// the shell's process group has the terminal again (if a job's group had it)
			takeTerminal(C.getpgrp());
			if( lentRaw ) {
				lentRaw = false;
				enableRawMode();
			}
		}
	}

	/** make group the terminal's foreground group (the shell may be in the background for this) */
	private void takeTerminal(int group) {
		takeTerminal(STDIN, group);
	}

	private void takeTerminal(int fd, int group) {
		if( C.isatty(fd) == 0 || C.tcgetpgrp(fd) == group ) {
			return;
		}
		// (SIGTTOU blocked: a background shell would be stopped by tcsetpgrp)
		Memory block = new Memory(256);
		Memory old = new Memory(256);
		block.clear();
		old.clear();
		C.sigemptyset(block);
		C.sigaddset(block, SIGTTOU);
		C.pthread_sigmask(sigBlock, block, old);
		try {
			C.tcsetpgrp(fd, group);
		} finally {
			C.pthread_sigmask(sigSetmask, old, null);
		}
	}

	@Override
	public int columns() {
		Memory ws = new Memory(8);
		ws.clear();
		if( v.ioctl(STDOUT, new NativeLong(tiocgwinsz), ws) == 0 ) {
			int cols = ws.getShort(2) & 0xffff;
			if( cols > 0 ) {
				return cols;
			}
		}
		return 0;
	}

	@Override
	public int rows() {
		Memory ws = new Memory(8);
		ws.clear();
		if( v.ioctl(STDOUT, new NativeLong(tiocgwinsz), ws) == 0 ) {
			// struct winsize { unsigned short ws_row, ws_col, ... }
			int rows = ws.getShort(0) & 0xffff;
			if( rows > 0 ) {
				return rows;
			}
		}
		return 0;
	}

	@Override
	public String ttyName() {
		return C.ttyname(STDIN);
	}

	@Override
	public int inputKind() {
		// st_mode of what descriptor 0 is: the unix view's mode is the same on both systems, where
		// struct stat is not
		try {
			Object mode = Files.getAttribute(Path.of("/dev/stdin"), "unix:mode");
			int type = ((Integer) mode) & 0170000;
			if( type == 0010000 || type == 0140000 ) {
				// a FIFO or a socket
				return 1;
			}
			return type == 0100000 ? 2 : 0;
		} catch (IOException | RuntimeException e) {
			throw new UnsatisfiedLinkError("inputKind: "+e);
		}
	}

	@Override
	public boolean isInputTerminal() {
		return C.isatty(STDIN) != 0;
	}

	@Override
	public boolean isTerminal() {
		return C.isatty(STDIN) != 0 && C.isatty(STDOUT) != 0;
	}

	@Override
	public boolean isatty(int fd) {
		return C.isatty(fd) != 0;
	}

	@Override
	public synchronized int ready() {
		if( idx < cnt ) {
			return cnt - idx;
		}
		enableRawMode();
		Memory n = new Memory(4);
		n.setInt(0, 0);
		if( v.ioctl(STDIN, new NativeLong(fionread), n) == -1 ) {
			return 0;
		}
		return n.getInt(0);
	}

	@Override
	public synchronized int getChar() {
		if( idx < cnt ) {
			return buffer[idx++] & 0xff;
		}

		enableRawMode();

		// struct pollfd { int fd; short events; short revents; }
		Memory p = new Memory(8);
		p.setInt(0, STDIN);
		p.setShort(4, (short) 1);
		p.setShort(6, (short) 0);
		int r = C.poll(p, 1, 1000);
		if( r == 0 ) {
			return KEY_NONE;
		}
		if( r < 0 ) {
			int err = Native.getLastError();
			// (a signal ended the wait: nothing was typed; anything else, the input is gone)
			return err == EINTR || err == 0 ? KEY_NONE : KEY_EOF;
		}

		long n;
		int tries = 0;
		do {
			n = C.read(STDIN, readBuffer, BUFFER_SIZE);
		} while( n < 0 && (Native.getLastError() == EINTR || Native.getLastError() == 0) && ++tries < 100 );

		if( n <= 0 ) {
			// 0 is end of file
			cnt = idx = 0;
			return KEY_EOF;
		}
		readBuffer.read(0, buffer, 0, (int) n);
		cnt = (int) n;
		idx = 0;

		int key = TtyOps.escapeKey(buffer, cnt);
		if( key != 0 ) {
			cnt = 0;
			return key;
		}

		return buffer[idx++] & 0xff;
	}
}
