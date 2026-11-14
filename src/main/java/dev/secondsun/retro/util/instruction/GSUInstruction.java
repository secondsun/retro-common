package dev.secondsun.retro.util.instruction;

import dev.secondsun.retro.util.Token;
import dev.secondsun.retro.util.TokenType;
import dev.secondsun.retro.util.sfx.SuperFxIsa;
import dev.secondsun.retro.util.vo.Tokens;
import java.util.ArrayList;
import java.util.function.Predicate;

public class GSUInstruction {

  public final String instruction;

  private final ArrayList<ArgumentMatcher> argumentMatchers;

  public GSUInstruction(String instruction, String... argumentDefinitions) {
    this.instruction = instruction;
    this.argumentMatchers = new ArrayList<>();
    for (String argument : argumentDefinitions) {
      argumentMatchers.add(ArgumentMatcher.create(argument));
    }
  }

  public boolean matches(Tokens tokenizedLine) {
    return matches(tokenizedLine, null);
  }

  public boolean matches(Tokens tokenizedLine, Predicate<String> isRegisterLabel) {
    var listTokens = new ArrayList<>(tokenizedLine.tokens());

    if (listTokens.isEmpty()) {
      return false;
    }

    var firstToken = listTokens.getFirst();
    if (!firstToken.text().equalsIgnoreCase(this.instruction)) {
      return false;
    }

    listTokens.remove(0); // consume instruction token

    var matches = true;

    for (ArgumentMatcher matcher : argumentMatchers) {
      matches = matches && matcher.match(listTokens, isRegisterLabel);
      if (!listTokens.isEmpty()) {
        matches = matches && listTokens.remove(0).type == TokenType.TOK_COMMA;
      }
    }

    return matches && listTokens.isEmpty();
  }

  public static boolean isInstruction(Token firstToken) {
    return firstToken != null && SuperFxIsa.isMnemonic(firstToken.text());
  }

  /**
   * Retained as a safe no-op for backward compatibility; {@link
   * dev.secondsun.retro.util.CA65Scanner} populates {@code GSU_INSTRUCTION} directly during
   * immutable token construction.
   */
  public static void mark(Token firstToken) {
    // Immutable Token instances already carry GSU_INSTRUCTION when scanned.
  }
}
