package dev.secondsun.retro.util.vo;

import dev.secondsun.retro.util.FunctionSyntaxHelper;
import dev.secondsun.retro.util.Token;
import dev.secondsun.retro.util.TokenType;
import dev.secondsun.retro.util.sfx.MacroEffectSummary;
import dev.secondsun.retro.util.sfx.SuperFxIsa;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Immutable per-file lexical scope tree, symbol definition table, conditional arm hierarchy,
 * macro/define table, anonymous label table, and {@link DialectProfile} map.
 *
 * <p>Built lazily once per {@link TokenizedFile} and shared across syntax validators, workspace
 * snapshots, liveness analyzers, macro expanders, and LSP features.
 */
public final class ScopeTree {

  public static final ScopeTree EMPTY =
      new ScopeTree(
          new Scope("<empty>", ScopeType.FILE, null, 0, 0, null),
          List.of(),
          List.of(),
          List.of(),
          Map.of(),
          Map.of(),
          List.of(),
          new Scope[0],
          new DialectProfile[0],
          new boolean[0],
          Map.of());

  /** Symbol definition discovered in a lexical scope or conditional arm. */
  public record SymbolDef(
      String name,
      String qualifiedName,
      String kind,
      Token token,
      Location location,
      Scope scope,
      String cleanDoc,
      String rawDoc,
      boolean globalVisible) {}

  /** Sibling conditional assembly arm ({@code .if*}, {@code .elseif}, or {@code .else}). */
  public record ConditionalArm(
      String directive,
      int startLine,
      int endLine,
      List<SymbolDef> definitions,
      List<ConditionalBlock> nestedBlocks) {
    public ConditionalArm {
      definitions = definitions != null ? List.copyOf(definitions) : List.of();
      nestedBlocks = nestedBlocks != null ? List.copyOf(nestedBlocks) : List.of();
    }
  }

  /**
   * Conditional assembly construct containing mutually exclusive sibling {@link ConditionalArm}s.
   */
  public record ConditionalBlock(
      int startLine, int endLine, Scope enclosingScope, List<ConditionalArm> arms) {
    public ConditionalBlock {
      arms = arms != null ? List.copyOf(arms) : List.of();
    }
  }

  /** Unexpanded ca65 {@code .macro} definition extracted from this file. */
  public record MacroDefinition(
      String name,
      List<String> parameters,
      int startLine,
      int endLine,
      List<String> rawBodyLines,
      List<List<Token>> tokenizedBodyLines,
      URI uri) {
    public MacroDefinition {
      parameters = parameters != null ? List.copyOf(parameters) : List.of();
      rawBodyLines = rawBodyLines != null ? List.copyOf(rawBodyLines) : List.of();
      tokenizedBodyLines = tokenizedBodyLines != null ? List.copyOf(tokenizedBodyLines) : List.of();
    }
  }

  /** Anonymous label {@code :} definition site (0-based line and column). */
  public record AnonymousLabelDef(int lineIdx, int colStart) {}

  private static final Set<String> EXCLUSIVE_65816_MNEMONICS =
      Set.of(
          "lda",
          "ldx",
          "ldy",
          "sta",
          "stx",
          "sty",
          "stz",
          "jsr",
          "jsl",
          "jml",
          "rts",
          "rtl",
          "rti",
          "rep",
          "sep",
          "pha",
          "phx",
          "phy",
          "pla",
          "plx",
          "ply",
          "phb",
          "plb",
          "phd",
          "pld",
          "phk",
          "php",
          "plp",
          "tax",
          "tay",
          "txa",
          "tya",
          "tsx",
          "txs",
          "txy",
          "tyx",
          "xba",
          "xce",
          "mvn",
          "mvp",
          "pea",
          "pei",
          "per",
          "ora",
          "eor",
          "cpx",
          "cpy",
          "bit",
          "clc",
          "sec",
          "cli",
          "sei",
          "cld",
          "sed",
          "clv",
          "iny",
          "dey",
          "inx",
          "dex",
          "asl",
          "tsb",
          "trb",
          "wai",
          "stp",
          "cop",
          "brk",
          "rw",
          "rw_forced",
          "rw_push",
          "rw_pull");

  private final Scope fileScope;
  private final List<Scope> allScopes;
  private final List<SymbolDef> allDefinitions;
  private final List<ConditionalBlock> rootConditionals;
  private final Map<String, MacroDefinition> macros;
  private final Map<String, String> defines;
  private final List<String> includes;
  private final Scope[] lineScopes;
  private final DialectProfile[] lineDialects;
  private final boolean[] macroBodyLines;
  private final Map<Scope, List<AnonymousLabelDef>> anonymousLabelsByScope;
  private final Map<String, MacroEffectSummary> summaryCache = new ConcurrentHashMap<>();

  private ScopeTree(
      Scope fileScope,
      List<Scope> allScopes,
      List<SymbolDef> allDefinitions,
      List<ConditionalBlock> rootConditionals,
      Map<String, MacroDefinition> macros,
      Map<String, String> defines,
      List<String> includes,
      Scope[] lineScopes,
      DialectProfile[] lineDialects,
      boolean[] macroBodyLines,
      Map<Scope, List<AnonymousLabelDef>> anonymousLabelsByScope) {
    this.fileScope = fileScope;
    this.allScopes = List.copyOf(allScopes);
    this.allDefinitions = List.copyOf(allDefinitions);
    this.rootConditionals = List.copyOf(rootConditionals);
    this.macros = Collections.unmodifiableMap(macros);
    this.defines = Collections.unmodifiableMap(defines);
    this.includes = List.copyOf(includes);
    this.lineScopes = lineScopes;
    this.lineDialects = lineDialects;
    this.macroBodyLines = macroBodyLines;
    this.anonymousLabelsByScope = anonymousLabelsByScope;
  }

