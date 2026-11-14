package dev.secondsun.retro.util.vo;

import dev.secondsun.retro.util.Token;
import dev.secondsun.retro.util.TokenType;
import dev.secondsun.retro.util.diagnostic.SyntaxDiagnostic;
import dev.secondsun.retro.util.diagnostic.SyntaxValidator;
import java.net.URI;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;

/**
 * Immutable, thread-safe lexical representation of an assembly source file, with lazily memoized
 * structural views ({@link ScopeTree}, {@link OccurrenceIndex}, and {@link SyntaxDiagnostic}s).
 */
public class TokenizedFile {
  public static final TokenizedFile EMPTY = new EmptyTokenizedFile();

  private final Map<Integer, Tokens> fileLines;
  private final int lineCount;
  public final URI uri;
  private final List<String> rawLines;

  private volatile ScopeTree cachedScopeTree;
  private volatile OccurrenceIndex cachedOccurrenceIndex;
  private volatile List<SyntaxDiagnostic> cachedDiagnostics;

  protected TokenizedFile(
      URI uri, List<String> rawLines, Map<Integer, Tokens> fileLines, int lineCount) {
    this.uri = uri;
    this.rawLines = rawLines != null ? List.copyOf(rawLines) : List.of();
    if (fileLines == null || fileLines.isEmpty()) {
      this.fileLines = Map.of();
    } else {
      this.fileLines = Collections.unmodifiableMap(new LinkedHashMap<>(fileLines));
    }
    this.lineCount = Math.max(0, lineCount);
  }

  public static TokenizedFile create(
      URI uri, List<String> rawLines, Map<Integer, Tokens> fileLines, int lineCount) {
    return new TokenizedFile(uri, rawLines, fileLines, lineCount);
  }

  public static TokenizedFile emptyWithUri(URI uri) {
    return new TokenizedFile(uri, List.of(), Map.of(), 0);
  }

  /** Returns an immutable copy bound to {@code newUri} (or {@code this} if already equal). */
  public TokenizedFile withUri(URI newUri) {
    if (Objects.equals(this.uri, newUri)) {
      return this;
    }
    return new TokenizedFile(newUri, this.rawLines, this.fileLines, this.lineCount);
  }

  public boolean isEmpty() {
    return textLines() == 0 || (fileLines.isEmpty() && rawLines.isEmpty());
  }

  public int textLines() {
    if (fileLines.isEmpty() && rawLines.isEmpty()) {
      return 0;
    }
    return Math.max(lineCount + 1, rawLines.size());
  }

  public List<String> getRawLines() {
    return rawLines;
  }

  public String getRawLine(int idx) {
    if (idx >= 0 && idx < rawLines.size()) {
      return rawLines.get(idx);
    }
    var thing = fileLines.get(idx);
    return thing != null ? thing.line() : "";
  }

  public String getLineText(int idx) {
    var thing = fileLines.get(idx);
    return thing != null ? thing.line() : "";
  }

  public List<Token> getLineTokens(int idx) {
    var thing = fileLines.get(idx);
    return thing != null ? thing.tokens() : List.of();
  }

  public URI uri() {
    return uri;
  }

  public void forEach(BiConsumer<Integer, Tokens> consumer) {
    fileLines.forEach(consumer);
  }

  public Tokens getLine(int idx) {
    return fileLines.get(idx);
  }

  /** Returns the lazily constructed, memoized {@link ScopeTree} for this file. */
  public ScopeTree scopeTree() {
    ScopeTree tree = cachedScopeTree;
    if (tree == null) {
      synchronized (this) {
        tree = cachedScopeTree;
        if (tree == null) {
          tree = ScopeTree.build(this);
          cachedScopeTree = tree;
        }
      }
    }
    return tree;
  }

  /** Returns the lazily constructed, memoized {@link OccurrenceIndex} for this file. */
  public OccurrenceIndex occurrenceIndex() {
    OccurrenceIndex occ = cachedOccurrenceIndex;
    if (occ == null) {
      synchronized (this) {
        occ = cachedOccurrenceIndex;
        if (occ == null) {
          occ = OccurrenceIndex.build(this);
          cachedOccurrenceIndex = occ;
        }
      }
    }
    return occ;
  }

  /** Returns the lazily computed, memoized syntax diagnostics for this file. */
  public List<SyntaxDiagnostic> getDiagnostics() {
    List<SyntaxDiagnostic> diags = cachedDiagnostics;
    if (diags == null) {
      synchronized (this) {
        diags = cachedDiagnostics;
        if (diags == null) {
          diags = new SyntaxValidator().validate(this);
          cachedDiagnostics = diags;
        }
      }
    }
    return diags;
  }

