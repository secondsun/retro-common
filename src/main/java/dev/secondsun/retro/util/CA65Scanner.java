package dev.secondsun.retro.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import dev.secondsun.retro.util.vo.DotKeywords;
import dev.secondsun.retro.util.vo.TokenizedFile;

/**
 * Scanner for unassembled ca65 assembly code.
 * This scanner does *NOT* expand macros and
 * works with my X_GSU library.
 * 
 * Based on scanner.c from ca65 source code.
 * https://github.com/cc65/cc65/blob/master/src/ca65/scanner.c
 */
public class CA65Scanner {

    private static final char LOCAL_START = '@';
    private boolean isForcedEnd = false;
    private char c = '\0';
    private int line = 0;
    private int column = 0;
    private List<String> lines = null;
    private boolean newLine = false;

    public List<Token> tokenizeLine(String line) {
        var token = nextToken();

        var list = new java.util.ArrayList<Token>();
        while (token.type != TokenType.TOK_EOF && token.type != TokenType.TOK_SEP && !newLine) {
            list.add(token);
            token = nextToken();
        }

        if (token.type != TokenType.TOK_EOF && token.type != TokenType.TOK_SEP) {
            list.add(token);
        }

        newLine = false;
        column = -1;
        c = nextChar();
        return list;
    }

    public TokenizedFile tokenize(String ca65programText) {
        var toReturn = new TokenizedFile();
        ca65programText = Util.removeComments(ca65programText);
        this.lines = Arrays.stream(ca65programText.split("\\n")).map(it->it + "\n").toList();
        this.line = 0;
        this.column = 0;
        while (lines.get(line).isBlank()) {
            line++;
        }
        c = lines.get(line).charAt(column);
        for (var line : lines) {

            if (!line.isBlank()) {
                var lineNumber = this.line;
                var tokens = tokenizeLine(line);
                tokens.forEach(it -> it.lineNumber = lineNumber);
                toReturn.addLine(line,lineNumber, tokens);
            }
        }

        return toReturn;
    }

    private Token nextToken() {
        return nextRawToken();
    }

