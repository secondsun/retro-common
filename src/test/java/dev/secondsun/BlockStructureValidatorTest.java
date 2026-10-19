package dev.secondsun;

import static org.junit.jupiter.api.Assertions.*;

import dev.secondsun.retro.util.CA65Scanner;
import dev.secondsun.retro.util.diagnostic.BlockStructureValidator;
import dev.secondsun.retro.util.diagnostic.DiagnosticSeverity;
import dev.secondsun.retro.util.diagnostic.SyntaxDiagnostic;
import java.util.List;
import org.junit.jupiter.api.Test;

public class BlockStructureValidatorTest {

  private final CA65Scanner scanner = new CA65Scanner();
  private final BlockStructureValidator validator = new BlockStructureValidator();

  @Test
  public void testValidFunctionAndIfBlocks() {
    var code =
        """
                function add_vec
                    .if .defined(SFX)
                        nop
                    .endif
                endfunction
                """;
    var file = scanner.tokenize(code);
    List<SyntaxDiagnostic> diags = validator.validate(file);
    assertTrue(diags.isEmpty(), "Expected no block diagnostics, found: " + diags);
  }

  @Test
  public void testMissingEndFunction() {
    var code =
        """
                function compute_matrix
                    nop
                    stop
                """;
    var file = scanner.tokenize(code);
    List<SyntaxDiagnostic> diags = validator.validate(file);
    assertEquals(1, diags.size());
    assertEquals("UNCLOSED_BLOCK", diags.get(0).code());
    assertEquals(DiagnosticSeverity.ERROR, diags.get(0).severity());
    assertTrue(diags.get(0).message().contains("function compute_matrix"));
  }

  @Test
  public void testMissingEndIf() {
    var code =
        """
                .if .defined(DEBUG)
                    nop
                """;
    var file = scanner.tokenize(code);
    List<SyntaxDiagnostic> diags = validator.validate(file);
    assertEquals(1, diags.size());
    assertEquals("UNCLOSED_BLOCK", diags.get(0).code());
    assertTrue(diags.get(0).message().contains(".if"));
  }

  @Test
  public void testOrphanEndIf() {
    var code =
        """
                nop
                .endif
                """;
    var file = scanner.tokenize(code);
    List<SyntaxDiagnostic> diags = validator.validate(file);
    assertEquals(1, diags.size());
    assertEquals("ORPHAN_BLOCK_CLOSER", diags.get(0).code());
    assertTrue(diags.get(0).message().contains(".endif"));
  }

  @Test
  public void testOrphanEndFunction() {
    var code =
        """
                nop
                endfunction
                """;
    var file = scanner.tokenize(code);
    List<SyntaxDiagnostic> diags = validator.validate(file);
    assertEquals(1, diags.size());
    assertEquals("ORPHAN_BLOCK_CLOSER", diags.get(0).code());
    assertTrue(diags.get(0).message().contains("endfunction"));
  }

  @Test
  public void testMismatchedBlockCloser() {
    var code =
        """
                function calculate
                    .proc helper
                        nop
                    endfunction
                """;
    var file = scanner.tokenize(code);
    List<SyntaxDiagnostic> diags = validator.validate(file);
    assertFalse(diags.isEmpty());
    assertTrue(diags.stream().anyMatch(d -> d.code().equals("MISMATCHED_BLOCK_CLOSER")));
  }

  @Test
  public void testForEndForPairing() {
    var valid =
        """
                for 10
                    nop
                endfor
                """;
    var file = scanner.tokenize(valid);
    assertTrue(validator.validate(file).isEmpty());

    var unclosed =
        """
                for 10
                    nop
                """;
    var unclosedFile = scanner.tokenize(unclosed);
    var diags = validator.validate(unclosedFile);
    assertEquals(1, diags.size());
    assertEquals("UNCLOSED_BLOCK", diags.get(0).code());
    assertTrue(diags.get(0).message().contains("for"));
  }

  @Test
  public void testElseWithoutIf() {
    var code =
        """
                nop
                .else
                nop
                .endif
                """;
    var file = scanner.tokenize(code);
    List<SyntaxDiagnostic> diags = validator.validate(file);
    assertTrue(diags.stream().anyMatch(d -> d.code().equals("ORPHAN_CONDITIONAL_BRANCH")));
  }
}
