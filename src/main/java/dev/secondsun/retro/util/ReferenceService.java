package dev.secondsun.retro.util;

import dev.secondsun.retro.util.instruction.GSUInstruction;
import dev.secondsun.retro.util.vo.Location;
import dev.secondsun.retro.util.vo.Scope;
import dev.secondsun.retro.util.vo.ScopeType;
import dev.secondsun.retro.util.vo.SymbolReference;
import dev.secondsun.retro.util.vo.TokenizedFile;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Service that resolves and classifies symbol references across a {@link WorkspaceIndex} for CA65
 * and X-GSU assembly sources.
 *
 * <p>Supports both symbol-name queries (used by MCP tools and static analysis) and cursor-position
 * queries (used by the LSP {@code textDocument/references} capability).
 */
public class ReferenceService {

  /** Default cap on the number of entries returned in {@link ReferenceResult#references()}. */
  public static final int DEFAULT_MAX_RESULTS = 50;

  /**
   * Path fragments identifying fixture copies and generated output that should never outrank real
   * sources when resolving references.
   */
  public static final List<String> LOW_PRIORITY_FRAGMENTS =
      List.of("/src/test/resources/", "/.build/", "/.build_libsfx/", "/build_tools/");

  private static final Set<String> STORE_OPCODES = Set.of("stw", "stb", "sta", "stx", "sty", "stz");
  private static final Set<String> READ_OPCODES =
      Set.of(
          "ldw", "ldb", "lda", "ldx", "ldy", "iwt", "move", "with", "from", "to", "adc", "sbc",
          "cmp", "cpx", "cpy", "and", "or", "ora", "xor", "eor", "bit");
  private static final Set<String> CALL_OPCODES = Set.of("call", "jsr", "bsr", "jsl");
  private static final Set<String> NON_SYMBOL_KEYWORDS =
      Set.of(
          "endfunction",
          "return",
          "register",
          "for",
          "endfor",
          "forr",
          "if_lt",
          "if_gt",
          "if_eq",
          "if_lte",
          "else",
          "endif");

  private final WorkspaceIndex workspaceIndex;

  /**
   * Constructs a {@code ReferenceService} backed by the given workspace index.
   *
   * @param workspaceIndex workspace index managing tokenized files and symbol scopes
   */
  public ReferenceService(WorkspaceIndex workspaceIndex) {
    this.workspaceIndex = Objects.requireNonNull(workspaceIndex, "workspaceIndex must not be null");
  }

  /**
   * A formatted symbol reference location with workspace-relative path and 1-based line number.
   *
   * @param uri canonical {@code file:///} URI
   * @param path workspace-relative path (absolute path if outside the workspace), or null
   * @param line zero-based line index
   * @param lineNumber one-based line number, matching editors, {@code grep -n} and {@code
   *     read_file}
   * @param startIndex start column (0-based)
   * @param endIndex end column (0-based)
   * @param kind reference kind ("definition", "call", "read", "write", "macro-invocation",
   *     "include", "use")
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

  /**
   * Result of a symbol reference search across the workspace.
   *
   * @param symbol the queried symbol name
   * @param totalReferences total count of references matching the query (before {@code maxResults}
   *     cap)
   * @param references list of reference locations up to {@code maxResults}
   * @param ambiguous whether multiple definitions were found without a disambiguating line context
   * @param definitionCount number of distinct definition sites found
   */
  public record ReferenceResult(
      String symbol,
      int totalReferences,
      List<ReferenceLocation> references,
      boolean ambiguous,
      int definitionCount) {}

