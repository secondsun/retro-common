package dev.secondsun.retro.util;

import dev.secondsun.retro.util.vo.TokenizedFile;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Default thread-safe implementation of {@link WorkspaceIndex} publishing immutable {@link
 * WorkspaceSnapshot}s (`effectiveFiles = diskFiles ⊕ openBuffers`) via an {@link AtomicReference}.
 */
public class DefaultWorkspaceIndex implements WorkspaceIndex {

  private static final Logger LOG = Logger.getLogger(DefaultWorkspaceIndex.class.getName());

  private static final Set<String> IGNORED_DIR_NAMES =
      Set.of("node_modules", "target", "dist", "out", "build", "tools", "build_tools");

  private final Object writeLock = new Object();
  private final FileService fileService;
  private final Path workspaceRoot;
  private final Map<URI, TokenizedFile> diskFiles = new LinkedHashMap<>();
  private final Map<URI, TokenizedFile> openBuffers = new LinkedHashMap<>();
  private final Set<URI> visitedDirs = ConcurrentHashMap.newKeySet();
  private final Set<Path> indexedDirectories = ConcurrentHashMap.newKeySet();
  private final AtomicReference<WorkspaceSnapshot> currentSnapshot;
  private volatile Consumer<Path> directoryIndexedListener;

  public DefaultWorkspaceIndex() {
    this(null);
  }

  public DefaultWorkspaceIndex(Path workspaceRoot) {
    this.fileService = new FileService();
    this.workspaceRoot = workspaceRoot != null ? workspaceRoot.toAbsolutePath().normalize() : null;
    if (this.workspaceRoot != null && Files.isDirectory(this.workspaceRoot)) {
      this.fileService.addSearchPath(this.workspaceRoot.toUri());
    }
    this.currentSnapshot =
        new AtomicReference<>(
            new WorkspaceSnapshot(
                this.workspaceRoot, Map.of(), Map.of(), this.fileService.searchPathsSnapshot()));
  }

  private static URI safeNormalize(URI uri) {
    return ReferenceService.normalizeUri(uri);
  }

  /**
   * Returns true if {@code uri} is an in-memory synthetic snippet URI (`snippet:...` or normalized
   * `/snippet[.ext]`) AFTER collapsing any `..` path traversal segments.
   */
  public static boolean isSyntheticSnippetUri(URI uri) {
    if (uri == null) {
      return false;
    }
    if ("snippet".equalsIgnoreCase(uri.getScheme())) {
      return true;
    }
    if ("file".equalsIgnoreCase(uri.getScheme())) {
      try {
        String norm = Path.of(uri).toAbsolutePath().normalize().toString().replace('\\', '/');
        return norm.equals("/snippet")
            || norm.startsWith("/snippet.")
            || norm.startsWith("/snippet/");
      } catch (Exception e) {
        return false;
      }
    }
    return false;
  }

  private void publishSnapshotLocked() {
    currentSnapshot.set(
        new WorkspaceSnapshot(
            workspaceRoot, diskFiles, openBuffers, fileService.searchPathsSnapshot()));
  }

  @Override
  public WorkspaceSnapshot snapshot() {
    return currentSnapshot.get();
  }

  @Override
  public WorkspaceSnapshot snapshotFor(URI nullableUri, String inlineCode) {
    if (inlineCode != null && !inlineCode.isBlank()) {
      if (nullableUri != null) {
        validateUriWithinWorkspace(nullableUri);
        if (!isSyntheticSnippetUri(nullableUri)) {
          ensureParentIndexedQuietly(nullableUri);
        }
        return snapshot().overlay(nullableUri, inlineCode);
      }
      return snapshot().overlay(null, inlineCode);
    }
    if (nullableUri != null) {
      resolveFile(nullableUri, null);
    }
    return snapshot();
  }

