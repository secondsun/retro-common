package dev.secondsun;

import static org.junit.jupiter.api.Assertions.*;

import dev.secondsun.retro.util.CA65Scanner;
import dev.secondsun.retro.util.diagnostic.InstructionSyntaxValidator;
import dev.secondsun.retro.util.diagnostic.SyntaxDiagnostic;
import java.util.List;
import org.junit.jupiter.api.Test;

public class InstructionSyntaxValidatorTest {

  private final CA65Scanner scanner = new CA65Scanner();
  private final InstructionSyntaxValidator validator = new InstructionSyntaxValidator();

  @Test
  public void testValidInstructions() {
    var code =
        """
                nop
                iwt r1, #5
                stw (r1)
                from r2
                to r3
                with r4
                add r5
                """;
    var file = scanner.tokenize(code);
    List<SyntaxDiagnostic> diags = validator.validate(file);
    assertTrue(diags.isEmpty(), "Expected no instruction diagnostics, found: " + diags);
  }

  @Test
  public void testMissingOperandSingleArg() {
    var code =
        """
                from
                """;
    var file = scanner.tokenize(code);
    List<SyntaxDiagnostic> diags = validator.validate(file);
    assertEquals(1, diags.size());
    assertEquals("MISSING_OPERAND", diags.get(0).code());
    assertTrue(diags.get(0).message().contains("from"));
  }

  @Test
  public void testMissingOperandTwoArg() {
    var code =
        """
                iwt r1
                """;
    var file = scanner.tokenize(code);
    List<SyntaxDiagnostic> diags = validator.validate(file);
    assertEquals(1, diags.size());
    assertEquals("MISSING_OPERAND", diags.get(0).code());
    assertTrue(diags.get(0).message().contains("iwt"));
  }

  @Test
  public void testExtraOperandZeroArg() {
    var code =
        """
                nop r1
                """;
    var file = scanner.tokenize(code);
    List<SyntaxDiagnostic> diags = validator.validate(file);
    assertEquals(1, diags.size());
    assertEquals("EXTRA_OPERAND", diags.get(0).code());
    assertTrue(diags.get(0).message().contains("nop"));
  }

  @Test
  public void testFunctionDeclarationErrors() {
    var code =
        """
                function
                function 123
                function bad_func a, a
                """;
    var file = scanner.tokenize(code);
    List<SyntaxDiagnostic> diags = validator.validate(file);
    assertFalse(diags.isEmpty());
    assertTrue(diags.stream().anyMatch(d -> d.code().equals("FUNCTION_DECLARATION_ERROR")));
  }

  @Test
  public void testCallStatementErrors() {
    var code =
        """
                call
                call foo :
                """;
    var file = scanner.tokenize(code);
    List<SyntaxDiagnostic> diags = validator.validate(file);
    assertFalse(diags.isEmpty());
    assertTrue(diags.stream().anyMatch(d -> d.code().equals("CALL_STATEMENT_ERROR")));
  }

  @Test
  public void testEndFunctionTrailingTokens() {
    var code =
        """
                endfunction extra_arg
                """;
    var file = scanner.tokenize(code);
    List<SyntaxDiagnostic> diags = validator.validate(file);
    assertEquals(1, diags.size());
    assertEquals("EXTRA_TOKENS", diags.get(0).code());
  }

  @Test
  public void testRegisterKeywordAndParameters() {
    var code =
        """
                function gsu_sqrt32 dividendhi, dividendlo : root
                    register partial_dividend
                    register divisor = r0

                    iwt partial_dividend, #$0
                    iwt r12, #$10
                    iwt root, #0
                    iwt divisor, #0

                    iwt r13, #square_root_loop
                    with dividendlo
                square_root_loop:
                    rol
                    with dividendhi
                    rol
                    with partial_dividend
                    rol

                    from root
                    add root
                    add divisor
                    cmp partial_dividend
                    with partial_dividend
                    sub divisor
                    return root
                endfunction
                """;
    var file = scanner.tokenize(code);
    List<SyntaxDiagnostic> diags = validator.validate(file);
    assertTrue(diags.isEmpty(), "Expected no instruction diagnostics, found: " + diags);
  }

