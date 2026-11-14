package dev.secondsun.retro.util;

import dev.secondsun.retro.util.sfx.SuperFxIsa;
import dev.secondsun.retro.util.vo.DotKeywords;
import dev.secondsun.retro.util.vo.TokenizedFile;
import dev.secondsun.retro.util.vo.Tokens;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thread-safe lexical scanner for unassembled ca65 and X-GSU assembly source code.
 *
 * <p>Populates {@link Token#text()} faithfully for every token type (including hex {@code $NN} /
 * {@code 0xNN}, binary {@code %NN}, char literals {@code 'c'}, and multi-character operators) and
 * produces immutable {@link Token} and {@link TokenizedFile} values.
 */
public class CA65Scanner {

  private static final char LOCAL_START = '@';

  public TokenizedFile tokenize(String ca65programText) {
    return tokenize(ca65programText, null);
  }

  public TokenizedFile tokenize(String ca65programText, URI uri) {
    if (ca65programText == null) {
      return uri == null ? TokenizedFile.EMPTY : TokenizedFile.emptyWithUri(uri);
    }
    var rawLines = Arrays.stream(ca65programText.split("\\r?\\n", -1)).toList();
    var strippedText = Util.removeComments(ca65programText);
    var lines = Arrays.stream(strippedText.split("\\n")).map(it -> it + "\n").toList();

    ScanSession session = new ScanSession(lines);
    Map<Integer, Tokens> fileLines = new LinkedHashMap<>();
    int maxLineIndex = rawLines.isEmpty() ? 0 : rawLines.size() - 1;

    for (String currentLine : lines) {
      if (!currentLine.isBlank()) {
        int lineNumber = session.line;
        List<Token> tokens = session.tokenizeLine(lineNumber);
        fileLines.put(lineNumber, new Tokens(currentLine, tokens));
        if (lineNumber > maxLineIndex) {
          maxLineIndex = lineNumber;
        }
      }
    }

    return TokenizedFile.create(uri, rawLines, fileLines, maxLineIndex);
  }

  /** Tokenizes a single line of assembly text in isolation. */
  public List<Token> tokenizeLine(String singleLine) {
    if (singleLine == null || singleLine.isBlank()) {
      return List.of();
    }
    var stripped = Util.removeComments(singleLine);
    if (stripped.isBlank()) {
      return List.of();
    }
    ScanSession session = new ScanSession(List.of(stripped + "\n"));
    return session.tokenizeLine(0);
  }

  private static final class ScanSession {
    private final List<String> lines;
    private boolean isForcedEnd = false;
    private char c = '\0';
    private int line = 0;
    private int column = 0;
    private boolean newLine = false;

    ScanSession(List<String> lines) {
      this.lines = lines;
      while (line < lines.size() && lines.get(line).isBlank()) {
        line++;
      }
      if (line < lines.size()) {
        c = lines.get(line).charAt(column);
      } else {
        c = '\0';
      }
    }

    List<Token> tokenizeLine(int lineNumber) {
      var list = new ArrayList<Token>();
      var token = nextRawToken(lineNumber);
      while (token.type != TokenType.TOK_EOF && token.type != TokenType.TOK_SEP && !newLine) {
        list.add(token);
        token = nextRawToken(lineNumber);
      }
      if (token.type != TokenType.TOK_EOF && token.type != TokenType.TOK_SEP) {
        list.add(token);
      }
      newLine = false;
      column = -1;
      c = nextChar();
      return List.copyOf(list);
    }

    private Token finalizeToken(Token.Builder builder) {
      String text = builder.text();
      if (!text.isEmpty() && SuperFxIsa.isMnemonic(text)) {
        builder.addAttribute(TokenAttribute.GSU_INSTRUCTION);
        if ("nop".equalsIgnoreCase(text)) {
          builder.addAttribute(TokenAttribute.NO_OPERATION);
        }
      }
      return builder.build();
    }

