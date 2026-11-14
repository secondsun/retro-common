package dev.secondsun.retro.util.vo;

import java.util.List;

/**
 * Structured result of a workspace symbol reference query.
 *
 * @param symbol queried symbol name
 * @param totalReferences total number of matching references before capping
 * @param references capped list of {@link ReferenceLocation}s
 * @param ambiguous true when multiple unrelated definitions matched without line context
 * @param definitionCount number of distinct definition sites found
 */
public record ReferenceResult(
    String symbol,
    int totalReferences,
    List<ReferenceLocation> references,
    boolean ambiguous,
    int definitionCount) {
  public ReferenceResult {
    references = references != null ? List.copyOf(references) : List.of();
  }
}