  public Scope fileScope() {
    return fileScope;
  }

  public List<Scope> allScopes() {
    return allScopes;
  }

  public List<SymbolDef> allDefinitions() {
    return allDefinitions;
  }

  public List<ConditionalBlock> rootConditionals() {
    return rootConditionals;
  }

  public Map<String, MacroDefinition> macros() {
    return macros;
  }

  public Optional<MacroDefinition> findMacro(String name) {
    if (name == null || name.isBlank()) {
      return Optional.empty();
    }
    return Optional.ofNullable(macros.get(name.toLowerCase(Locale.ROOT)));
  }

  /**
   * Lazily computes and memoizes the {@link MacroEffectSummary} for a macro defined in this file.
   */
  public Optional<MacroEffectSummary> macroSummary(String name) {
    MacroDefinition def = name != null ? macros.get(name.toLowerCase(Locale.ROOT)) : null;
    if (def == null) {
      return Optional.empty();
    }
    return Optional.of(
        summaryCache.computeIfAbsent(
            def.name().toLowerCase(Locale.ROOT),
            k -> {
              Map<String, String> lowerDefs = new LinkedHashMap<>();
              defines.forEach((dk, dv) -> lowerDefs.put(dk.toLowerCase(Locale.ROOT), dv));
              return MacroEffectSummary.derive(
                  toMacroDef(def),
                  lowerDefs,
                  nested -> findMacro(nested).map(ScopeTree::toMacroDef).orElse(null));
            }));
  }

  private static MacroEffectSummary.MacroDefinition toMacroDef(MacroDefinition def) {
    return new MacroEffectSummary.MacroDefinition(
        def.name(), def.parameters(), def.tokenizedBodyLines(), def.uri(), def.startLine());
  }

  public Map<String, String> defines() {
    return defines;
  }

  public List<String> includes() {
    return includes;
  }

  public Scope scopeAtLine(int lineIdx) {
    if (lineIdx < 0 || lineIdx >= lineScopes.length) {
      return fileScope;
    }
    Scope s = lineScopes[lineIdx];
    return s != null ? s : fileScope;
  }

  public DialectProfile dialectAtLine(int lineIdx) {
    if (lineIdx < 0 || lineIdx >= lineDialects.length) {
      return DialectProfile.SUPERFX;
    }
    DialectProfile d = lineDialects[lineIdx];
    return d != null ? d : DialectProfile.SUPERFX;
  }

  public boolean isMacroBodyLine(int lineIdx) {
    return lineIdx >= 0 && lineIdx < macroBodyLines.length && macroBodyLines[lineIdx];
  }

  public List<AnonymousLabelDef> anonymousLabelsForLine(int lineIdx) {
    Scope anonOwner = anonymousScopeOwner(scopeAtLine(lineIdx));
    List<AnonymousLabelDef> defs = anonymousLabelsByScope.get(anonOwner);
    return defs != null ? defs : List.of();
  }

  private static Scope anonymousScopeOwner(Scope scope) {
    Scope cur = scope;
    while (cur != null) {
      if (cur.type() == ScopeType.FUNCTION
          || cur.type() == ScopeType.PROC
          || cur.type() == ScopeType.SCOPE
          || cur.type() == ScopeType.FILE) {
        return cur;
      }
      cur = cur.parent();
    }
    return scope;
  }

  private static final class ArmBuilder {
    final String directive;
    final int startLine;
    int endLine;
    final List<SymbolDef> definitions = new ArrayList<>();
    final List<CondBlockBuilder> nestedBlocks = new ArrayList<>();

    ArmBuilder(String directive, int startLine) {
      this.directive = directive;
      this.startLine = startLine;
      this.endLine = startLine;
    }

    ConditionalArm freeze() {
      return new ConditionalArm(
          directive,
          startLine,
          endLine,
          definitions,
          nestedBlocks.stream().map(CondBlockBuilder::freeze).toList());
    }
  }

  private static final class CondBlockBuilder {
    final int startLine;
    int endLine;
    final Scope enclosingScope;
    final List<ArmBuilder> arms = new ArrayList<>();

    CondBlockBuilder(int startLine, Scope enclosingScope, String openerDirective) {
      this.startLine = startLine;
      this.endLine = startLine;
      this.enclosingScope = enclosingScope;
      this.arms.add(new ArmBuilder(openerDirective, startLine));
    }

    ArmBuilder currentArm() {
      return arms.getLast();
    }

    void startNextArm(String directive, int line) {
      currentArm().endLine = Math.max(currentArm().startLine, line - 1);
      arms.add(new ArmBuilder(directive, line));
    }

    ConditionalBlock freeze() {
      return new ConditionalBlock(
          startLine, endLine, enclosingScope, arms.stream().map(ArmBuilder::freeze).toList());
    }
  }

  private static final class MacroBuilder {
    final String name;
    final List<String> params;
    final int startLine;
    final List<String> rawLines = new ArrayList<>();
    final List<List<Token>> tokenLines = new ArrayList<>();

    MacroBuilder(String name, List<String> params, int startLine) {
      this.name = name;
      this.params = params;
      this.startLine = startLine;
    }
  }

