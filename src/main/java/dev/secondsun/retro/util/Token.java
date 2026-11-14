package dev.secondsun.retro.util;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** Immutable lexical token produced by {@link CA65Scanner}. */
public final class Token {

  public final TokenType type;
  public final int startIndex;
  public final int endIndex;
  public final String message;
  public final int lineNumber;
  public final int intVal;

  private final String text;
  private final Set<TokenAttribute> attributes;

  public Token(
      TokenType type,
      int startIndex,
      int endIndex,
      String text,
      String message,
      int lineNumber,
      int intVal,
      Set<TokenAttribute> attributes) {
    this.type = Objects.requireNonNullElse(type, TokenType.TOK_NONE);
    this.startIndex = startIndex;
    this.endIndex = endIndex;
    this.text = text != null ? text : "";
    this.message = message;
    this.lineNumber = lineNumber;
    this.intVal = intVal;
    if (attributes == null || attributes.isEmpty()) {
      this.attributes = Set.of();
    } else {
      EnumSet<TokenAttribute> copy = EnumSet.copyOf(attributes);
      this.attributes = Collections.unmodifiableSet(copy);
    }
  }

  public int getStartIndex() {
    return startIndex;
  }

  public int getEndIndex() {
    return endIndex;
  }

  public String text() {
    return text;
  }

  public TokenType getType() {
    return type;
  }

  public int lineNumber() {
    return lineNumber;
  }

  public int intVal() {
    return intVal;
  }

  public String message() {
    return message;
  }

  public Set<TokenAttribute> attributes() {
    return attributes;
  }

  public boolean hasAttribute(TokenAttribute attribute) {
    return attribute != null && attributes.contains(attribute);
  }

  /** Returns a new immutable {@link Token} carrying the additional {@link TokenAttribute}. */
  public Token withAttribute(TokenAttribute attribute) {
    if (attribute == null || attributes.contains(attribute)) {
      return this;
    }
    EnumSet<TokenAttribute> next =
        attributes.isEmpty() ? EnumSet.of(attribute) : EnumSet.copyOf(attributes);
    next.add(attribute);
    return new Token(type, startIndex, endIndex, text, message, lineNumber, intVal, next);
  }

  /** Returns a new immutable {@link Token} marked with {@link TokenAttribute#ERROR} and message. */
  public Token withError(String errorMessage) {
    EnumSet<TokenAttribute> next =
        attributes.isEmpty() ? EnumSet.of(TokenAttribute.ERROR) : EnumSet.copyOf(attributes);
    next.add(TokenAttribute.ERROR);
    return new Token(type, startIndex, endIndex, text, errorMessage, lineNumber, intVal, next);
  }

  /** Returns a new immutable {@link Token} at {@code newLineNumber}. */
  public Token withLineNumber(int newLineNumber) {
    if (this.lineNumber == newLineNumber) {
      return this;
    }
    return new Token(type, startIndex, endIndex, text, message, newLineNumber, intVal, attributes);
  }

  static final class Builder {
    TokenType type = TokenType.TOK_NONE;
    int startIndex;
    int endIndex;
    String message;
    int lineNumber;
    int intVal;
    final StringBuilder text = new StringBuilder();
    EnumSet<TokenAttribute> attributes;

    void appendChar(char ch) {
      text.append(ch);
    }

    String currentText() {
      return text.toString();
    }

    String text() {
      return text.toString();
    }

    Token build() {
      return build(this.lineNumber);
    }

    void addAttribute(TokenAttribute attribute) {
      if (attribute != null) {
        if (attributes == null) {
          attributes = EnumSet.of(attribute);
        } else {
          attributes.add(attribute);
        }
      }
    }

    Token build(int resolvedLineNumber) {
      return new Token(
          type,
          startIndex,
          endIndex,
          text.toString(),
          message,
          resolvedLineNumber,
          intVal,
          attributes);
    }
  }

  @Override
  public String toString() {
    return "Token[" + type + ", '" + text + "', " + startIndex + ".." + endIndex + "]";
  }
}
