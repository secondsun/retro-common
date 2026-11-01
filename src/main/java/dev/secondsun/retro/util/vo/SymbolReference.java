package dev.secondsun.retro.util.vo;

import java.net.URI;

/**
 * Detailed reference occurrence of a symbol in the workspace index.
 *
 * @param filename file URI
 * @param line 0-based line index
 * @param startIndex start column in line
 * @param endIndex end column in line
 * @param kind reference kind ("definition", "call", "read", "write", "macro-invocation", "include",
 *     "use")
 * @param lineText source text of the containing line (or null)
 */
public record SymbolReference(
    URI filename, int line, int startIndex, int endIndex, String kind, String lineText) {

  public SymbolReference(URI filename, int line, int startIndex, int endIndex, String kind) {
    this(filename, line, startIndex, endIndex, kind, null);
  }

  public Location toLocation() {
    return new Location(filename, line, startIndex, endIndex);
  }
}
