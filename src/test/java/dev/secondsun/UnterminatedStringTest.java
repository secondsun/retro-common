package dev.secondsun;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import dev.secondsun.retro.util.CA65Scanner;
import org.junit.jupiter.api.Test;

/** Regression: unterminated string on a long line followed by a short line crashed the scanner. */
public class UnterminatedStringTest {

  @Test
  public void unterminatedStringOnLongLineDoesNotOverrunNextLine() {
    var text =
        "else\n"
            + "# suppress the \"nothing to be done for 'samples' message\n"
            + "\t@echo > $(NULLDEV)\n"
            + "endif\n";
    assertDoesNotThrow(() -> new CA65Scanner().tokenize(text));
  }

  @Test
  public void unterminatedStringAtEndOfFile() {
    assertDoesNotThrow(() -> new CA65Scanner().tokenize("lda #1 ; x\n.byte \"abc\n"));
  }
}
