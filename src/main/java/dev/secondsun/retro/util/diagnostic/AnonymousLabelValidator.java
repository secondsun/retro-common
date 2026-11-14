package dev.secondsun.retro.util.diagnostic;

import dev.secondsun.retro.util.Token;
import dev.secondsun.retro.util.TokenType;
import dev.secondsun.retro.util.vo.ScopeTree;
import dev.secondsun.retro.util.vo.TokenizedFile;
import dev.secondsun.retro.util.vo.Tokens;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

/**
 * Validates ca65 anonymous label references (e.g. {@code :+}, {@code :++}, {@code :-}, {@code :--})
 * ensuring each reference resolves to an existing anonymous label definition ({@code :}) within the
 * same lexical scope (including {@code function}, {@code inline function}, {@code .proc}, and
 * {@code .scope}).
 */
public class AnonymousLabelValidator {

  public record AnonymousLabelDef(int lineIdx, int colStart) {}

  /**
   * Calculates the signed distance of a {@link TokenType#TOK_ULABEL} reference. Positive distance
   * represents forward jumps ({@code +1} for {@code :+}, {@code +2} for {@code :++}), negative
   * distance represents backward jumps ({@code -1} for {@code :-}, {@code -2} for {@code :--}).
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
      if (ch == '+') {
        plus++;
      } else if (ch == '-') {
        minus++;
      }
    }
    if (plus > 0) {
      return plus;
    }
    if (minus > 0) {
      return -minus;
    }
    return token.intVal;
  }

  /**
   * Resolves the 0-based target line index of a ca65 anonymous label reference token ({@code
   * TOK_ULABEL}) originating at {@code fromLineIdx}.
   *
   * @param file the tokenized file
   * @param fromLineIdx the 0-based line index containing the reference
   * @param ulabelToken the {@code TOK_ULABEL} token (e.g. {@code :+}, {@code :++}, {@code :-})
   * @return the 0-based target line index, or empty if no matching label exists in scope
   */
  public static OptionalInt resolveTargetLine(
      TokenizedFile file, int fromLineIdx, Token ulabelToken) {
    if (file == null || ulabelToken == null || fromLineIdx < 0 || fromLineIdx >= file.textLines()) {
      return OptionalInt.empty();
    }

    int distance = getDistance(ulabelToken);
    if (distance == 0) {
      return OptionalInt.empty();
    }

    List<ScopeTree.AnonymousLabelDef> defs = file.scopeTree().anonymousLabelsForLine(fromLineIdx);
    int refCol = ulabelToken.getStartIndex();

    if (distance < 0) {
      int wanted = -distance;
      List<ScopeTree.AnonymousLabelDef> backward = new ArrayList<>();
      for (ScopeTree.AnonymousLabelDef def : defs) {
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
      int wanted = distance;
      List<ScopeTree.AnonymousLabelDef> forward = new ArrayList<>();
      for (ScopeTree.AnonymousLabelDef def : defs) {
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
   * Validates all anonymous label references across {@code file}. Emits a syntax diagnostic error
   * if a branch target anonymous label does not exist in the enclosing scope.
   */
  public List<SyntaxDiagnostic> validate(TokenizedFile file) {
    if (file == null || file.isEmpty()) {
      return List.of();
    }

    ScopeTree tree = file.scopeTree();
    List<SyntaxDiagnostic> diagnostics = new ArrayList<>();
    URI uri = file.uri();

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
      for (Token t : lineTokens.tokens()) {
        if (t.type == TokenType.TOK_ULABEL) {
          int distance = getDistance(t);
          if (distance == 0) {
            continue;
          }
          if (resolveTargetLine(file, lineIdx, t).isEmpty()) {
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
