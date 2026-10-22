# parley-term

Terminal support for Parley applications, with no third-party UI library.

- Java 11 or later
- One runtime dependency: JNA, for the terminal on Linux, macOS and the Windows console
- Apache License 2.0

## Packages

| Package | Purpose |
|---|---|
| `us.bringardner.parley.term.tty` | The terminal: `Keyboard` (the process's keyboard: keys one at a time without echo, Ctrl-C and Ctrl-Z as keys or signals, lending the terminal to another program, the size, secrets read with a mask), `TtyOps` (what it needs from the operating system), `Terminal` (what a full-screen program needs) with `NativeTerminal` (the process's own terminal) and `StreamTerminal` (any pair of streams with a fixed size, for tests and SSH channels), `Key` and `KeyDecoder` (escape sequences, function keys, UTF-8, Alt and Ctrl). |
| `us.bringardner.parley.term.tty.jna` | `TtyOps` through JNA: libc on Linux and macOS, kernel32 on Windows (`JnaTty.create()`). An application can install its own instead (fsh installs its JNI library). |
| `us.bringardner.parley.term.line` | `LineEditor`: a line editor as bash's readline (emacs and vi keys, history and its searches, kill and yank, undo, key bindings, Tab completion through `Completions`). |
| `us.bringardner.parley.term.screen` | Full-screen text interfaces: `Screen` (a buffer of cells), `Tui` (the program loop: it reads keys, watches the size and sends only the cells that changed), `Style`, `Rect`, `Theme`, `Field` (text, multi-line, check box, choice, button, label), `ListView` and `Dialog` (confirm, message, prompt, choose, text screen, form, folder picker). |

fsh uses parley-term for its keyboard and line editing, and gives it its JNI library when that
is loaded.

## Example

```java
Terminal term = new NativeTerminal(true);
new Tui(term).run(app);
```

where `app` is a `Tui.App` that draws into the `Screen` and handles each `Key`.
