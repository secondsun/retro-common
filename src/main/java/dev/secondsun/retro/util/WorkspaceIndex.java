package dev.secondsun.retro.util;

import dev.secondsun.retro.util.vo.Location;
import dev.secondsun.retro.util.vo.ReferenceResult;
import dev.secondsun.retro.util.vo.Scope;
import dev.secondsun.retro.util.vo.SymbolReference;
import dev.secondsun.retro.util.vo.TokenizedFile;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Deep module boundary for workspace source file indexing, editor buffer precedence, workspace
 * sandbox confinement, on-demand directory indexing, and immutable {@link WorkspaceSnapshot}
 * publication.
 *
 * <p>Internal storage services (`FileService`, `SymbolService`, `ReferenceService`) are sealed
 * package-private implementation details; all cross-workspace read queries execute lock-free
 * against {@link #snapshot()} or an ephemeral {@link #snapshotFor(URI, String)} overlay.
 */
public interface WorkspaceIndex {

  /** Returns the latest immutable point-in-time {@link WorkspaceSnapshot}. */
  WorkspaceSnapshot snapshot();

  /**
   * Returns an immutable {@link WorkspaceSnapshot} suitable for analyzing {@code nullableUri} and
   * optional {@code inlineCode}.
   *
   * <ul>
   *   <li>When {@code inlineCode} is non-blank: validates workspace sandbox if {@code nullableUri}
   *       is a non-snippet file URI, lazily indexes its parent directory so sibling includes/macros
   *       are available, and returns an <b>ephemeral</b> overlay snapshot without mutating
   *       persistent open buffers or disk state.
   *   <li>When {@code inlineCode} is null/blank and {@code nullableUri} is non-null: validates
   *       sandbox, lazily indexes {@code nullableUri}, and returns {@link #snapshot()}.
   *   <li>When both are null: returns {@link #snapshot()}.
   * </ul>
   */
  WorkspaceSnapshot snapshotFor(URI nullableUri, String inlineCode);

  /**
   * Resolves a source file given an optional URI and/or source code content.
   *
   * <ol>
   *   <li>{@code uri != null && optionalCode != null}: for real workspace file URIs, updates the
   *       in-memory buffer ({@link #openOrUpdateBuffer(URI, String)}); for synthetic snippet URIs,
   *       returns an isolated transient {@link TokenizedFile} without mutating workspace state.
   *   <li>{@code uri == null && optionalCode != null}: returns an isolated transient snippet {@link
   *       TokenizedFile} ({@code snippet:///inline.s}) without mutating persistent workspace state.
   *   <li>{@code uri != null && optionalCode == null}: validates workspace sandbox boundary, lazily
   *       indexes the file's parent directory if needed, and returns the open buffer or loads from
   *       disk.
   *   <li>{@code uri == null && optionalCode == null}: returns {@link TokenizedFile#EMPTY}.
   * </ol>
   */
  TokenizedFile resolveFile(URI uri, String optionalCode);

  /**
   * Returns tokenized contents for an already-indexed or on-disk file within the workspace sandbox,
   * or {@link TokenizedFile#EMPTY} if not found.
   *
   * @throws SecurityException if {@code uri} escapes the workspace sandbox boundary
   */
  TokenizedFile getFileContents(URI uri);

  /**
   * Opens or updates an in-memory editor buffer, taking precedence over disk contents (`effective =
   * diskFiles ⊕ openBuffers`) and publishing a new {@link WorkspaceSnapshot}.
   */
  void openOrUpdateBuffer(URI uri, String text);

  /** Alias for {@link #openOrUpdateBuffer(URI, String)}. */
  default void updateFileContents(URI uri, String text) {
    openOrUpdateBuffer(uri, text);
  }

  /** Alias for {@link #openOrUpdateBuffer(URI, String)}. */
  default void updateBuffer(URI uri, String text) {
    openOrUpdateBuffer(uri, text);
  }

  /**
   * Closes an in-memory editor buffer, reverting the file's effective state to {@code diskFiles}
   * (loading from disk if present inside the workspace) and publishing a new {@link
   * WorkspaceSnapshot}.
   */
  void closeBuffer(URI uri);

  /**
   * Applies a background filesystem event (`CREATED`, `MODIFIED`, or `DELETED`) strictly to {@code
   * diskFiles} without clobbering any active editor buffer in {@code openBuffers}.
   */
  void notifyDiskEvent(URI uri, DiskEventKind kind);

  /**
   * Explicitly removes a file from both {@code diskFiles} and {@code openBuffers} and publishes a
   * new {@link WorkspaceSnapshot}.
   */
  void removeFile(URI uri);

  /** Reloads {@code uri} from disk into {@code diskFiles} without overwriting an open buffer. */
  default void refreshFileContents(URI uri) {
    notifyDiskEvent(uri, DiskEventKind.MODIFIED);
  }

  /**
   * Ensures the directory containing {@code uri} (or the workspace root if {@code uri} is null and
   * {@link #getWorkspaceRoot()} is configured) is indexed on demand.
   */
  void ensureIndexed(URI uri);

  /**
   * Recursively indexes a directory into {@code diskFiles}, enforcing workspace sandbox
   * confinement.
   *
   * @throws SecurityException if {@code directory} escapes the workspace boundary
   */
  void includeDir(URI directory);

  /**
   * Validates that {@code uri} resides within the configured workspace boundary (after normalizing
   * any {@code ..} path traversal segments).
   *
   * @throws SecurityException if {@code uri} escapes the workspace boundary
   */
  void validateUriWithinWorkspace(URI uri);

  /** Returns true if {@code uri} resides within the configured workspace boundary. */
  boolean isInsideWorkspace(URI uri);

  default Location resolveLocation(String symbol, URI callingUri, Integer line) {
    return snapshot().resolveLocation(symbol, callingUri, line);
  }

  default List<Location> resolveLocations(String symbol, URI callingUri, Integer line) {
    return snapshot().resolveLocations(symbol, callingUri, line);
  }

  default String resolveDocumentation(String symbol, URI callingUri, Integer line) {
    return snapshot().resolveDocumentation(symbol, callingUri, line);
  }

  default String resolveRawDocumentation(String symbol, URI callingUri, Integer line) {
    return snapshot().resolveRawDocumentation(symbol, callingUri, line);
  }

  default Optional<Scope> getScopeAt(URI uri, int line) {
    return snapshot().getScopeAt(uri, line);
  }

  default List<Location> findReferences(String symbol, URI callingUri, Integer line) {
    return snapshot().findReferences(symbol, callingUri, line, false);
  }

  default List<Location> findReferences(
      String symbol, URI callingUri, Integer line, boolean includeDefinitions) {
    return snapshot().findReferences(symbol, callingUri, line, includeDefinitions);
  }

  default List<SymbolReference> findDetailedReferences(
      String symbol, URI callingUri, Integer line, boolean includeDefinitions) {
    return snapshot().findDetailedReferences(symbol, callingUri, line, includeDefinitions);
  }

  default Optional<String> resolveSymbolAt(TokenizedFile file, int line, int column) {
    return snapshot().resolveSymbolAt(file, line, column);
  }

  default List<SymbolReference> findDetailedReferencesAt(
      TokenizedFile file, URI uri, int line, int column, boolean includeDefinitions) {
    return snapshot().findDetailedReferencesAt(file, uri, line, column, includeDefinitions);
  }

  default List<Location> findReferencesAt(
      TokenizedFile file, URI uri, int line, int column, boolean includeDefinitions) {
    return snapshot().findReferencesAt(file, uri, line, column, includeDefinitions);
  }

  default ReferenceResult findReferencesResult(
      String symbol,
      String code,
      String uriStr,
      Integer line,
      String pathFilter,
      Integer maxResults,
      Boolean includeDefinitions,
      Integer contextLines) {
    return snapshot()
        .findReferencesResult(
            symbol, code, uriStr, line, pathFilter, maxResults, includeDefinitions, contextLines);
  }

  /** Registers an optional callback invoked whenever a directory is indexed on demand. */
  void setDirectoryIndexedListener(Consumer<Path> listener);

  /** Returns the normalized workspace root boundary, or null if unconstrained. */
  Path getWorkspaceRoot();
}
