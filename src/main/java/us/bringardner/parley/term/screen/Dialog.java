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
package us.bringardner.parley.term.screen;

import us.bringardner.parley.term.tty.*;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;

/**
 * A window over the application that has the keyboard until it closes: a question, a form, a
 * list to choose from, a full-screen text. A dialog is state, a render method and a key method;
 * they stack ({@link Stack}), and each answers through a callback. Every way out but OK counts
 * as cancel.
 */
public abstract class Dialog {

	private boolean closed;

	public boolean isClosed() {
		return closed;
	}

	public void close() {
		closed = true;
	}

	public abstract void render(Screen screen, Rect area);

	/** A key; true if the screen should be drawn again. */
	public abstract boolean key(Key k);

	/** The open dialogs; the top one has the keyboard. */
	public static final class Stack {
		private final Deque<Dialog> open = new ArrayDeque<>();

		public void open(Dialog d) {
			open.push(d);
		}

		public boolean isEmpty() {
			prune();
			return open.isEmpty();
		}

		public Dialog top() {
			prune();
			return open.peek();
		}

		public boolean key(Key k) {
			Dialog d = top();
			boolean ret = d != null && d.key(k);
			prune();
			return ret;
		}

		private void prune() {
			for(Iterator<Dialog> it = open.iterator(); it.hasNext();) {
				if( it.next().isClosed()) {
					it.remove();
				}
			}
		}

		public void render(Screen screen) {
			prune();
			Iterator<Dialog> it = open.descendingIterator();
			while(it.hasNext()) {
				it.next().render(screen, screen.area());
			}
		}
	}

	/** text in lines of at most width, at spaces (a longer word is cut). */
	public static List<String> wrap(String text, int width) {
		List<String> out = new ArrayList<>();
		for(String para : text.split("\n", -1)) {
			StringBuilder line = new StringBuilder();
			for(String word : para.trim().split("\\s+")) {
				while( word.length() > width ) {
					if( line.length() > 0 ) {
						out.add(line.toString());
						line.setLength(0);
					}
					out.add(word.substring(0, width));
					word = word.substring(width);
				}
				if( word.isEmpty()) {
					continue;
				}
				if( line.length() > 0 && line.length()+1+word.length() > width ) {
					out.add(line.toString());
					line.setLength(0);
				}
				if( line.length() > 0 ) {
					line.append(' ');
				}
				line.append(word);
			}
			out.add(line.toString());
		}
		return out;
	}

	public static String fitFront(String text, int width) {
		return text.length() <= width ? text : "..."+text.substring(text.length()-Math.max(0, width-3));
	}

	// ---------------------------------------------------------------- simple ones

	/** Yes or no. */
	public static final class Confirm extends Dialog {
		private final String title;
		private final String text;
		private final Runnable yes;
		private final Runnable no;

		public Confirm(String title, String text, Runnable yes, Runnable no) {
			this.title = title;
			this.text = text;
			this.yes = yes;
			this.no = no;
		}

		@Override
		public void render(Screen screen, Rect area) {
			List<String> lines = wrap(text, 56);
			Rect inner = screen.box(area.centered(62, lines.size()+4), title, Theme.NORMAL);
			for(int i=0; i < lines.size(); i++) {
				screen.text(inner.row(i), lines.get(i), Theme.NORMAL);
			}
			screen.text(inner.row(lines.size()+1), "y or Enter: yes    n or Esc: no", Theme.HINT);
		}

		@Override
		public boolean key(Key k) {
			close();
			if( k.isChar('y') || k.isChar('Y') || k.is(Key.Code.ENTER)) {
				yes.run();
			} else if( no != null ) {
				no.run();
			}
			return true;
		}
	}

	/** Something to read; any key closes it. */
	public static final class Message extends Dialog {
		private final String title;
		private final String text;

		public Message(String title, String text) {
			this.title = title;
			this.text = text;
		}

		@Override
		public void render(Screen screen, Rect area) {
			List<String> lines = wrap(text, 66);
			Rect inner = screen.box(area.centered(72, Math.min(area.height, lines.size()+4)), title, Theme.NORMAL);
			for(int i=0; i < lines.size() && i < inner.height-2; i++) {
				screen.text(inner.row(i), lines.get(i), Theme.NORMAL);
			}
			screen.text(inner.row(inner.height-1), "Enter or Esc: close", Theme.HINT);
		}

		@Override
		public boolean key(Key k) {
			close();
			return true;
		}
	}

