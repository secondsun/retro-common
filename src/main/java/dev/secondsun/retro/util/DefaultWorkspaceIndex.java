package dev.secondsun.retro.util;

import dev.secondsun.retro.util.vo.Location;
import dev.secondsun.retro.util.vo.Scope;
import dev.secondsun.retro.util.vo.TokenizedFile;
import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Default implementation of {@link WorkspaceIndex} coordinating file loading, buffer precedence,
 * workspace sandbox confinement, on-demand directory inclusion, and scoped symbol resolution.
 */
public class DefaultWorkspaceIndex implements WorkspaceIndex {

  private static final Logger LOG = Logger.getLogger(DefaultWorkspaceIndex.class.getName());

  private static URI safeNormalize(URI uri) {
    if (uri == null) {
      return null;
    }
    if ("file".equalsIgnoreCase(uri.getScheme())) {
      try {
        return Util.normalize(uri);
      } catch (Exception e) {
        return uri.normalize();
      }
    }
    return uri.normalize();
  }

  protected final FileService fileService;
  protected final SymbolService symbolService;
  protected final Path workspaceRoot;
  protected final Map<URI, TokenizedFile> files = new ConcurrentHashMap<>();
  protected final Set<URI> inMemoryBuffers = ConcurrentHashMap.newKeySet();
  protected final Set<URI> visitedDirs = ConcurrentHashMap.newKeySet();
  protected final Set<Path> indexedDirectories = ConcurrentHashMap.newKeySet();
  private Consumer<Path> directoryIndexedListener;

  public DefaultWorkspaceIndex() {
    this(new FileService(), new SymbolService(), null);
  }

  public DefaultWorkspaceIndex(Path workspaceRoot) {
    this(new FileService(), new SymbolService(), workspaceRoot);
  }

  public DefaultWorkspaceIndex(FileService fileService, SymbolService symbolService) {
    this(fileService, symbolService, null);
  }

  public DefaultWorkspaceIndex(
      FileService fileService, SymbolService symbolService, Path workspaceRoot) {
    this.fileService = Objects.requireNonNull(fileService, "fileService must not be null");
    this.symbolService = Objects.requireNonNull(symbolService, "symbolService must not be null");
    this.workspaceRoot = workspaceRoot != null ? workspaceRoot.toAbsolutePath().normalize() : null;
  }

  @Override
  public TokenizedFile resolveFile(URI uri, String optionalCode) {
    if (optionalCode != null && !optionalCode.isBlank()) {
      if (uri != null) {
        validateUriWithinWorkspace(uri);
        URI normUri = safeNormalize(uri);
        updateFileContents(normUri, optionalCode);
        inMemoryBuffers.add(normUri);
        return getFileContents(normUri);
      } else {
        CA65Scanner scanner = new CA65Scanner();
        TokenizedFile snippet = scanner.tokenize(optionalCode);
        snippet.uri = URI.create("snippet:///inline.s");
        return snippet;
      }
    } else if (uri != null) {
      validateUriWithinWorkspace(uri);
      URI normUri = safeNormalize(uri);
      TokenizedFile existing = files.get(normUri);
      if (existing != null && existing != TokenizedFile.EMPTY) {
        return existing;
      }

      // On-demand indexing of parent directory if not yet loaded
      try {
        Path path = Path.of(normUri).toAbsolutePath().normalize();
        Path parent = Files.isDirectory(path) ? path : path.getParent();
        if (parent != null && !indexedDirectories.contains(parent)) {
          includeDir(parent.toUri());
        }
      } catch (Exception e) {
        LOG.log(Level.FINE, "Failed on-demand parent directory indexing for " + normUri, e);
      }

      existing = files.get(normUri);
      if (existing != null && existing != TokenizedFile.EMPTY) {
        return existing;
      }

      try {
        TokenizedFile loaded = fileService.readLines(normUri);
        if (loaded != null && loaded != TokenizedFile.EMPTY) {
          files.put(normUri, loaded);
          symbolService.extractDefinitions(loaded);
          return loaded;
        }
      } catch (IOException e) {
        LOG.log(Level.FINE, "Failed to read file from disk for " + normUri, e);
      }
      return TokenizedFile.EMPTY;
    }
    return TokenizedFile.EMPTY;
  }