    private Token nextRawToken(int lineNumber) {
      Token.Builder b = new Token.Builder();
      b.lineNumber = lineNumber;

      if (isForcedEnd) {
        b.startIndex = Math.max(0, column);
        b.type = TokenType.TOK_EOF;
        b.endIndex = 0;
        return b.build();
      }

      if (isBlank(c)) {
        do {
          c = nextChar();
        } while (isBlank(c) && !isForcedEnd && !newLine);
      }

      b.startIndex = Math.max(0, column);

      if (isForcedEnd) {
        b.type = TokenType.TOK_EOF;
        b.endIndex = 0;
        return b.build();
      }

      /* Hex number ($NN)? */
      if (c == '$') {
        b.appendChar('$');
        c = nextChar();

        if (!isXDigit(c)) {
          b.type = TokenType.TOK_ERROR;
          b.message = "Hex digit expected";
          b.endIndex = column;
          return b.build();
        }

        b.intVal = 0;
        while (isXDigit(c)) {
          if ((b.intVal & 0xF0000000) != 0) {
            b.type = TokenType.TOK_ERROR;
            b.message = "Overflow in hexadecimal number";
            b.intVal = 0;
            b.endIndex = column;
            return b.build();
          }
          b.appendChar(c);
          b.intVal = (b.intVal << 4) + digitVal(c);
          c = nextChar();
        }

        b.type = TokenType.TOK_INTCON;
        b.endIndex = column;
        return b.build();
      }

      /* Binary number (%NN)? */
      if (c == '%') {
        b.appendChar('%');
        c = nextChar();

        if (!isBDigit(c)) {
          return error("Binary digit expected", b);
        }

        b.intVal = 0;
        while (isBDigit(c)) {
          if ((b.intVal & 0x80000000) != 0) {
            b.intVal = 0;
            return error("Overflow in binary number", b);
          }
          b.appendChar(c);
          b.intVal = (b.intVal << 1) + digitVal(c);
          c = nextChar();
        }

        b.type = TokenType.TOK_INTCON;
        b.endIndex = column;
        return b.build();
      }

      /* Number (decimal, 0x hex, or Zilog h-suffixed hex)? */
      if (isDigit(c)) {
        if (c == '0') {
          b.appendChar(c);
          c = nextChar();
          if (c == 'x' || c == 'X') {
            b.appendChar(c);
            c = nextChar();
            if (!isXDigit(c)) {
              return error("Hex digit expected", b);
            }
            b.intVal = 0;
            while (isXDigit(c)) {
              if ((b.intVal & 0xF0000000) != 0) {
                b.intVal = 0;
                return error("Overflow in hexadecimal number", b);
              }
              b.appendChar(c);
              b.intVal = (b.intVal << 4) + digitVal(c);
              c = nextChar();
            }
            b.type = TokenType.TOK_INTCON;
            b.endIndex = column;
            return b.build();
          }
          while (c == '0') {
            b.appendChar(c);
            c = nextChar();
          }
        }

        char[] buf = new char[16];
        int digits = 0;
        while (isXDigit(c)) {
          if (digits < buf.length) {
            buf[digits++] = c;
          }
          b.appendChar(c);
          c = nextChar();
        }

        int base;
        long max;
        if (c == 'h' || c == 'H') {
          b.appendChar(c);
          c = nextChar();
          base = 16;
          max = 0x7FFFFFFF / 16;
        } else {
          base = 10;
          max = 0x7FFFFFFF / 10;
        }

        b.intVal = 0;
        for (int i = 0; i < digits; ++i) {
          if (b.intVal > max) {
            return error("Number out of range", b);
          }
          int dVal = digitVal(buf[i]);
          if (dVal >= base) {
            return error("Invalid digits in number", b);
          }
          b.intVal = (b.intVal * base) + dVal;
        }

        b.type = TokenType.TOK_INTCON;
        b.endIndex = column;
        return b.build();
      }

      /* Control command (.directive)? */
      if (c == '.') {
        c = nextChar();
        if (!isIdStart(c)) {
          b.appendChar('.');
          b.type = TokenType.TOK_DOT;
        } else {
          b.appendChar('.');
          readIdent(b);
          b.type = findDotKeyword(b);
        }
        b.endIndex = column;
        return b.build();
      }

      /* Cheap local symbol (@ident)? */
      if (c == LOCAL_START) {
        readIdent(b);
        if (b.text().length() == 1) {
          return error("Invalid cheap local symbol", b);
        }
        b.type = TokenType.TOK_LOCAL_IDENT;
        b.endIndex = column;
        return b.build();
      }

      /* Identifier, register, or keyword? */
      if (isIdStart(c)) {
        readIdent(b);
        String text = b.text();

        if (text.length() == 1) {
          switch (Character.toUpperCase(text.charAt(0))) {
            case 'A' -> {
              if (c == ':') {
                b.appendChar(c);
                c = nextChar();
                b.type = TokenType.TOK_OVERRIDE_ABS;
              } else {
                b.type = TokenType.TOK_A;
              }
              b.endIndex = column;
              return finalizeToken(b);
            }
            case 'F' -> {
              if (c == ':') {
                b.appendChar(c);
                c = nextChar();
                b.type = TokenType.TOK_OVERRIDE_FAR;
                b.endIndex = column;
                return finalizeToken(b);
              }
            }
            case 'S' -> {
              b.type = TokenType.TOK_S;
              b.endIndex = column;
              return finalizeToken(b);
            }
            case 'X' -> {
              b.type = TokenType.TOK_X;
              b.endIndex = column;
              return finalizeToken(b);
            }
            case 'Y' -> {
              b.type = TokenType.TOK_Y;
              b.endIndex = column;
              return finalizeToken(b);
            }
            case 'Z' -> {
              if (c == ':') {
                b.appendChar(c);
                c = nextChar();
                b.type = TokenType.TOK_OVERRIDE_ZP;
                b.endIndex = column;
                return finalizeToken(b);
              }
            }
            default -> {}
          }
        }

        if ((text.charAt(0) == 'r' || text.charAt(0) == 'R') && text.matches("[rR]\\d{1,2}")) {
          b.type = TokenType.TOK_REGISTER;
          b.endIndex = column;
          return finalizeToken(b);
        }

        if (text.equalsIgnoreCase("function")) {
          b.type = TokenType.TOK_FUNCTION;
          b.endIndex = column;
          return finalizeToken(b);
        }
        if (text.equalsIgnoreCase("endfunction")) {
          b.type = TokenType.TOK_ENDFUNCTION;
          b.endIndex = column;
          return finalizeToken(b);
        }
        if (text.equalsIgnoreCase("call")) {
          b.type = TokenType.TOK_CALL;
          b.endIndex = column;
          return finalizeToken(b);
        }
        if (text.equalsIgnoreCase("return")) {
          b.type = TokenType.TOK_RETURN;
          b.endIndex = column;
          return finalizeToken(b);
        }
        if (text.equalsIgnoreCase("register")) {
          b.type = TokenType.TOK_REGISTER_KEYWORD;
          b.endIndex = column;
          return finalizeToken(b);
        }

        b.type = TokenType.TOK_IDENT;
        b.endIndex = column;
        return finalizeToken(b);
      }

      switch (c) {
        case '+' -> {
          b.appendChar(c);
          c = nextChar();
          b.type = TokenType.TOK_PLUS;
          b.endIndex = column;
          return b.build();
        }
        case '-' -> {
          b.appendChar(c);
          c = nextChar();
          b.type = TokenType.TOK_MINUS;
          b.endIndex = column;
          return b.build();
        }
        case '/' -> {
          b.appendChar(c);
          c = nextChar();
          if (c != '*') {
            b.type = TokenType.TOK_DIV;
          }
          b.endIndex = column;
          return b.build();
        }
        case '*' -> {
          b.appendChar(c);
          c = nextChar();
          b.type = TokenType.TOK_MUL;
          b.endIndex = column;
          return b.build();
        }
        case '^' -> {
          b.appendChar(c);
          c = nextChar();
          b.type = TokenType.TOK_XOR;
          b.endIndex = column;
          return b.build();
        }
        case '&' -> {
          b.appendChar(c);
          c = nextChar();
          if (c == '&') {
            b.appendChar(c);
            c = nextChar();
            b.type = TokenType.TOK_BOOLAND;
          } else {
            b.type = TokenType.TOK_AND;
          }
          b.endIndex = column;
          return b.build();
        }
        case '|' -> {
          b.appendChar(c);
          c = nextChar();
          if (c == '|') {
            b.appendChar(c);
            c = nextChar();
            b.type = TokenType.TOK_BOOLOR;
          } else {
            b.type = TokenType.TOK_OR;
          }
          b.endIndex = column;
          return b.build();
        }
        case ':' -> {
          b.appendChar(c);
          c = nextChar();
          switch (c) {
            case ':' -> {
              b.appendChar(c);
              c = nextChar();
              b.type = TokenType.TOK_NAMESPACE;
            }
            case '-' -> {
              b.intVal = 0;
              do {
                --b.intVal;
                b.appendChar(c);
                c = nextChar();
              } while (c == '-');
              b.type = TokenType.TOK_ULABEL;
            }
            case '+' -> {
              b.intVal = 0;
              do {
                ++b.intVal;
                b.appendChar(c);
                c = nextChar();
              } while (c == '+');
              b.type = TokenType.TOK_ULABEL;
            }
            case '=' -> {
              b.appendChar(c);
              c = nextChar();
              b.type = TokenType.TOK_ASSIGN;
            }
            default -> b.type = TokenType.TOK_COLON;
          }
          b.endIndex = column;
          return b.build();
        }
        case ',' -> {
          b.appendChar(c);
          c = nextChar();
          b.type = TokenType.TOK_COMMA;
          b.endIndex = column;
          return b.build();
        }
        case '#' -> {
          b.appendChar(c);
          c = nextChar();
          b.type = TokenType.TOK_HASH;
          b.endIndex = column;
          return b.build();
        }
        case '(' -> {
          b.appendChar(c);
          c = nextChar();
          b.type = TokenType.TOK_LPAREN;
          b.endIndex = column;
          return b.build();
        }
        case ')' -> {
          b.appendChar(c);
          c = nextChar();
          b.type = TokenType.TOK_RPAREN;
          b.endIndex = column;
          return b.build();
        }
        case '[' -> {
          b.appendChar(c);
          c = nextChar();
          b.type = TokenType.TOK_LBRACK;
          b.endIndex = column;
          return b.build();
        }
        case ']' -> {
          b.appendChar(c);
          c = nextChar();
          b.type = TokenType.TOK_RBRACK;
          b.endIndex = column;
          return b.build();
        }
        case '{' -> {
          b.appendChar(c);
          c = nextChar();
          b.type = TokenType.TOK_LCURLY;
          b.endIndex = column;
          return b.build();
        }
        case '}' -> {
          b.appendChar(c);
          c = nextChar();
          b.type = TokenType.TOK_RCURLY;
          b.endIndex = column;
          return b.build();
        }
        case '<' -> {
          b.appendChar(c);
          c = nextChar();
          if (c == '=') {
            b.appendChar(c);
            c = nextChar();
            b.type = TokenType.TOK_LE;
          } else if (c == '<') {
            b.appendChar(c);
            c = nextChar();
            b.type = TokenType.TOK_SHL;
          } else if (c == '>') {
            b.appendChar(c);
            c = nextChar();
            b.type = TokenType.TOK_NE;
          } else {
            b.type = TokenType.TOK_LT;
          }
          b.endIndex = column;
          return b.build();
        }
        case '=' -> {
          b.appendChar(c);
          c = nextChar();
          b.type = TokenType.TOK_EQ;
          b.endIndex = column;
          return b.build();
        }
        case '!' -> {
          b.appendChar(c);
          c = nextChar();
          b.type = TokenType.TOK_BOOLNOT;
          b.endIndex = column;
          return b.build();
        }
        case '>' -> {
          b.appendChar(c);
          c = nextChar();
          if (c == '=') {
            b.appendChar(c);
            c = nextChar();
            b.type = TokenType.TOK_GE;
          } else if (c == '>') {
            b.appendChar(c);
            c = nextChar();
            b.type = TokenType.TOK_SHR;
          } else {
            b.type = TokenType.TOK_GT;
          }
          b.endIndex = column;
          return b.build();
        }
        case '~' -> {
          b.appendChar(c);
          c = nextChar();
          b.type = TokenType.TOK_NOT;
          b.endIndex = column;
          return b.build();
        }
        case '\'' -> {
          b.appendChar('\'');
          c = nextChar();
          if (c == '\0' || isControl(c)) {
            return error("Illegal character constant", b);
          }
          b.appendChar(c);
          b.intVal = c;
          b.type = TokenType.TOK_CHARCON;
          c = nextChar();
          if (c != '\'') {
            return error("Illegal character constant", b);
          }
          b.appendChar('\'');
          c = nextChar();
          b.endIndex = column;
          return b.build();
        }
        case '\"' -> {
          readStringConst('\"', b);
          if (b.type != TokenType.TOK_ERROR) {
            b.type = TokenType.TOK_STRCON;
          }
          b.endIndex = column;
          return b.build();
        }
        case '\\' -> {}
        case '\n' -> {
          b.type = TokenType.TOK_SEP;
          b.endIndex = column;
          return b.build();
        }
        case '\0' -> {
          b.type = TokenType.TOK_EOF;
          b.endIndex = column;
          return b.build();
        }
        default -> {}
      }

      b.appendChar(c);
      Token err = error(String.format("Invalid input character: 0x%02X", c & 0xFF), b);
      c = nextChar();
      return err;
    }

