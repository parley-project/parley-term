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

/**
 * What a full-screen program needs from a terminal: bytes typed, a place to write, its size, a
 * mode for a full-screen program and back, and a way to lend it to another program.
 */
public interface Terminal {

	/** One byte typed, waiting at most timeoutMs: -2 if none came, -1 at the end. */
	int read(int timeoutMs) throws IOException;

	void write(String text) throws IOException;

	void flush() throws IOException;

	/** {columns, rows} */
	int[] size() throws IOException;

	/** Keys one at a time, no echo (and back with {@link #endScreen}). */
	void startScreen() throws IOException;

	void endScreen() throws IOException;

	/**
	 * Run another program (an editor, a shell) on the terminal as it was before {@link #startScreen},
	 * and take it back after.
	 */
	int lend(ProcessBuilder program) throws IOException;
}