  @Override
  public TokenizedFile getFileContents(URI uri) {
    if (uri == null) {
      return TokenizedFile.EMPTY;
    }
    URI normUri = safeNormalize(uri);
    TokenizedFile file = files.get(normUri);
    if (file != null && file != TokenizedFile.EMPTY) {
      return file;
    }

    // Try fallback to FileService
    try {
      TokenizedFile loaded = fileService.readLines(normUri);
      if (loaded != null && loaded != TokenizedFile.EMPTY) {
        files.put(normUri, loaded);
        symbolService.extractDefinitions(loaded);
        return loaded;
      }
    } catch (IOException e) {
      LOG.log(Level.FINE, "Could not load file from disk for " + normUri, e);
    }

    return TokenizedFile.EMPTY;
  }

  @Override
  public synchronized void updateFileContents(URI uri, String text) {
    if (uri == null || text == null) {
      return;
    }
    URI normUri = safeNormalize(uri);
    try {
      TokenizedFile tokenized = new CA65Scanner().tokenize(text);
      tokenized.uri = normUri;
      files.put(normUri, tokenized);
      symbolService.extractDefinitions(tokenized);
    } catch (Exception e) {
      LOG.log(Level.WARNING, "Failed to tokenize and update file contents for " + normUri, e);
    }
  }

  @Override
  public synchronized void closeBuffer(URI uri) {
    if (uri == null) {
      return;
    }
    URI normUri = safeNormalize(uri);
    inMemoryBuffers.remove(normUri);
    try {
      TokenizedFile onDisk = fileService.readLines(normUri);
      if (onDisk != null && onDisk != TokenizedFile.EMPTY) {
        files.put(normUri, onDisk);
        symbolService.extractDefinitions(onDisk);
      } else {
        files.remove(normUri);
        symbolService.removeFile(normUri);
      }
    } catch (IOException e) {
      files.remove(normUri);
      symbolService.removeFile(normUri);
    }
  }

  @Override
  public synchronized void removeFile(URI uri) {
    if (uri == null) {
      return;
    }
    URI normUri = safeNormalize(uri);
    files.remove(normUri);
    inMemoryBuffers.remove(normUri);
    symbolService.removeFile(normUri);
  }

  @Override
  public synchronized void refreshFileContents(URI uri) {
    if (uri == null) {
      return;
    }
    URI normUri = safeNormalize(uri);
    try {
      TokenizedFile tokenized = fileService.readLines(normUri);
      if (tokenized != null && tokenized != TokenizedFile.EMPTY) {
        files.put(normUri, tokenized);
        symbolService.extractDefinitions(tokenized);
      }
    } catch (IOException e) {
      LOG.log(Level.SEVERE, e.getMessage(), e);
    }
  }

  @Override
  public void includeDir(URI directory) {
    if (directory == null) {
      return;
    }
    validateUriWithinWorkspace(directory);
    URI normDir = safeNormalize(directory);
    this.fileService.addSearchPath(normDir);
    _includeDir(normDir);
  }

  private void _includeDir(URI directory) {
    if (!visitedDirs.add(directory)) {
      return;
    }

    Path workspacePath;
    try {
      workspacePath = Path.of(directory);
    } catch (Exception e) {
      LOG.log(Level.FINE, "Failed to resolve path for directory: " + directory, e);
      return;
    }

    File dirFile = workspacePath.toFile();
    if (!dirFile.exists() || !dirFile.isDirectory()) {
      return;
    }

    indexedDirectories.add(workspacePath);
    if (directoryIndexedListener != null) {
      try {
        directoryIndexedListener.accept(workspacePath);
      } catch (Exception e) {
        LOG.log(Level.WARNING, "Error in directory indexed listener for " + workspacePath, e);
      }
    }

    File[] matchingFiles =
        dirFile.listFiles(
            new FileFilter() {
              @Override
              public boolean accept(File pathname) {
                String name = pathname.getName().toLowerCase(Locale.ROOT);
                return name.endsWith(".s")
                    || name.endsWith(".sgs")
                    || name.endsWith(".i")
                    || name.endsWith(".inc");
              }
            });

    if (matchingFiles != null) {
      List<URI> includeDirsToScan = new ArrayList<>();
      for (File file : matchingFiles) {
        URI fileUri = safeNormalize(file.toPath().toUri());
        if (!files.containsKey(fileUri)) {
          try {
            TokenizedFile tokenized = fileService.readLines(fileUri);
            if (tokenized != null && tokenized != TokenizedFile.EMPTY) {
              files.put(fileUri, tokenized);
              symbolService.extractDefinitions(tokenized);

              tokenized.forEach(
                  (line, tokens) -> {
                    if (Util.isIncludeDirective(tokens.line().trim())
                        && tokens.tokens().size() > 1) {
                      String includeTarget = tokens.tokens().get(1).text().replace("\"", "").trim();
                      for (URI foundUri : fileService.find(URI.create("./" + includeTarget))) {
                        File f = new File(foundUri);
                        if (f.exists() && f.getParentFile() != null) {
                          includeDirsToScan.add(f.getParentFile().getAbsoluteFile().toURI());
                        }
                      }
                    }
                  });
            }
          } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to read lines for " + fileUri, e);
          }
        }
      }

      for (URI incDir : includeDirsToScan) {
        includeDir(incDir);
      }
    }