  public static ScopeTree build(TokenizedFile file) {
    if (file == null || file.isEmpty()) {
      return EMPTY;
    }

    URI fileUri = file.uri();
    int totalLines = file.textLines();
    Scope fileScope =
        new Scope(
            fileUri != null ? fileUri.toString() : "<snippet>",
            ScopeType.FILE,
            fileUri,
            0,
            Math.max(0, totalLines - 1),
            null);

    List<Scope> allScopes = new ArrayList<>();
    allScopes.add(fileScope);
    List<SymbolDef> allDefs = new ArrayList<>();
    List<CondBlockBuilder> rootCondBuilders = new ArrayList<>();
    Deque<CondBlockBuilder> condStack = new ArrayDeque<>();
    Map<String, MacroDefinition> macros = new LinkedHashMap<>();
    Map<String, String> defines = new LinkedHashMap<>();
    List<String> includes = new ArrayList<>();
    Map<Scope, List<AnonymousLabelDef>> anonMap = new LinkedHashMap<>();
    anonMap.put(fileScope, new ArrayList<>());

    Scope[] lineScopes = new Scope[totalLines];
    boolean[] macroBodyLines = new boolean[totalLines];

    Deque<Scope> scopeStack = new ArrayDeque<>();
    scopeStack.push(fileScope);

    MacroBuilder activeMacro = null;
    int nestedMacroDepth = 0;

    for (int idx = 0; idx < totalLines; idx++) {
      Tokens lineObj = file.getLine(idx);
      List<Token> tokens = lineObj != null ? lineObj.tokens() : List.of();
      String rawLine = file.getRawLine(idx);

      if (activeMacro != null) {
        if (!tokens.isEmpty()) {
          Token first = tokens.get(0);
          String firstLower = first.text().toLowerCase(Locale.ROOT);
          if (first.type == TokenType.TOK_MACRO
              || ".macro".equals(firstLower)
              || ".mac".equals(firstLower)) {
            nestedMacroDepth++;
          } else if (first.type == TokenType.TOK_ENDMACRO
              || ".endmacro".equals(firstLower)
              || ".endmac".equals(firstLower)) {
            if (nestedMacroDepth == 0) {
              closeScope(scopeStack, ScopeType.MACRO, idx);
              macros.put(
                  activeMacro.name.toLowerCase(Locale.ROOT),
                  new MacroDefinition(
                      activeMacro.name,
                      activeMacro.params,
                      activeMacro.startLine,
                      idx,
                      activeMacro.rawLines,
                      activeMacro.tokenLines,
                      fileUri));
              activeMacro = null;
              lineScopes[idx] = scopeStack.peek();
              macroBodyLines[idx] = true;
              continue;
            }
            nestedMacroDepth--;
          }
        }
        activeMacro.rawLines.add(rawLine);
        activeMacro.tokenLines.add(tokens);
        lineScopes[idx] = scopeStack.peek();
        macroBodyLines[idx] = true;
        continue;
      }

      if (tokens == null || tokens.isEmpty()) {
        lineScopes[idx] = scopeStack.peek();
        continue;
      }

      Token first = tokens.get(0);
      String firstLower = first.text().toLowerCase(Locale.ROOT);

      if ((first.type == TokenType.TOK_INCLUDE || ".include".equals(firstLower))
          && tokens.size() > 1) {
        String incTarget = tokens.get(1).text().replace("\"", "").trim();
        if (!incTarget.isEmpty()) {
          includes.add(incTarget);
        }
        lineScopes[idx] = scopeStack.peek();
        continue;
      }

      if (isIfOpener(first, firstLower)) {
        CondBlockBuilder cb = new CondBlockBuilder(idx, scopeStack.peek(), firstLower);
        if (condStack.isEmpty()) {
          rootCondBuilders.add(cb);
        } else {
          condStack.peek().currentArm().nestedBlocks.add(cb);
        }
        condStack.push(cb);
        lineScopes[idx] = scopeStack.peek();
        continue;
      }
      if (first.type == TokenType.TOK_ELSEIF
          || ".elseif".equals(firstLower)
          || first.type == TokenType.TOK_ELSE
          || ".else".equals(firstLower)) {
        if (!condStack.isEmpty()) {
          condStack.peek().startNextArm(firstLower, idx);
        }
        lineScopes[idx] = scopeStack.peek();
        continue;
      }
      if (first.type == TokenType.TOK_ENDIF || ".endif".equals(firstLower)) {
        if (!condStack.isEmpty()) {
          CondBlockBuilder closed = condStack.pop();
          closed.currentArm().endLine = idx;
          closed.endLine = idx;
        }
        lineScopes[idx] = scopeStack.peek();
        continue;
      }

      int stmtIdx = 0;
      Token leadingLabelToken = null;
      if (tokens.size() >= 2 && tokens.get(1).type == TokenType.TOK_COLON) {
        Token candidate = tokens.get(0);
        if (candidate.type == TokenType.TOK_IDENT || candidate.type == TokenType.TOK_LOCAL_IDENT) {
          leadingLabelToken = candidate;
          stmtIdx = 2;
        }
      }

      List<Token> tail =
          stmtIdx < tokens.size() ? tokens.subList(stmtIdx, tokens.size()) : List.of();
      Token stmtFirst = tail.isEmpty() ? null : tail.get(0);
      String stmtLower = stmtFirst != null ? stmtFirst.text().toLowerCase(Locale.ROOT) : "";

      if (stmtFirst != null) {
        if (FunctionSyntaxHelper.isEndFunction(tail)) {
          if (leadingLabelToken != null) {
            recordSym(
                file,
                idx,
                leadingLabelToken,
                "label",
                new Location(
                    fileUri,
                    idx,
                    leadingLabelToken.getStartIndex(),
                    leadingLabelToken.getEndIndex()),
                scopeStack.peek(),
                condStack,
                allDefs);
          }
          lineScopes[idx] = scopeStack.peek();
          closeScope(scopeStack, ScopeType.FUNCTION, idx);
          continue;
        }
        if (stmtFirst.type == TokenType.TOK_ENDPROC || ".endproc".equals(stmtLower)) {
          lineScopes[idx] = scopeStack.peek();
          closeScope(scopeStack, ScopeType.PROC, idx);
          continue;
        }
        if (stmtFirst.type == TokenType.TOK_ENDSCOPE || ".endscope".equals(stmtLower)) {
          lineScopes[idx] = scopeStack.peek();
          closeScope(scopeStack, ScopeType.SCOPE, idx);
          continue;
        }
        if (stmtFirst.type == TokenType.TOK_ENDSTRUCT || ".endstruct".equals(stmtLower)) {
          lineScopes[idx] = scopeStack.peek();
          closeScope(scopeStack, ScopeType.STRUCT, idx);
          continue;
        }
        if (stmtFirst.type == TokenType.TOK_ENDENUM || ".endenum".equals(stmtLower)) {
          lineScopes[idx] = scopeStack.peek();
          closeScope(scopeStack, ScopeType.ENUM, idx);
          continue;
        }
      }

      if (!tail.isEmpty() && FunctionSyntaxHelper.isFunction(tail)) {
        if (leadingLabelToken != null) {
          recordSym(
              file,
              idx,
              leadingLabelToken,
              "label",
              new Location(
                  fileUri, idx, leadingLabelToken.getStartIndex(), leadingLabelToken.getEndIndex()),
              scopeStack.peek(),
              condStack,
              allDefs);
        }
        FunctionDeclaration fn = FunctionSyntaxHelper.parseFunction(tail);
        String fnName = fn.name();
        Scope parent = scopeStack.peek();
        if (!fnName.isEmpty()) {
          Location fnLoc = new Location(fileUri, idx, 0, lineObj.line().length());
          recordSym(file, idx, fn.nameToken(), "function", fnLoc, parent, condStack, allDefs);
          DocPair docs = extractDocs(file, idx);
          parent.addDefinition(fnName + "_function", fnLoc);
          if (docs != null) {
            parent.addDocumentation(fnName + "_function", docs.clean);
            parent.addRawDocumentation(fnName + "_function", docs.raw);
          }
          allDefs.add(
              new SymbolDef(
                  fnName + "_function",
                  qualify(parent, fnName + "_function"),
                  "function_alias",
                  fn.nameToken(),
                  fnLoc,
                  parent,
                  docs != null ? docs.clean : null,
                  docs != null ? docs.raw : null,
                  true));

          Scope fnScope =
              new Scope(
                  fnName,
                  ScopeType.FUNCTION,
                  fileUri,
                  idx,
                  Integer.MAX_VALUE,
                  parent,
                  fn.isInline(),
                  fn);
          allScopes.add(fnScope);
          anonMap.put(fnScope, new ArrayList<>());
          scopeStack.push(fnScope);

          for (Token p : fn.parameters()) {
            if (!isPhysicalRegister(p)) {
              fnScope.addRegisterLabel(p.text());
            }
            Location pLoc = new Location(fileUri, idx, p.getStartIndex(), p.getEndIndex());
            fnScope.addDefinition(p.text(), pLoc);
            allDefs.add(
                new SymbolDef(
                    p.text(),
                    fnName + "::" + p.text(),
                    "parameter",
                    p,
                    pLoc,
                    fnScope,
                    null,
                    null,
                    false));
          }
          if (fn.returnVariable().isPresent()) {
            Token ret = fn.returnVariable().get();
            if (!isPhysicalRegister(ret)) {
              fnScope.addRegisterLabel(ret.text());
              Location rLoc = new Location(fileUri, idx, ret.getStartIndex(), ret.getEndIndex());
              fnScope.addDefinition(ret.text(), rLoc);
              allDefs.add(
                  new SymbolDef(
                      ret.text(),
                      fnName + "::" + ret.text(),
                      "return_var",
                      ret,
                      rLoc,
                      fnScope,
                      null,
                      null,
                      false));
            }
          }
        }
        lineScopes[idx] = scopeStack.peek();
        continue;
      }

      if (stmtFirst != null
          && (stmtFirst.type == TokenType.TOK_PROC || ".proc".equals(stmtLower))) {
        Scope parent = scopeStack.peek();
        Token procNameTok = tail.size() > 1 ? tail.get(1) : leadingLabelToken;
        if (leadingLabelToken != null && tail.size() > 1) {
          recordSym(
              file,
              idx,
              leadingLabelToken,
              "label",
              new Location(
                  fileUri, idx, leadingLabelToken.getStartIndex(), leadingLabelToken.getEndIndex()),
              parent,
              condStack,
              allDefs);
        }
        String procName = procNameTok != null ? procNameTok.text() : "";
        if (!procName.isEmpty() && procNameTok != null) {
          Location loc = new Location(fileUri, idx, 0, procNameTok.getEndIndex());
          recordSym(file, idx, procNameTok, "procedure", loc, parent, condStack, allDefs);
        }
        Scope procScope = new Scope(procName, ScopeType.PROC, fileUri, idx, parent);
        allScopes.add(procScope);
        anonMap.put(procScope, new ArrayList<>());
        scopeStack.push(procScope);
        lineScopes[idx] = procScope;
        continue;
      }

      if (stmtFirst != null
          && (stmtFirst.type == TokenType.TOK_STRUCT || ".struct".equals(stmtLower))
          && tail.size() > 1) {
        Scope parent = scopeStack.peek();
        Token structTok = tail.get(1);
        String structName = structTok.text();
        Location loc = new Location(fileUri, idx, 0, structTok.getEndIndex());
        recordSym(file, idx, structTok, "struct", loc, parent, condStack, allDefs);
        Scope structScope = new Scope(structName, ScopeType.STRUCT, fileUri, idx, parent);
        allScopes.add(structScope);
        scopeStack.push(structScope);
        lineScopes[idx] = structScope;
        continue;
      }

      if (stmtFirst != null
          && (stmtFirst.type == TokenType.TOK_ENUM || ".enum".equals(stmtLower))) {
        Scope parent = scopeStack.peek();
        Token enumTok = tail.size() > 1 ? tail.get(1) : null;
        String enumName = enumTok != null ? enumTok.text() : "";
        if (!enumName.isEmpty()) {
          Location loc = new Location(fileUri, idx, 0, enumTok.getEndIndex());
          recordSym(file, idx, enumTok, "enum", loc, parent, condStack, allDefs);
        }
        Scope enumScope = new Scope(enumName, ScopeType.ENUM, fileUri, idx, parent);
        allScopes.add(enumScope);
        scopeStack.push(enumScope);
        lineScopes[idx] = enumScope;
        continue;
      }

      if (stmtFirst != null
          && (stmtFirst.type == TokenType.TOK_MACRO
              || ".macro".equals(stmtLower)
              || ".mac".equals(stmtLower))
          && tail.size() > 1) {
        Scope parent = scopeStack.peek();
        Token macroTok = tail.get(1);
        String macroName = macroTok.text();
        Location loc = new Location(fileUri, idx, 0, macroTok.getEndIndex());
        recordSym(file, idx, macroTok, "macro", loc, parent, condStack, allDefs);

        List<String> params = new ArrayList<>();
        for (int pIdx = 2; pIdx < tail.size(); pIdx++) {
          Token pt = tail.get(pIdx);
          if (pt.type != TokenType.TOK_COMMA && !pt.text().isBlank()) {
            params.add(pt.text());
          }
        }
        Scope macroScope = new Scope(macroName, ScopeType.MACRO, fileUri, idx, parent);
        allScopes.add(macroScope);
        scopeStack.push(macroScope);
        activeMacro = new MacroBuilder(macroName, params, idx);
        nestedMacroDepth = 0;
        lineScopes[idx] = macroScope;
        macroBodyLines[idx] = true;
        continue;
      }

      if (stmtFirst != null
          && (stmtFirst.type == TokenType.TOK_SCOPE || ".scope".equals(stmtLower))) {
        Scope parent = scopeStack.peek();
        Token scTok = tail.size() > 1 ? tail.get(1) : null;
        String scName = scTok != null ? scTok.text() : "";
        if (!scName.isEmpty()) {
          Location loc = new Location(fileUri, idx, 0, scTok.getEndIndex());
          recordSym(file, idx, scTok, "scope", loc, parent, condStack, allDefs);
        }
        Scope sc = new Scope(scName, ScopeType.SCOPE, fileUri, idx, parent);
        allScopes.add(sc);
        anonMap.put(sc, new ArrayList<>());
        scopeStack.push(sc);
        lineScopes[idx] = sc;
        continue;
      }

      Scope current = scopeStack.peek();
      lineScopes[idx] = current;

      boolean isCall = !tail.isEmpty() && FunctionSyntaxHelper.isCall(tail);
      if (!isCall) {
        Scope anonOwner = anonymousScopeOwner(current);
        List<AnonymousLabelDef> anonList =
            anonMap.computeIfAbsent(anonOwner, k -> new ArrayList<>());
        for (int i = 0; i < tokens.size(); i++) {
          Token t = tokens.get(i);
          if (t.type == TokenType.TOK_COLON) {
            boolean precededByIdent =
                i > 0
                    && (tokens.get(i - 1).type == TokenType.TOK_IDENT
                        || tokens.get(i - 1).type == TokenType.TOK_LOCAL_IDENT);
            if (!precededByIdent) {
              anonList.add(new AnonymousLabelDef(idx, t.getStartIndex()));
            }
          }
        }
      }

      if (leadingLabelToken != null) {
        Location loc =
            new Location(
                fileUri, idx, leadingLabelToken.getStartIndex(), leadingLabelToken.getEndIndex());
        String kind =
            (leadingLabelToken.type == TokenType.TOK_LOCAL_IDENT
                    || leadingLabelToken.text().startsWith("@"))
                ? "local_label"
                : "label";
        recordSym(file, idx, leadingLabelToken, kind, loc, current, condStack, allDefs);
      }

      if (tail.isEmpty()) {
        continue;
      }

      if (tail.size() > 1
          && (tail.get(1).type == TokenType.TOK_EQ || tail.get(1).type == TokenType.TOK_ASSIGN)) {
        Token symTok = tail.get(0);
        Location loc = new Location(fileUri, idx, symTok.getStartIndex(), symTok.getEndIndex());
        recordSym(file, idx, symTok, "constant", loc, current, condStack, allDefs);
        continue;
      }
      if (tail.size() > 2
          && tail.get(0).type == TokenType.TOK_NAMESPACE
          && (tail.get(2).type == TokenType.TOK_EQ || tail.get(2).type == TokenType.TOK_ASSIGN)) {
        Token symTok = tail.get(1);
        Location loc = new Location(fileUri, idx, symTok.getStartIndex(), symTok.getEndIndex());
        recordSym(file, idx, symTok, "constant", loc, fileScope, condStack, allDefs);
        continue;
      }

      if ((stmtFirst.type == TokenType.TOK_DEFINE || ".define".equals(stmtLower))
          && tail.size() > 1) {
        Token defTok = tail.get(1);
        Location loc = new Location(fileUri, idx, defTok.getStartIndex(), defTok.getEndIndex());
        recordSym(file, idx, defTok, "define", loc, current, condStack, allDefs);
        String lineStr = lineObj.line();
        if (defTok.getEndIndex() <= lineStr.length()) {
          defines.put(defTok.text(), lineStr.substring(defTok.getEndIndex()).trim());
        }
        continue;
      }

      if (stmtFirst.type == TokenType.TOK_REGISTER_KEYWORD || "register".equals(stmtLower)) {
        collectRegisterDeclarations(file, idx, tail, current, condStack, allDefs);
        continue;
      }

      if (current.type() == ScopeType.STRUCT
          && tail.size() > 1
          && tail.get(0).type == TokenType.TOK_IDENT) {
        Token fieldTok = tail.get(0);
        Location loc = new Location(fileUri, idx, fieldTok.getStartIndex(), fieldTok.getEndIndex());
        recordSym(file, idx, fieldTok, "field", loc, current, condStack, allDefs);
      }
    }

    int lastLine = Math.max(0, totalLines - 1);
    while (scopeStack.size() > 1) {
      Scope unclosed = scopeStack.pop();
      unclosed.setEndLine(lastLine);
    }
    fileScope.setEndLine(lastLine);

    while (!condStack.isEmpty()) {
      CondBlockBuilder unclosed = condStack.pop();
      unclosed.currentArm().endLine = lastLine;
      unclosed.endLine = lastLine;
    }

    DialectProfile[] lineDialects = computeLineDialects(file, lineScopes, macroBodyLines);

    Map<Scope, List<AnonymousLabelDef>> frozenAnon = new LinkedHashMap<>();
    for (Map.Entry<Scope, List<AnonymousLabelDef>> e : anonMap.entrySet()) {
      frozenAnon.put(e.getKey(), List.copyOf(e.getValue()));
    }

    return new ScopeTree(
        fileScope,
        allScopes,
        allDefs,
        rootCondBuilders.stream().map(CondBlockBuilder::freeze).toList(),
        macros,
        defines,
        includes,
        lineScopes,
        lineDialects,
        macroBodyLines,
        Collections.unmodifiableMap(frozenAnon));
  }

