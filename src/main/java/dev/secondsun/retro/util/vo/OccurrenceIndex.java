package dev.secondsun.retro.util.vo;

import dev.secondsun.retro.util.Token;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Immutable per-file identifier and token occurrence map.
 *
 * <p>Eliminates {@code O(TotalWorkspaceLines * TokensPerLine)} scans during reference and symbol
 * queries by indexing every identifier-like token in a single linear pass per {@link
 * TokenizedFile}.
 */
public final class OccurrenceIndex {

  public static final OccurrenceIndex EMPTY = new OccurrenceIndex(Map.of());

  /** Single occurrence of a token at a 0-based line and token-list index. */
  public record Occurrence(int line, int tokenIndex, Token token) {}

  private final Map<String, List<Occurrence>> byLowerName;

  private OccurrenceIndex(Map<String, List<Occurrence>> byLowerName) {
    this.byLowerName = byLowerName;
  }

  public static OccurrenceIndex build(TokenizedFile file) {
    if (file == null || file.isEmpty()) {
      return EMPTY;
    }
    Map<String, List<Occurrence>> map = new LinkedHashMap<>();
    int totalLines = file.textLines();
    for (int lineIdx = 0; lineIdx < totalLines; lineIdx++) {
      List<Token> tokens = file.getLineTokens(lineIdx);
      if (tokens == null || tokens.isEmpty()) {
        continue;
      }
      for (int tokIdx = 0; tokIdx < tokens.size(); tokIdx++) {
        Token t = tokens.get(tokIdx);
        if (!isIndexableToken(t)) {
          continue;
        }
        String text = t.text();
        if (text == null || text.isBlank()) {
          continue;
        }
        String key = text.toLowerCase(Locale.ROOT);
        map.computeIfAbsent(key, k -> new ArrayList<>()).add(new Occurrence(lineIdx, tokIdx, t));
      }
    }

    Map<String, List<Occurrence>> frozen = new LinkedHashMap<>(map.size());
    for (Map.Entry<String, List<Occurrence>> entry : map.entrySet()) {
      frozen.put(entry.getKey(), List.copyOf(entry.getValue()));
    }
    return new OccurrenceIndex(Collections.unmodifiableMap(frozen));
  }

  /** Returns all occurrences matching {@code name} (case-insensitively) in line order. */
  public List<Occurrence> lookup(String name) {
    if (name == null || name.isBlank()) {
      return List.of();
    }
    List<Occurrence> list = byLowerName.get(name.toLowerCase(Locale.ROOT));
    return list != null ? list : List.of();
  }

  /** Returns true if this file contains at least one occurrence of any candidate lowercase key. */
  public boolean containsAny(Set<String> lowerKeys) {
    if (lowerKeys == null || lowerKeys.isEmpty() || byLowerName.isEmpty()) {
      return false;
    }
    for (String key : lowerKeys) {
      if (byLowerName.containsKey(key)) {
        return true;
      }
    }
    return false;
  }

  public Set<String> indexedKeys() {
    return byLowerName.keySet();
  }

  private static boolean isIndexableToken(Token t) {
    if (t == null) {
      return false;
    }
    return switch (t.type) {
      case TOK_STRCON,
          TOK_CHARCON,
          TOK_INTCON,
          TOK_NONE,
          TOK_EOF,
          TOK_SEP,
          TOK_NAMESPACE,
          TOK_COLON,
          TOK_COMMA,
          TOK_HASH,
          TOK_LPAREN,
          TOK_RPAREN,
          TOK_LBRACK,
          TOK_RBRACK,
          TOK_LCURLY,
          TOK_RCURLY ->
          false;
      default -> true;
    };
  }
}