    File[] subDirs = dirFile.listFiles(File::isDirectory);
    if (subDirs != null) {
      Arrays.stream(subDirs)
          .filter(
              dir ->
                  !dir.getName().startsWith(".")
                      && !dir.getName().equals("node_modules")
                      && !dir.getName().equals("target")
                      && !dir.getName().equals("dist")
                      && !dir.getName().equals("out")
                      && !dir.getName().equals("build")
                      && !dir.getName().equals("tools")
                      && !dir.getName().equals("build_tools"))
          .map(File::toURI)
          .forEach(this::_includeDir);
    }
  }

  @Override
  public void validateUriWithinWorkspace(URI uri) {
    if (uri == null) {
      return;
    }
    String uriStr = uri.toString();
    if (uriStr.startsWith("snippet:")
        || uriStr.startsWith("file:///snippet")
        || !uri.isAbsolute()
        || !"file".equalsIgnoreCase(uri.getScheme())) {
      return;
    }
    if (workspaceRoot != null) {
      Path target = Path.of(uri).toAbsolutePath().normalize();
      if (!target.startsWith(workspaceRoot)) {
        throw new SecurityException(
            "Access denied: URI '"
                + uri
                + "' is outside the configured workspace boundary: '"
                + workspaceRoot
                + "'");
      }
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

  @Override
  public Location resolveLocation(String symbol, URI callingUri, Integer line) {
    if (symbol == null || symbol.isBlank()) {
      return null;
    }
    Location loc = null;
    if (callingUri != null && line != null) {
      loc = symbolService.getLocation(symbol, callingUri, line);
    }
    if (loc == null && callingUri != null) {
      loc = symbolService.getLocation(symbol, callingUri, 1);
    }
    if (loc == null) {
      loc = symbolService.getLocation(symbol);
    }
    return loc;
  }

  @Override
  public List<Location> resolveLocations(String symbol, URI callingUri, Integer line) {
    if (symbol == null || symbol.isBlank()) {
      return List.of();
    }
    List<Location> locs = null;
    if (callingUri != null && line != null) {
      locs = symbolService.getLocations(symbol, callingUri, line);
    }
    if (locs == null || locs.isEmpty()) {
      locs = symbolService.getLocations(symbol);
    }
    return locs != null ? locs : List.of();
  }

  @Override
  public String resolveDocumentation(String symbol, URI callingUri, Integer line) {
    if (symbol == null || symbol.isBlank()) {
      return null;
    }
    String doc = null;
    if (callingUri != null && line != null) {
      doc = symbolService.getDocumentation(symbol, callingUri, line);
    }
    if (doc == null || doc.isBlank()) {
      doc = symbolService.getDocumentation(symbol);
    }
    return doc;
  }

  @Override
  public Optional<Scope> getScopeAt(URI uri, int line) {
    if (uri == null) {
      return Optional.empty();
    }
    return symbolService.getScopeAt(uri, line);
  }

  private static final Set<String> STORE_OPCODES = Set.of("stw", "stb", "sta", "stx", "sty", "stz");
  private static final Set<String> READ_OPCODES =
      Set.of(
          "ldw", "ldb", "lda", "ldx", "ldy", "iwt", "move", "with", "from", "to", "adc", "sbc",
          "cmp", "cpx", "cpy", "and", "or", "ora", "xor", "eor", "bit");
  private static final Set<String> CALL_OPCODES = Set.of("call", "jsr", "bsr", "jsl");

  @Override
  public List<dev.secondsun.retro.util.vo.SymbolReference> findDetailedReferences(
      String symbol, URI callingUri, Integer line, boolean includeDefinitions) {
    if (symbol == null || symbol.isBlank()) {
      return List.of();
    }
    String trimmedSymbol = symbol.trim();
    String baseSymbol =
        trimmedSymbol.endsWith("_function")
            ? trimmedSymbol.substring(0, trimmedSymbol.length() - 9)
            : trimmedSymbol;
    String funcSymbol = baseSymbol + "_function";

    Location targetDef = null;
    if (callingUri != null && line != null) {
      targetDef = resolveLocation(trimmedSymbol, callingUri, line);
      if (targetDef == null && trimmedSymbol.endsWith("_function")) {
        targetDef = resolveLocation(baseSymbol, callingUri, line);
      }
      if (targetDef == null) {
        targetDef = resolveLocation(trimmedSymbol, null, null);
      }
    }

    List<dev.secondsun.retro.util.vo.SymbolReference> results = new ArrayList<>();

    // Iterate over snapshot of indexed files
    Map<URI, TokenizedFile> fileMap = new LinkedHashMap<>(files);
    for (Map.Entry<URI, TokenizedFile> entry : fileMap.entrySet()) {
      URI fileUri = entry.getKey();
      TokenizedFile tf = entry.getValue();
      if (tf == null || tf.isEmpty()) {
        continue;
      }

      for (int lineIdx = 0; lineIdx < tf.textLines(); lineIdx++) {
        var tokensLine = tf.getLine(lineIdx);
        if (tokensLine == null) {
          continue;
        }
        List<Token> tokenList = tokensLine.tokens();
        if (tokenList == null || tokenList.isEmpty()) {
          continue;
        }

        Token first = tokenList.get(0);
        boolean isFn = FunctionSyntaxHelper.isFunction(tokenList);
        boolean isCall = FunctionSyntaxHelper.isCall(tokenList);
        boolean isMacroDef =
            first.type == TokenType.TOK_MACRO || ".macro".equalsIgnoreCase(first.text());
        boolean isProcDef =
            first.type == TokenType.TOK_PROC || ".proc".equalsIgnoreCase(first.text());
        boolean isStructDef =
            first.type == TokenType.TOK_STRUCT || ".struct".equalsIgnoreCase(first.text());
        boolean isEnumDef =
            first.type == TokenType.TOK_ENUM || ".enum".equalsIgnoreCase(first.text());
        boolean isScopeDef =
            first.type == TokenType.TOK_SCOPE || ".scope".equalsIgnoreCase(first.text());
        boolean isRegister =
            first.type == TokenType.TOK_REGISTER_KEYWORD
                || "register".equalsIgnoreCase(first.text());
        boolean isInclude = Util.isIncludeDirective(tokensLine.line().trim());
        boolean isDefine =
            first.type == TokenType.TOK_DEFINE || ".define".equalsIgnoreCase(first.text());

        // Determine main opcode mnemonic on this line (after optional label)
        String statementOpcode = null;
        int mnemonicIdx = -1;
        if (isCall) {
          statementOpcode = "call";
          mnemonicIdx = 0;
        } else {
          for (int i = 0; i < tokenList.size(); i++) {
            Token cur = tokenList.get(i);
            if (i + 1 < tokenList.size() && tokenList.get(i + 1).type == TokenType.TOK_COLON) {
              i++; // skip label
              continue;
            }
            statementOpcode = cur.text().toLowerCase(Locale.ROOT);
            mnemonicIdx = i;
            break;
          }
        }

        // Get innermost scope for struct check
        var currentScopeOpt = symbolService.getScopeAt(fileUri, lineIdx);

        for (int i = 0; i < tokenList.size(); i++) {
          Token t = tokenList.get(i);
          if (t.type == TokenType.TOK_STRCON
              || t.type == TokenType.TOK_CHARCON
              || t.type == TokenType.TOK_NONE
              || t.type == TokenType.TOK_EOF
              || t.type == TokenType.TOK_SEP) {
            continue;
          }

          boolean isQualifiedMember =
              (i >= 2 && tokenList.get(i - 1).type == TokenType.TOK_NAMESPACE);
          String qualifier = isQualifiedMember ? tokenList.get(i - 2).text() : null;

          boolean nameMatches = false;
          if (trimmedSymbol.contains("::")) {
            if (isQualifiedMember) {
              String fullQualified = qualifier + "::" + t.text();
              nameMatches = fullQualified.equals(trimmedSymbol);
            }
          } else {
            String text = t.text();
            nameMatches =
                text.equals(trimmedSymbol) || text.equals(funcSymbol) || text.equals(baseSymbol);
          }

          if (!nameMatches) {
            continue;
          }

          // Determine if this token is a definition
          boolean isDef = false;
          if (isFn) {
            if (tokenList.size() > 1 && (t == tokenList.get(1) || i == 1)) {
              isDef = true;
            } else {
              var fnDecl = FunctionSyntaxHelper.parseFunction(tokenList);
              for (Token p : fnDecl.parameters()) {
                if (t == p
                    || (t.getStartIndex() == p.getStartIndex() && t.text().equals(p.text()))) {
                  isDef = true;
                  break;
                }
              }
              if (fnDecl.returnVariable().isPresent() && fnDecl.returnVariable().get() == t) {
                isDef = true;
              }
            }
          } else if (isProcDef || isStructDef || isEnumDef || isScopeDef || isDefine) {
            if (tokenList.size() > 1 && (t == tokenList.get(1) || i == 1)) {
              isDef = true;
            }
          } else if (isMacroDef) {
            if (tokenList.size() > 1 && (t == tokenList.get(1) || i == 1)) {
              isDef = true;
            } else if (i > 1 && t.type == TokenType.TOK_IDENT) {
              isDef = true;
            }
          } else if (isRegister) {
            isDef = true;
          } else if (i + 1 < tokenList.size()
              && tokenList.get(i + 1).type == TokenType.TOK_COLON
              && (mnemonicIdx == -1 || i < mnemonicIdx)) {
            isDef = true;
          } else if (i + 1 < tokenList.size()
              && (tokenList.get(i + 1).type == TokenType.TOK_EQ
                  || tokenList.get(i + 1).type == TokenType.TOK_ASSIGN)
              && i == 0) {
            isDef = true;
          } else if (currentScopeOpt.isPresent()
              && currentScopeOpt.get().type() == dev.secondsun.retro.util.vo.ScopeType.STRUCT
              && i == 0) {
            isDef = true;
          }

          if (isDef && !includeDefinitions) {
            continue;
          }

          // Determine kind
          String kind;
          if (isDef) {
            kind = "definition";
          } else if (isCall && (i == 1 || (isQualifiedMember && i == 2))) {
            kind = "call";
          } else if (isInclude) {
            kind = "include";
          } else if (statementOpcode != null
              && STORE_OPCODES.contains(statementOpcode)
              && i != mnemonicIdx) {
            kind = "write";
          } else if (statementOpcode != null
              && READ_OPCODES.contains(statementOpcode)
              && i != mnemonicIdx) {
            kind = "read";
          } else if (statementOpcode != null
              && CALL_OPCODES.contains(statementOpcode)
              && i != mnemonicIdx) {
            kind = "call";
          } else if (i == mnemonicIdx) {
            var scope = symbolService.getGlobalScope().findChildScope(t.text());
            if (scope != null && scope.type() == dev.secondsun.retro.util.vo.ScopeType.MACRO) {
              kind = "macro-invocation";
            } else {
              Location loc = symbolService.getLocation(t.text());
              if (loc != null) {
                var sOpt = symbolService.getScopeAt(loc.filename(), loc.line());
                if (sOpt.isPresent()
                    && sOpt.get().type() == dev.secondsun.retro.util.vo.ScopeType.MACRO) {
                  kind = "macro-invocation";
                } else {
                  kind = "use";
                }
              } else {
                kind = "use";
              }
            }
          } else {
            kind = "use";
          }

          // Disambiguation / scoping check if targetDef is present
          if (targetDef != null) {
            Location resolved;
            if (isQualifiedMember) {
              resolved = resolveLocation(qualifier + "::" + t.text(), fileUri, lineIdx);
            } else {
              resolved = resolveLocation(t.text(), fileUri, lineIdx);
              if (resolved == null && t.text().equals(funcSymbol)) {
                resolved = resolveLocation(baseSymbol, fileUri, lineIdx);
              }
            }
            if (resolved == null) {
              Location globalLoc = symbolService.getLocation(t.text());
              if (globalLoc != null && sameLocation(globalLoc, targetDef)) {
                resolved = globalLoc;
              }
            }
            if (resolved == null || !sameLocation(resolved, targetDef)) {
              continue;
            }
          }

          results.add(
              new dev.secondsun.retro.util.vo.SymbolReference(
                  fileUri, lineIdx, t.getStartIndex(), t.getEndIndex(), kind, tokensLine.line()));
        }
      }
    }

    return results;
  }

  private static boolean sameLocation(Location a, Location b) {
    if (a == null || b == null) {
      return false;
    }
    URI u1 = safeNormalize(a.filename());
    URI u2 = safeNormalize(b.filename());
    if (!Objects.equals(u1, u2)) {
      return false;
    }
    return a.line() == b.line();
  }

  @Override
  public void setDirectoryIndexedListener(Consumer<Path> listener) {
    this.directoryIndexedListener = listener;
  }

  @Override
  public Path getWorkspaceRoot() {
    return workspaceRoot;
  }

  @Override
  public FileService getFileService() {
    return fileService;
  }

  @Override
  public SymbolService getSymbolService() {
    return symbolService;
  }
}