  private static DialectProfile[] computeLineDialects(
      TokenizedFile file, Scope[] lineScopes, boolean[] macroBodyLines) {
    int totalLines = file.textLines();
    DialectProfile[] result = new DialectProfile[totalLines];
    URI uri = file.uri();
    String uriStr = uri != null ? uri.toString().toLowerCase(Locale.ROOT) : "";
    if (uriStr.endsWith(".s700")) {
      java.util.Arrays.fill(result, DialectProfile.OTHER);
      return result;
    }

    int spanStart = 0;
    DialectProfile explicitSpanMode = null;

    for (int i = 0; i <= totalLines; i++) {
      boolean startNewSpan = (i == totalLines);
      DialectProfile nextExplicitMode = explicitSpanMode;

      if (i < totalLines && !macroBodyLines[i]) {
        List<Token> tokens = file.getLineTokens(i);
        if (tokens != null && !tokens.isEmpty()) {
          Token first = tokens.get(0);
          String lower = first.text().toLowerCase(Locale.ROOT);
          if (first.type == TokenType.TOK_SEGMENT || ".segment".equals(lower)) {
            startNewSpan = (i > spanStart);
            if (tokens.size() > 1) {
              String segName =
                  tokens.get(1).text().replace("\"", "").trim().toLowerCase(Locale.ROOT);
              if ("gsucode".equals(segName) || "sfxcode".equals(segName)) {
                nextExplicitMode = DialectProfile.SUPERFX;
              } else {
                nextExplicitMode = null;
              }
            }
          }
        }
      }

      if (startNewSpan) {
        DialectProfile spanDialect =
            classifySpan(file, spanStart, i, explicitSpanMode, lineScopes, macroBodyLines, uri);
        for (int line = spanStart; line < i; line++) {
          Scope sc = lineScopes[line];
          if (isInsideFunctionScope(sc)) {
            result[line] = DialectProfile.SUPERFX;
          } else {
            result[line] = spanDialect;
          }
        }
        spanStart = i;
        explicitSpanMode = nextExplicitMode;
      }
    }

    return result;
  }