    private void readStringConst(char stringTerm, Token.Builder b) {
      c = nextChar();
      while (true) {
        if (c == stringTerm) {
          break;
        }
        if (c == '\n' || c == '\0') {
          b.message = "Newline in string constant";
          b.intVal = 0;
          b.type = TokenType.TOK_ERROR;
          break;
        }
        if (c == '\\') {
          c = nextChar();
          switch (c) {
            case '\0' -> {
              b.message = "Unterminated escape sequence in string constant";
              b.type = TokenType.TOK_ERROR;
            }
            case '\\', '\'', '"', 't', 'r', 'n' -> {}
            case 'x' -> {
              c = nextChar();
              if (isXDigit(c)) {
                c = nextChar();
                if (isXDigit(c)) {
                  break;
                }
              }
              b.message = "Unsupported escape sequence in string constant";
              b.type = TokenType.TOK_ERROR;
            }
            default -> {
              b.message = "Unsupported escape sequence in string constant";
              b.type = TokenType.TOK_ERROR;
            }
          }
        }
        b.appendChar(c);
        c = nextChar();
      }
      if (c == stringTerm) {
        c = nextChar();
      }
    }

    private TokenType findDotKeyword(Token.Builder b) {
      var directive = DotKeywords.fromText(b.text());
      if (directive != null) {
        return directive.type;
      }
      b.message = "Unknown Directive";
      b.intVal = 0;
      return TokenType.TOK_ERROR;
    }

