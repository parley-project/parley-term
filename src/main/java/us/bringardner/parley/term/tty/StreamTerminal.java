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
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;

/**
 * A terminal that's only streams and a size: an SSH channel, or a test. Input is read only as
 * far as it's there (available()), so nothing typed after the program ends is taken.
 * <p>
 * available() can't tell the end of the input from a pause, so a read with a timeout only sees
 * the end when the {@code ended} check given to the constructor says so (an SSH channel that was
 * closed, say). A read without a timeout waits in {@link InputStream#read()}, which does see it.
 */
public class StreamTerminal implements Terminal {

	private static final int POLL_MS = 5;

	private final InputStream in;
	private final OutputStream out;
	private final int[] size;
	private final BooleanSupplier ended;

	public StreamTerminal(InputStream in, OutputStream out, int columns, int rows) {
		this(in, out, columns, rows, null);
	}

	/**
	 * @param ended true once the input has ended (and nothing more will come), so a read with a
	 *              timeout returns -1 instead of waiting; null if there's no way to tell
	 * @since 2.0.0
	 */
	public StreamTerminal(InputStream in, OutputStream out, int columns, int rows, BooleanSupplier ended) {
		this.in = in;
		this.out = out;
		this.size = new int[] {columns, rows};
		this.ended = ended;
	}

	@Override
	public int read(int timeoutMs) throws IOException {
		if( timeoutMs < 0 ) {
			// waiting for the next byte whatever happens: whatever comes is for this program
			return in.read();
		}
		long end = System.currentTimeMillis()+timeoutMs;
		while(true) {
			if( in.available() > 0 ) {
				return in.read();
			}
			if( ended != null && ended.getAsBoolean()) {
				// it may have ended just after available() was asked
				return in.available() > 0 ? in.read() : -1;
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
		return size.clone();
	}

	@Override
	public void startScreen() {
	}

	@Override
	public void endScreen() {
	}

	@Override
	public int lend(ProcessBuilder program) throws IOException {
		throw new IOException("can't run a program on this terminal");
	}
}