  private static DialectProfile classifySpan(
      TokenizedFile file,
      int startLine,
      int endLineExclusive,
      DialectProfile explicitSegmentMode,
      Scope[] lineScopes,
      boolean[] macroBodyLines,
      URI uri) {
    if (explicitSegmentMode != null) {
      return explicitSegmentMode;
    }

    boolean sawSuperFxMarker = false;
    boolean saw65816Marker = false;

    for (int line = startLine; line < endLineExclusive; line++) {
      if (macroBodyLines[line]) {
        continue;
      }
      Scope sc = lineScopes[line];
      if (isInsideFunctionScope(sc)) {
        continue;
      }
      List<Token> tokens = file.getLineTokens(line);
      if (tokens == null || tokens.isEmpty()) {
        continue;
      }

      int opIdx = 0;
      if (tokens.get(0).type == TokenType.TOK_COLON) {
        opIdx = 1;
      } else if (tokens.size() >= 2 && tokens.get(1).type == TokenType.TOK_COLON) {
        opIdx = 2;
      } else if (tokens.get(0).text().endsWith(":")) {
        opIdx = 1;
      }
      if (opIdx >= tokens.size()) {
        continue;
      }

      Token op = tokens.get(opIdx);
      String lower = op.text().toLowerCase(Locale.ROOT);

      if (op.type == TokenType.TOK_P816
          || op.type == TokenType.TOK_P02
          || op.type == TokenType.TOK_PC02
          || op.type == TokenType.TOK_A8
          || op.type == TokenType.TOK_A16
          || op.type == TokenType.TOK_I8
          || op.type == TokenType.TOK_I16
          || op.type == TokenType.TOK_SMART
          || ".p816".equals(lower)
          || ".a8".equals(lower)
          || ".a16".equals(lower)
          || ".i8".equals(lower)
          || ".i16".equals(lower)) {
        saw65816Marker = true;
        continue;
      }
      if (op.type == TokenType.TOK_SETCPU || ".setcpu".equals(lower)) {
        if (opIdx + 1 < tokens.size()) {
          String cpu = tokens.get(opIdx + 1).text().replace("\"", "").toLowerCase(Locale.ROOT);
          if ("none".equals(cpu)) {
            sawSuperFxMarker = true;
          } else if (cpu.contains("65816") || cpu.contains("6502") || cpu.contains("65c02")) {
            saw65816Marker = true;
          }
        }
        continue;
      }

      if (op.type == TokenType.TOK_REGISTER_KEYWORD
          || "register".equals(lower)
          || op.type == TokenType.TOK_CALL
          || "call".equals(lower)
          || op.type == TokenType.TOK_RETURN
          || "return".equals(lower)
          || "for".equals(lower)
          || "forr".equals(lower)
          || "endfor".equals(lower)
          || SuperFxIsa.isExclusiveSuperFxMnemonic(lower)) {
        sawSuperFxMarker = true;
      } else if (EXCLUSIVE_65816_MNEMONICS.contains(lower)) {
        saw65816Marker = true;
      }

      for (int tIdx = opIdx + 1; tIdx < tokens.size(); tIdx++) {
        Token t = tokens.get(tIdx);
        if (t.type == TokenType.TOK_REGISTER) {
          sawSuperFxMarker = true;
        } else if (t.type == TokenType.TOK_OVERRIDE_ABS
            || t.type == TokenType.TOK_OVERRIDE_FAR
            || t.type == TokenType.TOK_OVERRIDE_ZP) {
          saw65816Marker = true;
        } else if (t.type == TokenType.TOK_COMMA && tIdx + 1 < tokens.size()) {
          Token idxReg = tokens.get(tIdx + 1);
          if ((idxReg.type == TokenType.TOK_X
                  || idxReg.type == TokenType.TOK_Y
                  || idxReg.type == TokenType.TOK_S)
              && (sc == null || !sc.isRegisterLabel(idxReg.text()))) {
            saw65816Marker = true;
          }
        }
      }

      if (!sawSuperFxMarker) {
        int argCount = tokens.size() - (opIdx + 1);
        if (("sbc".equals(lower) || "cmp".equals(lower))
            && argCount >= 1
            && tokens.get(opIdx + 1).type == TokenType.TOK_HASH
            && isNonSnippetDotS(uri)) {
          saw65816Marker = true;
        } else if (("lsr".equals(lower) || "rol".equals(lower) || "ror".equals(lower))
            && argCount == 1
            && tokens.get(opIdx + 1).type == TokenType.TOK_A
            && (sc == null || !sc.isRegisterLabel("a"))) {
          saw65816Marker = true;
        } else if (("inc".equals(lower) || "dec".equals(lower))
            && argCount == 0
            && isNonSnippetDotS(uri)) {
          saw65816Marker = true;
        } else if ("jmp".equals(lower)
            && argCount == 1
            && tokens.get(opIdx + 1).type == TokenType.TOK_IDENT
            && (sc == null || !sc.isRegisterLabel(tokens.get(opIdx + 1).text()))
            && isNonSnippetDotS(uri)) {
          saw65816Marker = true;
        } else if ("and".equals(lower)
            && argCount >= 2
            && tokens.get(opIdx + 1).type == TokenType.TOK_HASH
            && isNonSnippetDotS(uri)) {
          saw65816Marker = true;
        }
      }
    }

    if (sawSuperFxMarker && !saw65816Marker) {
      return DialectProfile.SUPERFX;
    }
    if (saw65816Marker && !sawSuperFxMarker) {
      return DialectProfile.CPU_65816;
    }
    return DialectProfile.SUPERFX;
  }

