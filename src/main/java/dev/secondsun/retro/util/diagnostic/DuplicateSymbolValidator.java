package dev.secondsun.retro.util.diagnostic;

import dev.secondsun.retro.util.FunctionSyntaxHelper;
import dev.secondsun.retro.util.Token;
import dev.secondsun.retro.util.TokenType;
import dev.secondsun.retro.util.vo.ScopeTree;
import dev.secondsun.retro.util.vo.TokenizedFile;
import dev.secondsun.retro.util.vo.Tokens;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Validates symbol uniqueness across lexical scopes ({@code [inline] function}, {@code .proc},
 * {@code .scope}, {@code .struct}, {@code .enum}) and conditional assembly arms ({@code .if}/{@code
 * .elseif}/{@code .else}/{@code .endif}), while isolating unexpanded {@code .macro} bodies.
 *
 * <p>Mutually exclusive definitions in sibling conditional arms do not conflict with each other,
 * whereas duplicate definitions within the same arm or colliding with an unconditional definition
 * in the enclosing scope emit {@code DUPLICATE_SYMBOL}.
 */
public class DuplicateSymbolValidator {

  private static final class CondState {
    final Map<String, Integer> snapshotDefs;
    final Map<String, Integer> snapshotCheap;
    final Map<String, Integer> mergedDefs;
    final Map<String, Integer> mergedCheap;

    CondState(Map<String, Integer> currentDefs, Map<String, Integer> currentCheap) {
      this.snapshotDefs = new HashMap<>(currentDefs);
      this.snapshotCheap = new HashMap<>(currentCheap);
      this.mergedDefs = new HashMap<>(currentDefs);
      this.mergedCheap = new HashMap<>(currentCheap);
    }

    void mergeCurrentArm(Map<String, Integer> armDefs, Map<String, Integer> armCheap) {
      armDefs.forEach(mergedDefs::putIfAbsent);
      armCheap.forEach(mergedCheap::putIfAbsent);
    }
  }

  private static final class ScopeState {
    Map<String, Integer> defs = new HashMap<>();
    Map<String, Integer> cheapLabels = new HashMap<>();
    final Deque<CondState> condStack = new ArrayDeque<>();
  }

