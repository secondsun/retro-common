package dev.secondsun;

import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.secondsun.retro.util.CA65Scanner;
import dev.secondsun.retro.util.Token;
import dev.secondsun.retro.util.Util;
import org.junit.jupiter.api.Test;

public class FirstTest {
  @Test
  public void printTestSgs() throws Exception {
    CA65Scanner grammar = new CA65Scanner();
    var sgsProgram = Util.toString(UtilTest.class.getClassLoader().getResourceAsStream("libSFX.i"));
    var tf = grammar.tokenize(sgsProgram);
    assertFalse(tf.isEmpty());
    tf.forEach(
        (index, line) -> {
          var lineTokens = line.tokens();
          for (Token token : lineTokens) {
            assertFalse(token.getType().name().isEmpty());
          }
        });
  }
}