  @Override
  public TokenizedFile resolveFile(URI uri, String optionalCode) {
    if (optionalCode != null && !optionalCode.isBlank()) {
      if (uri != null) {
        validateUriWithinWorkspace(uri);
        URI normUri = safeNormalize(uri);
        if (isSyntheticSnippetUri(normUri)) {
          return new CA65Scanner().tokenize(optionalCode, normUri);
        }
        ensureParentIndexedQuietly(normUri);
        openOrUpdateBuffer(normUri, optionalCode);
        return snapshot().file(normUri);
      }
      return new CA65Scanner().tokenize(optionalCode, WorkspaceSnapshot.TRANSIENT_SNIPPET_URI);
    }

    if (uri != null) {
      validateUriWithinWorkspace(uri);
      if (isSyntheticSnippetUri(uri)) {
        return TokenizedFile.EMPTY;
      }
      URI normUri = safeNormalize(uri);
      TokenizedFile existing = snapshot().file(normUri);
      if (!existing.isEmpty()) {
        return existing;
      }

      ensureParentIndexedQuietly(normUri);
      existing = snapshot().file(normUri);
      if (!existing.isEmpty()) {
        return existing;
      }

      return loadDiskFileIfAbsent(normUri);
    }

    return TokenizedFile.EMPTY;
  }

  @Override
  public TokenizedFile getFileContents(URI uri) {
    if (uri == null) {
      return TokenizedFile.EMPTY;
    }
    validateUriWithinWorkspace(uri);
    if (isSyntheticSnippetUri(uri)) {
      return TokenizedFile.EMPTY;
    }
    URI normUri = safeNormalize(uri);
    TokenizedFile existing = snapshot().file(normUri);
    if (!existing.isEmpty()) {
      return existing;
    }
    return loadDiskFileIfAbsent(normUri);
  }

  private TokenizedFile loadDiskFileIfAbsent(URI normUri) {
    try {
      TokenizedFile loaded = fileService.readLines(normUri);
      if (loaded != null && !loaded.isEmpty()) {
        URI key = loaded.uri() != null ? safeNormalize(loaded.uri()) : normUri;
        validateUriWithinWorkspace(key);
        synchronized (writeLock) {
          diskFiles.put(key, loaded);
          publishSnapshotLocked();
        }
        return snapshot().file(key);
      }
    } catch (IOException e) {
      LOG.log(Level.FINE, "Could not load file from disk for " + normUri, e);
    }
    return TokenizedFile.EMPTY;
  }

  private void ensureParentIndexedQuietly(URI normUri) {
    if (!"file".equalsIgnoreCase(normUri.getScheme())) {
      return;
    }
    try {
      Path path = Path.of(normUri).toAbsolutePath().normalize();
      if (Files.exists(path)) {
        Path parent = Files.isDirectory(path) ? path : path.getParent();
        if (parent != null
            && parent.getParent() != null
            && isInsideWorkspace(parent.toUri())
            && !indexedDirectories.contains(parent)) {
          includeDir(parent.toUri());
        }
      }
    } catch (Exception e) {
      LOG.log(Level.FINE, "Failed on-demand parent directory indexing for " + normUri, e);
    }
  }

  @Override
  public void ensureIndexed(URI uri) {
    if (uri != null) {
      validateUriWithinWorkspace(uri);
      if (isSyntheticSnippetUri(uri)) {
        return;
      }
      if ("file".equalsIgnoreCase(uri.getScheme())) {
        try {
          Path path = Path.of(uri).toAbsolutePath().normalize();
          Path dir = Files.isDirectory(path) ? path : path.getParent();
          if (dir != null
              && Files.isDirectory(dir)
              && isInsideWorkspace(dir.toUri())
              && !indexedDirectories.contains(dir)) {
            includeDir(dir.toUri());
          }
        } catch (Exception e) {
          LOG.log(Level.FINE, "Failed ensureIndexed for " + uri, e);
        }
      }
    } else if (workspaceRoot != null && Files.isDirectory(workspaceRoot)) {
      includeDir(workspaceRoot.toUri());
    }
  }