  public List<SyntaxDiagnostic> validate(TokenizedFile file) {
    if (file == null) {
      return List.of();
    }

    List<SyntaxDiagnostic> diagnostics = new ArrayList<>();
    URI uri = file.uri();
    ScopeTree tree = file.scopeTree();

    Deque<ScopeState> scopes = new ArrayDeque<>();
    scopes.push(new ScopeState());

    int totalLines = file.textLines();
    for (int lineIdx = 0; lineIdx < totalLines; lineIdx++) {
      if (tree.isMacroBodyLine(lineIdx)) {
        continue;
      }

      Tokens lineTokens = file.getLine(lineIdx);
      if (lineTokens == null || lineTokens.tokens().isEmpty()) {
        continue;
      }

      int lineNum = lineIdx + 1;
      List<Token> tokens = lineTokens.tokens();
      Token first = tokens.get(0);
      String firstLower = first.text().toLowerCase(Locale.ROOT);
      ScopeState active = scopes.peek();

      // 1. Conditional assembly branching within the active lexical scope
      if (isIfOpener(first, firstLower)) {
        active.condStack.push(new CondState(active.defs, active.cheapLabels));
        continue;
      }
      if (first.type == TokenType.TOK_ELSEIF
          || ".elseif".equals(firstLower)
          || first.type == TokenType.TOK_ELSE
          || ".else".equals(firstLower)) {
        if (!active.condStack.isEmpty()) {
          CondState cond = active.condStack.peek();
          cond.mergeCurrentArm(active.defs, active.cheapLabels);
          active.defs = new HashMap<>(cond.snapshotDefs);
          active.cheapLabels = new HashMap<>(cond.snapshotCheap);
        }
        continue;
      }
      if (first.type == TokenType.TOK_ENDIF || ".endif".equals(firstLower)) {
        if (!active.condStack.isEmpty()) {
          CondState cond = active.condStack.pop();
          cond.mergeCurrentArm(active.defs, active.cheapLabels);
          active.defs = cond.mergedDefs;
          active.cheapLabels = cond.mergedCheap;
        }
        continue;
      }

      // 2. Leading label extraction (e.g. "lbl: .proc" or "name: nop" or "@loop:")
      int stmtIdx = 0;
      Token leadingLabel = null;
      if (tokens.size() >= 2 && tokens.get(1).type == TokenType.TOK_COLON) {
        Token candidate = tokens.get(0);
        if (candidate.type == TokenType.TOK_IDENT || candidate.type == TokenType.TOK_LOCAL_IDENT) {
          leadingLabel = candidate;
          stmtIdx = 2;
        }
      } else if (first.type == TokenType.TOK_COLON) {
        stmtIdx = 1;
      }

      List<Token> tail =
          stmtIdx < tokens.size() ? tokens.subList(stmtIdx, tokens.size()) : List.of();
      Token stmtFirst = tail.isEmpty() ? null : tail.get(0);
      String stmtLower = stmtFirst != null ? stmtFirst.text().toLowerCase(Locale.ROOT) : "";

      // 3. Scope closers
      if (stmtFirst != null
          && (FunctionSyntaxHelper.isEndFunction(tail)
              || stmtFirst.type == TokenType.TOK_ENDPROC
              || ".endproc".equals(stmtLower)
              || stmtFirst.type == TokenType.TOK_ENDSCOPE
              || ".endscope".equals(stmtLower)
              || stmtFirst.type == TokenType.TOK_ENDSTRUCT
              || ".endstruct".equals(stmtLower)
              || stmtFirst.type == TokenType.TOK_ENDENUM
              || ".endenum".equals(stmtLower))) {
        if (leadingLabel != null) {
          recordLabel(leadingLabel, uri, lineNum, active, diagnostics);
        }
        if (scopes.size() > 1) {
          scopes.pop();
        }
        scopes.peek().cheapLabels.clear();
        continue;
      }

      // 4. Scope openers ([inline] function, .proc, .scope, .struct, .enum)
      if (!tail.isEmpty() && FunctionSyntaxHelper.isFunction(tail)) {
        if (leadingLabel != null) {
          recordLabel(leadingLabel, uri, lineNum, active, diagnostics);
        }
        var decl = FunctionSyntaxHelper.parseFunction(tail);
        if (decl.nameToken() != null && decl.nameToken().type == TokenType.TOK_IDENT) {
          recordDefinition(decl.nameToken(), "function", uri, lineNum, active.defs, diagnostics);
        }
        scopes.push(new ScopeState());
        continue;
      }

      if (stmtFirst != null
          && (stmtFirst.type == TokenType.TOK_PROC || ".proc".equals(stmtLower))) {
        if (tail.size() > 1 && tail.get(1).type == TokenType.TOK_IDENT) {
          if (leadingLabel != null) {
            recordLabel(leadingLabel, uri, lineNum, active, diagnostics);
          }
          recordDefinition(tail.get(1), "procedure", uri, lineNum, active.defs, diagnostics);
        } else if (leadingLabel != null && leadingLabel.type == TokenType.TOK_IDENT) {
          active.cheapLabels.clear();
          recordDefinition(leadingLabel, "procedure", uri, lineNum, active.defs, diagnostics);
        }
        scopes.push(new ScopeState());
        continue;
      }

      if (stmtFirst != null
          && (stmtFirst.type == TokenType.TOK_SCOPE || ".scope".equals(stmtLower))) {
        if (leadingLabel != null) {
          recordLabel(leadingLabel, uri, lineNum, active, diagnostics);
        }
        if (tail.size() > 1 && tail.get(1).type == TokenType.TOK_IDENT) {
          recordDefinition(tail.get(1), "scope", uri, lineNum, active.defs, diagnostics);
        }
        scopes.push(new ScopeState());
        continue;
      }

      if (stmtFirst != null
          && (stmtFirst.type == TokenType.TOK_STRUCT
              || ".struct".equals(stmtLower)
              || stmtFirst.type == TokenType.TOK_ENUM
              || ".enum".equals(stmtLower))) {
        if (leadingLabel != null) {
          recordLabel(leadingLabel, uri, lineNum, active, diagnostics);
        }
        if (tail.size() > 1 && tail.get(1).type == TokenType.TOK_IDENT) {
          String kind =
              (stmtFirst.type == TokenType.TOK_STRUCT || ".struct".equals(stmtLower))
                  ? "struct"
                  : "enum";
          recordDefinition(tail.get(1), kind, uri, lineNum, active.defs, diagnostics);
        }
        scopes.push(new ScopeState());
        continue;
      }

      // 5. Standard, cheap local label, or constant '=' definitions (:= is redefinable in ca65)
      if (leadingLabel != null) {
        recordLabel(leadingLabel, uri, lineNum, active, diagnostics);
      } else if (first.text().endsWith(":") && first.text().length() > 1) {
        String labelText = first.text().substring(0, first.text().length() - 1);
        recordLabelByText(first, labelText, uri, lineNum, active, diagnostics);
      } else if (tokens.size() >= 3
          && first.type == TokenType.TOK_IDENT
          && tokens.get(1).type == TokenType.TOK_EQ) {
        recordDefinition(first, "constant", uri, lineNum, active.defs, diagnostics);
      }
    }

    return diagnostics;
  }