    private Token nextRawToken() {

        Token toReturn = new Token();
        toReturn.startIndex = column;
        if (isForcedEnd) {
            toReturn.type = TokenType.TOK_EOF;
            toReturn.endIndex = 0;
            return toReturn;
        }


        /* Skip whitespace */
        if (isBlank(c)) {

            do {
                c = nextChar();
            } while (isBlank(c) && !isForcedEnd && !newLine);
        }

        if (isForcedEnd) {// reached EOF above
            toReturn.type = TokenType.TOK_EOF;
            toReturn.endIndex = 0;
            return toReturn;
        }


        /* Hex number or PC symbol? */
        if (c == '$') {
            c = nextChar();

            /* Hex digit must follow or DollarIsPC must be enabled */
            if (!isXDigit(c)) {
                toReturn.type = TokenType.TOK_ERROR;
                toReturn.message = "Hex digit expected";
                toReturn.endIndex = column;
                return toReturn;
            }

            /* Read the number */
            toReturn.intVal = 0;
            while (true) {
                if (isXDigit(c)) {
                    if ((toReturn.intVal & 0xF0000000) != 0) {
                        toReturn.type = TokenType.TOK_ERROR;
                        toReturn.message = ("Overflow in hexadecimal number");
                        toReturn.intVal = 0;
                        return toReturn;
                    }
                    toReturn.intVal = (toReturn.intVal << 4) + digitVal(c);
                    c = nextChar();
                } else {
                    break;
                }
            }

            /* This is an integer constant */
            toReturn.type = TokenType.TOK_INTCON;
            toReturn.endIndex = column;
            return toReturn;
        }

        /* Binary number? */
        if (c == '%') {
            c = nextChar();

            /* 0 or 1 must follow */
            if (!isBDigit(c)) {
                return error("Binary digit expected", toReturn);
            }

            /* Read the number */
            toReturn.intVal = 0;
            while (true) {
                if (isBDigit(c)) {
                    if ((toReturn.intVal & 0x80000000) != 0) {
                        toReturn.intVal = 0;
                        return error("Overflow in binary number", toReturn);

                    }
                    toReturn.intVal = (toReturn.intVal << 1) + digitVal(c);
                    c = nextChar();
                } else {
                    break;
                }
            }

            /* This is an integer constant */
            toReturn.type = TokenType.TOK_INTCON;
            toReturn.endIndex = column;
            return toReturn;
        }

        /* Number? */
        if (isDigit(c)) {

            char[] buf = new char[16];
            int Digits;
            int Base;
            int I;
            long Max;
            int DVal;

            /* Ignore leading zeros */
            while (c == '0') {
                toReturn.appendChar(c);
                c = nextChar();
            }

            /* Read the number into Buf counting the digits */
            Digits = 0;
            while (true) {
                if (isXDigit(c)) {
                    /*
                     * Buf is big enough to allow any decimal and hex number to
                     ** overflow, so ignore excess digits here, they will be detected
                     ** when we convert the value.
                     */
                    if (Digits < buf.length) {
                        buf[Digits++] = c;
                    }
                    toReturn.appendChar(c);
                    c = nextChar();
                } else {
                    break;
                }
            }

            /* Allow zilog/intel style hex numbers with a 'h' suffix */
            if (c == 'h' || c == 'H') {
                toReturn.appendChar(c);
                c = nextChar();
                Base = 16;
                Max = 0x7FFFFFFF / 16;
            } else {
                Base = 10;
                Max = 0x7FFFFFFF / 10;
            }

            /* Convert the number using the given base */
            toReturn.intVal = 0;
            for (I = 0; I < Digits; ++I) {
                if (toReturn.intVal > Max) {
                    return error("Number out of range", toReturn);
                }
                DVal = digitVal(buf[I]);
                if (DVal >= Base) {
                    return error("Invalid digits in number", toReturn);
                }
                toReturn.intVal = (toReturn.intVal * Base) + DVal;
            }

            /* This is an integer constant */
            toReturn.type = TokenType.TOK_INTCON;
            toReturn.endIndex = column;
            return toReturn;
        }

        /* Control command? */
        if (c == '.') {

            /* Remember and skip the dot */
            c = nextChar();

            /* Check if it's just a dot */
            if (!isIdStart(c)) {
                /* Just a dot */
                toReturn.appendChar('.');
                toReturn.type = TokenType.TOK_DOT;
            } else {

                /* Read the remainder of the identifier */
                toReturn.appendChar('.');
                readIdent(toReturn);

                /* Dot keyword, search for it */
                toReturn.type = findDotKeyword(toReturn);

            }
            toReturn.endIndex = column;
            return toReturn;
        }

        /* Local symbol? */
        if (c == LOCAL_START) {

            /* Read the identifier. */
            readIdent(toReturn);

            /* Start character alone is not enough */
            if (toReturn.text().length() == 1) {
                error("Invalid cheap local symbol", toReturn);
                toReturn.endIndex = column;
                return toReturn;
            }

            if (toReturn.text().equals("register")) {
                toReturn.type = TokenType.TOK_REGISTER_KEYWORD;
                toReturn.endIndex = column;
                return toReturn;
            }

            /* A local identifier */
            toReturn.type = TokenType.TOK_LOCAL_IDENT;
            toReturn.endIndex = column;
            return toReturn;
        }

        /* Identifier or keyword? */
        if (isIdStart(c)) {

            /* Read the identifier */
            readIdent(toReturn);

            /*
             * Check for special names. Bail out if we have identified the type of
             ** the token. Go on if the token is an identifier.
             */
            switch (toReturn.text().length()) {
                case 1:
                    switch (Character.toUpperCase(toReturn.text().charAt(0))) {
                        case 'A':
                            if (c == ':') {
                                c = nextChar();
                                toReturn.type = TokenType.TOK_OVERRIDE_ABS;
                            } else {
                                toReturn.type = TokenType.TOK_A;
                            }
                            toReturn.endIndex = column;
                            return toReturn;

                        case 'F':
                            if (c == ':') {
                                c = nextChar();
                                toReturn.type = TokenType.TOK_OVERRIDE_FAR;
                                toReturn.endIndex = column;
                                return toReturn;
                            }
                            break;

                        case 'S':
                            toReturn.type = TokenType.TOK_S;
                            toReturn.endIndex = column;
                            return toReturn;
                        case 'X':
                            toReturn.type = TokenType.TOK_X;
                            toReturn.endIndex = column;
                            return toReturn;

                        case 'Y':
                            toReturn.type = TokenType.TOK_Y;
                            toReturn.endIndex = column;
                            return toReturn;

                        case 'Z':
                            if (c == ':') {
                                c = nextChar();
                                toReturn.type = TokenType.TOK_OVERRIDE_ZP;
                                toReturn.endIndex = column;
                                return toReturn;
                            }
                            break;

                        default:
                            break;
                    }
                default:
                    break;

            }

            switch (Character.toUpperCase(toReturn.text().charAt(0))) {
                case 'r':
                case 'R':
                    if (toReturn.text().matches("[rR]\\d{1,2}")) {
                        toReturn.type = TokenType.TOK_REGISTER;
                        toReturn.endIndex = column;
                        return toReturn;
                    }
                    break;
            }

            if (toReturn.text().equals("register")) {
                toReturn.type = TokenType.TOK_REGISTER_KEYWORD;
                toReturn.endIndex = column;
                return toReturn;
            }
            /* Check for define style macro */
            toReturn.type = TokenType.TOK_IDENT;
            toReturn.endIndex = column;
            return toReturn;
        }

        /* Ok, let's do the switch */
        CharAgain: switch (c) {

            case '+':
                toReturn.appendChar(c);
                c = nextChar();
                toReturn.type = TokenType.TOK_PLUS;
                toReturn.endIndex = column;
                return toReturn;

            case '-':
                toReturn.appendChar(c);
                c = nextChar();
                toReturn.type = TokenType.TOK_MINUS;
                toReturn.endIndex = column;
                return toReturn;

            case '/':
                toReturn.appendChar(c);
                c = nextChar();
                if (c != '*') {
                    toReturn.type = TokenType.TOK_DIV;
                }
                toReturn.endIndex = column;
                return toReturn;

            case '*':
                toReturn.appendChar(c);
                c = nextChar();
                toReturn.type = TokenType.TOK_MUL;
                toReturn.endIndex = column;
                return toReturn;

            case '^':
                toReturn.appendChar(c);
                c = nextChar();
                toReturn.type = TokenType.TOK_XOR;
                toReturn.endIndex = column;
                return toReturn;

            case '&':
                toReturn.appendChar(c);
                c = nextChar();
                if (c == '&') {
                    toReturn.appendChar(c);
                    c = nextChar();
                    toReturn.type = TokenType.TOK_BOOLAND;
                } else {
                    toReturn.type = TokenType.TOK_AND;
                }
                toReturn.endIndex = column;
                return toReturn;

            case '|':
                toReturn.appendChar(c);
                c = nextChar();
                if (c == '|') {
                    c = nextChar();
                    toReturn.type = TokenType.TOK_BOOLOR;
                } else {
                    toReturn.type = TokenType.TOK_OR;
                }
                toReturn.endIndex = column;
                return toReturn;

            case ':':
                toReturn.appendChar(c);
                c = nextChar();
                switch (c) {
                    case ':':
                        toReturn.appendChar(c);
                        c = nextChar();
                        toReturn.type = TokenType.TOK_NAMESPACE;
                        toReturn.endIndex = column;
                        break;

                    case '-':
                        toReturn.intVal = 0;
                        do {
                            --toReturn.intVal;
                            toReturn.endIndex = column;
                            toReturn.appendChar(c);
                            c = nextChar();
                        } while (c == '-');
                        toReturn.type = TokenType.TOK_ULABEL;
                        break;

                    case '+':
                        toReturn.intVal = 0;
                        do {
                            ++toReturn.intVal;
                            toReturn.endIndex = column;
                            toReturn.appendChar(c);
                            c = nextChar();
                        } while (c == '+');
                        toReturn.type = TokenType.TOK_ULABEL;
                        break;

                    case '=':
                        toReturn.appendChar(c);
                        c = nextChar();
                        toReturn.type = TokenType.TOK_ASSIGN;
                        break;

                    default:
                        toReturn.type = TokenType.TOK_COLON;
                        break;

                }
                toReturn.endIndex = column;
                return toReturn;

            case ',':
                toReturn.appendChar(c);
                c = nextChar();
                toReturn.type = TokenType.TOK_COMMA;
                toReturn.endIndex = column;
                return toReturn;
            case '#':
                toReturn.appendChar(c);
                c = nextChar();
                toReturn.type = TokenType.TOK_HASH;
                toReturn.endIndex = column;
                return toReturn;

            case '(':
                toReturn.appendChar(c);
                c = nextChar();
                toReturn.type = TokenType.TOK_LPAREN;
                toReturn.endIndex = column;
                return toReturn;
            case ')':
                toReturn.appendChar(c);
                c = nextChar();
                toReturn.type = TokenType.TOK_RPAREN;
                toReturn.endIndex = column;
                return toReturn;

            case '[':
                toReturn.appendChar(c);
                c = nextChar();
                toReturn.type = TokenType.TOK_LBRACK;
                toReturn.endIndex = column;
                return toReturn;

            case ']':
                c = nextChar();
                toReturn.type = TokenType.TOK_RBRACK;
                toReturn.endIndex = column;return toReturn;

            case '{':
                c = nextChar();
                toReturn.type = TokenType.TOK_LCURLY;
                toReturn.endIndex = column;return toReturn;

            case '}':
                c = nextChar();
                toReturn.type = TokenType.TOK_RCURLY;
                toReturn.endIndex = column;return toReturn;

            case '<':
                c = nextChar();
                if (c == '=') {
                    c = nextChar();
                    toReturn.type = TokenType.TOK_LE;
                } else if (c == '<') {
                    c = nextChar();
                    toReturn.type = TokenType.TOK_SHL;
                } else if (c == '>') {
                    c = nextChar();
                    toReturn.type = TokenType.TOK_NE;
                } else {
                    toReturn.type = TokenType.TOK_LT;
                }
                toReturn.endIndex = column;return toReturn;

            case '=':
                c = nextChar();
                toReturn.type = TokenType.TOK_EQ;
                toReturn.endIndex = column;return toReturn;

            case '!':
                c = nextChar();
                toReturn.type = TokenType.TOK_BOOLNOT;
                toReturn.endIndex = column;return toReturn;

            case '>':
                c = nextChar();
                if (c == '=') {
                    c = nextChar();
                    toReturn.type = TokenType.TOK_GE;
                } else if (c == '>') {
                    c = nextChar();
                    toReturn.type = TokenType.TOK_SHR;
                } else {
                    toReturn.type = TokenType.TOK_GT;
                }
                toReturn.endIndex = column;return toReturn;

            case '~':
                c = nextChar();
                toReturn.type = TokenType.TOK_NOT;
                toReturn.endIndex = column;return toReturn;

            case '\'': {
                /* Always a character constant */
                c = nextChar();
                if (c == '\0' || isControl(c)) {
                    error("Illegal character constant", toReturn);
                    toReturn.endIndex = column;return toReturn;
                }
                toReturn.intVal = c;
                toReturn.type = TokenType.TOK_CHARCON;
                c = nextChar();
                if (c != '\'') {
                    error("Illegal character constant", toReturn);
                    toReturn.endIndex = column;return toReturn;
                } else {
                    c = nextChar();
                }
            }
            toReturn.endIndex = column;return toReturn;

            case '\"':
                readStringConst('\"', toReturn);
                toReturn.type = TokenType.TOK_STRCON;
                toReturn.endIndex = column;return toReturn;

            case '\\':
                break;

            case '\n':
                //nextChar is handled in line setup
                toReturn.type = TokenType.TOK_SEP;
                toReturn.endIndex = column;
                return toReturn;

            case '\0':
                toReturn.type = TokenType.TOK_EOF;
                toReturn.endIndex = column;
                return toReturn;
        }

        /*
         * If we go here, we could not identify the current character. Skip it
         ** and try again.
         */
        error(String.format("Invalid input character: 0x%02X", c & 0xFF), toReturn);
        toReturn.endIndex = column;
        c = nextChar();
        return toReturn;
    }