  @Override
  public void openOrUpdateBuffer(URI uri, String text) {
    if (uri == null || text == null) {
      return;
    }
    validateUriWithinWorkspace(uri);
    if (isSyntheticSnippetUri(uri)) {
      return;
    }
    URI normUri = safeNormalize(uri);
    TokenizedFile tokenized = new CA65Scanner().tokenize(text, normUri);
    synchronized (writeLock) {
      openBuffers.put(normUri, tokenized);
      publishSnapshotLocked();
    }
  }

  @Override
  public void closeBuffer(URI uri) {
    if (uri == null) {
      return;
    }
    URI normUri = safeNormalize(uri);
    TokenizedFile reloadedDisk = null;
    boolean diskExists = false;
    if (!isSyntheticSnippetUri(normUri) && isInsideWorkspace(normUri)) {
      try {
        TokenizedFile onDisk = fileService.readLines(normUri);
        if (onDisk != null && !onDisk.isEmpty()) {
          reloadedDisk = onDisk;
          diskExists = true;
        }
      } catch (IOException ignored) {
      }
    }

    synchronized (writeLock) {
      openBuffers.remove(normUri);
      if (diskExists) {
        diskFiles.put(normUri, reloadedDisk);
      } else {
        diskFiles.remove(normUri);
      }
      publishSnapshotLocked();
    }
  }

  @Override
  public void notifyDiskEvent(URI uri, DiskEventKind kind) {
    if (uri == null || kind == null) {
      return;
    }
    if (!isInsideWorkspace(uri) || isSyntheticSnippetUri(uri)) {
      return;
    }
    URI normUri = safeNormalize(uri);
    if (kind == DiskEventKind.DELETED) {
      synchronized (writeLock) {
        if (diskFiles.remove(normUri) != null) {
          publishSnapshotLocked();
        }
      }
      return;
    }

    // CREATED or MODIFIED: update diskFiles ONLY — never overwrite active openBuffers!
    try {
      TokenizedFile onDisk = fileService.readLines(normUri);
      synchronized (writeLock) {
        if (onDisk != null && !onDisk.isEmpty()) {
          diskFiles.put(normUri, onDisk);
        } else {
          diskFiles.remove(normUri);
        }
        publishSnapshotLocked();
      }
    } catch (IOException e) {
      LOG.log(Level.FINE, "Could not reload modified file from disk: " + normUri, e);
    }
  }

  @Override
  public void removeFile(URI uri) {
    if (uri == null) {
      return;
    }
    URI normUri = safeNormalize(uri);
    synchronized (writeLock) {
      boolean changed = (diskFiles.remove(normUri) != null) | (openBuffers.remove(normUri) != null);
      if (changed) {
        publishSnapshotLocked();
      }
    }
  }

  @Override
  public void includeDir(URI directory) {
    if (directory == null) {
      return;
    }
    validateUriWithinWorkspace(directory);
    URI normDir = safeNormalize(directory);
    fileService.addSearchPath(normDir);

    Map<URI, TokenizedFile> loadedBatch = new LinkedHashMap<>();
    scanDirRecursive(normDir, loadedBatch);

    synchronized (writeLock) {
      diskFiles.putAll(loadedBatch);
      publishSnapshotLocked();
    }
  }

