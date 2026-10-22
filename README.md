# parley-term

Terminal support for Parley applications, with no third-party UI library.

- Java 11 or later
- No runtime dependencies
- Apache License 2.0

## Packages

| Package | Purpose |
|---|---|
| `us.bringardner.parley.term.tty` | The terminal: `Terminal` (read a byte with a timeout, write, size, a full-screen mode, lend the terminal to another program), `StreamTerminal` (any pair of streams with a fixed size, for tests and SSH channels), `Key` and `KeyDecoder` (escape sequences, function keys, UTF-8, Alt and Ctrl combinations). |
| `us.bringardner.parley.term.screen` | Full-screen text interfaces: `Screen` (a buffer of cells), `Tui` (the program loop: it reads keys, watches the size and sends only the cells that changed), `Style`, `Rect`, `Theme`, `Field` (text, multi-line, check box, choice, button, label), `ListView` and `Dialog` (confirm, message, prompt, choose, text screen, form, folder picker). |

Planned for 2.0.0 (Parley 2.0.0 To-Do, items 6.6 and 6.7): raw mode and the terminal size for
Unix, macOS and the Windows console, and line editing, moved here from fsh, so fsh and the
console file browser share one terminal layer.

## Example

```java
Terminal term = new StreamTerminal(System.in, System.out, 80, 24);
new Tui(term).run(app);
```

where `app` is a `Tui.App` that draws into the `Screen` and handles each `Key`.
