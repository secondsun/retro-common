package dev.secondsun.retro.util;

import dev.secondsun.retro.util.vo.CallStatement;
import dev.secondsun.retro.util.vo.FunctionDeclaration;
import dev.secondsun.retro.util.vo.ReturnStatement;
import dev.secondsun.retro.util.vo.Tokens;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Pure parser and syntax helper for X-GSU high-level {@code [inline] function}, {@code call},
 * {@code return}, and {@code endfunction} statements. Never mutates input {@link Token} instances.
 */
public final class FunctionSyntaxHelper {

  private FunctionSyntaxHelper() {}

  public static boolean isFunction(Tokens tokens) {
    return isFunction(tokens != null ? tokens.tokens() : null);
  }

  public static boolean isFunction(List<Token> tokenList) {
    if (tokenList == null || tokenList.isEmpty()) {
      return false;
    }
    Token first = tokenList.get(0);
    if (first.type == TokenType.TOK_FUNCTION || "function".equalsIgnoreCase(first.text())) {
      return true;
    }
    return isInlineFunction(tokenList);
  }

  public static boolean isInlineFunction(Tokens tokens) {
    return isInlineFunction(tokens != null ? tokens.tokens() : null);
  }

  public static boolean isInlineFunction(List<Token> tokenList) {
    if (tokenList == null || tokenList.size() < 2) {
      return false;
    }
    Token first = tokenList.get(0);
    Token second = tokenList.get(1);
    return "inline".equalsIgnoreCase(first.text())
        && (second.type == TokenType.TOK_FUNCTION || "function".equalsIgnoreCase(second.text()));
  }

  public static boolean isEndFunction(Tokens tokens) {
    return isEndFunction(tokens != null ? tokens.tokens() : null);
  }

  public static boolean isEndFunction(List<Token> tokenList) {
    if (tokenList == null || tokenList.isEmpty()) {
      return false;
    }
    Token first = tokenList.get(0);
    return first.type == TokenType.TOK_ENDFUNCTION || "endfunction".equalsIgnoreCase(first.text());
  }

  public static boolean validateEndFunction(Tokens tokens) {
    return validateEndFunction(tokens != null ? tokens.tokens() : null);
  }

  public static boolean validateEndFunction(List<Token> tokenList) {
    return isEndFunction(tokenList) && tokenList.size() == 1;
  }

  /** Returns immutable copies of any unexpected trailing tokens after {@code endfunction}. */
  public static List<Token> parseEndFunction(Tokens tokens) {
    return parseEndFunction(tokens != null ? tokens.tokens() : null);
  }

  /** Returns immutable copies of any unexpected trailing tokens after {@code endfunction}. */
  public static List<Token> parseEndFunction(List<Token> tokenList) {
    if (!isEndFunction(tokenList) || tokenList.size() <= 1) {
      return List.of();
    }
    List<Token> errors = new ArrayList<>();
    for (int i = 1; i < tokenList.size(); i++) {
      Token extra = tokenList.get(i);
      errors.add(extra.withError("Unexpected token after endfunction: " + extra.text()));
    }
    return List.copyOf(errors);
  }

  public static boolean isCall(Tokens tokens) {
    return isCall(tokens != null ? tokens.tokens() : null);
  }

  public static boolean isCall(List<Token> tokenList) {
    if (tokenList == null || tokenList.isEmpty()) {
      return false;
    }
    Token first = tokenList.get(0);
    return first.type == TokenType.TOK_CALL || "call".equalsIgnoreCase(first.text());
  }

  public static boolean isReturn(Tokens tokens) {
    return isReturn(tokens != null ? tokens.tokens() : null);
  }

  public static boolean isReturn(List<Token> tokenList) {
    if (tokenList == null || tokenList.isEmpty()) {
      return false;
    }
    Token first = tokenList.get(0);
    return first.type == TokenType.TOK_RETURN || "return".equalsIgnoreCase(first.text());
  }

  public static FunctionDeclaration parseFunction(Tokens tokens) {
    return parseFunction(tokens != null ? tokens.tokens() : null);
  }

