package dev.secondsun.retro.util.vo;

import dev.secondsun.retro.util.Token;
import java.util.List;
import java.util.Optional;

public record FunctionDeclaration(
    Token functionToken,
    Token nameToken,
    List<Token> parameters,
    Optional<Token> returnVariable,
    boolean hasErrors,
    boolean isInline,
    List<Token> errorTokens) {

  public FunctionDeclaration {
    parameters = parameters != null ? List.copyOf(parameters) : List.of();
    returnVariable = returnVariable != null ? returnVariable : Optional.empty();
    errorTokens = errorTokens != null ? List.copyOf(errorTokens) : List.of();
  }

  public FunctionDeclaration(
      Token functionToken,
      Token nameToken,
      List<Token> parameters,
      Optional<Token> returnVariable,
      boolean hasErrors) {
    this(functionToken, nameToken, parameters, returnVariable, hasErrors, false, List.of());
  }

  public FunctionDeclaration(
      Token functionToken,
      Token nameToken,
      List<Token> parameters,
      Optional<Token> returnVariable,
      boolean hasErrors,
      boolean isInline) {
    this(functionToken, nameToken, parameters, returnVariable, hasErrors, isInline, List.of());
  }

  public String name() {
    return nameToken != null ? nameToken.text() : "";
  }
}
