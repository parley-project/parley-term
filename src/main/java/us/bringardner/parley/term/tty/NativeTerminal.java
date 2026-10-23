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

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * The process's own terminal, through {@link Keyboard} (raw mode and keys on Unix, macOS and the
 * Windows console; the size; lending the terminal to another program). Keyboard gives the bytes
 * a terminal sends, special keys as their escape sequences, which {@link KeyDecoder} decodes.
 */
public class NativeTerminal implements Terminal {

	private static final int POLL_MS = 5;

	private final Keyboard keys = new Keyboard();
	private final OutputStream out = new FileOutputStream(FileDescriptor.out);
	private final boolean ownSignals;
	/** the shell's handler of Ctrl-C and Ctrl-Z (fsh's), set aside while the screen is up */
	private Keyboard.ControlKeys shellKeys;

	/**
	 * @param ownSignals read Ctrl-C and Ctrl-Z as keys (a program on its own; inside a shell that
	 *        reads them itself, such as fsh, the shell has already set this)
	 */
	public NativeTerminal(boolean ownSignals) {
		this.ownSignals = ownSignals;
	}

	/** There is a terminal here and a way to work it. */
	public static boolean isAvailable() {
		return Keyboard.isAvailable() && Keyboard.terminal();
	}

	@Override
	public int read(int timeoutMs) throws IOException {
		long end = timeoutMs < 0 ? Long.MAX_VALUE : System.currentTimeMillis()+timeoutMs;
		while(true) {
			// (available() also puts the terminal in raw mode, the first time)
			if( keys.available() > 0 ) {
				return keys.read();
			}
			if( System.currentTimeMillis() >= end ) {
				return -2;
			}
			try {
				Thread.sleep(POLL_MS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new InterruptedIOException();
			}
		}
	}

	@Override
	public void write(String text) throws IOException {
		out.write(text.getBytes(StandardCharsets.UTF_8));
	}

	@Override
	public void flush() throws IOException {
		out.flush();
	}

	@Override
	public int[] size() {
		return new int[] {Keyboard.columns(), Keyboard.rows()};
	}

	@Override
	public void startScreen() throws IOException {
		if( ownSignals ) {
			Keyboard.signalKeys(true);
		}
		// Ctrl-C and Ctrl-Z are this program's keys while its screen is up: a shell that watches for
		// them while a command runs (fsh) would otherwise end the program at Ctrl-C
		shellKeys = Keyboard.controlKeys;
		Keyboard.controlKeys = null;
		keys.available();
	}

	@Override
	public void endScreen() throws IOException {
		Keyboard.controlKeys = shellKeys;
		shellKeys = null;
		if( ownSignals ) {
			Keyboard.signalKeys(false);
		}
	}

	@Override
	public int lend(ProcessBuilder program) throws IOException {
		Object user = new Object();
		Keyboard.lendTerminal(user);
		try {
			return program.inheritIO().start().waitFor();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return -1;
		} finally {
			Keyboard.reclaimTerminal(user);
		}
	}
}
