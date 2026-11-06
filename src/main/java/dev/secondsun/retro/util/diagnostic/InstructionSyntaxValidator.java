package dev.secondsun.retro.util.diagnostic;

import dev.secondsun.retro.util.FunctionSyntaxHelper;
import dev.secondsun.retro.util.Token;
import dev.secondsun.retro.util.TokenAttribute;
import dev.secondsun.retro.util.TokenType;
import dev.secondsun.retro.util.instruction.GSUInstruction;
import dev.secondsun.retro.util.instruction.Instructions;
import dev.secondsun.retro.util.vo.TokenizedFile;
import dev.secondsun.retro.util.vo.Tokens;
import java.net.URI;
import java.util.*;

/**
 * Validates opcode arity, operand patterns, macro calling conventions, and lexical token errors.
 */
public class InstructionSyntaxValidator {

  private final AnonymousLabelValidator anonymousLabelValidator = new AnonymousLabelValidator();

  private static final Set<String> ZERO_ARG_INSTRUCTIONS =
      Set.of(
          "nop", "stop", "cache", "cmode", "color", "div2", "fmult", "lmult", "lob", "hib", "loop",
          "lsr", "not", "plot", "ramb", "romb", "rol", "ror", "rpix", "sbk", "sex", "swap", "alt1",
          "alt2", "alt3", "merge");

  private static final Set<String> TWO_ARG_INSTRUCTIONS =
      Set.of("iwt", "ibt", "move", "moves", "lm", "sm", "lms", "sms");

  private static final Set<String> R1_TO_R15_ONLY_INSTRUCTIONS =
      Set.of("and", "bic", "or", "xor", "mult", "umult");

  private static final Set<String> IMM_0_TO_15_INSTRUCTIONS =
      Set.of("add", "adc", "sub", "sbc", "and", "bic", "or", "xor", "mult", "umult");

  private static final String LINK_INSTRUCTION = "link";

  private static class Scope {
    final Scope parent;
    final Set<String> registerLabels = new HashSet<>();
    final Map<String, String> precoloredRegisters = new HashMap<>();

    Scope(Scope parent) {
      this.parent = parent;
    }

    boolean isRegisterLabel(String name) {
      if (name == null) {
        return false;
      }
      if (registerLabels.contains(name.toLowerCase(Locale.ROOT))) {
        return true;
      }
      return parent != null && parent.isRegisterLabel(name);
    }

    void addRegisterLabel(String name) {
      if (name != null && !name.isBlank()) {
        registerLabels.add(name.toLowerCase(Locale.ROOT));
      }
    }

    void addPrecoloredRegister(String name, String reg) {
      if (name != null && !name.isBlank() && reg != null && !reg.isBlank()) {
        precoloredRegisters.put(name.toLowerCase(Locale.ROOT), reg.toLowerCase(Locale.ROOT));
      }
    }

    String getPrecoloredRegister(String name) {
      if (name == null) {
        return null;
      }
      String reg = precoloredRegisters.get(name.toLowerCase(Locale.ROOT));
      if (reg != null) {
        return reg;
      }
      return parent != null ? parent.getPrecoloredRegister(name) : null;
    }
  }

