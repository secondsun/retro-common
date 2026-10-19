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

    public RegisterMatcher(String registerPattern) {
      if (registerPattern.length() == 2 || registerPattern.length() == 3) {
        char secondChar = Character.toLowerCase(registerPattern.charAt(1));
        if (secondChar == 'n' || secondChar == 'm') {
          specificRegister = -1;
        } else {
          specificRegister = Integer.parseInt(registerPattern.substring(1));
        }
        return;
      }
      throw new IllegalArgumentException(
          registerPattern + " is not a valid register pattern. Valid patterns are [rR][n\\d{1,2}]");
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
            return actualRegister > -1 && actualRegister < 16;
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
      Token inner = in.remove(0);
      boolean validInner =
          inner.type == TokenType.TOK_INTCON
              || inner.type == TokenType.TOK_IDENT
              || inner.type == TokenType.TOK_LOCAL_IDENT
              || inner.type == TokenType.TOK_REGISTER
              || inner.hasAttribute(TokenAttribute.REGISTER_LABEL);
      if (!validInner) {
        return false;
      }
      return in.remove(0).type == TokenType.TOK_RPAREN;
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
      return token.type == TokenType.TOK_IDENT || token.type == TokenType.TOK_LOCAL_IDENT;
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
