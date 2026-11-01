package dev.secondsun;

import static org.junit.jupiter.api.Assertions.*;

import dev.secondsun.retro.util.CA65Scanner;
import dev.secondsun.retro.util.TokenType;
import dev.secondsun.retro.util.diagnostic.AnonymousLabelValidator;
import dev.secondsun.retro.util.diagnostic.SyntaxDiagnostic;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

public class AnonymousLabelValidatorTest {

  private final AnonymousLabelValidator validator = new AnonymousLabelValidator();

  @Test
  public void testValidBackwardAndForwardLabels() {
    var code =
        """
        :   nop
            bra :-
            bra :+
        :   rts
        """;
    var file = new CA65Scanner().tokenize(code);
    List<SyntaxDiagnostic> diags = validator.validate(file);
    assertTrue(diags.isEmpty(), "Expected no diagnostics, found: " + diags);
  }

  @Test
  public void testMultiHopAnonymousLabels() {
    var code =
        """
        :   nop
        :   nop
            bra :--
            bra :++
        :   nop
        :   rts
        """;
    var file = new CA65Scanner().tokenize(code);
    List<SyntaxDiagnostic> diags = validator.validate(file);
    assertTrue(diags.isEmpty(), "Expected no diagnostics, found: " + diags);
  }

  @Test
  public void testMissingBackwardAnonymousLabel() {
    var code =
        """
            bra :-
        :   rts
        """;
    var file = new CA65Scanner().tokenize(code);
    List<SyntaxDiagnostic> diags = validator.validate(file);
    assertEquals(1, diags.size());
    assertEquals("ANONYMOUS_LABEL_NOT_FOUND", diags.get(0).code());
    assertEquals("Target anonymous label ':-' does not exist", diags.get(0).message());
  }

  @Test
  public void testMissingForwardAnonymousLabel() {
    var code =
        """
        :   nop
            bra :++
        :   rts
        """;
    var file = new CA65Scanner().tokenize(code);
    List<SyntaxDiagnostic> diags = validator.validate(file);
    assertEquals(1, diags.size());
    assertEquals("ANONYMOUS_LABEL_NOT_FOUND", diags.get(0).code());
    assertEquals("Target anonymous label ':++' does not exist", diags.get(0).message());
  }

  @Test
  public void testScopeIsolation() {
    var code =
        """
        function func1
        :   nop
            bra :-
        endfunction

        function func2
            bra :-
        endfunction
        """;
    var file = new CA65Scanner().tokenize(code);
    List<SyntaxDiagnostic> diags = validator.validate(file);
    assertEquals(1, diags.size(), "func2 should not see func1's anonymous label");
    assertEquals(7, diags.get(0).line());
    assertEquals("Target anonymous label ':-' does not exist", diags.get(0).message());
  }

  @Test
  public void testResolveTargetLine() {
    var code =
        """
        :   nop
            bne :-
            beq :+
        :   rts
        """;
    var file = new CA65Scanner().tokenize(code);
    // Line 1: bne :-
    var backToken =
        file.getLineTokens(1).stream()
            .filter(t -> t.type == TokenType.TOK_ULABEL)
            .findFirst()
            .orElseThrow();
    OptionalInt backTarget = AnonymousLabelValidator.resolveTargetLine(file, 1, backToken);
    assertTrue(backTarget.isPresent());
    assertEquals(0, backTarget.getAsInt());

    // Line 2: beq :+
    var forwardToken =
        file.getLineTokens(2).stream()
            .filter(t -> t.type == TokenType.TOK_ULABEL)
            .findFirst()
            .orElseThrow();
    OptionalInt forwardTarget = AnonymousLabelValidator.resolveTargetLine(file, 2, forwardToken);
    assertTrue(forwardTarget.isPresent());
    assertEquals(3, forwardTarget.getAsInt());
  }
}