  public List<SyntaxDiagnostic> validate(TokenizedFile file) {
    if (file == null) {
      return List.of();
    }

    List<SyntaxDiagnostic> diagnostics = new ArrayList<>();
    URI uri = file.uri();

    int totalLines = file.textLines();
    Scope fileScope = new Scope(null);
    Deque<Scope> scopeStack = new ArrayDeque<>();
    scopeStack.push(fileScope);
    Scope[] lineScopes = new Scope[totalLines];

    // Pass 1: Build lexical scopes and collect register labels
    for (int lineIdx = 0; lineIdx < totalLines; lineIdx++) {
      Tokens lineTokens = file.getLine(lineIdx);
      if (lineTokens == null || lineTokens.tokens().isEmpty()) {
        lineScopes[lineIdx] = scopeStack.peek();
        continue;
      }

      List<Token> tokens = lineTokens.tokens();
      int startIdx = findStartIdx(tokens);
      if (startIdx >= tokens.size()) {
        lineScopes[lineIdx] = scopeStack.peek();
        continue;
      }

      Token first = tokens.get(startIdx);
      String firstText = first.text().toLowerCase(Locale.ROOT);

      // Scope openers
      if (first.type == TokenType.TOK_FUNCTION || "function".equals(firstText)) {
        Scope fnScope = new Scope(scopeStack.peek());
        scopeStack.push(fnScope);
        collectFunctionRegisterLabels(tokens, startIdx, fnScope);
      } else if (first.type == TokenType.TOK_PROC
          || ".proc".equals(firstText)
          || first.type == TokenType.TOK_SCOPE
          || ".scope".equals(firstText)) {
        Scope blockScope = new Scope(scopeStack.peek());
        scopeStack.push(blockScope);
      }

      // Register declarations: register var1 [= rX], var2 [= rY]
      if (first.type == TokenType.TOK_REGISTER_KEYWORD || "register".equals(firstText)) {
        collectRegisterKeywordLabels(tokens, startIdx, scopeStack.peek());
      }

      lineScopes[lineIdx] = scopeStack.peek();

      // Scope closers
      if (first.type == TokenType.TOK_ENDFUNCTION
          || "endfunction".equals(firstText)
          || first.type == TokenType.TOK_ENDPROC
          || ".endproc".equals(firstText)
          || first.type == TokenType.TOK_ENDSCOPE
          || ".endscope".equals(firstText)) {
        if (scopeStack.size() > 1) {
          scopeStack.pop();
        }
      }
    }

    // Pass 2: Tag register label tokens and validate instructions/statements
    boolean inMacro = false;
    for (int lineIdx = 0; lineIdx < totalLines; lineIdx++) {
      Tokens lineTokens = file.getLine(lineIdx);
      if (lineTokens == null || lineTokens.tokens().isEmpty()) {
        continue;
      }

      int lineNum = lineIdx + 1; // 1-based
      List<Token> tokens = lineTokens.tokens();
      Scope scope = lineScopes[lineIdx];

      // Tag tokens in active scope matching declared register labels
      if (scope != null) {
        for (Token token : tokens) {
          if (scope.isRegisterLabel(token.text())) {
            token.addAttribute(TokenAttribute.REGISTER_LABEL);
          }
        }
      }

      // 1. Check for scanner lexical errors in any token
      for (Token token : tokens) {
        // The .error directive is scanned as TOK_ERROR but is not a lexical error.
        if (token.type == TokenType.TOK_ERROR && !".error".equalsIgnoreCase(token.text())) {
          String msg =
              token.message != null && !token.message.isBlank()
                  ? token.message
                  : "Lexical error: " + token.text();
          diagnostics.add(
              SyntaxDiagnostic.error(
                  uri, lineNum, token.getStartIndex(), token.getEndIndex(), "LEXICAL_ERROR", msg));
        }
      }

      // Identify leading label if present
      int startIdx = findStartIdx(tokens);
      if (startIdx >= tokens.size()) {
        continue;
      }

      Token first = tokens.get(startIdx);
      String firstText = first.text().toLowerCase(Locale.ROOT);

      // Macro bodies contain parameters and .define names as operands, which are only
      // meaningful after expansion, so skip operand/instruction validation inside them.
      if (first.type == TokenType.TOK_MACRO
          || ".macro".equals(firstText)
          || ".mac".equals(firstText)) {
        inMacro = true;
        continue;
      }
      if (first.type == TokenType.TOK_ENDMACRO
          || ".endmacro".equals(firstText)
          || ".endmac".equals(firstText)) {
        inMacro = false;
        continue;
      }
      if (inMacro) {
        continue;
      }

      // 2. High-level DSL macro validation (function, call, return, endfunction, register)
      if (first.type == TokenType.TOK_FUNCTION || "function".equals(firstText)) {
        validateFunctionDeclaration(tokens, startIdx, lineTokens.line(), uri, lineNum, diagnostics);
        continue;
      }

      if (first.type == TokenType.TOK_CALL || "call".equals(firstText)) {
        validateCallStatement(tokens, startIdx, uri, lineNum, diagnostics);
        continue;
      }

      if (first.type == TokenType.TOK_RETURN || "return".equals(firstText)) {
        validateReturnStatement(tokens, startIdx, uri, lineNum, diagnostics);
        continue;
      }

      if (first.type == TokenType.TOK_ENDFUNCTION || "endfunction".equals(firstText)) {
        validateEndFunctionStatement(tokens, startIdx, uri, lineNum, diagnostics);
        continue;
      }

      if (first.type == TokenType.TOK_REGISTER_KEYWORD || "register".equals(firstText)) {
        validateRegisterDeclaration(tokens, startIdx, uri, lineNum, diagnostics);
        continue;
      }

      // 3. SuperFX GSU Instruction arity & syntax
      if (GSUInstruction.isInstruction(first)) {
        validateGsuInstruction(tokens, startIdx, lineTokens, scope, uri, lineNum, diagnostics);
      }
    }

    diagnostics.addAll(anonymousLabelValidator.validate(file));

    return diagnostics;
  }

