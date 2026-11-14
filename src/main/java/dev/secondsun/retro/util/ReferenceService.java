package dev.secondsun.retro.util;

import dev.secondsun.retro.util.sfx.SuperFxIsa;
import dev.secondsun.retro.util.vo.Location;
import dev.secondsun.retro.util.vo.OccurrenceIndex;
import dev.secondsun.retro.util.vo.ReferenceLocation;
import dev.secondsun.retro.util.vo.ReferenceResult;
import dev.secondsun.retro.util.vo.Scope;
import dev.secondsun.retro.util.vo.ScopeType;
import dev.secondsun.retro.util.vo.SymbolReference;
import dev.secondsun.retro.util.vo.TokenizedFile;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Package-private, lock-free symbol reference and cursor-symbol resolver operating on an immutable
 * {@link WorkspaceSnapshot} using per-file {@link OccurrenceIndex} tables (`O(MatchingFiles)`).
 */
final class ReferenceService {

  static final int DEFAULT_MAX_RESULTS = 50;

  static final List<String> LOW_PRIORITY_FRAGMENTS =
      List.of("/src/test/resources/", "/.build/", "/.build_libsfx/", "/build_tools/");

  private static final Set<String> STORE_OPCODES =
      Set.of("stw", "stb", "sm", "sms", "moveb", "movew", "sta", "stx", "sty", "stz");
  private static final Set<String> READ_OPCODES =
      Set.of(
          "ldw", "ldb", "lm", "lms", "lda", "ldx", "ldy", "iwt", "ibt", "lea", "move", "moves",
          "with", "from", "to", "adc", "sbc", "cmp", "cpx", "cpy", "and", "or", "ora", "xor", "eor",
          "bit");
  private static final Set<String> CALL_OPCODES = Set.of("call", "jsr", "bsr", "jsl");
  private static final Set<String> NON_SYMBOL_KEYWORDS =
      Set.of(
          "inline",
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

  private final WorkspaceSnapshot snapshot;

  ReferenceService(WorkspaceSnapshot snapshot) {
    this.snapshot = Objects.requireNonNull(snapshot, "snapshot must not be null");
  }

  static boolean isLowPriority(String uri) {
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

  static boolean isLowPriority(URI uri) {
    return uri != null && isLowPriority(uri.toString());
  }

  Optional<String> resolveSymbolAt(TokenizedFile file, int line, int column) {
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

      int tokIdx = lineTokens.indexOf(tok);
      if (FunctionSyntaxHelper.isFunction(lineTokens)
          && (tokIdx == 0 || (tokIdx == 1 && FunctionSyntaxHelper.isInlineFunction(lineTokens)))) {
        String fnName = FunctionSyntaxHelper.parseFunction(lineTokens).name();
        if (fnName != null && !fnName.isBlank()) {
          return Optional.of(fnName.trim());
        }
      }
      if (tokIdx == 0) {
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
        if (("function".equalsIgnoreCase(q) || "inline".equalsIgnoreCase(q))
            && FunctionSyntaxHelper.isFunction(lineTokens)) {
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

  List<SymbolReference> findDetailedReferencesAt(
      TokenizedFile file, URI uri, int line, int column, boolean includeDefinitions) {
    URI effectiveUri = normalizeUri(uri != null ? uri : (file != null ? file.uri() : null));
    TokenizedFile targetFile = file;
    if ((targetFile == null || targetFile.isEmpty()) && effectiveUri != null) {
      targetFile = snapshot.file(effectiveUri);
    }
    if (targetFile == null || targetFile.isEmpty() || line < 0 || line >= targetFile.textLines()) {
      return List.of();
    }
    if (effectiveUri == null) {
      effectiveUri = normalizeUri(targetFile.uri());
    }

    WorkspaceSnapshot activeSnap = snapshot;
    if (effectiveUri != null && snapshot.file(effectiveUri).isEmpty()) {
      activeSnap = snapshot.overlayFile(effectiveUri, targetFile);
    }
    ReferenceService activeRef = activeSnap == snapshot ? this : new ReferenceService(activeSnap);

    Optional<String> primarySymbol = activeRef.resolveSymbolAt(targetFile, line, column);
    if (primarySymbol.isPresent()) {
      List<SymbolReference> refs =
          activeRef.findDetailedReferences(
              primarySymbol.get(), effectiveUri, line, includeDefinitions);
      if (!refs.isEmpty()) {
        return refs;
      }
    }

    Optional<Token> tokenOpt = targetFile.getTokenAt(line, Math.max(0, column));
    if (tokenOpt.isPresent()) {
      Token tok = tokenOpt.get();
      String text = tok.text() != null ? tok.text().trim() : "";
      if (!text.isEmpty()
          && (primarySymbol.isEmpty() || !text.equals(primarySymbol.get()))
          && !NON_SYMBOL_KEYWORDS.contains(text.toLowerCase(Locale.ROOT))
          && !activeRef.isUnboundInstructionOrRegister(text, tok, effectiveUri, line)) {
        return activeRef.findDetailedReferences(text, effectiveUri, line, includeDefinitions);
      }
    }

    return List.of();
  }

  List<Location> findReferencesAt(
      TokenizedFile file, URI uri, int line, int column, boolean includeDefinitions) {
    return findDetailedReferencesAt(file, uri, line, column, includeDefinitions).stream()
        .map(SymbolReference::toLocation)
        .toList();
  }

  List<SymbolReference> findDetailedReferences(
      String symbol, URI callingUri, Integer line, boolean includeDefinitions) {
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
    URI normCallingUri = normalizeUri(callingUri);

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
          findInnerScopeWithDefinition(trimmedSymbol, baseSymbol, normCallingUri, line);
      if (targetInnerScope != null) {
        targetInnerDef = targetInnerScope.getLocalDefinition(trimmedSymbol);
        if (targetInnerDef == null) {
          targetInnerDef = targetInnerScope.getLocalDefinition(baseSymbol);
        }
      } else {
        TokenizedFile callingFile = snapshot.file(normCallingUri);
        String lineQualifier = findQualifierOnLine(callingFile, line, trimmedSymbol);
        if (lineQualifier != null) {
          explicitQualifier = lineQualifier;
          explicitMember = trimmedSymbol;
        }
      }
    }

    Set<String> candidateNames = new LinkedHashSet<>();
    if (explicitQualifier != null && explicitMember != null) {
      candidateNames.add(explicitMember);
    } else {
      candidateNames.add(trimmedSymbol);
      candidateNames.add(baseSymbol);
      candidateNames.add(funcSymbol);
    }

    List<SymbolReference> results = new ArrayList<>();

    for (Map.Entry<URI, TokenizedFile> entry : snapshot.effectiveFiles().entrySet()) {
      URI fileUri = entry.getKey();
      TokenizedFile tf = entry.getValue();
      if (tf == null || tf.isEmpty()) {
        continue;
      }

      // O(1) per-file OccurrenceIndex probe: skip files that do not mention any candidate token!
      OccurrenceIndex occIndex = tf.occurrenceIndex();
      TreeSet<Integer> candidateLines = null;
      for (String cand : candidateNames) {
        List<OccurrenceIndex.Occurrence> hits = occIndex.lookup(cand);
        if (!hits.isEmpty()) {
          if (candidateLines == null) {
            candidateLines = new TreeSet<>();
          }
          for (OccurrenceIndex.Occurrence hit : hits) {
            candidateLines.add(hit.line());
          }
        }
      }
      if (candidateLines == null) {
        continue;
      }

      var scopeTree = tf.scopeTree();

      for (int lineIdx : candidateLines) {
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
        boolean isInlineFn = isFn && FunctionSyntaxHelper.isInlineFunction(tokenList);
        var parsedFn = isFn ? FunctionSyntaxHelper.parseFunction(tokenList) : null;
        boolean isCall = FunctionSyntaxHelper.isCall(tokenList);
        boolean isMacroDef =
            first.type == TokenType.TOK_MACRO
                || ".macro".equalsIgnoreCase(first.text())
                || ".mac".equalsIgnoreCase(first.text());
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

        String statementOpcode = null;
        int mnemonicIdx = -1;
        if (isCall) {
          statementOpcode = "call";
          mnemonicIdx = 0;
        } else {
          for (int i = 0; i < tokenList.size(); i++) {
            Token cur = tokenList.get(i);
            if (i + 1 < tokenList.size() && tokenList.get(i + 1).type == TokenType.TOK_COLON) {
              i++;
              continue;
            }
            statementOpcode = cur.text().toLowerCase(Locale.ROOT);
            mnemonicIdx = i;
            break;
          }
        }

        Scope currentScope = scopeTree.scopeAtLine(lineIdx);

        for (int i = 0; i < tokenList.size(); i++) {
          if (i == 0 && (isFn || isCall || isRegister)) {
            continue;
          }
          if (i == 1 && isInlineFn) {
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
            } else if (currentScope != null && explicitQualifier.equals(currentScope.name())) {
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

          boolean isDef = false;
          if (isFn && parsedFn != null) {
            if (parsedFn.nameToken() != null
                && t.getStartIndex() == parsedFn.nameToken().getStartIndex()
                && t.text().equals(parsedFn.nameToken().text())) {
              isDef = true;
            } else {
              for (Token p : parsedFn.parameters()) {
                if (t.getStartIndex() == p.getStartIndex() && t.text().equals(p.text())) {
                  isDef = true;
                  break;
                }
              }
              if (parsedFn.returnVariable().isPresent()
                  && t.getStartIndex() == parsedFn.returnVariable().get().getStartIndex()
                  && t.text().equals(parsedFn.returnVariable().get().text())) {
                isDef = true;
              }
            }
          } else if (isProcDef || isStructDef || isEnumDef || isScopeDef || isDefine) {
            if (tokenList.size() > 1 && i == 1) {
              isDef = true;
            }
          } else if (isMacroDef) {
            if (tokenList.size() > 1 && (i == 1 || (i > 1 && t.type == TokenType.TOK_IDENT))) {
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
          } else if (currentScope != null && currentScope.type() == ScopeType.STRUCT && i == 0) {
            isDef = true;
          }

          if (isDef && !includeDefinitions) {
            continue;
          }

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
            kind = snapshot.isMacro(t.text()) ? "macro-invocation" : "use";
          } else {
            kind = "use";
          }

          // Lexical scope disambiguation
          if (explicitQualifier != null && explicitMember != null) {
            // Filtered in nameMatches
          } else if (targetInnerScope != null) {
            if (isQualifiedMember) {
              if (targetInnerScope.name().isEmpty() || !targetInnerScope.name().equals(qualifier)) {
                continue;
              }
            } else {
              if (!targetInnerScope.contains(fileUri, lineIdx)) {
                continue;
              }
              Scope refInnerScope =
                  findInnerScopeWithDefinition(t.text(), baseSymbol, fileUri, lineIdx);
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
            if (isQualifiedMember) {
              if (qualifier != null && !qualifier.isEmpty()) {
                continue;
              }
            } else {
              Scope shadowingScope =
                  findInnerScopeWithDefinition(t.text(), baseSymbol, fileUri, lineIdx);
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

    results.sort(Comparator.comparingInt(ref -> isLowPriority(ref.filename()) ? 1 : 0));
    return results;
  }

  ReferenceResult find(
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
    WorkspaceSnapshot activeSnap =
        (code != null && !code.isBlank()) ? snapshot.overlay(uri, code) : snapshot;
    if (uri == null && code != null && !code.isBlank()) {
      uri = WorkspaceSnapshot.TRANSIENT_SNIPPET_URI;
    } else if (uri != null) {
      uri = normalizeUri(uri);
    }

    boolean includeDefs = Boolean.TRUE.equals(includeDefinitions);
    int ctxLines = contextLines != null ? contextLines : 0;

    List<Location> allDefs = activeSnap.resolveLocations(symbol, uri, line);
    int defCount = allDefs != null ? allDefs.size() : 0;
    boolean ambiguous = (line == null && defCount > 1);

    ReferenceService activeRef = activeSnap == snapshot ? this : new ReferenceService(activeSnap);
    List<SymbolReference> rawReferences =
        activeRef.findDetailedReferences(symbol, uri, line, includeDefs);

    List<ReferenceLocation> ordered = new ArrayList<>();
    for (SymbolReference ref : rawReferences) {
      ReferenceLocation rl = activeRef.toReferenceLocation(ref, ctxLines);
      if (rl != null) {
        ordered.add(rl);
      }
    }

    ordered.sort(Comparator.comparingInt(rl -> isLowPriority(rl.uri()) ? 1 : 0));

    if (pathFilter != null && !pathFilter.isBlank()) {
      String prefix = pathFilter.replaceFirst("^\\./", "");
      ordered.removeIf(rl -> rl.path() == null || !rl.path().startsWith(prefix));
    }

    int total = ordered.size();
    int cap = maxResults != null && maxResults > 0 ? maxResults : DEFAULT_MAX_RESULTS;
    List<ReferenceLocation> capped =
        total > cap ? List.copyOf(ordered.subList(0, cap)) : List.copyOf(ordered);

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
          Path root = snapshot.workspaceRoot();
          path =
              root != null && abs.startsWith(root)
                  ? root.relativize(abs).toString()
                  : abs.toString();
        }
      } catch (RuntimeException ignored) {
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

  private Scope findInnerScopeWithDefinition(String sym, String baseSym, URI uri, int line) {
    if (uri == null) {
      return null;
    }
    Scope curr = snapshot.getScopeAt(uri, line).orElse(null);
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
        || ".mac".equalsIgnoreCase(text)
        || ".scope".equalsIgnoreCase(text)
        || ".define".equalsIgnoreCase(text);
  }

  private boolean isUnboundInstructionOrRegister(String name, Token token, URI uri, int line) {
    if (uri != null && snapshot.resolveLocation(name, uri, line) != null) {
      return false;
    }
    if (snapshot.resolveLocation(name) != null || !snapshot.resolveLocations(name).isEmpty()) {
      return false;
    }
    if (SuperFxIsa.parsePhysicalRegister(name).isPresent()) {
      return true;
    }
    return token != null && SuperFxIsa.isMnemonic(token.text());
  }

  static URI normalizeUri(URI uri) {
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
    return Objects.equals(u1, u2) && a.line() == b.line();
  }
}