	/** One line of text: Enter OK, Esc cancel. */
	public static final class Prompt extends Dialog {
		private final String title;
		private final String label;
		private final Field.Text field;
		private final Consumer<String> ok;

		public Prompt(String title, String label, String initial, Consumer<String> ok) {
			this.title = title;
			this.label = label;
			this.field = new Field.Text(initial, false, false);
			this.ok = ok;
		}

		@Override
		public void render(Screen screen, Rect area) {
			int w = Math.max(50, Math.min(area.width-4, Math.max(label.length(), field.value().length()+2)+4));
			Rect inner = screen.box(area.centered(w, 6), title, Theme.NORMAL);
			screen.text(inner.row(0), label, Theme.NORMAL);
			field.render(screen, inner.row(1), true);
			screen.text(inner.row(3), "Enter: OK   Esc: cancel", Theme.HINT);
		}

		@Override
		public boolean key(Key k) {
			if( k.isCancel()) {
				close();
			} else if( k.is(Key.Code.ENTER)) {
				close();
				ok.accept(field.value());
			} else {
				field.key(k);
			}
			return true;
		}
	}

	/** Pick one from a list: Enter picks, Esc cancels. */
	public static final class Choose extends Dialog {
		private final String title;
		private final String description;
		private final List<String> items;
		private final ListView list = new ListView();
		private final IntConsumer chosen;
		private final Runnable canceled;
		private final int width;
		private int page = 10;

		public Choose(String title, String description, List<String> items, int width, IntConsumer chosen, Runnable canceled) {
			this.title = title;
			this.description = description;
			this.items = items;
			this.width = width;
			this.chosen = chosen;
			this.canceled = canceled;
		}

		@Override
		public void render(Screen screen, Rect area) {
			int head = description == null ? 0 : 2;
			Rect inner = screen.box(area.centered(width, Math.min(area.height-2, items.size()+head+4)), title, Theme.NORMAL);
			if( description != null ) {
				screen.text(inner.row(0), fitFront(description, inner.width), Theme.NORMAL);
			}
			Rect rows = inner.rows(head, Math.max(1, inner.height-head-2));
			page = rows.height;
			list.render(screen, rows, items, true);
			screen.text(inner.row(inner.height-1), items.isEmpty() ? "Nothing to choose from   Esc: back" : "Enter: choose   Esc: cancel", Theme.HINT);
		}

		@Override
		public boolean key(Key k) {
			if( k.isCancel()) {
				close();
				if( canceled != null ) {
					canceled.run();
				}
			} else if( k.is(Key.Code.ENTER) && !items.isEmpty()) {
				close();
				chosen.accept(list.selected());
			} else {
				return list.key(k, items.size(), page);
			}
			return true;
		}
	}

	/** Full-screen, read-only, scrolling text (a file's preview, the help), shown as it is. */
	public static final class TextScreen extends Dialog {
		private final String title;
		private final List<String> lines;
		private int top;
		private int height = 20;

		public TextScreen(String title, String text) {
			this.title = title;
			this.lines = Field.split(text);
		}

		@Override
		public void render(Screen screen, Rect area) {
			Rect inner = screen.box(area, title, Theme.NORMAL);
			height = Math.max(1, inner.height-1);
			scroll(top);
			for(int i=0; i < height && top+i < lines.size(); i++) {
				screen.text(inner.row(i), lines.get(top+i), Theme.NORMAL);
			}
			screen.text(inner.row(inner.height-1), "Esc or q to close   line "+(top+1)+" of "+lines.size(), Theme.HINT);
		}

		private void scroll(int to) {
			top = Math.max(0, Math.min(Math.max(0, lines.size()-height), to));
		}

		@Override
		public boolean key(Key k) {
			if( k.isCancel() || k.isChar('q') || k.isChar('v') || k.is(Key.Code.F3) || k.is(Key.Code.F10)) {
				close();
			} else if( k.is(Key.Code.UP) || k.isChar('k')) {
				scroll(top-1);
			} else if( k.is(Key.Code.DOWN) || k.isChar('j')) {
				scroll(top+1);
			} else if( k.is(Key.Code.PAGE_UP)) {
				scroll(top-height);
			} else if( k.is(Key.Code.PAGE_DOWN) || k.isChar(' ')) {
				scroll(top+height);
			} else if( k.is(Key.Code.HOME)) {
				scroll(0);
			} else if( k.is(Key.Code.END)) {
				scroll(lines.size());
			} else {
				return false;
			}
			return true;
		}
	}

	// ---------------------------------------------------------------- forms

