package dev.secondsun.retro.util.diagnostic;

import java.net.URI;
import java.util.Objects;

/**
 * Represents a structured syntax or static analysis diagnostic.
 *
 * @param uri the file URI where this diagnostic occurred (nullable for snippets)
 * @param line the 1-based line number (LSP standard lines in diagnostics are 0-based, but 1-based
 *     in editors/logs; standardizing on 1-based here)
 * @param columnStart the 0-based start character offset
 * @param columnEnd the 0-based end character offset
 * @param code canonical diagnostic rule code (e.g. UNCLOSED_BLOCK, MISSING_OPERAND)
 * @param severity the severity level
 * @param message human-readable explanation of the issue
 */
public record SyntaxDiagnostic(
    URI uri,
    int line,
    int columnStart,
    int columnEnd,
    String code,
    DiagnosticSeverity severity,
    String message) {

  public SyntaxDiagnostic {
    Objects.requireNonNull(code, "code must not be null");
    Objects.requireNonNull(severity, "severity must not be null");
    Objects.requireNonNull(message, "message must not be null");
  }

  public static SyntaxDiagnostic error(
      URI uri, int line, int columnStart, int columnEnd, String code, String message) {
    return new SyntaxDiagnostic(
        uri, line, columnStart, columnEnd, code, DiagnosticSeverity.ERROR, message);
  }

  public static SyntaxDiagnostic warning(
      URI uri, int line, int columnStart, int columnEnd, String code, String message) {
    return new SyntaxDiagnostic(
        uri, line, columnStart, columnEnd, code, DiagnosticSeverity.WARNING, message);
  }

  public static SyntaxDiagnostic info(
      URI uri, int line, int columnStart, int columnEnd, String code, String message) {
    return new SyntaxDiagnostic(
        uri, line, columnStart, columnEnd, code, DiagnosticSeverity.INFO, message);
  }
}
