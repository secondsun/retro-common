package dev.secondsun;

import static org.junit.jupiter.api.Assertions.*;

import dev.secondsun.retro.util.CA65Scanner;
import dev.secondsun.retro.util.diagnostic.SyntaxDiagnostic;
import dev.secondsun.retro.util.diagnostic.SyntaxValidator;
import java.util.List;
import org.junit.jupiter.api.Test;

public class SyntaxValidatorTest {

    private final CA65Scanner scanner = new CA65Scanner();
    private final SyntaxValidator validator = new SyntaxValidator();

    @Test
    public void testCleanFile() {
        var code = """
                function add_numbers
                    iwt r1, #10
                    iwt r2, #20
                    from r1
                    to r3
                    add r2
                    return
                endfunction
                """;
        var file = scanner.tokenize(code);
        List<SyntaxDiagnostic> diags = validator.validate(file);
        assertTrue(diags.isEmpty(), "Expected 0 diagnostics, found: " + diags);
        assertEquals(0, file.getDiagnostics().size());
    }

    @Test
    public void testMultipleErrorsCollected() {
        var code = """
                function broken_fn
                    iwt r1
                    nop extra
                my_label:
                my_label:
                """;
        var file = scanner.tokenize(code);
        List<SyntaxDiagnostic> diags = validator.validate(file);
        // Expects:
        // 1. UNCLOSED_BLOCK (function broken_fn)
        // 2. MISSING_OPERAND (iwt r1)
        // 3. EXTRA_OPERAND (nop extra)
        // 4. DUPLICATE_SYMBOL (my_label)
        assertTrue(diags.size() >= 4, "Expected at least 4 diagnostics, found: " + diags);
        assertEquals(diags.size(), file.getDiagnostics().size());
    }
}
