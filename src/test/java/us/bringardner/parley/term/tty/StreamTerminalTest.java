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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

public class StreamTerminalTest {

	@Test
	@Timeout(10)
	public void aReadWithoutATimeoutSeesTheEnd() throws IOException {
		StreamTerminal t = new StreamTerminal(new ByteArrayInputStream("a".getBytes()), new ByteArrayOutputStream(), 80, 24);
		assertEquals('a', t.read(-1));
		assertEquals(-1, t.read(-1));
	}

	@Test
	@Timeout(10)
	public void aTimedReadSeesTheEndWhenTold() throws IOException {
		PipedOutputStream typing = new PipedOutputStream();
		PipedInputStream in = new PipedInputStream(typing);
		AtomicBoolean closed = new AtomicBoolean();
		StreamTerminal t = new StreamTerminal(in, new ByteArrayOutputStream(), 80, 24, closed::get);
		assertEquals(-2, t.read(20));
		typing.write('x');
		typing.close();
		closed.set(true);
		// what came before the end is still read
		assertEquals('x', t.read(20));
		assertEquals(-1, t.read(20));
		// and a full-screen program stops
		KeyDecoder keys = new KeyDecoder(t::read);
		assertThrows(EOFException.class, ()->keys.read(20));
	}

	@Test
	@Timeout(10)
	public void withoutAnEndCheckATimedReadTimesOut() throws IOException {
		StreamTerminal t = new StreamTerminal(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream(), 80, 24);
		assertEquals(-2, t.read(20));
	}
}