  private void scanDirRecursive(URI directory, Map<URI, TokenizedFile> loadedBatch) {
    if (!visitedDirs.add(directory)) {
      return;
    }

    Path workspacePath;
    try {
      workspacePath = Path.of(directory).toAbsolutePath().normalize();
    } catch (Exception e) {
      LOG.log(Level.FINE, "Failed to resolve path for directory: " + directory, e);
      return;
    }

    File dirFile = workspacePath.toFile();
    if (!dirFile.exists() || !dirFile.isDirectory()) {
      return;
    }

    fileService.addSearchPath(directory);
    indexedDirectories.add(workspacePath);
    Consumer<Path> listener = directoryIndexedListener;
    if (listener != null) {
      try {
        listener.accept(workspacePath);
      } catch (Exception e) {
        LOG.log(Level.WARNING, "Error in directory indexed listener for " + workspacePath, e);
      }
    }

    File[] matchingFiles =
        dirFile.listFiles(
            pathname -> {
              if (!pathname.isFile()) {
                return false;
              }
              String name = pathname.getName().toLowerCase(Locale.ROOT);
              return name.endsWith(".s")
                  || name.endsWith(".sgs")
                  || name.endsWith(".i")
                  || name.endsWith(".inc");
            });

    if (matchingFiles != null) {
      List<URI> includeDirsToScan = new ArrayList<>();
      WorkspaceSnapshot snap = currentSnapshot.get();
      for (File file : matchingFiles) {
        URI fileUri = safeNormalize(file.toPath().toUri());
        if (!snap.diskFiles().containsKey(fileUri) && !loadedBatch.containsKey(fileUri)) {
          try {
            TokenizedFile tokenized = fileService.readLines(fileUri);
            if (tokenized != null && !tokenized.isEmpty()) {
              loadedBatch.put(fileUri, tokenized);
              for (String includeTarget : tokenized.scopeTree().includes()) {
                Path relResolved = workspacePath.resolve(includeTarget).normalize();
                if (Files.isRegularFile(relResolved) && relResolved.getParent() != null) {
                  includeDirsToScan.add(safeNormalize(relResolved.getParent().toUri()));
                }
                for (URI foundUri : fileService.find(URI.create("./" + includeTarget))) {
                  File f = new File(foundUri);
                  if (f.exists() && f.getParentFile() != null) {
                    includeDirsToScan.add(
                        safeNormalize(f.getParentFile().getAbsoluteFile().toURI()));
                  }
                }
              }
            }
          } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to read lines for " + fileUri, e);
          }
        }
      }

      // Skip external out-of-workspace include directories gracefully without aborting indexing
      for (URI incDir : includeDirsToScan) {
        if (isInsideWorkspace(incDir)) {
          scanDirRecursive(incDir, loadedBatch);
        }
      }
    }

    File[] subDirs = dirFile.listFiles(File::isDirectory);
    if (subDirs != null) {
      Arrays.stream(subDirs)
          .filter(
              dir -> !dir.getName().startsWith(".") && !IGNORED_DIR_NAMES.contains(dir.getName()))
          .map(f -> safeNormalize(f.toPath().toUri()))
          .filter(this::isInsideWorkspace)
          .forEach(sub -> scanDirRecursive(sub, loadedBatch));
    }
  }

  @Override
  public void validateUriWithinWorkspace(URI uri) {
    if (uri == null) {
      return;
    }
    String scheme = uri.getScheme();
    if ("snippet".equalsIgnoreCase(scheme)) {
      return;
    }
    if (!uri.isAbsolute() || !"file".equalsIgnoreCase(scheme)) {
      return;
    }
    // Normalize '..' segments BEFORE checking synthetic '/snippet' or workspace boundary!
    Path target = Path.of(uri).toAbsolutePath().normalize();
    if (isSyntheticSnippetUri(uri)) {
      return;
    }
    if (workspaceRoot != null && !target.startsWith(workspaceRoot)) {
      throw new SecurityException(
          "Access denied: URI '"
              + uri
              + "' is outside the configured workspace boundary: '"
              + workspaceRoot
              + "'");
    }
  }

  @Override
  public boolean isInsideWorkspace(URI uri) {
    if (uri == null) {
      return true;
    }
    try {
      validateUriWithinWorkspace(uri);
      return true;
    } catch (SecurityException e) {
      return false;
    }
  }

  public Map<URI, TokenizedFile> getIndexedFiles() {
    return snapshot().effectiveFiles();
  }

  @Override
  public void setDirectoryIndexedListener(Consumer<Path> listener) {
    this.directoryIndexedListener = listener;
  }

  @Override
  public Path getWorkspaceRoot() {
    return workspaceRoot;
  }
}
