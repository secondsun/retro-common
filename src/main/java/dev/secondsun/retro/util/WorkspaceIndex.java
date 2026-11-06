package dev.secondsun.retro.util;

import dev.secondsun.retro.util.vo.Location;
import dev.secondsun.retro.util.vo.Scope;
import dev.secondsun.retro.util.vo.TokenizedFile;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Deep module interface for workspace source file indexing, buffer precedence, sandbox confinement,
 * on-demand directory inclusion, and symbol resolution.
 */
public interface WorkspaceIndex {

  /**
   * Resolves a source file given an optional URI and/or source code content.
   *
   * <p>Permutations handled:
   *
   * <ol>
   *   <li>{@code uri != null && optionalCode != null}: updates in-memory buffer for {@code uri} and
   *       returns the tokenized file.
   *   <li>{@code uri == null && optionalCode != null}: returns an isolated transient snippet {@link
   *       TokenizedFile} (with URI {@code snippet:///inline.s}) without mutating persistent project
   *       files or global symbols.
   *   <li>{@code uri != null && optionalCode == null}: validates workspace sandbox boundary, lazily
   *       indexes the file's parent directory if needed, and returns the open buffer or loads from
   *       disk.
   *   <li>{@code uri == null && optionalCode == null}: returns {@link TokenizedFile#EMPTY}.
   * </ol>
   *
   * @param uri optional target file URI
   * @param optionalCode optional source code text
   * @return tokenized representation of the resolved file
   * @throws SecurityException if an absolute file URI escapes the workspace sandbox boundary
   */
  TokenizedFile resolveFile(URI uri, String optionalCode);

  /**
   * Returns tokenized contents for an already-indexed or on-disk file, or {@link
   * TokenizedFile#EMPTY} if not found.
   *
   * @param uri file URI to look up
   * @return tokenized file contents
   * @throws SecurityException if URI is outside workspace sandbox
   */
  TokenizedFile getFileContents(URI uri);

  /**
   * Updates an in-memory editor buffer, taking precedence over disk contents and refreshing symbol
   * definitions.
   *
   * @param uri file URI to update
   * @param text new buffer text
   */
  void updateFileContents(URI uri, String text);

  /** Synonym for {@link #updateFileContents(URI, String)} emphasizing buffer precedence. */
  default void updateBuffer(URI uri, String text) {
    updateFileContents(uri, text);
  }

  /**
   * Closes an in-memory buffer. If the file exists on disk, it refreshes from disk; otherwise
   * removes it.
   *
   * @param uri file URI to close
   */
  void closeBuffer(URI uri);

  /**
   * Completely removes a file from the workspace index and symbol table.
   *
   * @param uri file URI to remove
   */
  void removeFile(URI uri);

  /**
   * Refreshes file contents from disk into the index and updates symbols.
   *
   * @param uri file URI to reload
   */
  void refreshFileContents(URI uri);

  /**
   * Recursively indexes a directory into the workspace index, enforcing sandbox confinement.
   *
   * @param directory directory URI
   * @throws SecurityException if directory escapes workspace boundary
   */
  void includeDir(URI directory);

  /**
   * Validates that the provided URI resides within the configured workspace boundary.
   *
   * @param uri URI to validate
   * @throws SecurityException if URI is outside workspace boundary
   */
  void validateUriWithinWorkspace(URI uri);

  /**
   * Returns true if the URI is within the configured workspace sandbox boundary.
   *
   * @param uri URI to test
   * @return true if within workspace boundary or if sandbox is unconstrained
   */
  boolean isInsideWorkspace(URI uri);

  /**
   * Resolves the definition location for a symbol, using calling file URI and line context for
   * scope fallback.
   *
   * @param symbol symbol name
   * @param callingUri calling file URI context (nullable)
   * @param line calling line number context (nullable)
   * @return matching definition {@link Location}, or null if not found
   */
  Location resolveLocation(String symbol, URI callingUri, Integer line);

  /**
   * Resolves all definition locations for a symbol across lexical and global scopes.
   *
   * @param symbol symbol name
   * @param callingUri calling file URI context (nullable)
   * @param line calling line number context (nullable)
   * @return list of matching {@link Location}s
   */
  List<Location> resolveLocations(String symbol, URI callingUri, Integer line);

  /**
   * Finds all references/usages of a symbol across the workspace index (excluding definitions).
   *
   * @param symbol symbol identifier to search for
   * @param callingUri optional contextual calling URI
   * @param line optional contextual line number (0-based)
   * @return list of reference locations
   */
  default List<Location> findReferences(String symbol, URI callingUri, Integer line) {
    return findReferences(symbol, callingUri, line, false);
  }