	/**
	 * Labeled fields over buttons, a line for problems and two lines describing the field being
	 * edited. Tab / Shift-Tab (or Down / Up) go from field to field; Enter in a one-line field
	 * submits; Esc cancels. Rows can come and go (visible).
	 */
	public static class Form extends Dialog {

		public static final class Row {
			final String label;
			final Field field;
			final Field extra;
			final BooleanSupplier visible;

			Row(String label, Field field, Field extra, BooleanSupplier visible) {
				this.label = label;
				this.field = field;
				this.extra = extra;
				this.visible = visible == null ? ()->true : visible;
			}
		}

		final String title;
		final List<Row> rows = new ArrayList<>();
		public final List<Field.Button> buttons = new ArrayList<>();
		/** checks and acts on the values: null closes the form, else the problems to show */
		public Function<Form, String> submit;
		public Runnable cancel;
		public String problems = "";
		public String footer = "Enter: OK   Esc: cancel   Tab: next field";
		public int width = 70;
		private Field focus;

		public Form(String title) {
			this.title = title;
		}

		public Row add(String label, Field field) {
			return add(label, field, null, null);
		}

		public Row add(String label, Field field, Field extra, BooleanSupplier visible) {
			Row r = new Row(label, field, extra, visible);
			rows.add(r);
			return r;
		}

		List<Row> visibleRows() {
			List<Row> ret = new ArrayList<>();
			for(Row r : rows) {
				if( r.visible.getAsBoolean()) {
					ret.add(r);
				}
			}
			return ret;
		}

		List<Field> focusOrder() {
			List<Field> ret = new ArrayList<>();
			for(Row r : visibleRows()) {
				if( r.field.focusable()) {
					ret.add(r.field);
				}
				if( r.extra != null && r.extra.focusable()) {
					ret.add(r.extra);
				}
			}
			ret.addAll(buttons);
			return ret;
		}

		public Field focus() {
			List<Field> order = focusOrder();
			if( focus == null || !order.contains(focus)) {
				// the field being edited no longer applies: the first one that does
				focus = order.isEmpty() ? null : order.get(0);
			}
			return focus;
		}

		public void setFocus(Field f) {
			focus = f;
		}

		private void moveFocus(int by) {
			List<Field> order = focusOrder();
			if( !order.isEmpty()) {
				focus = order.get(Math.floorMod(order.indexOf(focus())+by, order.size()));
			}
		}

		public void submit() {
			String err = submit == null ? null : submit.apply(this);
			if( err == null ) {
				close();
			} else {
				problems = err;
			}
		}

		public void cancel() {
			close();
			if( cancel != null ) {
				cancel.run();
			}
		}

		@Override
		public boolean key(Key k) {
			Field f = focus();
			if( k.isCancel()) {
				cancel();
				return true;
			}
			if( k.is(Key.Code.TAB) || k.is(Key.Code.BACKTAB)) {
				moveFocus(k.is(Key.Code.TAB) ? 1 : -1);
				return true;
			}
			if( k.is(Key.Code.ENTER) && (f == null || f.submitsOnEnter())) {
				submit();
				return true;
			}
			if( f != null && f.key(k)) {
				return true;
			}
			if( k.is(Key.Code.DOWN)) {
				moveFocus(1);
			} else if( k.is(Key.Code.UP)) {
				moveFocus(-1);
			} else {
				return false;
			}
			return true;
		}