    private void readIdent(Token.Builder b) {
      do {
        b.appendChar(c);
        c = nextChar();
        b.endIndex = column;
      } while (isIdChar(c));
    }

    private Token error(String message, Token.Builder b) {
      b.message = message;
      b.endIndex = column;
      b.intVal = 0;
      b.type = TokenType.TOK_ERROR;
      return b.build();
    }

    private char nextChar() {
      if (line >= lines.size()) {
        return '\0';
      }
      this.column++;
      var currentLine = lines.get(line);
      if (currentLine.length() > column) {
        var toReturn = currentLine.charAt(column);
        if (toReturn == '\n') {
          newLine = true;
          line++;
          while (line < lines.size() && lines.get(line).isBlank()) {
            line++;
          }
          if (line < lines.size()) {
            return '\n';
          } else {
            isForcedEnd = true;
            return '\0';
          }
        }
        return toReturn;
      } else {
        throw new RuntimeException(
            "Should not reach " + this.line + ", " + this.column + " was " + currentLine);
      }
    }
  }

  private static boolean isControl(char c2) {
    return Character.isISOControl(c2);
  }

  private static boolean isIdChar(char c2) {
    return isAlNum(c2) || (c2 == '_') || (c2 == '@') || (c2 == '$');
  }

  private static boolean isAlNum(char c2) {
    return Character.isAlphabetic(c2) || Character.isDigit(c2);
  }

  private static boolean isIdStart(char c2) {
    return Character.isAlphabetic(c2) || c2 == '_';
  }

  private static boolean isDigit(char c2) {
    return Character.isDigit(c2);
  }

  private static boolean isBDigit(char c2) {
    return c2 == '0' || c2 == '1';
  }

  private static int digitVal(char c2) {
    return Integer.parseInt(String.valueOf(c2), 16);
  }

  private static boolean isXDigit(char c2) {
    return Character.isDigit(c2) || (c2 >= 'a' && c2 <= 'f') || (c2 >= 'A' && c2 <= 'F');
  }

  private static boolean isBlank(char c2) {
    return !Character.isWhitespace(c2) ? false : c2 != '\n';
  }
}
