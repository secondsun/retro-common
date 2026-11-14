package dev.secondsun.retro.util.vo;

/**
 * A resolved reference location in the workspace.
 *
 * @param uri canonical {@code file:///} URI (or {@code snippet:///} URI)
 * @param path workspace-relative path (absolute path if outside workspace), or null
 * @param line zero-based line index
 * @param lineNumber one-based line number matching editors and {@code read_file}
 * @param startIndex 0-based start column
 * @param endIndex 0-based exclusive end column
 * @param kind reference classification ({@code "definition"}, {@code "call"}, {@code "read"},
 *     {@code "write"}, {@code "macro-invocation"}, {@code "use"})
 * @param text source line text when {@code contextLines > 0}, otherwise null
 */
public record ReferenceLocation(
    String uri,
    String path,
    int line,
    int lineNumber,
    int startIndex,
    int endIndex,
    String kind,
    String text) {}