  private static boolean isNonSnippetDotS(URI uri) {
    if (uri == null) {
      return false;
    }
    if ("snippet".equalsIgnoreCase(uri.getScheme())) {
      return false;
    }
    String path = uri.getPath();
    return path != null && path.toLowerCase(Locale.ROOT).endsWith(".s");
  }

  private static boolean isInsideFunctionScope(Scope sc) {
    Scope cur = sc;
    while (cur != null) {
      if (cur.type() == ScopeType.FUNCTION) {
        return true;
      }
      cur = cur.parent();
    }
    return false;
  }

  private static void collectRegisterDeclarations(
      TokenizedFile file,
      int lineIdx,
      List<Token> tail,
      Scope current,
      Deque<CondBlockBuilder> condStack,
      List<SymbolDef> allDefs) {
    URI fileUri = file.uri();
    int i = 1;
    while (i < tail.size()) {
      Token t = tail.get(i);
      if (isVarToken(t)) {
        String varName = t.text();
        current.addRegisterLabel(varName);
        Location loc = new Location(fileUri, lineIdx, t.getStartIndex(), t.getEndIndex());
        recordSym(file, lineIdx, t, "register", loc, current, condStack, allDefs);
        i++;
        if (i < tail.size()) {
          Token next = tail.get(i);
          if (next.type == TokenType.TOK_EQ
              || next.type == TokenType.TOK_ASSIGN
              || "=".equals(next.text())) {
            i++;
            if (i < tail.size() && tail.get(i).type != TokenType.TOK_COMMA) {
              current.addPrecoloredRegister(varName, tail.get(i).text());
            }
            while (i < tail.size() && tail.get(i).type != TokenType.TOK_COMMA) {
              i++;
            }
          }
        }
      } else {
        i++;
      }
    }
  }

