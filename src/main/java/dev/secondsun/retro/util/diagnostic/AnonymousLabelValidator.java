package dev.secondsun.retro.util.diagnostic;

import dev.secondsun.retro.util.Token;
import dev.secondsun.retro.util.TokenType;
import dev.secondsun.retro.util.vo.TokenizedFile;
import dev.secondsun.retro.util.vo.Tokens;
import java.net.URI;
import java.util.*;

/**
 * Validates ca65 anonymous label references (e.g. :+, :++, :-, :--) ensuring each reference
 * resolves to an existing anonymous label definition (:) within the same lexical scope.
 */
public class AnonymousLabelValidator {

  public record AnonymousLabelDef(int lineIdx, int colStart) {}

  private static class AnonymousScope {
    final AnonymousScope parent;
    final List<AnonymousLabelDef> labels = new ArrayList<>();

    AnonymousScope(AnonymousScope parent) {
      this.parent = parent;
    }
  }

  private record ScopedModel(AnonymousScope[] lineScopes, List<AnonymousScope> allScopes) {}

  private static ScopedModel buildScopedModel(TokenizedFile file) {
    int totalLines = file.textLines();
    AnonymousScope fileScope = new AnonymousScope(null);
    Deque<AnonymousScope> stack = new ArrayDeque<>();
    stack.push(fileScope);

    AnonymousScope[] lineScopes = new AnonymousScope[totalLines];
    List<AnonymousScope> allScopes = new ArrayList<>();
    allScopes.add(fileScope);

    boolean inMacro = false;

    for (int lineIdx = 0; lineIdx < totalLines; lineIdx++) {
      Tokens lineTokens = file.getLine(lineIdx);
      if (lineTokens == null || lineTokens.tokens().isEmpty()) {
        lineScopes[lineIdx] = stack.peek();
        continue;
      }

      List<Token> tokens = lineTokens.tokens();
      Token first = tokens.get(0);
      String firstText = first.text().toLowerCase(Locale.ROOT);

      // Track macro boundaries; macro templates are skipped
      if (first.type == TokenType.TOK_MACRO
          || ".macro".equals(firstText)
          || ".mac".equals(firstText)) {
        inMacro = true;
        lineScopes[lineIdx] = stack.peek();
        continue;
      }
      if (first.type == TokenType.TOK_ENDMACRO
          || ".endmacro".equals(firstText)
          || ".endmac".equals(firstText)) {
        inMacro = false;
        lineScopes[lineIdx] = stack.peek();
        continue;
      }
      if (inMacro) {
        lineScopes[lineIdx] = stack.peek();
        continue;
      }

      // Scope openers: function, .proc, .scope
      if (first.type == TokenType.TOK_FUNCTION
          || "function".equals(firstText)
          || first.type == TokenType.TOK_PROC
          || ".proc".equals(firstText)
          || first.type == TokenType.TOK_SCOPE
          || ".scope".equals(firstText)) {
        AnonymousScope childScope = new AnonymousScope(stack.peek());
        stack.push(childScope);
        allScopes.add(childScope);
      }

      lineScopes[lineIdx] = stack.peek();

      // Collect anonymous label definitions on this line.
      // In function and call statements, colons are return/destination separators.
      boolean isFnOrCall =
          first.type == TokenType.TOK_FUNCTION
              || "function".equals(firstText)
              || first.type == TokenType.TOK_CALL
              || "call".equals(firstText);

      if (!isFnOrCall) {
        for (int i = 0; i < tokens.size(); i++) {
          Token t = tokens.get(i);
          if (t.type == TokenType.TOK_COLON) {
            boolean isPrecededByIdent =
                i > 0
                    && (tokens.get(i - 1).type == TokenType.TOK_IDENT
                        || tokens.get(i - 1).type == TokenType.TOK_LOCAL_IDENT);
            if (!isPrecededByIdent) {
              stack.peek().labels.add(new AnonymousLabelDef(lineIdx, t.getStartIndex()));
            }
          }
        }
      }

      // Scope closers: endfunction, .endproc, .endscope
      if (first.type == TokenType.TOK_ENDFUNCTION
          || "endfunction".equals(firstText)
          || first.type == TokenType.TOK_ENDPROC
          || ".endproc".equals(firstText)
          || first.type == TokenType.TOK_ENDSCOPE
          || ".endscope".equals(firstText)) {
        if (stack.size() > 1) {
          stack.pop();
        }
      }
    }

    return new ScopedModel(lineScopes, allScopes);
  }

  /**
   * Calculates the signed distance of a TOK_ULABEL reference. Positive distance represents forward
   * jumps (+1 for :+, +2 for :++), negative distance represents backward jumps (-1 for :-, -2 for
   * :--).
   */
  public static int getDistance(Token token) {
    if (token == null) {
      return 0;
    }
    String text = token.text();
    if (text == null || text.isBlank()) {
      return token.intVal;
    }
    int plus = 0;
    int minus = 0;
    for (int i = 0; i < text.length(); i++) {
      char ch = text.charAt(i);
      if (ch == '+') plus++;
      else if (ch == '-') minus++;
    }
    if (plus > 0) return plus;
    if (minus > 0) return -minus;
    return token.intVal;
  }

