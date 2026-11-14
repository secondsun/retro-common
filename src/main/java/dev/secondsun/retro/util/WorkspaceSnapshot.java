package dev.secondsun.retro.util;

import dev.secondsun.retro.util.sfx.MacroEffectSummary;
import dev.secondsun.retro.util.vo.Location;
import dev.secondsun.retro.util.vo.ReferenceResult;
import dev.secondsun.retro.util.vo.Scope;
import dev.secondsun.retro.util.vo.ScopeTree;
import dev.secondsun.retro.util.vo.SymbolReference;
import dev.secondsun.retro.util.vo.TokenizedFile;
import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Immutable point-in-time snapshot of workspace state (`effectiveFiles = diskFiles ⊕ openBuffers`),
 * providing lock-free symbol, scope, documentation, macro, include, and reference queries.
 *
 * <p>Supports zero-mutation ephemeral overlays via {@link #overlay(URI, String)} so transient
 * snippets and inline code inspections enjoy full cross-workspace symbol, macro, and reference
 * resolution without polluting persistent index state.
 */
public final class WorkspaceSnapshot {

  public static final URI TRANSIENT_SNIPPET_URI = URI.create("snippet:///inline.s");
  public static final int DEFAULT_MAX_REFERENCES = ReferenceService.DEFAULT_MAX_RESULTS;

  private static final Set<String> INCLUDE_EXTENSIONS =
      Set.of(".i", ".inc", ".s", ".sgs", ".asm", ".h");

  /** Candidate path entry returned by {@link #listIncludeCandidates(URI, String)}. */
  public record IncludeCandidate(String name, boolean isDirectory) {}

  public static final WorkspaceSnapshot EMPTY =
      new WorkspaceSnapshot(null, Map.of(), Map.of(), Set.of());

  private final Path workspaceRoot;
  private final Map<URI, TokenizedFile> diskFiles;
  private final Map<URI, TokenizedFile> openBuffers;
  private final Map<URI, TokenizedFile> effectiveFiles;
  private final Set<URI> searchPaths;

  private volatile SymbolService symbolService;
  private volatile ReferenceService referenceService;
  private final Map<String, Optional<MacroEffectSummary>> macroSummaryCache =
      new ConcurrentHashMap<>();

  public WorkspaceSnapshot(
      Path workspaceRoot,
      Map<URI, TokenizedFile> diskFiles,
      Map<URI, TokenizedFile> openBuffers,
      Set<URI> searchPaths) {
    this.workspaceRoot = workspaceRoot != null ? workspaceRoot.toAbsolutePath().normalize() : null;
    this.diskFiles = Collections.unmodifiableMap(new LinkedHashMap<>(diskFiles));
    this.openBuffers = Collections.unmodifiableMap(new LinkedHashMap<>(openBuffers));
    Map<URI, TokenizedFile> merged = new LinkedHashMap<>(this.diskFiles);
    merged.putAll(this.openBuffers);
    this.effectiveFiles = Collections.unmodifiableMap(merged);
    this.searchPaths = Collections.unmodifiableSet(new LinkedHashSet<>(searchPaths));
  }

  public Path workspaceRoot() {
    return workspaceRoot;
  }

  public Map<URI, TokenizedFile> diskFiles() {
    return diskFiles;
  }

  public Map<URI, TokenizedFile> openBuffers() {
    return openBuffers;
  }

  public Map<URI, TokenizedFile> effectiveFiles() {
    return effectiveFiles;
  }

  public Set<URI> searchPaths() {
    return searchPaths;
  }

  public boolean isOpenBuffer(URI uri) {
    if (uri == null) {
      return false;
    }
    return openBuffers.containsKey(ReferenceService.normalizeUri(uri));
  }

  public TokenizedFile file(URI uri) {
    if (uri == null) {
      return TokenizedFile.EMPTY;
    }
    TokenizedFile tf = effectiveFiles.get(ReferenceService.normalizeUri(uri));
    return tf != null ? tf : TokenizedFile.EMPTY;
  }

  /**
   * Returns a new immutable {@link WorkspaceSnapshot} with {@code inlineCode} overlaid at {@code
   * nullableUri} (or {@link #TRANSIENT_SNIPPET_URI} when {@code nullableUri} is null). Never
   * mutates {@code this} snapshot or any {@link WorkspaceIndex}.
   */
  public WorkspaceSnapshot overlay(URI nullableUri, String inlineCode) {
    if (inlineCode == null) {
      return this;
    }
    URI targetUri =
        nullableUri != null ? ReferenceService.normalizeUri(nullableUri) : TRANSIENT_SNIPPET_URI;
    TokenizedFile tokenized = new CA65Scanner().tokenize(inlineCode, targetUri);
    return overlayFile(targetUri, tokenized);
  }

  WorkspaceSnapshot overlayFile(URI uri, TokenizedFile tokenizedFile) {
    if (uri == null || tokenizedFile == null) {
      return this;
    }
    URI norm = ReferenceService.normalizeUri(uri);
    TokenizedFile bound =
        norm.equals(tokenizedFile.uri()) ? tokenizedFile : tokenizedFile.withUri(norm);
    Map<URI, TokenizedFile> nextBuffers = new LinkedHashMap<>(this.openBuffers);
    nextBuffers.put(norm, bound);
    return new WorkspaceSnapshot(this.workspaceRoot, this.diskFiles, nextBuffers, this.searchPaths);
  }

  public static boolean isLowPriority(URI uri) {
    return ReferenceService.isLowPriority(uri);
  }

  public static boolean isLowPriority(String uri) {
    return ReferenceService.isLowPriority(uri);
  }

  private SymbolService symbols() {
    SymbolService s = symbolService;
    if (s == null) {
      synchronized (this) {
        s = symbolService;
        if (s == null) {
          s = new SymbolService(effectiveFiles);
          symbolService = s;
        }
      }
    }
    return s;
  }

  private ReferenceService references() {
    ReferenceService r = referenceService;
    if (r == null) {
      synchronized (this) {
        r = referenceService;
        if (r == null) {
          r = new ReferenceService(this);
          referenceService = r;
        }
      }
    }
    return r;
  }

  public Location resolveLocation(String symbol) {
    return resolveLocation(symbol, null, null);
  }

  public Location resolveLocation(String symbol, URI callingUri, Integer line) {
    URI norm = callingUri != null ? ReferenceService.normalizeUri(callingUri) : null;
    return symbols().resolveLocation(symbol, norm, line);
  }

  public Location resolveLocation(String symbol, Location context) {
    if (context == null) {
      return resolveLocation(symbol);
    }
    return resolveLocation(symbol, context.filename(), context.line());
  }

  public Location resolveLocation(Token token, TokenizedFile file) {
    if (token == null) {
      return null;
    }
    URI uri = file != null ? file.uri() : null;
    return resolveLocation(token.text(), uri, token.lineNumber);
  }

  public List<Location> resolveLocations(String symbol) {
    return resolveLocations(symbol, null, null);
  }

  public List<Location> resolveLocations(String symbol, URI callingUri, Integer line) {
    URI norm = callingUri != null ? ReferenceService.normalizeUri(callingUri) : null;
    return symbols().resolveLocations(symbol, norm, line);
  }

  public String resolveDocumentation(String symbol) {
    return resolveDocumentation(symbol, null, null);
  }

  public String resolveDocumentation(String symbol, URI callingUri, Integer line) {
    URI norm = callingUri != null ? ReferenceService.normalizeUri(callingUri) : null;
    return symbols().resolveDocumentation(symbol, norm, line);
  }

  public String resolveRawDocumentation(String symbol) {
    return resolveRawDocumentation(symbol, null, null);
  }

  public String resolveRawDocumentation(String symbol, URI callingUri, Integer line) {
    URI norm = callingUri != null ? ReferenceService.normalizeUri(callingUri) : null;
    return symbols().resolveRawDocumentation(symbol, norm, line);
  }

  public boolean hasDocumentation(String symbol) {
    return resolveDocumentation(symbol) != null;
  }

  public boolean hasDocumentation(String symbol, URI callingUri, Integer line) {
    return resolveDocumentation(symbol, callingUri, line) != null;
  }

  public Optional<Scope> getScopeAt(URI uri, int line) {
    if (uri == null) {
      return Optional.empty();
    }
    return symbols().getScopeAt(ReferenceService.normalizeUri(uri), line);
  }

  public List<Scope> getScopes(URI uri) {
    if (uri == null) {
      return List.of();
    }
    return symbols().getScopes(ReferenceService.normalizeUri(uri));
  }

  public Set<String> allSymbolNames() {
    return symbols().allSymbolNames();
  }

  public Optional<ScopeTree.MacroDefinition> resolveMacro(String name) {
    return symbols().resolveMacro(name);
  }

  /**
   * Derives and memoizes the {@link MacroEffectSummary} for {@code name} across all indexed files
   * in this snapshot, resolving nested macro calls transitively.
   */
  public Optional<MacroEffectSummary> resolveMacroSummary(String name) {
    if (name == null || name.isBlank()) {
      return Optional.empty();
    }
    String key = name.toLowerCase(Locale.ROOT);
    return macroSummaryCache.computeIfAbsent(
        key,
        k -> {
          Optional<ScopeTree.MacroDefinition> defOpt = symbols().resolveMacro(k);
          if (defOpt.isEmpty()) {
            return Optional.empty();
          }
          Map<String, String> lowerDefs = new LinkedHashMap<>();
          symbols()
              .allDefines()
              .forEach((dk, dv) -> lowerDefs.put(dk.toLowerCase(Locale.ROOT), dv));
          MacroEffectSummary derived =
              MacroEffectSummary.derive(
                  toMacroDef(defOpt.get()),
                  lowerDefs,
                  nested ->
                      symbols()
                          .resolveMacro(nested)
                          .map(WorkspaceSnapshot::toMacroDef)
                          .orElse(null));
          return Optional.of(derived);
        });
  }

  private static MacroEffectSummary.MacroDefinition toMacroDef(ScopeTree.MacroDefinition def) {
    return new MacroEffectSummary.MacroDefinition(
        def.name(), def.parameters(), def.tokenizedBodyLines(), def.uri(), def.startLine());
  }

  public Map<String, ScopeTree.MacroDefinition> allMacros() {
    return symbols().allMacros();
  }

  public Map<String, String> allDefines() {
    return symbols().allDefines();
  }

  public boolean isMacro(String name) {
    return symbols().isMacroName(name);
  }

  /**
   * Resolves all candidate target URIs for an {@code .include} / {@code .incbin} path relative to
   * {@code fromFile}'s parent directory, workspace root, and registered search paths, respecting
   * workspace sandbox confinement when {@link #workspaceRoot()} is set.
   */
  public List<URI> findIncludeUris(URI fromFile, String includeTarget) {
    if (includeTarget == null || includeTarget.isBlank()) {
      return List.of();
    }
    String cleanTarget = includeTarget.replace("\"", "").trim();
    if (cleanTarget.isEmpty()) {
      return List.of();
    }

    Set<URI> matches = new LinkedHashSet<>();

    // 1. Relative to calling file's parent directory
    if (fromFile != null && "file".equalsIgnoreCase(fromFile.getScheme())) {
      try {
        Path callerPath = Path.of(fromFile).toAbsolutePath().normalize();
        Path parent = Files.isDirectory(callerPath) ? callerPath : callerPath.getParent();
        if (parent != null) {
          addCandidateIfPresent(matches, parent.resolve(cleanTarget).normalize());
        }
      } catch (Exception ignored) {
      }
    }

    // 2. Relative to workspace root
    if (workspaceRoot != null) {
      try {
        addCandidateIfPresent(matches, workspaceRoot.resolve(cleanTarget).normalize());
      } catch (Exception ignored) {
      }
    }

    // 3. Relative to registered search paths
    for (URI repo : searchPaths) {
      try {
        Path candidate = Path.of(repo).resolve(cleanTarget).toAbsolutePath().normalize();
        addCandidateIfPresent(matches, candidate);
      } catch (Exception ignored) {
      }
    }

    // 4. Suffix match against open buffers / effective files for virtual or unindexed paths
    String normalizedSuffix = "/" + cleanTarget.replace('\\', '/').replaceFirst("^\\./", "");
    for (URI known : effectiveFiles.keySet()) {
      if (known.getPath() != null && known.getPath().endsWith(normalizedSuffix)) {
        if (isPathAllowed(known)) {
          matches.add(known);
        }
      }
    }

    return List.copyOf(matches);
  }

  public Optional<URI> resolveInclude(URI fromFile, String includeTarget) {
    List<URI> found = findIncludeUris(fromFile, includeTarget);
    return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
  }

  private void addCandidateIfPresent(Set<URI> out, Path normalizedCandidate) {
    if (normalizedCandidate == null) {
      return;
    }
    if (workspaceRoot != null && !normalizedCandidate.startsWith(workspaceRoot)) {
      return;
    }
    URI candidateUri = ReferenceService.normalizeUri(normalizedCandidate.toUri());
    if (effectiveFiles.containsKey(candidateUri) || Files.isRegularFile(normalizedCandidate)) {
      out.add(candidateUri);
    }
  }

  private boolean isPathAllowed(URI uri) {
    if (workspaceRoot == null || uri == null || !"file".equalsIgnoreCase(uri.getScheme())) {
      return true;
    }
    try {
      return Path.of(uri).toAbsolutePath().normalize().startsWith(workspaceRoot);
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * Lists completion candidates (subdirectories and include files) for {@code .include "..."} at
   * {@code fromFile} given the partial directory prefix typed before the cursor.
   */
  public List<IncludeCandidate> listIncludeCandidates(URI fromFile, String partialDirPrefix) {
    String cleanPrefix = partialDirPrefix != null ? partialDirPrefix.replace('\\', '/').trim() : "";
    Set<Path> baseDirs = new LinkedHashSet<>();

    if (fromFile != null && "file".equalsIgnoreCase(fromFile.getScheme())) {
      try {
        Path p = Path.of(fromFile).toAbsolutePath().normalize().getParent();
        if (p != null) {
          baseDirs.add(p);
        }
      } catch (Exception ignored) {
      }
    }
    if (workspaceRoot != null) {
      baseDirs.add(workspaceRoot);
    }
    for (URI repo : searchPaths) {
      try {
        baseDirs.add(Path.of(repo).toAbsolutePath().normalize());
      } catch (Exception ignored) {
      }
    }

    Map<String, Boolean> seen = new LinkedHashMap<>();
    for (Path base : baseDirs) {
      Path targetDir = cleanPrefix.isEmpty() ? base : base.resolve(cleanPrefix).normalize();
      if (workspaceRoot != null && !targetDir.startsWith(workspaceRoot)) {
        continue;
      }
      File dirFile = targetDir.toFile();
      if (!dirFile.isDirectory()) {
        continue;
      }
      File[] entries = dirFile.listFiles();
      if (entries == null) {
        continue;
      }
      for (File entry : entries) {
        String entryName = entry.getName();
        if (entryName.startsWith(".")) {
          continue;
        }
        if (entry.isDirectory()) {
          seen.putIfAbsent(entryName, true);
        } else if (hasIncludeExtension(entryName)) {
          seen.putIfAbsent(entryName, false);
        }
      }
    }

    List<IncludeCandidate> out = new ArrayList<>(seen.size());
    for (Map.Entry<String, Boolean> e : seen.entrySet()) {
      out.add(new IncludeCandidate(e.getKey(), e.getValue()));
    }
    out.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
    return List.copyOf(out);
  }

  private static boolean hasIncludeExtension(String fileName) {
    String lower = fileName.toLowerCase(Locale.ROOT);
    for (String ext : INCLUDE_EXTENSIONS) {
      if (lower.endsWith(ext)) {
        return true;
      }
    }
    return false;
  }

  public Optional<String> resolveSymbolAt(TokenizedFile file, int line, int column) {
    return references().resolveSymbolAt(file, line, column);
  }

  public List<SymbolReference> findDetailedReferences(
      String symbol, URI callingUri, Integer line, boolean includeDefinitions) {
    return references().findDetailedReferences(symbol, callingUri, line, includeDefinitions);
  }

  public List<Location> findReferences(String symbol, URI callingUri, Integer line) {
    return findReferences(symbol, callingUri, line, false);
  }

  public List<Location> findReferences(
      String symbol, URI callingUri, Integer line, boolean includeDefinitions) {
    return findDetailedReferences(symbol, callingUri, line, includeDefinitions).stream()
        .map(SymbolReference::toLocation)
        .toList();
  }

  public List<SymbolReference> findDetailedReferencesAt(
      TokenizedFile file, URI uri, int line, int column, boolean includeDefinitions) {
    return references().findDetailedReferencesAt(file, uri, line, column, includeDefinitions);
  }

  public List<Location> findReferencesAt(
      TokenizedFile file, URI uri, int line, int column, boolean includeDefinitions) {
    return references().findReferencesAt(file, uri, line, column, includeDefinitions);
  }

  public ReferenceResult findReferencesResult(
      String symbol,
      String code,
      String uriStr,
      Integer line,
      String pathFilter,
      Integer maxResults,
      Boolean includeDefinitions,
      Integer contextLines) {
    return references()
        .find(symbol, code, uriStr, line, pathFilter, maxResults, includeDefinitions, contextLines);
  }
}