  private static boolean isVariableIdentifier(Token t) {
    return t != null
        && (t.type == TokenType.TOK_IDENT
            || t.type == TokenType.TOK_LOCAL_IDENT
            || t.type == TokenType.TOK_A
            || t.type == TokenType.TOK_X
            || t.type == TokenType.TOK_Y
            || t.type == TokenType.TOK_S);
  }

  private int findStartIdx(List<Token> tokens) {
    if (tokens.size() > 1
        && (tokens.get(1).type == TokenType.TOK_COLON || tokens.get(0).text().endsWith(":"))) {
      return (tokens.get(1).type == TokenType.TOK_COLON) ? 2 : 1;
    }
    return 0;
  }

  private void collectFunctionRegisterLabels(List<Token> tokens, int startIdx, Scope fnScope) {
    List<Token> fnTokens = tokens.subList(startIdx, tokens.size());
    var decl = FunctionSyntaxHelper.parseFunction(fnTokens);
    for (Token param : decl.parameters()) {
      if (!param.text().matches("^[rR]\\d+$")) {
        fnScope.addRegisterLabel(param.text());
        param.addAttribute(TokenAttribute.REGISTER_LABEL);
      }
    }
    if (decl.returnVariable().isPresent()) {
      Token ret = decl.returnVariable().get();
      if (!ret.text().matches("^[rR]\\d+$")) {
        fnScope.addRegisterLabel(ret.text());
        ret.addAttribute(TokenAttribute.REGISTER_LABEL);
      }
    }
  }

  private void collectRegisterKeywordLabels(List<Token> tokens, int startIdx, Scope currentScope) {
    int i = startIdx + 1;
    while (i < tokens.size()) {
      Token t = tokens.get(i);
      if (isVariableIdentifier(t)) {
        String varName = t.text();
        currentScope.addRegisterLabel(varName);
        t.addAttribute(TokenAttribute.REGISTER_LABEL);
        i++;
        if (i < tokens.size()) {
          Token next = tokens.get(i);
          if (next.type == TokenType.TOK_EQ
              || next.type == TokenType.TOK_ASSIGN
              || "=".equals(next.text())) {
            i++;
            if (i < tokens.size() && tokens.get(i).type != TokenType.TOK_COMMA) {
              currentScope.addPrecoloredRegister(varName, tokens.get(i).text());
            }
            while (i < tokens.size() && tokens.get(i).type != TokenType.TOK_COMMA) {
              i++;
            }
          }
        }
      } else {
        i++;
      }
    }
  }

