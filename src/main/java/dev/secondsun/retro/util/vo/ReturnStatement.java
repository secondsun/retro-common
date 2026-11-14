package dev.secondsun.retro.util.vo;

import dev.secondsun.retro.util.Token;
import java.util.List;
import java.util.Optional;

public record ReturnStatement(
    Token returnToken, Optional<Token> returnVariable, boolean hasErrors, List<Token> errorTokens) {

  public ReturnStatement {
    returnVariable = returnVariable != null ? returnVariable : Optional.empty();
    errorTokens = errorTokens != null ? List.copyOf(errorTokens) : List.of();
  }

  public ReturnStatement(Token returnToken, Optional<Token> returnVariable, boolean hasErrors) {
    this(returnToken, returnVariable, hasErrors, List.of());
  }
}
