package dev.secondsun.retro.util.vo;

import dev.secondsun.retro.util.Token;
import dev.secondsun.retro.util.TokenType;
import java.util.List;
import java.util.Optional;

/**
 * Immutable parsed representation of a ca65 immediate operand (following {@code #}), capturing
 * whether the operand evaluates to a compile-time literal, its signed value, 16-bit unsigned value,
 * and whether byte-masking ({@code #<(...)} or {@code #>(...)}) was applied.
 *
 * @param isConstant true when the immediate evaluates to a literal numeric/char constant
 * @param signedValue signed 32-bit value when {@code isConstant} is true (after byte-masking if
 *     {@code isByteMasked} is true)
 * @param rawValue unmasked signed value when {@code isByteMasked} is true (e.g. {@code -16} for
 *     {@code #<(-16)}), otherwise equal to {@code signedValue}
 * @param isByteMasked true when prefixed with low-byte {@code <} or high-byte {@code >} operator
 * @param rawText raw source text following {@code #} (without the leading {@code #})
 * @param startColumn 0-based start column of the {@code #} token
 * @param endColumn 0-based exclusive end column of the immediate operand
 */
public record ImmediateLiteral(
    boolean isConstant,
    int signedValue,
    int rawValue,
    boolean isByteMasked,
    String rawText,
    int startColumn,
    int endColumn) {

  /** Unsigned 16-bit representation ({@code 0..0xFFFF}) of {@link #signedValue()}. */
  public int unsigned16Value() {
    return signedValue & 0xFFFF;
  }

  /**
   * Parses an immediate operand starting at {@code hashTokenIdx} (which must be {@link
   * TokenType#TOK_HASH}) up to the next top-level comma or end of line.
   *
   * @param tokens token list for the line
   * @param hashTokenIdx index of the {@code #} token
   * @param lineText optional raw line text for exact slice reconstruction
   * @return parsed {@link ImmediateLiteral}, or empty if {@code hashTokenIdx} is invalid or has no
   *     following operand tokens
   */
  public static Optional<ImmediateLiteral> parse(
      List<Token> tokens, int hashTokenIdx, String lineText) {
    if (tokens == null || hashTokenIdx < 0 || hashTokenIdx >= tokens.size()) {
      return Optional.empty();
    }
    Token hash = tokens.get(hashTokenIdx);
    if (hash.type != TokenType.TOK_HASH) {
      return Optional.empty();
    }
    int exprStart = hashTokenIdx + 1;
    if (exprStart >= tokens.size()) {
      return Optional.empty();
    }

    int parenDepth = 0;
    int exprEnd = exprStart;
    while (exprEnd < tokens.size()) {
      Token t = tokens.get(exprEnd);
      if (t.type == TokenType.TOK_LPAREN) {
        parenDepth++;
      } else if (t.type == TokenType.TOK_RPAREN) {
        parenDepth--;
      } else if (t.type == TokenType.TOK_COMMA && parenDepth <= 0) {
        break;
      }
      exprEnd++;
    }
    if (exprEnd <= exprStart) {
      return Optional.empty();
    }

    int startCol = hash.getStartIndex();
    int endCol = tokens.get(exprEnd - 1).getEndIndex();
    String rawText = sliceOrJoin(tokens, exprStart, exprEnd, lineText);

    List<Token> slice = tokens.subList(exprStart, exprEnd);
    Token first = slice.get(0);

    // Low-byte #<expr or high-byte #>expr
    if (first.type == TokenType.TOK_LT || first.type == TokenType.TOK_GT) {
      boolean lowByte = first.type == TokenType.TOK_LT;
      Optional<Integer> inner = evalSimpleConstant(slice.subList(1, slice.size()));
      if (inner.isPresent()) {
        int raw = inner.get();
        int masked = lowByte ? (raw & 0xFF) : ((raw >>> 8) & 0xFF);
        return Optional.of(
            new ImmediateLiteral(true, masked, raw, true, rawText, startCol, endCol));
      }
      return Optional.of(new ImmediateLiteral(false, 0, 0, true, rawText, startCol, endCol));
    }

    Optional<Integer> constant = evalSimpleConstant(slice);
    if (constant.isPresent()) {
      int val = constant.get();
      return Optional.of(new ImmediateLiteral(true, val, val, false, rawText, startCol, endCol));
    }

    return Optional.of(new ImmediateLiteral(false, 0, 0, false, rawText, startCol, endCol));
  }

  private static Optional<Integer> evalSimpleConstant(List<Token> slice) {
    if (slice.isEmpty()) {
      return Optional.empty();
    }
    // Unwrap balanced outer parentheses: e.g. (-16) or ($10)
    while (slice.size() >= 3
        && slice.get(0).type == TokenType.TOK_LPAREN
        && slice.get(slice.size() - 1).type == TokenType.TOK_RPAREN
        && isSingleBalancedGroup(slice)) {
      slice = slice.subList(1, slice.size() - 1);
    }

    if (slice.size() == 1) {
      Token tok = slice.get(0);
      if (tok.type == TokenType.TOK_INTCON || tok.type == TokenType.TOK_CHARCON) {
        return Optional.of(tok.intVal);
      }
      return Optional.empty();
    }

    if (slice.size() == 2) {
      Token sign = slice.get(0);
      Token lit = slice.get(1);
      if ((lit.type == TokenType.TOK_INTCON || lit.type == TokenType.TOK_CHARCON)) {
        if (sign.type == TokenType.TOK_MINUS) {
          return Optional.of(-lit.intVal);
        }
        if (sign.type == TokenType.TOK_PLUS) {
          return Optional.of(lit.intVal);
        }
        if (sign.type == TokenType.TOK_NOT) {
          return Optional.of(~lit.intVal);
        }
      }
    }
    return Optional.empty();
  }

  private static boolean isSingleBalancedGroup(List<Token> tokens) {
    int depth = 0;
    for (int i = 0; i < tokens.size(); i++) {
      TokenType t = tokens.get(i).type;
      if (t == TokenType.TOK_LPAREN) {
        depth++;
      } else if (t == TokenType.TOK_RPAREN) {
        depth--;
        if (depth == 0 && i < tokens.size() - 1) {
          return false;
        }
      }
    }
    return depth == 0;
  }

  private static String sliceOrJoin(
      List<Token> tokens, int exprStart, int exprEnd, String lineText) {
    int firstStart = tokens.get(exprStart).getStartIndex();
    int lastEnd = tokens.get(exprEnd - 1).getEndIndex();
    if (lineText != null
        && firstStart >= 0
        && lastEnd <= lineText.length()
        && firstStart <= lastEnd) {
      String sub = lineText.substring(firstStart, lastEnd).trim();
      if (!sub.isEmpty()) {
        return sub;
      }
    }
    StringBuilder sb = new StringBuilder();
    for (int i = exprStart; i < exprEnd; i++) {
      sb.append(tokens.get(i).text());
    }
    return sb.toString();
  }
}