  public void setRawLines(List<String> rawLines) {
    throw new UnsupportedOperationException("TokenizedFile is immutable");
  }

  public void addLine(String line, int index, List<Token> tokens) {
    throw new UnsupportedOperationException("TokenizedFile is immutable");
  }

  public void addDiagnostic(SyntaxDiagnostic diagnostic) {
    throw new UnsupportedOperationException("TokenizedFile is immutable");
  }

  public void addDiagnostics(Collection<SyntaxDiagnostic> newDiagnostics) {
    throw new UnsupportedOperationException("TokenizedFile is immutable");
  }

  public void clearDiagnostics() {
    throw new UnsupportedOperationException("TokenizedFile is immutable");
  }

  /**
   * Retrieves the token at the specified line and character column.
   *
   * @param line 0-based line index
   * @param col 0-based character column index
   * @return Optional containing the token, or empty if out of bounds or not found
   */
  public Optional<Token> getTokenAt(int line, int col) {
    if (line < 0 || line >= textLines()) {
      return Optional.empty();
    }
    List<Token> tokens = getLineTokens(line);
    if (tokens == null || tokens.isEmpty()) {
      return Optional.empty();
    }
    for (Token t : tokens) {
      if (t.getStartIndex() <= col && col <= t.getEndIndex()) {
        return Optional.of(t);
      }
    }
    return Optional.empty();
  }

  /**
   * Reconstructs the fully qualified symbol at or adjacent to the given line and column, such as
   * {@code scope::symbol}, {@code ::global_var}, or a standalone identifier.
   *
   * @param line 0-based line index
   * @param col 0-based character column index
   * @return Optional containing the qualified symbol string, or empty if none found
   */
  public Optional<String> getQualifiedSymbolAt(int line, int col) {
    if (line < 0 || line >= textLines()) {
      return Optional.empty();
    }
    List<Token> tokens = getLineTokens(line);
    if (tokens == null || tokens.isEmpty()) {
      return Optional.empty();
    }

    for (int i = 0; i < tokens.size(); i++) {
      Token t = tokens.get(i);
      if (t.getType() == TokenType.TOK_NAMESPACE) {
        if (isCursorOnOrAdjacentToNamespace(tokens, i, col)) {
          String qualifiedName = reconstructQualifiedSymbol(tokens, i);
          if (qualifiedName != null && !qualifiedName.isBlank()) {
            return Optional.of(qualifiedName.trim());
          }
        }
      }
    }

    for (int i = 0; i < tokens.size(); i++) {
      Token t = tokens.get(i);
      if (t.getType() == TokenType.TOK_IDENT || t.getType() == TokenType.TOK_LOCAL_IDENT) {
        if (t.getStartIndex() <= col && col <= t.getEndIndex()) {
          if ((i > 0 && tokens.get(i - 1).getType() == TokenType.TOK_NAMESPACE)
              || (i + 1 < tokens.size()
                  && tokens.get(i + 1).getType() == TokenType.TOK_NAMESPACE)) {
            String qualifiedName = reconstructQualifiedSymbol(tokens, i);
            if (qualifiedName != null && !qualifiedName.isBlank()) {
              return Optional.of(qualifiedName.trim());
            }
          }
          String text = t.text();
          if (text != null && !text.isBlank()) {
            return Optional.of(text.trim());
          }
        }
      }
    }

    return Optional.empty();
  }

  private boolean isCursorOnOrAdjacentToNamespace(List<Token> tokenList, int nsIndex, int column) {
    Token nsToken = tokenList.get(nsIndex);
    if (column >= nsToken.getStartIndex() - 1 && column <= nsToken.getEndIndex() + 1) {
      return true;
    }
    if (nsIndex + 1 < tokenList.size()) {
      Token next = tokenList.get(nsIndex + 1);
      if (next.getStartIndex() == nsToken.getEndIndex()) {
        if (column >= next.getStartIndex() && column <= next.getEndIndex()) {
          return true;
        }
      }
    }
    if (nsIndex > 0) {
      Token prev = tokenList.get(nsIndex - 1);
      if (prev.getEndIndex() == nsToken.getStartIndex()) {
        if (column >= prev.getStartIndex() && column <= prev.getEndIndex()) {
          return true;
        }
      }
    }
    return false;
  }