  @Test
  public void testRegisterScoping() {
    var code =
        """
                function func1
                    register local_var
                    with local_var
                endfunction

                function func2
                    with local_var
                endfunction
                """;
    var file = scanner.tokenize(code);
    List<SyntaxDiagnostic> diags = validator.validate(file);
    assertEquals(
        1, diags.size(), "Expected 1 diagnostic for out-of-scope register, found: " + diags);
    assertEquals("INVALID_OPERAND", diags.get(0).code());
    assertEquals(7, diags.get(0).line());
  }

  @Test
  public void testFileScopeRegister() {
    var code =
        """
                register global_reg

                function func1
                    with global_reg
                endfunction

                function func2
                    with global_reg
                endfunction
                """;
    var file = scanner.tokenize(code);
    List<SyntaxDiagnostic> diags = validator.validate(file);
    assertTrue(
        diags.isEmpty(), "Expected no diagnostics for file-scoped register, found: " + diags);
  }

  @Test
  public void testRegisterDeclarationErrors() {
    var code =
        """
                register
                register bad_dangling,
                register missing_val =
                """;
    var file = scanner.tokenize(code);
    List<SyntaxDiagnostic> diags = validator.validate(file);
    assertEquals(3, diags.size(), "Expected 3 register declaration errors, found: " + diags);
    assertTrue(diags.stream().allMatch(d -> "REGISTER_DECLARATION_ERROR".equals(d.code())));
  }

  @Test
  public void testAnonymousLabelBranchOperands() {
    var code =
        """
                :       wai
                        bra :-
                        beq :--
                        bne :+
                        bra :++
                :       nop
                """;
    var file = new CA65Scanner().tokenize(code);
    List<SyntaxDiagnostic> diags = validator.validate(file);
    assertTrue(diags.isEmpty(), "Anonymous label branch targets are valid ca65, found: " + diags);
  }

  @Test
  public void testScopedLabelBranchOperands() {
    var code =
        """
                bne foo::bar
                bra ::top
                beq outer::inner::target
                """;
    var diags = validator.validate(new CA65Scanner().tokenize(code));
    assertTrue(diags.isEmpty(), "Scoped label branch targets are valid ca65, found: " + diags);
  }

  @Test
  public void testErrorDirectiveIsNotLexicalError() {
    var diags = validator.validate(new CA65Scanner().tokenize(".error \"boom\"\n"));
    assertTrue(diags.isEmpty(), "found: " + diags);
  }

  @Test
  public void testMacroBodyOperandsAreNotValidated() {
    var code =
        """
                .macro gsu_pop R
                    add R
                    inc stackPointer
                    iwt R, #.sizeof(x)
                .endmacro
                """;
    var diags = validator.validate(new CA65Scanner().tokenize(code));
    assertTrue(diags.isEmpty(), "Macro bodies should not be operand-validated, found: " + diags);
  }

  @Test
  public void testInvalidOperandStillReportedOutsideMacro() {
    var code =
        """
                .macro m
                .endmacro
                add notAReg ,, 1
                """;
    var diags = validator.validate(new CA65Scanner().tokenize(code));
    assertFalse(diags.isEmpty());
  }

  @Test
  public void testSmLmParenthesizedExpressionsAndSingleLetterRegisterVars() {
    var code =
        """
                function test_fn
                    register a, x, y, s
                    lm a, (GSU_INIT_FLAG)
                    lm x, (OBJ_ORDER + 0)
                    sm (GSU_INIT_FLAG), y
                    sm (RNC_WORD_BUFFER + rncbuffer::address), s
                    sms ($10), a
                    lms y, ($20)
                endfunction
                """;
    var diags = validator.validate(new CA65Scanner().tokenize(code));
    assertTrue(
        diags.isEmpty(),
        "Expected sm/lm expressions and 1-letter register variables (a,x,y,s) to be valid, found: "
            + diags);
  }

  @Test
  public void testR1ToR15OnlyInstructionsRejectR0() {
    var code =
        """
                register zero_var = r0
                and r0
                bic r0
                or r0
                xor r0
                mult r0
                umult r0
                and zero_var
                and r1
                and #5
                add r0
                sub r0
                """;
    var diags = validator.validate(new CA65Scanner().tokenize(code));
    assertEquals(7, diags.size(), "Expected 7 INVALID_R0_OPERAND diagnostics, found: " + diags);
    assertTrue(diags.stream().allMatch(d -> "INVALID_R0_OPERAND".equals(d.code())));
  }
}