  public static FunctionDeclaration parseFunction(List<Token> tokenList) {
    if (tokenList == null || tokenList.isEmpty()) {
      return new FunctionDeclaration(
          null, null, List.of(), Optional.empty(), true, false, List.of());
    }

    boolean isInline = isInlineFunction(tokenList);
    int fnIdx = isInline ? 1 : 0;

    boolean hasErrors = false;
    List<Token> errorTokens = new ArrayList<>();
    Token functionToken = tokenList.get(fnIdx);
    if (functionToken.type != TokenType.TOK_FUNCTION
        && !"function".equalsIgnoreCase(functionToken.text())) {
      functionToken = functionToken.withError("Expected 'function' keyword");
      errorTokens.add(functionToken);
      hasErrors = true;
    }

    if (tokenList.size() <= fnIdx + 1) {
      functionToken = functionToken.withError("Missing function name");
      errorTokens.add(functionToken);
      return new FunctionDeclaration(
          functionToken, null, List.of(), Optional.empty(), true, isInline, errorTokens);
    }

    Token nameToken = tokenList.get(fnIdx + 1);
    if (!isIdentifier(nameToken)) {
      nameToken =
          nameToken.withError("Expected function name identifier, found: " + nameToken.text());
      errorTokens.add(nameToken);
      hasErrors = true;
    }

    List<Token> parameters = new ArrayList<>();
    Set<String> seenParamNames = new HashSet<>();
    Optional<Token> returnVariable = Optional.empty();

    int i = fnIdx + 2;
    boolean afterComma = false;
    Token lastComma = null;

    while (i < tokenList.size()) {
      Token token = tokenList.get(i);
      if (token.type == TokenType.TOK_COLON) {
        break;
      }

      if (token.type == TokenType.TOK_COMMA) {
        if (parameters.isEmpty() || afterComma) {
          Token errTok = token.withError("Unexpected comma");
          errorTokens.add(errTok);
          hasErrors = true;
        }
        afterComma = true;
        lastComma = token;
      } else if (isIdentifier(token)) {
        Token paramTok = token;
        if (!seenParamNames.add(token.text())) {
          paramTok = token.withError("Duplicate parameter name: " + token.text());
          errorTokens.add(paramTok);
          hasErrors = true;
        }
        parameters.add(paramTok);
        afterComma = false;
        lastComma = null;
      } else {
        Token paramTok = token.withError("Expected parameter identifier, found: " + token.text());
        errorTokens.add(paramTok);
        hasErrors = true;
        parameters.add(paramTok);
        afterComma = false;
        lastComma = null;
      }
      i++;
    }

    if (afterComma && lastComma != null) {
      String msg =
          (i < tokenList.size() && tokenList.get(i).type == TokenType.TOK_COLON)
              ? "Unexpected comma before ':'"
              : "Dangling comma in parameter list";
      errorTokens.add(lastComma.withError(msg));
      hasErrors = true;
    }

    if (i < tokenList.size() && tokenList.get(i).type == TokenType.TOK_COLON) {
      Token colonToken = tokenList.get(i);
      i++;
      if (i >= tokenList.size()) {
        errorTokens.add(colonToken.withError("Missing return variable after ':'"));
        hasErrors = true;
      } else {
        Token retToken = tokenList.get(i);
        if (isValidVariableOrRegister(retToken)) {
          returnVariable = Optional.of(retToken);
        } else {
          Token errRet =
              retToken.withError(
                  "Expected identifier or register for return variable, found: " + retToken.text());
          errorTokens.add(errRet);
          hasErrors = true;
          returnVariable = Optional.of(errRet);
        }
        i++;

        while (i < tokenList.size()) {
          Token extra = tokenList.get(i);
          errorTokens.add(
              extra.withError("Unexpected token after return variable: " + extra.text()));
          hasErrors = true;
          i++;
        }
      }
    }

    return new FunctionDeclaration(
        functionToken, nameToken, parameters, returnVariable, hasErrors, isInline, errorTokens);
  }

  public static CallStatement parseCall(Tokens tokens) {
    return parseCall(tokens != null ? tokens.tokens() : null);
  }