  private void validateRegisterDeclaration(
      List<Token> tokens, int startIdx, URI uri, int lineNum, List<SyntaxDiagnostic> diagnostics) {
    if (startIdx + 1 >= tokens.size()) {
      Token kw = tokens.get(startIdx);
      diagnostics.add(
          SyntaxDiagnostic.error(
              uri,
              lineNum,
              kw.getStartIndex(),
              kw.getEndIndex(),
              "REGISTER_DECLARATION_ERROR",
              "Missing variable name after 'register'"));
      return;
    }

    int i = startIdx + 1;
    boolean expectIdent = true;
    while (i < tokens.size()) {
      Token t = tokens.get(i);
      if (expectIdent) {
        if (isVariableIdentifier(t)) {
          expectIdent = false;
          i++;
          if (i < tokens.size()) {
            Token next = tokens.get(i);
            if (next.type == TokenType.TOK_EQ
                || next.type == TokenType.TOK_ASSIGN
                || "=".equals(next.text())) {
              i++; // Skip '='
              if (i >= tokens.size() || tokens.get(i).type == TokenType.TOK_COMMA) {
                diagnostics.add(
                    SyntaxDiagnostic.error(
                        uri,
                        lineNum,
                        next.getStartIndex(),
                        next.getEndIndex(),
                        "REGISTER_DECLARATION_ERROR",
                        "Missing register or value after '='"));
              } else {
                while (i < tokens.size() && tokens.get(i).type != TokenType.TOK_COMMA) {
                  i++;
                }
              }
            }
          }
        } else {
          diagnostics.add(
              SyntaxDiagnostic.error(
                  uri,
                  lineNum,
                  t.getStartIndex(),
                  t.getEndIndex(),
                  "REGISTER_DECLARATION_ERROR",
                  "Expected variable identifier, found: " + t.text()));
          i++;
        }
      } else {
        if (t.type == TokenType.TOK_COMMA) {
          expectIdent = true;
          i++;
          if (i >= tokens.size()) {
            diagnostics.add(
                SyntaxDiagnostic.error(
                    uri,
                    lineNum,
                    t.getStartIndex(),
                    t.getEndIndex(),
                    "REGISTER_DECLARATION_ERROR",
                    "Dangling comma in register declaration"));
          }
        } else {
          diagnostics.add(
              SyntaxDiagnostic.error(
                  uri,
                  lineNum,
                  t.getStartIndex(),
                  t.getEndIndex(),
                  "REGISTER_DECLARATION_ERROR",
                  "Unexpected token in register declaration: " + t.text()));
          i++;
        }
      }
    }
  }

