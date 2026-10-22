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

/**
 * A terminal that's only streams and a size: an SSH channel, or a test. Input is read only as
 * far as it's there (available()), so nothing typed after the program ends is taken.
 */
public class StreamTerminal implements Terminal {

	private static final int POLL_MS = 5;

	private final InputStream in;
	private final OutputStream out;
	private final int[] size;

	public StreamTerminal(InputStream in, OutputStream out, int columns, int rows) {
		this.in = in;
		this.out = out;
		this.size = new int[] {columns, rows};
	}

	@Override
	public int read(int timeoutMs) throws IOException {
		long end = timeoutMs < 0 ? Long.MAX_VALUE : System.currentTimeMillis()+timeoutMs;
		while(true) {
			if( in.available() > 0 ) {
				return in.read();
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
