package dev.secondsun;

import dev.secondsun.retro.util.CA65Scanner;
import dev.secondsun.retro.util.TokenType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class GrammarTest {

    @Test
    public void testGeneralTokenization() {
        var program = """
            iwt r1 , #5
            stw ( r1 ) 
            bra next
            nop

            iwt r2 , #5 
            stw ( r2 )
            
            next:
            iwt r3 , #5 
            stw ( r3 ) 
        """;

        var file = new CA65Scanner().tokenize(program);
        assertEquals(TokenType.TOK_REGISTER, file.getLineTokens(0).get(1).type);
        assertEquals(TokenType.TOK_IDENT, file.getLineTokens(2).get(1).type);
    }
}