    private void readStringConst(char stringTerm, Token toReturn) {
        /* Skip the leading string terminator */
        c = nextChar();

        /* Read the string */
        while (true) {
            if (c == stringTerm) {
                break;
            }
            if (c == '\n' || c == '\0') {
                error("Newline in string constant", toReturn);
                break;
            }

            if (c == '\\') {
                c = nextChar();

                switch (c) {
                    case '\0':
                        error("Unterminated escape sequence in string constant", toReturn);
                        break;
                    case '\\':
                    case '\'':
                    case '"':
                        break;
                    case 't':

                        break;
                    case 'r':

                        break;
                    case 'n':

                        break;
                    case 'x':
                        c = nextChar();
                        if (isXDigit(c)) {
                            // char high_nibble = digitVal (c) << 4;
                            c = nextChar();
                            if (isXDigit(c)) {
                                // c = high_nibble | digitVal (c);
                                break;
                            }
                        }
                        /* FALLTHROUGH */
                    default:
                        error("Unsupported escape sequence in string constant", toReturn);
                        break;
                }
            }

            /* Append the char to the string */
            toReturn.appendChar(c);

            /* Skip the character */
            c = nextChar();
        }

        /* Skip the trailing terminator */
        c = nextChar();

    }

    private boolean isControl(char c2) {
        return Character.isISOControl(c2);
    }

