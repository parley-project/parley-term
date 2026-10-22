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

import us.bringardner.parley.term.tty.TtyOps;

/** The terminal through JNA: Linux, macOS and the Windows console. */
public final class JnaTty {

	/** why create() gave none (JNA or its native library is not there), or null */
	public static volatile String failure;

	private JnaTty() {
	}

	/** the platform's implementation, or null if there is none or JNA cannot be loaded */
	public static TtyOps create() {
		try {
			String os = System.getProperty("os.name", "").toLowerCase();
			if( os.startsWith("windows") ) {
				return new WindowsTty();
			}
			if( os.startsWith("mac") || os.startsWith("darwin") ) {
				return new UnixTty(true);
			}
			if( os.startsWith("linux") ) {
				return new UnixTty(false);
			}
			failure = "no JNA terminal for "+System.getProperty("os.name");
		} catch (Throwable e) {
			// (JNA or its native library is not there)
			Throwable cause = e.getCause() != null ? e.getCause() : e;
			failure = cause.getClass().getSimpleName()+": "+cause.getMessage();
			if( Boolean.getBoolean("parley.term.native.debug") ) {
				e.printStackTrace();
			}
		}
		return null;
	}
}
