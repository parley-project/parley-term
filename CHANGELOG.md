# Changelog

## parley-term 2.0.0 (unreleased)

First release, starting at Parley's lockstep version.

### Added

- `us.bringardner.parley.term.tty`: `Keyboard`, `TtyOps`, `Terminal`, `NativeTerminal`,
  `StreamTerminal`, `Key` and `KeyDecoder`.
- `us.bringardner.parley.term.tty.jna`: `JnaTty`, the terminal through JNA on Linux, macOS and the
  Windows console.
- `us.bringardner.parley.term.line`: `LineEditor` and `Completions`.
- `us.bringardner.parley.term.screen`: `Screen`, `Tui`, `Style`, `Rect`, `Theme`, `Field`,
  `ListView` and `Dialog`. In a `Field.Text`, Ctrl-U clears it and Ctrl-W deletes back to the
  previous `/` or space (back a folder in a path); `Dialog.Prompt` shows them (its `hint`).

Taken from the console file browser trial (parley-files-viewer-tui, branch `tui-native`) and from
fsh 1.0.0 (`NativeKeyboard`'s keyboard part, the JNA terminal code and `LineEditor`).