  /**
   * Checks whether a URI string points to a low-priority fixture copy or build output directory.
   *
   * @param uri URI string to check
   * @return true if the URI contains any low-priority path fragment
   */
  public static boolean isLowPriority(String uri) {
    if (uri == null) {
      return false;
    }
    for (String fragment : LOW_PRIORITY_FRAGMENTS) {
      if (uri.contains(fragment)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Checks whether a URI points to a low-priority fixture copy or build output directory.
   *
   * @param uri URI to check
   * @return true if the URI contains any low-priority path fragment
   */
  public static boolean isLowPriority(URI uri) {
    return uri != null && isLowPriority(uri.toString());
  }

  /**
   * Resolves the symbol name at the given 0-based line and column in a tokenized file.
   *
   * @param file tokenized source file
   * @param line 0-based line index
   * @param column 0-based character column index
   * @return optional symbol string (may be qualified, e.g. {@code Point::coordX}), or empty if no
   *     symbol is present at the cursor
   */
  public Optional<String> resolveSymbolAt(TokenizedFile file, int line, int column) {
    if (file == null || file.isEmpty() || line < 0 || line >= file.textLines()) {
      return Optional.empty();
    }

    List<Token> lineTokens = file.getLineTokens(line);
    if (lineTokens == null || lineTokens.isEmpty()) {
      return Optional.empty();
    }

    int col = Math.max(0, column);
    Optional<Token> tokenOpt = file.getTokenAt(line, col);

    if (tokenOpt.isPresent()) {
      Token tok = tokenOpt.get();
      if (tok.type == TokenType.TOK_STRCON
          || tok.type == TokenType.TOK_CHARCON
          || tok.type == TokenType.TOK_INTCON
          || tok.type == TokenType.TOK_NONE
          || tok.type == TokenType.TOK_EOF
          || tok.type == TokenType.TOK_SEP) {
        return Optional.empty();
      }

      // If cursor is on the colon of a label definition (e.g. "my_label:"), resolve to the label
      if (tok.type == TokenType.TOK_COLON) {
        int idx = lineTokens.indexOf(tok);
        if (idx > 0) {
          Token prev = lineTokens.get(idx - 1);
          if (prev.type == TokenType.TOK_IDENT || prev.type == TokenType.TOK_LOCAL_IDENT) {
            return Optional.of(prev.text().trim());
          }
        }
        return Optional.empty();
      }

      // If cursor is on a declaration opener keyword (e.g. "function foo", ".proc bar"),
      // resolve to the declared symbol name
      if (tok == lineTokens.get(0)) {
        if (FunctionSyntaxHelper.isFunction(lineTokens)) {
          String fnName = FunctionSyntaxHelper.parseFunction(lineTokens).name();
          if (fnName != null && !fnName.isBlank()) {
            return Optional.of(fnName.trim());
          }
        }
        if (FunctionSyntaxHelper.isCall(lineTokens)) {
          var callStmt = FunctionSyntaxHelper.parseCall(lineTokens);
          if (callStmt != null
              && callStmt.targetName() != null
              && !callStmt.targetName().isBlank()) {
            return Optional.of(callStmt.targetName().trim());
          }
        }
        if (lineTokens.size() > 1 && isScopeOrDefineOpener(tok)) {
          String targetName = lineTokens.get(1).text();
          if (targetName != null && !targetName.isBlank()) {
            return Optional.of(targetName.trim());
          }
        }
      }

      String rawText = tok.text() != null ? tok.text().trim() : "";
      if (NON_SYMBOL_KEYWORDS.contains(rawText.toLowerCase(Locale.ROOT))) {
        return Optional.empty();
      }
    }

    Optional<String> qualified = file.getQualifiedSymbolAt(line, col);
    if (qualified.isPresent()) {
      String q = qualified.get().trim();
      if (!q.isEmpty() && !NON_SYMBOL_KEYWORDS.contains(q.toLowerCase(Locale.ROOT))) {
        if ("function".equalsIgnoreCase(q) && FunctionSyntaxHelper.isFunction(lineTokens)) {
          String fnName = FunctionSyntaxHelper.parseFunction(lineTokens).name();
          if (fnName != null && !fnName.isBlank()) {
            return Optional.of(fnName.trim());
          }
        }
        if ("call".equalsIgnoreCase(q) && FunctionSyntaxHelper.isCall(lineTokens)) {
          var callStmt = FunctionSyntaxHelper.parseCall(lineTokens);
          if (callStmt != null
              && callStmt.targetName() != null
              && !callStmt.targetName().isBlank()) {
            return Optional.of(callStmt.targetName().trim());
          }
        }
        if (!isUnboundInstructionOrRegister(q, tokenOpt.orElse(null), file.uri(), line)) {
          return Optional.of(q);
        }
      }
    }

    if (tokenOpt.isPresent()) {
      Token tok = tokenOpt.get();
      String text = tok.text() != null ? tok.text().trim() : "";
      if (!text.isEmpty()
          && !NON_SYMBOL_KEYWORDS.contains(text.toLowerCase(Locale.ROOT))
          && !isUnboundInstructionOrRegister(text, tok, file.uri(), line)) {
        return Optional.of(text);
      }
    }

    return Optional.empty();
  }

  /**
   * Finds all detailed symbol references at the given cursor position in a file.
   *
   * @param file tokenized file content (may be null if {@code uri} can be resolved from index)
   * @param uri file URI
   * @param line 0-based cursor line index
   * @param column 0-based cursor column index
   * @param includeDefinitions whether to include symbol definitions in the returned list
   * @return list of detailed symbol references
   */
  public List<SymbolReference> findDetailedReferencesAt(
      TokenizedFile file, URI uri, int line, int column, boolean includeDefinitions) {
    URI effectiveUri = uri != null ? uri : (file != null ? file.uri() : null);
    TokenizedFile targetFile = file;
    if ((targetFile == null || targetFile.isEmpty()) && effectiveUri != null) {
      targetFile = workspaceIndex.resolveFile(effectiveUri, null);
    }
    if (targetFile == null || targetFile.isEmpty() || line < 0 || line >= targetFile.textLines()) {
      return List.of();
    }
    if (effectiveUri == null) {
      effectiveUri = targetFile.uri();
    }

    Optional<String> primarySymbol = resolveSymbolAt(targetFile, line, column);
    if (primarySymbol.isPresent()) {
      List<SymbolReference> refs =
          findDetailedReferencesWithFile(
              primarySymbol.get(), effectiveUri, line, includeDefinitions, targetFile);
      if (!refs.isEmpty()) {
        return refs;
      }
    }

    // Fallback to raw token text at cursor (e.g. when cursor is on the scope prefix of a qualified
    // symbol like Point::coordX and searching for the scope name itself)
    Optional<Token> tokenOpt = targetFile.getTokenAt(line, Math.max(0, column));
    if (tokenOpt.isPresent()) {
      Token tok = tokenOpt.get();
      String text = tok.text() != null ? tok.text().trim() : "";
      if (!text.isEmpty()
          && (primarySymbol.isEmpty() || !text.equals(primarySymbol.get()))
          && !NON_SYMBOL_KEYWORDS.contains(text.toLowerCase(Locale.ROOT))
          && !isUnboundInstructionOrRegister(text, tok, effectiveUri, line)) {
        return findDetailedReferencesWithFile(
            text, effectiveUri, line, includeDefinitions, targetFile);
      }
    }

    return List.of();
  }

  /**
   * Finds all reference locations at the given cursor position in a file.
   *
   * @param file tokenized file content (may be null if {@code uri} can be resolved from index)
   * @param uri file URI
   * @param line 0-based cursor line index
   * @param column 0-based cursor column index
   * @param includeDefinitions whether to include symbol definitions in the returned list
   * @return list of reference {@link Location}s
   */
  public List<Location> findReferencesAt(
      TokenizedFile file, URI uri, int line, int column, boolean includeDefinitions) {
    return findDetailedReferencesAt(file, uri, line, column, includeDefinitions).stream()
        .map(SymbolReference::toLocation)
        .toList();
  }

  /**
   * Finds all detailed references for a symbol name across the workspace index.
   *
   * @param symbol symbol identifier to search for
   * @param callingUri optional contextual file URI for lexical scope disambiguation
   * @param line optional 0-based line number for lexical scope disambiguation
   * @param includeDefinitions whether to include definition sites in the results
   * @return list of detailed symbol references ordered with real sources before low-priority paths
   */
  public List<SymbolReference> findDetailedReferences(
      String symbol, URI callingUri, Integer line, boolean includeDefinitions) {
    return findDetailedReferencesWithFile(symbol, callingUri, line, includeDefinitions, null);
  }

  private List<SymbolReference> findDetailedReferencesWithFile(
      String symbol,
      URI callingUri,
      Integer line,
      boolean includeDefinitions,
      TokenizedFile fallbackFile) {
    if (symbol == null || symbol.isBlank()) {
      return List.of();
    }
    String trimmedSymbol = symbol.trim();
    if (trimmedSymbol.startsWith("::")) {
      trimmedSymbol = trimmedSymbol.substring(2).trim();
    }
    if (trimmedSymbol.isEmpty()) {
      return List.of();
    }

    String baseSymbol =
        trimmedSymbol.endsWith("_function")
            ? trimmedSymbol.substring(0, trimmedSymbol.length() - 9)
            : trimmedSymbol;
    String funcSymbol = baseSymbol + "_function";

    SymbolService symbolService = workspaceIndex.getSymbolService();
    URI normCallingUri = normalizeUri(callingUri);

    // Ensure fallbackFile is registered in SymbolService if not yet indexed
    if (fallbackFile != null && !fallbackFile.isEmpty() && normCallingUri != null) {
      if (symbolService.getFileScope(normCallingUri) == null) {
        if (fallbackFile.uri == null) {
          fallbackFile.uri = normCallingUri;
        }
        symbolService.extractDefinitions(fallbackFile);
      }
    }

    // Determine whether the symbol at (callingUri, line) belongs to an inner lexical scope
    // (FUNCTION, PROC, STRUCT, ENUM, MACRO, SCOPE) vs a top-level (FILE / GLOBAL) symbol.
    Scope targetInnerScope = null;
    Location targetInnerDef = null;
    String explicitQualifier = null;
    String explicitMember = null;

    if (trimmedSymbol.contains("::")) {
      int colonIdx = trimmedSymbol.indexOf("::");
      explicitQualifier = trimmedSymbol.substring(0, colonIdx);
      explicitMember = trimmedSymbol.substring(colonIdx + 2);
    } else if (normCallingUri != null && line != null) {
      targetInnerScope =
          findInnerScopeWithDefinition(
              symbolService, trimmedSymbol, baseSymbol, normCallingUri, line);
      if (targetInnerScope != null) {
        targetInnerDef = targetInnerScope.getLocalDefinition(trimmedSymbol);
        if (targetInnerDef == null) {
          targetInnerDef = targetInnerScope.getLocalDefinition(baseSymbol);
        }
      } else {
        // Check if on the calling line itself the symbol was written as qualifier::symbol
        TokenizedFile callingFile =
            fallbackFile != null ? fallbackFile : workspaceIndex.getFileContents(normCallingUri);
        String lineQualifier = findQualifierOnLine(callingFile, line, trimmedSymbol);
        if (lineQualifier != null) {
          explicitQualifier = lineQualifier;
          explicitMember = trimmedSymbol;
        }
      }
    }

    Map<URI, TokenizedFile> fileMap = new LinkedHashMap<>();
    if (workspaceIndex instanceof DefaultWorkspaceIndex dwi) {
      fileMap.putAll(dwi.getIndexedFiles());
    }
    if (fallbackFile != null && !fallbackFile.isEmpty() && normCallingUri != null) {
      fileMap.putIfAbsent(normCallingUri, fallbackFile);
    }

    List<SymbolReference> results = new ArrayList<>();

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

        var currentScopeOpt = symbolService.getScopeAt(fileUri, lineIdx);

        for (int i = 0; i < tokenList.size(); i++) {
          // Skip statement opener keywords themselves
          if (i == 0 && (isFn || isCall || isRegister)) {
            continue;
          }

          Token t = tokenList.get(i);
          if (t.type == TokenType.TOK_STRCON
              || t.type == TokenType.TOK_CHARCON
              || t.type == TokenType.TOK_NONE
              || t.type == TokenType.TOK_EOF
              || t.type == TokenType.TOK_SEP
              || t.type == TokenType.TOK_NAMESPACE
              || t.type == TokenType.TOK_COLON) {
            continue;
          }

          boolean isQualifiedMember =
              (i >= 2 && tokenList.get(i - 1).type == TokenType.TOK_NAMESPACE);
          String qualifier = isQualifiedMember ? tokenList.get(i - 2).text() : null;

          boolean nameMatches = false;
          if (explicitQualifier != null && explicitMember != null) {
            if (isQualifiedMember) {
              nameMatches = explicitQualifier.equals(qualifier) && explicitMember.equals(t.text());
            } else if (currentScopeOpt.isPresent()
                && explicitQualifier.equals(currentScopeOpt.get().name())) {
              nameMatches = explicitMember.equals(t.text());
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
            boolean afterEquals =
                i >= 2
                    && (tokenList.get(i - 1).type == TokenType.TOK_EQ
                        || tokenList.get(i - 1).type == TokenType.TOK_ASSIGN
                        || "=".equals(tokenList.get(i - 1).text()));
            isDef = !afterEquals;
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
              && currentScopeOpt.get().type() == ScopeType.STRUCT
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
          } else if (isCall && (i == 1 || (isQualifiedMember && (i == 2 || i == 3)))) {
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
          } else if (!isCall
              && statementOpcode != null
              && CALL_OPCODES.contains(statementOpcode)
              && i != mnemonicIdx) {
            kind = "call";
          } else if (i == mnemonicIdx) {
            var scope = symbolService.getGlobalScope().findChildScope(t.text());
            if (scope != null && scope.type() == ScopeType.MACRO) {
              kind = "macro-invocation";
            } else {
              Location loc = symbolService.getLocation(t.text());
              if (loc != null) {
                var sOpt = symbolService.getScopeAt(loc.filename(), loc.line());
                if (sOpt.isPresent() && sOpt.get().type() == ScopeType.MACRO) {
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

          // Scope disambiguation
          if (explicitQualifier != null && explicitMember != null) {
            // Already filtered by explicitQualifier::explicitMember in nameMatches above
          } else if (targetInnerScope != null) {
            // Symbol at (callingUri, line) belongs to an inner lexical scope (e.g. FUNCTION,
            // STRUCT, PROC, MACRO, ENUM, SCOPE).
            if (isQualifiedMember) {
              if (targetInnerScope.name().isEmpty() || !targetInnerScope.name().equals(qualifier)) {
                continue;
              }
            } else {
              if (!targetInnerScope.contains(fileUri, lineIdx)) {
                continue;
              }
              Scope refInnerScope =
                  findInnerScopeWithDefinition(
                      symbolService, t.text(), baseSymbol, fileUri, lineIdx);
              if (refInnerScope != null && targetInnerDef != null) {
                Location refDef = refInnerScope.getLocalDefinition(t.text());
                if (refDef == null) {
                  refDef = refInnerScope.getLocalDefinition(baseSymbol);
                }
                if (refDef != null && !sameLocation(refDef, targetInnerDef)) {
                  continue;
                }
              }
            }
          } else if (normCallingUri != null && line != null) {
            // Symbol at (callingUri, line) is a top-level (FILE / GLOBAL) symbol.
            if (isQualifiedMember) {
              // A qualified member reference (e.g. structA::field) only matches a top-level
              // query if qualifier is empty.
              if (qualifier != null && !qualifier.isEmpty()) {
                continue;
              }
            } else {
              // Ensure the token at (fileUri, lineIdx) is not shadowed by a local definition in an
              // inner scope (e.g. a local parameter or local label with the same name).
              Scope shadowingScope =
                  findInnerScopeWithDefinition(
                      symbolService, t.text(), baseSymbol, fileUri, lineIdx);
              if (shadowingScope != null) {
                continue;
              }
            }
          }

          results.add(
              new SymbolReference(
                  fileUri, lineIdx, t.getStartIndex(), t.getEndIndex(), kind, tokensLine.line()));
        }
      }
    }

    // Stable sort: real sources first, fixture copies/generated output last.
    results.sort(Comparator.comparingInt(ref -> isLowPriority(ref.filename()) ? 1 : 0));
    return results;
  }

  /**
   * Finds all reference {@link Location}s for a symbol name across the workspace index.
   *
   * @param symbol symbol identifier to search for
   * @param callingUri optional contextual file URI
   * @param line optional 0-based line number
   * @param includeDefinitions whether to include definition sites in the results
   * @return list of matching {@link Location}s
   */
  public List<Location> findReferences(
      String symbol, URI callingUri, Integer line, boolean includeDefinitions) {
    return findDetailedReferences(symbol, callingUri, line, includeDefinitions).stream()
        .map(SymbolReference::toLocation)
        .toList();
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
   * @param maxResults maximum number of reference locations to return (default {@link
   *     #DEFAULT_MAX_RESULTS})
   * @param includeDefinitions whether to include definition sites in results
   * @param contextLines when {@code > 0}, includes the source line text for each reference
   * @return structured {@link ReferenceResult}
   */
  public ReferenceResult find(
      String symbol,
      String code,
      String uriStr,
      Integer line,
      String pathFilter,
      Integer maxResults,
      Boolean includeDefinitions,
      Integer contextLines) {
    if (symbol == null || symbol.isBlank()) {
      return new ReferenceResult("", 0, List.of(), false, 0);
    }

    URI uri = uriStr != null && !uriStr.isBlank() ? URI.create(uriStr) : null;
    TokenizedFile file = workspaceIndex.resolveFile(uri, code);
    if (file != null && file.uri != null) {
      uri = file.uri;
    }

    boolean includeDefs = Boolean.TRUE.equals(includeDefinitions);
    int ctxLines = contextLines != null ? contextLines : 0;

    List<Location> allDefs = workspaceIndex.resolveLocations(symbol, uri, line);
    int defCount = allDefs != null ? allDefs.size() : 0;
    boolean ambiguous = (line == null && defCount > 1);

    List<SymbolReference> rawReferences =
        findDetailedReferencesWithFile(symbol, uri, line, includeDefs, file);

    List<ReferenceLocation> ordered = new ArrayList<>();
    if (rawReferences != null) {
      for (SymbolReference ref : rawReferences) {
        ReferenceLocation rl = toReferenceLocation(ref, ctxLines);
        if (rl != null) {
          ordered.add(rl);
        }
      }
    }

    // Stable sort: real sources first, fixture copies/generated output last.
    ordered.sort(Comparator.comparingInt(rl -> isLowPriority(rl.uri()) ? 1 : 0));

    if (pathFilter != null && !pathFilter.isBlank()) {
      String prefix = pathFilter.replaceFirst("^\\./", "");
      ordered.removeIf(rl -> rl.path() == null || !rl.path().startsWith(prefix));
    }

    int total = ordered.size();
    int cap = maxResults != null && maxResults > 0 ? maxResults : DEFAULT_MAX_RESULTS;
    List<ReferenceLocation> capped =
        total > cap ? new ArrayList<>(ordered.subList(0, cap)) : ordered;

    return new ReferenceResult(symbol, total, capped, ambiguous, defCount);
  }

  private ReferenceLocation toReferenceLocation(SymbolReference ref, int contextLines) {
    if (ref == null) {
      return null;
    }
    String uriStr = null;
    String path = null;
    if (ref.filename() != null) {
      URI u = ref.filename();
      uriStr = u.toString();
      try {
        if ("file".equalsIgnoreCase(u.getScheme()) && u.getPath() != null) {
          Path abs = Path.of(u).toAbsolutePath().normalize();
          uriStr = abs.toUri().toString();
          Path root = workspaceIndex.getWorkspaceRoot();
          path =
              root != null && abs.startsWith(root)
                  ? root.relativize(abs).toString()
                  : abs.toString();
        }
      } catch (RuntimeException e) {
        // keep the raw URI string
      }
    }

    String text = null;
    if (contextLines > 0 && ref.lineText() != null) {
      text = ref.lineText().stripTrailing();
    }

    return new ReferenceLocation(
        uriStr,
        path,
        ref.line(),
        ref.line() + 1,
        ref.startIndex(),
        ref.endIndex(),
        ref.kind(),
        text);
  }

  private static Scope findInnerScopeWithDefinition(
      SymbolService symbolService, String sym, String baseSym, URI uri, int line) {
    if (symbolService == null || uri == null) {
      return null;
    }
    Scope curr = symbolService.getScopeAt(uri, line).orElse(null);
    while (curr != null) {
      if (curr.type() != ScopeType.FILE && curr.type() != ScopeType.GLOBAL) {
        Location loc = curr.getLocalDefinition(sym);
        if (loc == null && !sym.equals(baseSym)) {
          loc = curr.getLocalDefinition(baseSym);
        }
        if (loc != null) {
          return curr;
        }
      }
      curr = curr.parent();
    }
    return null;
  }

  private static String findQualifierOnLine(TokenizedFile file, int line, String memberName) {
    if (file == null || file.isEmpty() || line < 0 || line >= file.textLines()) {
      return null;
    }
    List<Token> tokens = file.getLineTokens(line);
    if (tokens == null || tokens.size() < 3) {
      return null;
    }
    for (int i = 2; i < tokens.size(); i++) {
      if (tokens.get(i - 1).type == TokenType.TOK_NAMESPACE
          && memberName.equals(tokens.get(i).text())) {
        return tokens.get(i - 2).text();
      }
    }
    return null;
  }

  private static boolean isScopeOrDefineOpener(Token first) {
    if (first == null || first.text() == null) {
      return false;
    }
    String text = first.text();
    return first.type == TokenType.TOK_PROC
        || first.type == TokenType.TOK_STRUCT
        || first.type == TokenType.TOK_ENUM
        || first.type == TokenType.TOK_MACRO
        || first.type == TokenType.TOK_SCOPE
        || first.type == TokenType.TOK_DEFINE
        || ".proc".equalsIgnoreCase(text)
        || ".struct".equalsIgnoreCase(text)
        || ".enum".equalsIgnoreCase(text)
        || ".macro".equalsIgnoreCase(text)
        || ".scope".equalsIgnoreCase(text)
        || ".define".equalsIgnoreCase(text);
  }

  private boolean isUnboundInstructionOrRegister(String name, Token token, URI uri, int line) {
    SymbolService symbolService = workspaceIndex.getSymbolService();
    if (symbolService != null) {
      if (uri != null && symbolService.getLocation(name, uri, line) != null) {
        return false;
      }
      if (symbolService.getLocation(name) != null || !symbolService.getLocations(name).isEmpty()) {
        return false;
      }
    }
    if (name.matches("(?i)r(\\d|1[0-5])")) {
      return true;
    }
    return token != null && GSUInstruction.isInstruction(token);
  }

  private static URI normalizeUri(URI uri) {
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

  private static boolean sameLocation(Location a, Location b) {
    if (a == null || b == null) {
      return false;
    }
    URI u1 = normalizeUri(a.filename());
    URI u2 = normalizeUri(b.filename());
    if (!Objects.equals(u1, u2)) {
      return false;
    }
    return a.line() == b.line();
  }
}