  public static CallStatement parseCall(List<Token> tokenList) {
    if (tokenList == null || tokenList.isEmpty()) {
      return new CallStatement(null, null, List.of(), Optional.empty(), true, List.of());
    }

    boolean hasErrors = false;
    List<Token> errorTokens = new ArrayList<>();
    Token callToken = tokenList.get(0);
    if (callToken.type != TokenType.TOK_CALL && !"call".equalsIgnoreCase(callToken.text())) {
      callToken = callToken.withError("Expected 'call' keyword");
      errorTokens.add(callToken);
      hasErrors = true;
    }

    if (tokenList.size() == 1) {
      callToken = callToken.withError("Missing function target name");
      errorTokens.add(callToken);
      return new CallStatement(callToken, null, List.of(), Optional.empty(), true, errorTokens);
    }

    Token targetToken = tokenList.get(1);
    if (!isIdentifier(targetToken)) {
      targetToken =
          targetToken.withError(
              "Expected function target identifier, found: " + targetToken.text());
      errorTokens.add(targetToken);
      hasErrors = true;
    }

    List<Token> arguments = new ArrayList<>();
    Optional<Token> destinationVariable = Optional.empty();

    int i = 2;
    boolean afterComma = false;
    Token lastComma = null;

    while (i < tokenList.size()) {
      Token token = tokenList.get(i);
      if (token.type == TokenType.TOK_COLON) {
        break;
      }

      if (token.type == TokenType.TOK_COMMA) {
        if (arguments.isEmpty() || afterComma) {
          errorTokens.add(token.withError("Unexpected comma"));
          hasErrors = true;
        }
        afterComma = true;
        lastComma = token;
      } else if (isValidArgumentToken(token)) {
        arguments.add(token);
        afterComma = false;
        lastComma = null;
      } else {
        Token errArg = token.withError("Unexpected argument token: " + token.text());
        errorTokens.add(errArg);
        hasErrors = true;
        arguments.add(errArg);
        afterComma = false;
        lastComma = null;
      }
      i++;
    }

    if (afterComma && lastComma != null) {
      String msg =
          (i < tokenList.size() && tokenList.get(i).type == TokenType.TOK_COLON)
              ? "Unexpected comma before ':'"
              : "Dangling comma in argument list";
      errorTokens.add(lastComma.withError(msg));
      hasErrors = true;
    }

    if (i < tokenList.size() && tokenList.get(i).type == TokenType.TOK_COLON) {
      Token colonToken = tokenList.get(i);
      i++;
      if (i >= tokenList.size()) {
        errorTokens.add(colonToken.withError("Missing destination variable after ':'"));
        hasErrors = true;
      } else {
        Token destToken = tokenList.get(i);
        if (isValidVariableOrRegister(destToken)) {
          destinationVariable = Optional.of(destToken);
        } else {
          Token errDest =
              destToken.withError(
                  "Expected identifier or register for destination variable, found: "
                      + destToken.text());
          errorTokens.add(errDest);
          hasErrors = true;
          destinationVariable = Optional.of(errDest);
        }
        i++;

        while (i < tokenList.size()) {
          Token extra = tokenList.get(i);
          errorTokens.add(
              extra.withError("Unexpected token after destination variable: " + extra.text()));
          hasErrors = true;
          i++;
        }
      }
    }

    return new CallStatement(
        callToken, targetToken, arguments, destinationVariable, hasErrors, errorTokens);
  }

  public static ReturnStatement parseReturn(Tokens tokens) {
    return parseReturn(tokens != null ? tokens.tokens() : null);
  }

  public static ReturnStatement parseReturn(List<Token> tokenList) {
    if (tokenList == null || tokenList.isEmpty()) {
      return new ReturnStatement(null, Optional.empty(), true, List.of());
    }

    boolean hasErrors = false;
    List<Token> errorTokens = new ArrayList<>();
    Token returnToken = tokenList.get(0);
    if (returnToken.type != TokenType.TOK_RETURN
        && !"return".equalsIgnoreCase(returnToken.text())) {
      returnToken = returnToken.withError("Expected 'return' keyword");
      errorTokens.add(returnToken);
      hasErrors = true;
    }

    Optional<Token> returnVariable = Optional.empty();

    if (tokenList.size() > 1) {
      Token firstArg = tokenList.get(1);
      if (isValidArgumentToken(firstArg)) {
        returnVariable = Optional.of(firstArg);
      } else {
        Token errArg = firstArg.withError("Invalid return value: " + firstArg.text());
        errorTokens.add(errArg);
        hasErrors = true;
        returnVariable = Optional.of(errArg);
      }

      for (int i = 2; i < tokenList.size(); i++) {
        Token extra = tokenList.get(i);
        errorTokens.add(extra.withError("Unexpected token after return value: " + extra.text()));
        hasErrors = true;
      }
    }

    return new ReturnStatement(returnToken, returnVariable, hasErrors, errorTokens);
  }

  private static boolean isIdentifier(Token token) {
    return token != null
        && (token.type == TokenType.TOK_IDENT
            || token.type == TokenType.TOK_A
            || token.type == TokenType.TOK_X
            || token.type == TokenType.TOK_Y
            || token.type == TokenType.TOK_S);
  }

  private static boolean isValidVariableOrRegister(Token token) {
    return isIdentifier(token) || (token != null && token.type == TokenType.TOK_REGISTER);
  }

  private static boolean isValidArgumentToken(Token token) {
    return isIdentifier(token)
        || (token != null
            && (token.type == TokenType.TOK_REGISTER
                || token.type == TokenType.TOK_INTCON
                || token.type == TokenType.TOK_LOCAL_IDENT
                || token.type == TokenType.TOK_CHARCON
                || token.type == TokenType.TOK_STRCON));
  }
}