  private void validateGsuInstruction(
      List<Token> tokens,
      int startIdx,
      Tokens lineTokens,
      Scope scope,
      URI uri,
      int lineNum,
      List<SyntaxDiagnostic> diagnostics) {

    Token opcodeToken = tokens.get(startIdx);
    String mnemonic = opcodeToken.text().toLowerCase(Locale.ROOT);

    // Check R1..R15-only instructions (and, bic, or, xor, mult, umult) against r0
    if (R1_TO_R15_ONLY_INSTRUCTIONS.contains(mnemonic) && startIdx + 1 < tokens.size()) {
      Token firstArg = tokens.get(startIdx + 1);
      if (firstArg.type != TokenType.TOK_HASH) {
        String argText = firstArg.text();
        String precolored = scope != null ? scope.getPrecoloredRegister(argText) : null;
        if ("r0".equalsIgnoreCase(argText) || "r0".equalsIgnoreCase(precolored)) {
          diagnostics.add(
              SyntaxDiagnostic.error(
                  uri,
                  lineNum,
                  firstArg.getStartIndex(),
                  firstArg.getEndIndex(),
                  "INVALID_R0_OPERAND",
                  String.format(
                      "Instruction '%s' only accepts registers R1-R15 (found '%s'). In SuperFX hardware, '%s r0' is not a valid register encoding (e.g. 'and r0' encodes $70 = 'merge', which is unsupported on FX3).",
                      opcodeToken.text(), argText, mnemonic)));
          return;
        }
      }
    }

    // Check immediate operand bounds for short-immediate GSU instructions and link
    if (startIdx + 1 < tokens.size() && tokens.get(startIdx + 1).type == TokenType.TOK_HASH) {
      if (IMM_0_TO_15_INSTRUCTIONS.contains(mnemonic) || LINK_INSTRUCTION.equals(mnemonic)) {
        ParsedImmediate parsedImm = tryParseImmediateLiteral(tokens, startIdx, lineTokens);
        if (parsedImm != null) {
          int immStart = tokens.get(startIdx + 1).getStartIndex();
          int immEnd = tokens.get(tokens.size() - 1).getEndIndex();
          if (IMM_0_TO_15_INSTRUCTIONS.contains(mnemonic)) {
            if (parsedImm.value < 0 || parsedImm.value > 15) {
              diagnostics.add(
                  SyntaxDiagnostic.error(
                      uri,
                      lineNum,
                      immStart,
                      immEnd,
                      "IMMEDIATE_OUT_OF_RANGE",
                      String.format(
                          "Immediate operand '#%s' for '%s' is out of 4-bit range (#0 to #15 / #$0 to #$F). Load larger constants into a register via 'ibt' or 'iwt' first.",
                          parsedImm.rawText, opcodeToken.text())));
              return;
            }
          } else if (LINK_INSTRUCTION.equals(mnemonic)) {
            if (parsedImm.value < 1 || parsedImm.value > 4) {
              diagnostics.add(
                  SyntaxDiagnostic.error(
                      uri,
                      lineNum,
                      immStart,
                      immEnd,
                      "IMMEDIATE_OUT_OF_RANGE",
                      String.format(
                          "Immediate operand '#%s' for 'link' is out of range (#1 to #4).",
                          parsedImm.rawText)));
              return;
            }
          }
        }
      }
    }

    // Sub-tokens starting from the opcode
    List<Token> subTokensList = tokens.subList(startIdx, tokens.size());
    Tokens subTokens = new Tokens(lineTokens.line(), subTokensList);

    boolean anyMatches =
        Arrays.stream(Instructions.values())
            .filter(i -> i.instruction.instruction.equalsIgnoreCase(mnemonic))
            .anyMatch(i -> i.matches(subTokens));

    if (anyMatches) {
      return;
    }

    // Operand list (excluding commas)
    List<Token> operandTokens = new ArrayList<>();
    for (int i = startIdx + 1; i < tokens.size(); i++) {
      Token t = tokens.get(i);
      if (t.type != TokenType.TOK_COMMA && !";".equals(t.text())) {
        operandTokens.add(t);
      }
    }

    if (ZERO_ARG_INSTRUCTIONS.contains(mnemonic)) {
      if (!operandTokens.isEmpty()) {
        Token extra = operandTokens.get(0);
        diagnostics.add(
            SyntaxDiagnostic.error(
                uri,
                lineNum,
                extra.getStartIndex(),
                tokens.get(tokens.size() - 1).getEndIndex(),
                "EXTRA_OPERAND",
                String.format(
                    "Instruction '%s' takes no operands (found %d)",
                    opcodeToken.text(), operandTokens.size())));
      }
      return;
    }

    if (TWO_ARG_INSTRUCTIONS.contains(mnemonic)) {
      // Count logical arguments separated by commas
      int commaCount = 0;
      for (int i = startIdx + 1; i < tokens.size(); i++) {
        if (tokens.get(i).type == TokenType.TOK_COMMA) {
          commaCount++;
        }
      }

      if (operandTokens.isEmpty()) {
        diagnostics.add(
            SyntaxDiagnostic.error(
                uri,
                lineNum,
                opcodeToken.getStartIndex(),
                opcodeToken.getEndIndex(),
                "MISSING_OPERAND",
                String.format(
                    "Instruction '%s' requires 2 operands (e.g. register, value/address), found none",
                    opcodeToken.text())));
      } else if (commaCount == 0 && operandTokens.size() < 2) {
        Token last = operandTokens.get(operandTokens.size() - 1);
        diagnostics.add(
            SyntaxDiagnostic.error(
                uri,
                lineNum,
                last.getStartIndex(),
                last.getEndIndex(),
                "MISSING_OPERAND",
                String.format(
                    "Instruction '%s' requires 2 operands separated by comma, found 1",
                    opcodeToken.text())));
      } else {
        diagnostics.add(
            SyntaxDiagnostic.error(
                uri,
                lineNum,
                tokens.get(startIdx + 1).getStartIndex(),
                tokens.get(tokens.size() - 1).getEndIndex(),
                "INVALID_OPERAND",
                String.format("Invalid operands for instruction '%s'", opcodeToken.text())));
      }
      return;
    }

    // Single operand instructions (branches, jumps, arithmetic with register/immediate,
    // to/from/with)
    if (operandTokens.isEmpty()) {
      diagnostics.add(
          SyntaxDiagnostic.error(
              uri,
              lineNum,
              opcodeToken.getStartIndex(),
              opcodeToken.getEndIndex(),
              "MISSING_OPERAND",
              String.format(
                  "Instruction '%s' requires an operand (register, immediate, or label)",
                  opcodeToken.text())));
    } else {
      diagnostics.add(
          SyntaxDiagnostic.error(
              uri,
              lineNum,
              tokens.get(startIdx + 1).getStartIndex(),
              tokens.get(tokens.size() - 1).getEndIndex(),
              "INVALID_OPERAND",
              String.format("Invalid operand format for instruction '%s'", opcodeToken.text())));
    }
  }

