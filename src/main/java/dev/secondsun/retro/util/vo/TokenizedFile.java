package dev.secondsun.retro.util.vo;

import dev.secondsun.retro.util.Token;
import dev.secondsun.retro.util.TokenType;
import dev.secondsun.retro.util.diagnostic.SyntaxDiagnostic;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;

public class TokenizedFile {
  public static final TokenizedFile EMPTY = new EmptyTokenizedFile();
  private Map<Integer, Tokens> fileLines = new HashMap<>();
  private Integer lineCount = 0;
  public URI uri;
  private List<String> rawLines = new ArrayList<>();
  private final List<SyntaxDiagnostic> diagnostics = new ArrayList<>();

  public boolean isEmpty() {
    return textLines() == 0 || (fileLines.isEmpty() && rawLines.isEmpty());
  }

  public int textLines() {
    return Math.max(lineCount + 1, rawLines.size());
  }

  public void setRawLines(List<String> rawLines) {
    this.rawLines = rawLines;
    if (!rawLines.isEmpty() && rawLines.size() - 1 > lineCount) {
      lineCount = rawLines.size() - 1;
    }
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

  public void addLine(String line, int index, List<Token> tokens) {
    fileLines.put(index, new Tokens(line, tokens));
    if (index > lineCount) {
      lineCount = index;
    }
  }

  public String getLineText(int idx) {
    var thing = fileLines.get(idx);
    if (thing == null) {
      return "";
    }
    return fileLines.get(idx).line();
  }

  public List<Token> getLineTokens(int idx) {
    var thing = fileLines.get(idx);
    if (thing == null) {
      return List.of();
    }
    return fileLines.get(idx).tokens();
  }

  public URI uri() {
    return uri;
  }

  public void forEach(BiConsumer<Integer, Tokens> object) {
    fileLines.forEach(object);
  }

  public Tokens getLine(int idx) {
    return fileLines.get(idx);
  }

  public List<SyntaxDiagnostic> getDiagnostics() {
    return Collections.unmodifiableList(diagnostics);
  }

  public void addDiagnostic(SyntaxDiagnostic diagnostic) {
    if (diagnostic != null) {
      diagnostics.add(diagnostic);
    }
  }

  public void addDiagnostics(Collection<SyntaxDiagnostic> newDiagnostics) {
    if (newDiagnostics != null) {
      diagnostics.addAll(newDiagnostics);
    }
  }

  public void clearDiagnostics() {
    diagnostics.clear();
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

    // 1. Check if cursor is on or adjacent to a namespace delimiter "::"
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

    // 2. Check if cursor is on an identifier or local identifier
    for (int i = 0; i < tokens.size(); i++) {
      Token t = tokens.get(i);
      if (t.getType() == TokenType.TOK_IDENT || t.getType() == TokenType.TOK_LOCAL_IDENT) {
        if (t.getStartIndex() <= col && col <= t.getEndIndex()) {
          // If adjacent to a namespace token, reconstruct qualified symbol
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
    // Directly on the namespace token or within 1 character of it
    if (column >= nsToken.getStartIndex() - 1 && column <= nsToken.getEndIndex() + 1) {
      return true;
    }
    // On the contiguous token immediately after "::"
    if (nsIndex + 1 < tokenList.size()) {
      Token next = tokenList.get(nsIndex + 1);
      if (next.getStartIndex() == nsToken.getEndIndex()) {
        if (column >= next.getStartIndex() && column <= next.getEndIndex()) {
          return true;
        }
      }
    }
    // On the contiguous token immediately before "::"
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

    // Scan backwards from centerIndex
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

    // Scan forwards from centerIndex
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
    @Override
    public boolean isEmpty() {
      return true;
    }

    @Override
    public int textLines() {
      return 0;
    }

    @Override
    public List<String> getRawLines() {
      return List.of();
    }

    @Override
    public String getRawLine(int idx) {
      return "";
    }

    @Override
    public String getLineText(int idx) {
      return "";
    }

    @Override
    public List<Token> getLineTokens(int idx) {
      return List.of();
    }

    @Override
    public Tokens getLine(int idx) {
      return null;
    }

    @Override
    public void addLine(String line, int index, List<Token> tokens) {
      throw new UnsupportedOperationException("TokenizedFile.EMPTY is immutable");
    }

    @Override
    public void setRawLines(List<String> rawLines) {
      throw new UnsupportedOperationException("TokenizedFile.EMPTY is immutable");
    }

    @Override
    public void addDiagnostic(SyntaxDiagnostic diagnostic) {
      throw new UnsupportedOperationException("TokenizedFile.EMPTY is immutable");
    }

    @Override
    public void addDiagnostics(Collection<SyntaxDiagnostic> newDiagnostics) {
      throw new UnsupportedOperationException("TokenizedFile.EMPTY is immutable");
    }

    @Override
    public void clearDiagnostics() {
      throw new UnsupportedOperationException("TokenizedFile.EMPTY is immutable");
    }
  }
}
