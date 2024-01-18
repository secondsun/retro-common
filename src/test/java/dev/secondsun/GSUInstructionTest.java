package dev.secondsun;

import dev.secondsun.retro.util.instruction.Instructions;
import org.junit.jupiter.api.Test;

import dev.secondsun.retro.util.CA65Scanner;
import dev.secondsun.retro.util.GsuInstructionAttributeAdder;
import dev.secondsun.retro.util.TokenAttribute;
import dev.secondsun.retro.util.TokenType;

import static org.junit.jupiter.api.Assertions.*;

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
        assertTrue(token.hasAttribute(TokenAttribute.NO_OPERATION));
        System.out.println("test");
    }

    @Test
    public void testInstructionMatchers() {
        var tokenized = new CA65Scanner().tokenize("NOP");
        new GsuInstructionAttributeAdder().applyAttributes(tokenized.getLineTokens(0));
        assertTrue(tokenized.getLineTokens(0).get(0).hasAttribute(TokenAttribute.GSU_INSTRUCTION));
        assertTrue(Instructions.NOP.matches(tokenized.getLine(0)));

        tokenized = new CA65Scanner().tokenize("IWT r1");
        new GsuInstructionAttributeAdder().applyAttributes(tokenized.getLineTokens(0));
        assertFalse(Instructions.IWT.matches(tokenized.getLine(0)));

        tokenized = new CA65Scanner().tokenize("IWT r5, #4");
        new GsuInstructionAttributeAdder().applyAttributes(tokenized.getLineTokens(0));
        assertTrue(tokenized.getLineTokens(0).get(0).hasAttribute(TokenAttribute.GSU_INSTRUCTION));
        assertTrue(Instructions.IWT.matches(tokenized.getLine(0)));


    }

    @Test
    public void testLabelMatcher() {
        var program = """
                    bra e
                """;
        var tokenized = new CA65Scanner().tokenize(program);
        new GsuInstructionAttributeAdder().applyAttributes(tokenized.getLineTokens(0));
        assertTrue(Instructions.BRA.matches(tokenized.getLine(0)));
        assertEquals(TokenType.TOK_IDENT, tokenized.getLineTokens(0).get(1).type);

    }
    @Test
    public void testTokenizationAndInstructionID() {
        var program = """
                iwt r1, #5
                stw (r1)
                
                """;
        var tokenized = new CA65Scanner().tokenize(program);
        new GsuInstructionAttributeAdder().applyAttributes(tokenized.getLineTokens(0));
        new GsuInstructionAttributeAdder().applyAttributes(tokenized.getLineTokens(1));
        assertEquals("iwt", tokenized.getLineTokens(0).get(0).text());
        assertTrue(tokenized.getLineTokens(0).get(0).hasAttribute(TokenAttribute.GSU_INSTRUCTION));
        assertEquals("r1", tokenized.getLineTokens(0).get(1).text());
        assertEquals(",", tokenized.getLineTokens(0).get(2).text());
        assertEquals("#", tokenized.getLineTokens(0).get(3).text());
        assertEquals("5", tokenized.getLineTokens(0).get(4).text());
        assertEquals("stw", tokenized.getLineTokens(1).get(0).text());
        assertTrue(tokenized.getLineTokens(1).get(0).hasAttribute(TokenAttribute.GSU_INSTRUCTION));
        assertEquals("(", tokenized.getLineTokens(1).get(1).text());
        assertFalse(tokenized.getLineTokens(1).get(1).hasAttribute(TokenAttribute.GSU_INSTRUCTION));
        assertEquals("r1", tokenized.getLineTokens(1).get(2).text());
        assertEquals(")", tokenized.getLineTokens(1).get(3).text());
    }

}
