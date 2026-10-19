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
        var code = """
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
        var code = """
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
        var code = """
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
        var code = """
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
        var code = """
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
        var code = """
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
        var code = """
                endfunction extra_arg
                """;
        var file = scanner.tokenize(code);
        List<SyntaxDiagnostic> diags = validator.validate(file);
        assertEquals(1, diags.size());
        assertEquals("EXTRA_TOKENS", diags.get(0).code());
    }
}
