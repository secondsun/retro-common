package dev.secondsun.retro.util.instruction;

import dev.secondsun.retro.util.Token;
import dev.secondsun.retro.util.TokenAttribute;
import dev.secondsun.retro.util.TokenType;
import java.util.List;

public sealed interface ArgumentMatcher {
  boolean match(List<Token> in);

  final class BrokenMatcher implements ArgumentMatcher {

    @Override
    public boolean match(List<Token> in) {
      throw new IllegalStateException("Not Implemented");
    }
  }

  final class RegisterMatcher implements ArgumentMatcher {
    final int specificRegister;
    final int minRegister;
    final int maxRegister;

    public RegisterMatcher(String registerPattern) {
      if ("R1-R15".equalsIgnoreCase(registerPattern)) {
        specificRegister = -1;
        minRegister = 1;
        maxRegister = 15;
        return;
      }
      if (registerPattern.length() == 2 || registerPattern.length() == 3) {
        char secondChar = Character.toLowerCase(registerPattern.charAt(1));
        if (secondChar == 'n' || secondChar == 'm') {
          specificRegister = -1;
          minRegister = 0;
          maxRegister = 15;
        } else {
          specificRegister = Integer.parseInt(registerPattern.substring(1));
          minRegister = specificRegister;
          maxRegister = specificRegister;
        }
        return;
      }
      throw new IllegalArgumentException(
          registerPattern
              + " is not a valid register pattern. Valid patterns are [rR][n\\d{1,2}] or R1-R15");
    }

    @Override
    public boolean match(List<Token> in) {
      if (in.isEmpty()) {
        return false;
      } else {
        var token = in.remove(0);

        var tokenText = token.text();
        if (tokenText.matches("[rR]\\d+")) {
          var actualRegister = Integer.parseInt(tokenText.substring(1));
          if (specificRegister > -1) {
            return specificRegister == actualRegister;
          } else {
            return actualRegister >= minRegister && actualRegister <= maxRegister;
          }
        }
        return (specificRegister == -1) && token.hasAttribute(TokenAttribute.REGISTER_LABEL);
      }
    }
  }

  /** Matches #nn, #label, #(expression) (TOK_HASH followed by immediate value/expression) */
  final class NumberMatcher implements ArgumentMatcher {
    public NumberMatcher() {}

    @Override
    public boolean match(List<Token> in) {
      if (in.size() < 2) {
        return false;
      }
      if (in.remove(0).type != TokenType.TOK_HASH) {
        return false;
      }
      if (in.isEmpty() || in.get(0).type == TokenType.TOK_COMMA) {
        return false;
      }
      int parenDepth = 0;
      while (!in.isEmpty()) {
        TokenType t = in.get(0).type;
        if (t == TokenType.TOK_LPAREN) {
          parenDepth++;
        } else if (t == TokenType.TOK_RPAREN) {
          parenDepth--;
        } else if (t == TokenType.TOK_COMMA && parenDepth <= 0) {
          break;
        }
        in.remove(0);
      }
      return true;
    }
  }

  final class AddressMatcher implements ArgumentMatcher {
    public AddressMatcher(String pattern) {}

    @Override
    public boolean match(List<Token> in) {
      if (in.size() < 3) {
        return false;
      }
      if (in.remove(0).type != TokenType.TOK_LPAREN) {
        return false;
      }
      int parenDepth = 1;
      boolean hasValidInner = false;
      while (!in.isEmpty() && parenDepth > 0) {
        Token t = in.remove(0);
        if (t.type == TokenType.TOK_LPAREN) {
          parenDepth++;
        } else if (t.type == TokenType.TOK_RPAREN) {
          parenDepth--;
        } else if (t.type == TokenType.TOK_COMMA && parenDepth == 1) {
          return false;
        } else if (t.type == TokenType.TOK_INTCON
            || t.type == TokenType.TOK_IDENT
            || t.type == TokenType.TOK_LOCAL_IDENT
            || t.type == TokenType.TOK_REGISTER
            || t.type == TokenType.TOK_A
            || t.type == TokenType.TOK_X
            || t.type == TokenType.TOK_Y
            || t.type == TokenType.TOK_S
            || t.hasAttribute(TokenAttribute.REGISTER_LABEL)) {
          hasValidInner = true;
        }
      }
      return parenDepth == 0 && hasValidInner;
    }
  }

  final class LabelMatcher implements ArgumentMatcher {
    public LabelMatcher() {}

    @Override
    public boolean match(List<Token> in) {
      if (in.isEmpty()) {
        return false;
      }
      var token = in.remove(0);
      if (token.type == TokenType.TOK_ULABEL) {
        return true;
      }
      // Optional leading '::' (global scope), e.g. ::label
      if (token.type == TokenType.TOK_NAMESPACE) {
        if (in.isEmpty()) {
          return false;
        }
        token = in.remove(0);
      }
      if (token.type != TokenType.TOK_IDENT && token.type != TokenType.TOK_LOCAL_IDENT) {
        return false;
      }
      // Scoped names: scope::label::label
      while (in.size() >= 2
          && in.get(0).type == TokenType.TOK_NAMESPACE
          && (in.get(1).type == TokenType.TOK_IDENT
              || in.get(1).type == TokenType.TOK_LOCAL_IDENT)) {
        in.remove(0);
        in.remove(0);
      }
      return true;
    }
  }

  static ArgumentMatcher create(String pattern) {
    if (pattern.startsWith("R") || pattern.startsWith("r")) {
      return new RegisterMatcher(pattern);
    } else if (pattern.startsWith("#")) {
      return new NumberMatcher();
    } else if (pattern.startsWith("(")) {
      return new AddressMatcher(pattern);
    } else if (pattern.equalsIgnoreCase("e")) {
      return new LabelMatcher();
    }

    throw new IllegalStateException("Illegal argument definition:" + pattern);
  }
}