  private void recordLabel(
      Token labelToken,
      URI uri,
      int lineNum,
      ScopeState active,
      List<SyntaxDiagnostic> diagnostics) {
    String labelText = labelToken.text();
    if (labelText.startsWith("@") || labelToken.type == TokenType.TOK_LOCAL_IDENT) {
      Integer prevLine = active.cheapLabels.get(labelText);
      if (prevLine != null) {
        diagnostics.add(
            SyntaxDiagnostic.error(
                uri,
                lineNum,
                labelToken.getStartIndex(),
                labelToken.getEndIndex(),
                "DUPLICATE_SYMBOL",
                String.format(
                    "Duplicate local label '%s' already defined at line %d", labelText, prevLine)));
      } else {
        active.cheapLabels.put(labelText, lineNum);
      }
    } else if (labelToken.type == TokenType.TOK_IDENT) {
      active.cheapLabels.clear();
      recordDefinition(labelToken, "label", uri, lineNum, active.defs, diagnostics);
    }
  }

  private void recordLabelByText(
      Token token,
      String labelText,
      URI uri,
      int lineNum,
      ScopeState active,
      List<SyntaxDiagnostic> diagnostics) {
    if (labelText.startsWith("@")) {
      Integer prevLine = active.cheapLabels.get(labelText);
      if (prevLine != null) {
        diagnostics.add(
            SyntaxDiagnostic.error(
                uri,
                lineNum,
                token.getStartIndex(),
                token.getEndIndex(),
                "DUPLICATE_SYMBOL",
                String.format(
                    "Duplicate local label '%s' already defined at line %d", labelText, prevLine)));
      } else {
        active.cheapLabels.put(labelText, lineNum);
      }
    } else {
      active.cheapLabels.clear();
      Integer prevLine = active.defs.get(labelText);
      if (prevLine != null) {
        diagnostics.add(
            SyntaxDiagnostic.error(
                uri,
                lineNum,
                token.getStartIndex(),
                token.getEndIndex(),
                "DUPLICATE_SYMBOL",
                String.format(
                    "Duplicate label '%s' already defined at line %d", labelText, prevLine)));
      } else {
        active.defs.put(labelText, lineNum);
      }
    }
  }

  private void recordDefinition(
      Token token,
      String kind,
      URI uri,
      int lineNum,
      Map<String, Integer> currentScope,
      List<SyntaxDiagnostic> diagnostics) {
    String name = token.text();
    Integer prevLine = currentScope.get(name);
    if (prevLine != null) {
      diagnostics.add(
          SyntaxDiagnostic.error(
              uri,
              lineNum,
              token.getStartIndex(),
              token.getEndIndex(),
              "DUPLICATE_SYMBOL",
              String.format("Duplicate %s '%s' already defined at line %d", kind, name, prevLine)));
    } else {
      currentScope.put(name, lineNum);
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
