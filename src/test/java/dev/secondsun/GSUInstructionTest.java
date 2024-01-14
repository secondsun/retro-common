package dev.secondsun;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;

import dev.secondsun.retro.util.CA65Scanner;
import dev.secondsun.retro.util.GsuInstructionAttributeAdder;
import dev.secondsun.retro.util.TokenAttribute;
import dev.secondsun.retro.util.TokenType;

public class GSUInstructionTest {

    public void testLine(){
        var scanner = new CA65Scanner();
        var tokens = scanner.tokenize("NOP").getLineTokens(0);
        var checker = new GsuInstructionAttributeAdder();
        checker.applyAttributes(tokens);
        assertEquals(1, tokens.size());
        assertEquals(TokenType.TOK_IDENT, tokens.get(0).type);
        var token = tokens.get(0);
        assertTrue(token.hasAttribute(TokenAttribute.GSU_INSTRUCTION));
        assertTrue(token.hasAttribute(TokenAttribute.GSU_INSTRUCTION_NOP));
        System.out.println("test");
    }

    @Test
    public void testTokenization() {
        var program = """
                iwt r1, #5
                stw (r1)
                """;
        var tokenized = new CA65Scanner().tokenize(program);
        assertEquals("iwt", tokenized.getLineTokens(0).get(0).text());
        assertEquals("r1", tokenized.getLineTokens(0).get(1).text());
        assertEquals(",", tokenized.getLineTokens(0).get(2).text());
        assertEquals("#", tokenized.getLineTokens(0).get(3).text());
        assertEquals("5", tokenized.getLineTokens(0).get(4).text());
        assertEquals("stw", tokenized.getLineTokens(1).get(0).text());
        assertEquals("(", tokenized.getLineTokens(1).get(1).text());
        assertEquals("r1", tokenized.getLineTokens(1).get(2).text());
        assertEquals(")", tokenized.getLineTokens(1).get(3).text());

    }

}