  /**
   * Finds all references/usages of a symbol across the workspace index.
   *
   * @param symbol symbol identifier to search for
   * @param callingUri optional contextual calling URI
   * @param line optional contextual line number (0-based)
   * @param includeDefinitions whether to include definitions in the results
   * @return list of reference locations
   */
  default List<Location> findReferences(
      String symbol, URI callingUri, Integer line, boolean includeDefinitions) {
    return findDetailedReferences(symbol, callingUri, line, includeDefinitions).stream()
        .map(dev.secondsun.retro.util.vo.SymbolReference::toLocation)
        .toList();
  }

  /**
   * Finds all detailed references of a symbol across the workspace index.
   *
   * @param symbol symbol identifier to search for
   * @param callingUri optional contextual calling URI
   * @param line optional contextual line number (0-based)
   * @param includeDefinitions whether to include definitions in the results
   * @return list of detailed symbol references
   */
  List<dev.secondsun.retro.util.vo.SymbolReference> findDetailedReferences(
      String symbol, URI callingUri, Integer line, boolean includeDefinitions);

  /**
   * Resolves the symbol name at the given 0-based line and column in a tokenized file.
   *
   * @param file tokenized source file
   * @param line 0-based line index
   * @param column 0-based character column index
   * @return optional symbol string, or empty if no symbol is present at the cursor
   */
  default Optional<String> resolveSymbolAt(TokenizedFile file, int line, int column) {
    return getReferenceService().resolveSymbolAt(file, line, column);
  }

  /**
   * Finds all detailed symbol references at the given cursor position in a file.
   *
   * @param file tokenized file content
   * @param uri file URI
   * @param line 0-based cursor line index
   * @param column 0-based cursor column index
   * @param includeDefinitions whether to include symbol definitions in the returned list
   * @return list of detailed symbol references
   */
  default List<dev.secondsun.retro.util.vo.SymbolReference> findDetailedReferencesAt(
      TokenizedFile file, URI uri, int line, int column, boolean includeDefinitions) {
    return getReferenceService()
        .findDetailedReferencesAt(file, uri, line, column, includeDefinitions);
  }

  /**
   * Finds all reference locations at the given cursor position in a file.
   *
   * @param file tokenized file content
   * @param uri file URI
   * @param line 0-based cursor line index
   * @param column 0-based cursor column index
   * @param includeDefinitions whether to include symbol definitions in the returned list
   * @return list of reference {@link Location}s
   */
  default List<Location> findReferencesAt(
      TokenizedFile file, URI uri, int line, int column, boolean includeDefinitions) {
    return getReferenceService().findReferencesAt(file, uri, line, column, includeDefinitions);
  }

  /**
   * Executes a full symbol reference search with optional inline code resolution, path filtering,
   * result capping, and ambiguity reporting.
   *
   * @param symbol name of the symbol to find
   * @param code optional assembly source code to resolve context from
   * @param uriStr optional document URI string
   * @param line optional line number for lexical scope disambiguation
   * @param pathFilter optional workspace-relative path or directory filter
   * @param maxResults maximum number of reference locations to return
   * @param includeDefinitions whether to include definition sites in results
   * @param contextLines when {@code > 0}, includes the source line text for each reference
   * @return structured {@link ReferenceService.ReferenceResult}
   */
  default ReferenceService.ReferenceResult findReferencesResult(
      String symbol,
      String code,
      String uriStr,
      Integer line,
      String pathFilter,
      Integer maxResults,
      Boolean includeDefinitions,
      Integer contextLines) {
    return getReferenceService()
        .find(symbol, code, uriStr, line, pathFilter, maxResults, includeDefinitions, contextLines);
  }

  /**
   * Returns the {@link ReferenceService} associated with this workspace index.
   *
   * @return reference service
   */
  default ReferenceService getReferenceService() {
    return new ReferenceService(this);
  }

  /**
   * Resolves documentation for a symbol, checking contextual scope first before falling back to
   * global docs.
   *
   * @param symbol symbol name
   * @param callingUri calling file URI context (nullable)
   * @param line calling line number context (nullable)
   * @return documentation string, or null if none
   */
  String resolveDocumentation(String symbol, URI callingUri, Integer line);

  /**
   * Retrieves lexical scope at a given line in a file.
   *
   * @param uri file URI
   * @param line 0-based line number
   * @return optional lexical scope
   */
  Optional<Scope> getScopeAt(URI uri, int line);

  /**
   * Sets an optional listener callback invoked when a directory is indexed into the workspace.
   *
   * @param listener directory indexed listener
   */
  void setDirectoryIndexedListener(Consumer<Path> listener);

  /** Returns the configured workspace root boundary, or null if unconstrained. */
  Path getWorkspaceRoot();

  /** Returns the underlying {@link FileService} for low-level or legacy operations. */
  FileService getFileService();

  /** Returns the underlying {@link SymbolService} for low-level or legacy operations. */
  SymbolService getSymbolService();
}