    private TokenType findDotKeyword(Token token) {
        var directive = DotKeywords.fromText(token.text());
        if (directive != null) {
            return directive.type;
        }
        error("Unknown Directive", token);
        return TokenType.TOK_ERROR;
    }

    private void readIdent(Token token) {
        do {
            token.appendChar(c);
            c = nextChar();
            token.endIndex = column;
        } while (isIdChar(c));

    }

    private boolean isIdChar(char c2) {
        return isAlNum(c2) ||
                (c2 == '_') ||
                (c2 == '@') ||
                (c2 == '$');
    }

    private boolean isAlNum(char c2) {
        return Character.isAlphabetic(c2) || Character.isDigit(c2);
    }

    private boolean isIdStart(char c2) {
        return Character.isAlphabetic(c2) || c2 == '_';
    }

    private boolean isDigit(char c2) {
        return Character.isDigit(c2);
    }

    private Token error(String string, Token toReturn) {
        toReturn.message = string;
        toReturn.endIndex = column;
        toReturn.intVal = 0;
        toReturn.type = TokenType.TOK_ERROR;
        return toReturn;
    }

    private boolean isBDigit(char c2) {
        return c2 == '0' || c2 == '1';
    }

    private int digitVal(char c2) {
        return Integer.parseInt(String.valueOf(c2), 16);
    }

    private boolean isXDigit(char c2) {
        return Character.isDigit(c2) || (c2 >= 'a' && c2 <= 'f') || (c2 >= 'A' && c2 <= 'F');
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
            throw new RuntimeException("Should not reach " + this.line + ", " + this.column + " was " + currentLine);
        }
    }

    private boolean isBlank(char c) {
        return Character.isWhitespace(c);
    }

}