  private void validateFunctionDeclaration(
      List<Token> tokens,
      int startIdx,
      String rawLine,
      URI uri,
      int lineNum,
      List<SyntaxDiagnostic> diagnostics) {

    List<Token> fnTokens = tokens.subList(startIdx, tokens.size());
    var decl = FunctionSyntaxHelper.parseFunction(fnTokens);

    if (decl.hasErrors()) {
      for (Token t : fnTokens) {
        if (t.hasAttribute(TokenAttribute.ERROR)) {
          diagnostics.add(
              SyntaxDiagnostic.error(
                  uri,
                  lineNum,
                  t.getStartIndex(),
                  t.getEndIndex(),
                  "FUNCTION_DECLARATION_ERROR",
                  t.message != null ? t.message : "Invalid function declaration syntax"));
        }
      }
    }
  }

  private void validateCallStatement(
      List<Token> tokens, int startIdx, URI uri, int lineNum, List<SyntaxDiagnostic> diagnostics) {

    List<Token> callTokens = tokens.subList(startIdx, tokens.size());
    var call = FunctionSyntaxHelper.parseCall(callTokens);

    if (call.hasErrors()) {
      for (Token t : callTokens) {
        if (t.hasAttribute(TokenAttribute.ERROR)) {
          diagnostics.add(
              SyntaxDiagnostic.error(
                  uri,
                  lineNum,
                  t.getStartIndex(),
                  t.getEndIndex(),
                  "CALL_STATEMENT_ERROR",
                  t.message != null ? t.message : "Invalid call statement syntax"));
        }
      }
    }
  }

  private void validateReturnStatement(
      List<Token> tokens, int startIdx, URI uri, int lineNum, List<SyntaxDiagnostic> diagnostics) {

    List<Token> returnTokens = tokens.subList(startIdx, tokens.size());
    var ret = FunctionSyntaxHelper.parseReturn(returnTokens);

    if (ret.hasErrors()) {
      for (Token t : returnTokens) {
        if (t.hasAttribute(TokenAttribute.ERROR)) {
          diagnostics.add(
              SyntaxDiagnostic.error(
                  uri,
                  lineNum,
                  t.getStartIndex(),
                  t.getEndIndex(),
                  "RETURN_STATEMENT_ERROR",
                  t.message != null ? t.message : "Invalid return statement syntax"));
        }
      }
    }
  }

  private void validateEndFunctionStatement(
      List<Token> tokens, int startIdx, URI uri, int lineNum, List<SyntaxDiagnostic> diagnostics) {

    List<Token> endTokens = tokens.subList(startIdx, tokens.size());
    if (!FunctionSyntaxHelper.validateEndFunction(endTokens)) {
      for (int i = 1; i < endTokens.size(); i++) {
        Token t = endTokens.get(i);
        diagnostics.add(
            SyntaxDiagnostic.error(
                uri,
                lineNum,
                t.getStartIndex(),
                t.getEndIndex(),
                "EXTRA_TOKENS",
                "Unexpected token after endfunction: " + t.text()));
      }
    }
  }

  private record ParsedImmediate(int value, String rawText) {}

