package dev.secondsun;

import static org.junit.jupiter.api.Assertions.*;

import dev.secondsun.retro.util.CA65Scanner;
import dev.secondsun.retro.util.diagnostic.DuplicateSymbolValidator;
import dev.secondsun.retro.util.diagnostic.SyntaxDiagnostic;
import java.util.List;
import org.junit.jupiter.api.Test;

public class DuplicateSymbolValidatorTest {

    private final CA65Scanner scanner = new CA65Scanner();
    private final DuplicateSymbolValidator validator = new DuplicateSymbolValidator();

    @Test
    public void testUniqueLabelsAndFunctions() {
        var code = """
                main:
                    nop
                loop:
                    bra loop
                function test_fn
                    return
                endfunction
                """;
        var file = scanner.tokenize(code);
        List<SyntaxDiagnostic> diags = validator.validate(file);
        assertTrue(diags.isEmpty(), "Expected no duplicate diagnostics, found: " + diags);
    }

    @Test
    public void testDuplicateGlobalLabels() {
        var code = """
                my_label:
                    nop
                my_label:
                    nop
                """;
        var file = scanner.tokenize(code);
        List<SyntaxDiagnostic> diags = validator.validate(file);
        assertEquals(1, diags.size());
        assertEquals("DUPLICATE_SYMBOL", diags.get(0).code());
        assertTrue(diags.get(0).message().contains("my_label"));
    }

    @Test
    public void testDuplicateCheapLocalLabelsUnderSameScope() {
        var code = """
                my_label:
                @loop:
                    nop
                @loop:
                    bra @loop
                """;
        var file = scanner.tokenize(code);
        List<SyntaxDiagnostic> diags = validator.validate(file);
        assertEquals(1, diags.size());
        assertEquals("DUPLICATE_SYMBOL", diags.get(0).code());
        assertTrue(diags.get(0).message().contains("@loop"));
    }

    @Test
    public void testCheapLocalLabelsAllowedUnderDifferentLabels() {
        var code = """
                sub1:
                @loop:
                    bra @loop
                sub2:
                @loop:
                    bra @loop
                """;
        var file = scanner.tokenize(code);
        List<SyntaxDiagnostic> diags = validator.validate(file);
        assertTrue(diags.isEmpty(), "Expected no duplicate diagnostics across different parent labels");
    }
}