  private String reconstructQualifiedSymbol(List<Token> tokens, int centerIndex) {
    int start = centerIndex;
    int end = centerIndex;

    while (start > 0) {
      Token curr = tokens.get(start);
      Token prev = tokens.get(start - 1);
      if (curr.getType() == TokenType.TOK_NAMESPACE) {
        if ((prev.getType() == TokenType.TOK_IDENT || prev.getType() == TokenType.TOK_LOCAL_IDENT)
            && prev.getEndIndex() == curr.getStartIndex()) {
          start--;
        } else {
          break;
        }
      } else if (curr.getType() == TokenType.TOK_IDENT
          || curr.getType() == TokenType.TOK_LOCAL_IDENT) {
        if (prev.getType() == TokenType.TOK_NAMESPACE
            && prev.getEndIndex() == curr.getStartIndex()) {
          start--;
        } else {
          break;
        }
      } else {
        break;
      }
    }

    while (end + 1 < tokens.size()) {
      Token curr = tokens.get(end);
      Token next = tokens.get(end + 1);
      if (curr.getType() == TokenType.TOK_NAMESPACE) {
        if ((next.getType() == TokenType.TOK_IDENT || next.getType() == TokenType.TOK_LOCAL_IDENT)
            && next.getStartIndex() == curr.getEndIndex()) {
          end++;
        } else {
          break;
        }
      } else if (curr.getType() == TokenType.TOK_IDENT
          || curr.getType() == TokenType.TOK_LOCAL_IDENT) {
        if (next.getType() == TokenType.TOK_NAMESPACE
            && next.getStartIndex() == curr.getEndIndex()) {
          end++;
        } else {
          break;
        }
      } else {
        break;
      }
    }

    StringBuilder sb = new StringBuilder();
    for (int k = start; k <= end; k++) {
      sb.append(tokens.get(k).text());
    }
    return sb.toString();
  }

  /**
   * Checks whether the specified line contains exclusively a label definition (e.g. {@code label:}
   * or {@code @local:}) with no opcode or directive.
   *
   * @param line 0-based line index
   * @return true if the line defines only a label
   */
  public boolean isLabelOnly(int line) {
    if (line < 0 || line >= textLines()) {
      return false;
    }
    List<Token> tokens = getLineTokens(line);
    if (tokens == null || tokens.isEmpty()) {
      return false;
    }
    String lineText = getLineText(line).trim();
    if (tokens.size() == 1 && (tokens.get(0).text().endsWith(":") || lineText.endsWith(":"))) {
      return true;
    }
    if (tokens.size() == 2 && tokens.get(1).getType() == TokenType.TOK_COLON) {
      return true;
    }
    return lineText.endsWith(":")
        && tokens.get(tokens.size() - 1).getType() == TokenType.TOK_COLON
        && tokens.size() <= 2;
  }

  /**
   * Returns the primary opcode/instruction token on the line, skipping leading labels and ignoring
   * directive lines starting with a dot.
   *
   * @param line 0-based line index
   * @return Optional containing the primary opcode token, or empty if none found
   */
  public Optional<Token> getPrimaryOpcode(int line) {
    if (line < 0 || line >= textLines()) {
      return Optional.empty();
    }
    List<Token> tokens = getLineTokens(line);
    if (tokens == null || tokens.isEmpty()) {
      return Optional.empty();
    }

    for (int i = 0; i < tokens.size(); i++) {
      Token t = tokens.get(i);
      if (t.text() != null && t.text().startsWith(".")) {
        return Optional.empty();
      }
      if (t.text() != null && t.text().endsWith(":")) {
        continue;
      }
      if (t.getType() == TokenType.TOK_COLON) {
        continue;
      }
      if (i + 1 < tokens.size() && tokens.get(i + 1).getType() == TokenType.TOK_COLON) {
        i++;
        continue;
      }
      return Optional.of(t);
    }
    return Optional.empty();
  }

  private static final class EmptyTokenizedFile extends TokenizedFile {
    EmptyTokenizedFile() {
      super(null, List.of(), Map.of(), 0);
    }

    @Override
    public boolean isEmpty() {
      return true;
    }

    @Override
    public int textLines() {
      return 0;
    }

    @Override
    public ScopeTree scopeTree() {
      return ScopeTree.EMPTY;
    }

    @Override
    public OccurrenceIndex occurrenceIndex() {
      return OccurrenceIndex.EMPTY;
    }

    @Override
    public List<SyntaxDiagnostic> getDiagnostics() {
      return List.of();
    }
  }
}