  private ParsedImmediate tryParseImmediateLiteral(
      List<Token> tokens, int startIdx, Tokens lineTokens) {
    int afterHashIdx = startIdx + 2;
    if (afterHashIdx >= tokens.size()) {
      return null;
    }

    // Case 1: Simple single token after '#' (tokens.size() == startIdx + 3)
    if (tokens.size() == startIdx + 3) {
      Token litToken = tokens.get(afterHashIdx);
      if (litToken.type == TokenType.TOK_INTCON) {
        int val = litToken.intVal;
        String raw = getRawText(lineTokens, litToken.getStartIndex(), litToken.getEndIndex());
        if (raw.isEmpty()) {
          raw = !litToken.text().isEmpty() ? litToken.text() : Integer.toString(val);
        }
        return new ParsedImmediate(val, raw);
      }
      return null;
    }

    // Case 2: Negative literal '#-n' (tokens.size() == startIdx + 4)
    if (tokens.size() == startIdx + 4
        && tokens.get(afterHashIdx).type == TokenType.TOK_MINUS
        && tokens.get(afterHashIdx + 1).type == TokenType.TOK_INTCON) {
      Token litToken = tokens.get(afterHashIdx + 1);
      int val = -litToken.intVal;
      int start = tokens.get(afterHashIdx).getStartIndex();
      int end = litToken.getEndIndex();
      String raw = getRawText(lineTokens, start, end);
      if (raw.isEmpty()) {
        raw =
            "-"
                + (!litToken.text().isEmpty()
                    ? litToken.text()
                    : Integer.toString(litToken.intVal));
      }
      return new ParsedImmediate(val, raw);
    }

    // Case 3: Hex literal with 0x prefix (tokenized as TOK_INTCON:0 followed by TOK_IDENT:xNN)
    if (tokens.size() == startIdx + 4
        && tokens.get(afterHashIdx).type == TokenType.TOK_INTCON
        && "0".equals(tokens.get(afterHashIdx).text())
        && tokens.get(afterHashIdx + 1).type == TokenType.TOK_IDENT
        && tokens.get(afterHashIdx).getEndIndex() == tokens.get(afterHashIdx + 1).getStartIndex()) {
      int start = tokens.get(afterHashIdx).getStartIndex();
      int end = tokens.get(afterHashIdx + 1).getEndIndex();
      String raw = getRawText(lineTokens, start, end);
      if (raw.matches("(?i)^0x[0-9a-fA-F]+$")) {
        try {
          long parsed = Long.parseLong(raw.substring(2), 16);
          if (parsed <= Integer.MAX_VALUE && parsed >= Integer.MIN_VALUE) {
            return new ParsedImmediate((int) parsed, raw);
          }
        } catch (NumberFormatException ignored) {
        }
      }
    }

    // Case 4: Negative 0x hex literal '#-0xNN' (tokens.size() == startIdx + 5)
    if (tokens.size() == startIdx + 5
        && tokens.get(afterHashIdx).type == TokenType.TOK_MINUS
        && tokens.get(afterHashIdx + 1).type == TokenType.TOK_INTCON
        && "0".equals(tokens.get(afterHashIdx + 1).text())
        && tokens.get(afterHashIdx + 2).type == TokenType.TOK_IDENT
        && tokens.get(afterHashIdx + 1).getEndIndex()
            == tokens.get(afterHashIdx + 2).getStartIndex()) {
      int start = tokens.get(afterHashIdx).getStartIndex();
      int end = tokens.get(afterHashIdx + 2).getEndIndex();
      String raw = getRawText(lineTokens, start, end);
      if (raw.matches("(?i)^-0x[0-9a-fA-F]+$")) {
        try {
          long parsed = Long.parseLong(raw.substring(3), 16);
          return new ParsedImmediate((int) -parsed, raw);
        } catch (NumberFormatException ignored) {
        }
      }
    }

    return null;
  }

  private String getRawText(Tokens lineTokens, int startIndex, int endIndex) {
    if (lineTokens != null && lineTokens.line() != null) {
      String line = lineTokens.line();
      if (startIndex >= 0 && endIndex <= line.length() && startIndex <= endIndex) {
        return line.substring(startIndex, endIndex).trim();
      }
    }
    return "";
  }
}