  /**
   * Resolves the 0-based target line index of a ca65 anonymous label reference token (TOK_ULABEL)
   * originating at [fromLineIdx].
   *
   * @param file the tokenized file
   * @param fromLineIdx the 0-based line index containing the reference
   * @param ulabelToken the TOK_ULABEL token (e.g. :+, :++, :-, :--)
   * @return the 0-based target line index, or empty if no matching label exists in scope
   */
  public static OptionalInt resolveTargetLine(
      TokenizedFile file, int fromLineIdx, Token ulabelToken) {
    if (file == null || ulabelToken == null || fromLineIdx < 0 || fromLineIdx >= file.textLines()) {
      return OptionalInt.empty();
    }

    ScopedModel model = buildScopedModel(file);
    AnonymousScope scope = model.lineScopes[fromLineIdx];
    if (scope == null) {
      return OptionalInt.empty();
    }

    int distance = getDistance(ulabelToken);
    if (distance == 0) {
      return OptionalInt.empty();
    }

    int refCol = ulabelToken.getStartIndex();

    if (distance < 0) {
      // Backward search (closest first)
      int wanted = -distance;
      List<AnonymousLabelDef> backward = new ArrayList<>();
      for (AnonymousLabelDef def : scope.labels) {
        if (def.lineIdx() < fromLineIdx
            || (def.lineIdx() == fromLineIdx && def.colStart() < refCol)) {
          backward.add(def);
        }
      }
      int count = backward.size();
      if (wanted <= count) {
        return OptionalInt.of(backward.get(count - wanted).lineIdx());
      }
      return OptionalInt.empty();
    } else {
      // Forward search (closest first)
      int wanted = distance;
      List<AnonymousLabelDef> forward = new ArrayList<>();
      for (AnonymousLabelDef def : scope.labels) {
        if (def.lineIdx() > fromLineIdx
            || (def.lineIdx() == fromLineIdx && def.colStart() > refCol)) {
          forward.add(def);
        }
      }
      if (wanted <= forward.size()) {
        return OptionalInt.of(forward.get(wanted - 1).lineIdx());
      }
      return OptionalInt.empty();
    }
  }

  /**
   * Validates all anonymous label references across [file]. Emits a syntax diagnostic error if a
   * branch target anonymous label does not exist.
   */
  public List<SyntaxDiagnostic> validate(TokenizedFile file) {
    if (file == null || file.isEmpty()) {
      return List.of();
    }

    ScopedModel model = buildScopedModel(file);
    List<SyntaxDiagnostic> diagnostics = new ArrayList<>();
    URI uri = file.uri();

    int totalLines = file.textLines();
    boolean inMacro = false;

    for (int lineIdx = 0; lineIdx < totalLines; lineIdx++) {
      Tokens lineTokens = file.getLine(lineIdx);
      if (lineTokens == null || lineTokens.tokens().isEmpty()) {
        continue;
      }

      int lineNum = lineIdx + 1; // 1-based
      List<Token> tokens = lineTokens.tokens();
      Token first = tokens.get(0);
      String firstText = first.text().toLowerCase(Locale.ROOT);

      if (first.type == TokenType.TOK_MACRO
          || ".macro".equals(firstText)
          || ".mac".equals(firstText)) {
        inMacro = true;
        continue;
      }
      if (first.type == TokenType.TOK_ENDMACRO
          || ".endmacro".equals(firstText)
          || ".endmac".equals(firstText)) {
        inMacro = false;
        continue;
      }
      if (inMacro) {
        continue;
      }

      AnonymousScope scope = model.lineScopes[lineIdx];
      if (scope == null) {
        continue;
      }

      for (Token t : tokens) {
        if (t.type == TokenType.TOK_ULABEL) {
          int distance = getDistance(t);
          if (distance == 0) {
            continue;
          }

          int refCol = t.getStartIndex();
          boolean found = false;

          if (distance < 0) {
            int wanted = -distance;
            int count = 0;
            for (AnonymousLabelDef def : scope.labels) {
              if (def.lineIdx() < lineIdx
                  || (def.lineIdx() == lineIdx && def.colStart() < refCol)) {
                count++;
              }
            }
            found = count >= wanted;
          } else {
            int wanted = distance;
            int count = 0;
            for (AnonymousLabelDef def : scope.labels) {
              if (def.lineIdx() > lineIdx
                  || (def.lineIdx() == lineIdx && def.colStart() > refCol)) {
                count++;
              }
            }
            found = count >= wanted;
          }

          if (!found) {
            diagnostics.add(
                SyntaxDiagnostic.error(
                    uri,
                    lineNum,
                    t.getStartIndex(),
                    t.getEndIndex(),
                    "ANONYMOUS_LABEL_NOT_FOUND",
                    String.format("Target anonymous label '%s' does not exist", t.text())));
          }
        }
      }
    }

    return diagnostics;
  }
}