  private static boolean isVarToken(Token t) {
    return t != null
        && (t.type == TokenType.TOK_IDENT
            || t.type == TokenType.TOK_LOCAL_IDENT
            || t.type == TokenType.TOK_A
            || t.type == TokenType.TOK_X
            || t.type == TokenType.TOK_Y
            || t.type == TokenType.TOK_S);
  }

  private static boolean isPhysicalRegister(Token token) {
    if (token == null) {
      return false;
    }
    if (token.type == TokenType.TOK_REGISTER) {
      return true;
    }
    String text = token.text();
    return text != null && text.matches("(?i)r\\d{1,2}");
  }

  private record DocPair(String clean, String raw) {}

  private static DocPair extractDocs(TokenizedFile file, int defLineIdx) {
    List<String> commentBlock = new ArrayList<>();
    for (int i = defLineIdx - 1; i >= 0; i--) {
      String rawLine = file.getRawLine(i);
      if (rawLine == null) {
        break;
      }
      String trimmed = rawLine.trim();
      if (trimmed.startsWith(";")) {
        commentBlock.add(0, rawLine);
      } else {
        break;
      }
    }
    if (commentBlock.isEmpty()) {
      return null;
    }
    String rawDoc = String.join("\n", commentBlock);
    String cleanDoc =
        commentBlock.stream()
            .map(l -> l.trim().replaceFirst("^;+\\s?", ""))
            .collect(Collectors.joining("\n"));
    return new DocPair(cleanDoc, rawDoc);
  }