		@Override
		public void render(Screen screen, Rect area) {
			Field f = focus();
			List<Row> shown = visibleRows();
			int labelWidth = 0;
			for(Row r : shown) {
				labelWidth = Math.max(labelWidth, r.label.length());
			}
			labelWidth += 2;
			int body = 0;
			for(Row r : shown) {
				body += r.field.height();
			}
			int w = Math.min(area.width, width);
			List<String> problemLines = problems.isEmpty() ? new ArrayList<>() : wrap(problems, w-4);
			int h = body+1+problemLines.size()+2+1+1+2;
			Rect inner = screen.box(area.centered(w, h), title, Theme.NORMAL);
			int y = 0;
			for(Row r : shown) {
				screen.text(inner.row(y), r.label, r.field == f || r.extra == f ? Theme.BOLD : Theme.NORMAL);
				int extraWidth = r.extra instanceof Field.Button ? ((Field.Button) r.extra).width()+1 : 0;
				Rect fieldArea = new Rect(inner.x+labelWidth, inner.y+y, Math.max(1, inner.width-labelWidth-extraWidth), r.field.height());
				r.field.render(screen, fieldArea, r.field == f);
				if( r.extra != null ) {
					r.extra.render(screen, new Rect(fieldArea.right()+1, inner.y+y, extraWidth-1, 1), r.extra == f);
				}
				y += r.field.height();
			}
			y++;
			for(String p : problemLines) {
				screen.text(inner.row(y++), p, Theme.ERROR);
			}
			List<String> help = wrap(f == null || f.help == null ? "" : f.help, inner.width);
			for(int i=0; i < 2; i++) {
				screen.text(inner.row(y++), i < help.size() ? help.get(i) : "", Theme.HINT);
			}
			screen.text(inner.row(y++), footer, Theme.HINT);
			int x = inner.x;
			for(Field.Button b : buttons) {
				b.render(screen, new Rect(x, inner.y+y, Math.min(b.width(), inner.right()-x), 1), b == f);
				x += b.width()+1;
			}
			// (the cursor belongs to the focused field; a button or a check box has none)
			if( !(f instanceof Field.Text) && !(f instanceof Field.MultiLine)) {
				screen.cursor(-1, -1);
			}
		}
	}

	// ---------------------------------------------------------------- local files

	/**
	 * Choose a local folder or file from lists: Enter goes into a folder (or chooses a file),
	 * Backspace goes up, c chooses the folder shown (folder mode).
	 */
	public static final class Picker extends Dialog {
		private final String title;
		private final boolean files;
		private final Consumer<File> chosen;
		private File dir;
		private List<File> entries = new ArrayList<>();
		private final ListView list = new ListView();
		private int page = 10;

		public Picker(String title, File start, boolean files, Consumer<File> chosen) {
			this.title = title;
			this.files = files;
			this.chosen = chosen;
			File d = start;
			while(d != null && !d.isDirectory()) {
				d = d.getParentFile();
			}
			show(d == null ? new File(System.getProperty("user.home")) : d, start == null ? null : start.getName());
		}

		private void show(File d, String select) {
			dir = d;
			entries = new ArrayList<>();
			File[] kids = d.listFiles();
			if( kids != null ) {
				Arrays.sort(kids, (a, b)->a.isDirectory() != b.isDirectory() ? (a.isDirectory() ? -1 : 1)
						: a.getName().compareToIgnoreCase(b.getName()));
				for(File k : kids) {
					if( k.isDirectory() || files ) {
						entries.add(k);
					}
				}
			}
			int idx = 0;
			for(int i=0; i < entries.size(); i++) {
				if( entries.get(i).getName().equals(select)) {
					idx = i+1;
				}
			}
			list.select(idx, entries.size()+1);
		}

		@Override
		public void render(Screen screen, Rect area) {
			Rect inner = screen.box(area.centered(72, area.height-4), title, Theme.NORMAL);
			screen.text(inner.row(0), fitFront(home(dir.getAbsolutePath()), inner.width), Theme.BOLD);
			List<String> items = new ArrayList<>();
			items.add("../");
			for(File f : entries) {
				items.add(f.getName()+(f.isDirectory() ? "/" : ""));
			}
			Rect rows = inner.rows(1, Math.max(1, inner.height-2));
			page = rows.height;
			list.render(screen, rows, items, true);
			screen.text(inner.row(inner.height-1), files ? "Enter: open folder / choose file   Bksp: up   Esc: cancel"
					: "Enter: open   c: choose this folder   Bksp: up   Esc: cancel", Theme.HINT);
		}

		/** ~ for the home folder */
		private static String home(String path) {
			String home = System.getProperty("user.home");
			return home != null && path.startsWith(home) ? "~"+path.substring(home.length()) : path;
		}

		@Override
		public boolean key(Key k) {
			int idx = list.selected();
			if( k.isCancel()) {
				close();
			} else if( k.is(Key.Code.BACKSPACE) || k.is(Key.Code.LEFT) || (k.is(Key.Code.ENTER) && idx == 0)) {
				if( dir.getParentFile() != null ) {
					show(dir.getParentFile(), dir.getName());
				}
			} else if( k.is(Key.Code.ENTER) || k.is(Key.Code.RIGHT)) {
				File f = entries.get(idx-1);
				if( f.isDirectory()) {
					show(f, null);
				} else if( files ) {
					close();
					chosen.accept(f);
				}
			} else if( k.isChar('c') && !files ) {
				close();
				chosen.accept(dir);
			} else {
				return list.key(k, entries.size()+1, page);
			}
			return true;
		}
	}
}