  private static void recordSym(
      TokenizedFile file,
      int lineIdx,
      Token token,
      String kind,
      Location loc,
      Scope scope,
      Deque<CondBlockBuilder> condStack,
      List<SymbolDef> allDefs) {
    if (token == null || token.text() == null || token.text().isBlank()) {
      return;
    }
    String name = token.text();
    DocPair docs = extractDocs(file, lineIdx);
    scope.addDefinition(name, loc);
    if (docs != null) {
      scope.addDocumentation(name, docs.clean);
      scope.addRawDocumentation(name, docs.raw);
    }

    boolean globalVisible =
        scope.type() == ScopeType.FILE
            || scope.type() == ScopeType.GLOBAL
            || "function".equals(kind)
            || "procedure".equals(kind)
            || "struct".equals(kind)
            || "enum".equals(kind)
            || "macro".equals(kind);

    SymbolDef def =
        new SymbolDef(
            name,
            qualify(scope, name),
            kind,
            token,
            loc,
            scope,
            docs != null ? docs.clean : null,
            docs != null ? docs.raw : null,
            globalVisible);

    allDefs.add(def);
    if (!condStack.isEmpty() && condStack.peek().enclosingScope == scope) {
      condStack.peek().currentArm().definitions.add(def);
    }
  }

  private static String qualify(Scope scope, String name) {
    if (scope == null || scope.type() == ScopeType.FILE || scope.type() == ScopeType.GLOBAL) {
      return name;
    }
    Deque<String> parts = new ArrayDeque<>();
    parts.push(name);
    Scope cur = scope;
    while (cur != null && cur.type() != ScopeType.FILE && cur.type() != ScopeType.GLOBAL) {
      if (!cur.name().isEmpty()) {
        parts.push(cur.name());
      }
      cur = cur.parent();
    }
    return String.join("::", parts);
  }

  private static void closeScope(Deque<Scope> stack, ScopeType expectedType, int endLine) {
    while (stack.size() > 1) {
      Scope top = stack.peek();
      if (top.type() == expectedType) {
        top.setEndLine(endLine);
        stack.pop();
        return;
      } else if (top.type() == ScopeType.FILE || top.type() == ScopeType.GLOBAL) {
        return;
      } else {
        top.setEndLine(endLine);
        stack.pop();
      }
    }
  }

  private static boolean isIfOpener(Token token, String lowerText) {
    return switch (token.type) {
      case TOK_IF,
          TOK_IFBLANK,
          TOK_IFNBLANK,
          TOK_IFDEF,
          TOK_IFNDEF,
          TOK_IFCONST,
          TOK_IFNCONST,
          TOK_IFREF,
          TOK_IFNREF,
          TOK_IFP02,
          TOK_IFP4510,
          TOK_IFP816,
          TOK_IFPC02,
          TOK_IFPDTV,
          TOK_IFPSC02 ->
          true;
      default -> lowerText.startsWith(".if");
    };
  }
}
