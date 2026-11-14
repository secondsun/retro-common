package dev.secondsun.retro.util.diagnostic;

import dev.secondsun.retro.util.FunctionSyntaxHelper;
import dev.secondsun.retro.util.Token;
import dev.secondsun.retro.util.TokenType;
import dev.secondsun.retro.util.instruction.Instructions;
import dev.secondsun.retro.util.sfx.SuperFxIsa;
import dev.secondsun.retro.util.vo.DialectProfile;
import dev.secondsun.retro.util.vo.ImmediateLiteral;
import dev.secondsun.retro.util.vo.Scope;
import dev.secondsun.retro.util.vo.ScopeTree;
import dev.secondsun.retro.util.vo.TokenizedFile;
import dev.secondsun.retro.util.vo.Tokens;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Pure, non-mutating validator for SuperFX opcode arity, operand patterns, immediate and register
 * bounds, X-GSU high-level macro calling conventions ({@code [inline] function}, {@code call},
 * {@code return}, {@code endfunction}, {@code register}), anonymous label targets, and lexical
 * token errors.
 *
 * <p>SuperFX instruction checks are gated on {@link DialectProfile#SUPERFX} from {@link ScopeTree}
 * so 65816 host assembly files (`.s`) using shared mnemonics (`and #$FF00`, `sbc #$01`, `cmp #$80`,
 * `inc`, `lsr a`, `jmp Label`) never produce false-positive coprocessor errors.
 */
public class InstructionSyntaxValidator {

  private final AnonymousLabelValidator anonymousLabelValidator = new AnonymousLabelValidator();

  public List<SyntaxDiagnostic> validate(TokenizedFile file) {
    if (file == null) {
      return List.of();
    }

    List<SyntaxDiagnostic> diagnostics = new ArrayList<>();
    URI uri = file.uri();
    ScopeTree tree = file.scopeTree();

    int totalLines = file.textLines();
    for (int lineIdx = 0; lineIdx < totalLines; lineIdx++) {
      Tokens lineTokens = file.getLine(lineIdx);
      if (lineTokens == null || lineTokens.tokens().isEmpty()) {
        continue;
      }

      int lineNum = lineIdx + 1;
      List<Token> tokens = lineTokens.tokens();

      // 1. Check for scanner lexical errors in any token (all dialects, including macros)
      for (Token token : tokens) {
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

      // Skip unexpanded macro template bodies
      if (tree.isMacroBodyLine(lineIdx)) {
        continue;
      }

      int startIdx = findStartIdx(tokens);
      if (startIdx >= tokens.size()) {
        continue;
      }

      List<Token> tail = tokens.subList(startIdx, tokens.size());
      Token first = tail.get(0);
      String firstText = first.text().toLowerCase(Locale.ROOT);

      // 2. High-level X-GSU DSL macro validation ([inline] function, call, return, endfunction,
      // register)
      if (FunctionSyntaxHelper.isFunction(tail)) {
        validateFunctionDeclaration(tail, uri, lineNum, diagnostics);
        continue;
      }

      if (FunctionSyntaxHelper.isCall(tail)) {
        validateCallStatement(tail, uri, lineNum, diagnostics);
        continue;
      }

      if (FunctionSyntaxHelper.isReturn(tail)) {
        validateReturnStatement(tail, uri, lineNum, diagnostics);
        continue;
      }

      if (FunctionSyntaxHelper.isEndFunction(tail)) {
        validateEndFunctionStatement(tail, uri, lineNum, diagnostics);
        continue;
      }

      if (first.type == TokenType.TOK_REGISTER_KEYWORD || "register".equals(firstText)) {
        validateRegisterDeclaration(tokens, startIdx, uri, lineNum, diagnostics);
        continue;
      }

      // 3. SuperFX GSU Instruction arity & operand bounds (gated on SUPERFX dialect)
      if (tree.dialectAtLine(lineIdx) == DialectProfile.SUPERFX
          && SuperFxIsa.isMnemonic(firstText)) {
        Scope scope = tree.scopeAtLine(lineIdx);
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
    if (tokens.get(0).type == TokenType.TOK_COLON) {
      return 1;
    }
    if (tokens.size() > 1 && tokens.get(1).type == TokenType.TOK_COLON) {
      return 2;
    }
    if (tokens.get(0).text().endsWith(":")) {
      return 1;
    }
    return 0;
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
              i++;
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
    SuperFxIsa.OpcodeSpec spec = SuperFxIsa.lookup(mnemonic).orElse(null);
    if (spec == null) {
      return;
    }

    int topLevelCommas = countTopLevelCommas(tokens, startIdx + 1);

    // 1. Check R1..R15-only instructions (and, bic, or, xor, mult, umult) against r0
    if (spec.minRegister() == 1 && startIdx + 1 < tokens.size() && topLevelCommas == 0) {
      Token firstArg = tokens.get(startIdx + 1);
      if (firstArg.type != TokenType.TOK_HASH) {
        OptionalInt regIdx = resolveEffectiveRegister(firstArg.text(), scope);
        if (regIdx.isPresent() && regIdx.getAsInt() == 0) {
          diagnostics.add(
              SyntaxDiagnostic.error(
                  uri,
                  lineNum,
                  firstArg.getStartIndex(),
                  firstArg.getEndIndex(),
                  "INVALID_R0_OPERAND",
                  String.format(
                      "Instruction '%s' only accepts registers R1-R15 (found '%s'). In SuperFX hardware, '%s r0' is not a valid register encoding (e.g. 'and r0' encodes $70 = 'merge', which is unsupported on FX3).",
                      opcodeToken.text(), firstArg.text(), mnemonic)));
          return;
        }
      }
    }

    // 2. Check precolored register range violations for inc/dec (R0..R14), indirect (R0..R11),
    // jmp/ljmp (R8..R13)
    if (spec.maxRegister() == 14 && startIdx + 1 < tokens.size() && topLevelCommas == 0) {
      Token firstArg = tokens.get(startIdx + 1);
      OptionalInt regIdx = resolveEffectiveRegister(firstArg.text(), scope);
      if (regIdx.isPresent() && regIdx.getAsInt() > 14) {
        diagnostics.add(
            SyntaxDiagnostic.error(
                uri,
                lineNum,
                firstArg.getStartIndex(),
                firstArg.getEndIndex(),
                "INVALID_OPERAND",
                String.format(
                    "Instruction '%s' only accepts registers R0-R14 (found '%s')",
                    opcodeToken.text(), firstArg.text())));
        return;
      }
    }

    if (spec.category() == SuperFxIsa.Category.MEMORY_INDIRECT
        && !spec.isZeroArg()
        && tokens.size() == startIdx + 4
        && tokens.get(startIdx + 1).type == TokenType.TOK_LPAREN
        && tokens.get(startIdx + 3).type == TokenType.TOK_RPAREN) {
      Token inner = tokens.get(startIdx + 2);
      OptionalInt regIdx = resolveEffectiveRegister(inner.text(), scope);
      if (regIdx.isPresent() && (regIdx.getAsInt() < 0 || regIdx.getAsInt() > 11)) {
        diagnostics.add(
            SyntaxDiagnostic.error(
                uri,
                lineNum,
                tokens.get(startIdx + 1).getStartIndex(),
                tokens.get(startIdx + 3).getEndIndex(),
                "INVALID_OPERAND",
                String.format(
                    "Indirect memory operand for '%s' only accepts registers R0-R11 (found '%s')",
                    opcodeToken.text(), inner.text())));
        return;
      }
    }

    if (("jmp".equals(mnemonic) || "ljmp".equals(mnemonic)) && startIdx + 1 < tokens.size()) {
      Token targetTok =
          (tokens.size() == startIdx + 4
                  && tokens.get(startIdx + 1).type == TokenType.TOK_LPAREN
                  && tokens.get(startIdx + 3).type == TokenType.TOK_RPAREN)
              ? tokens.get(startIdx + 2)
              : (tokens.size() == startIdx + 2 ? tokens.get(startIdx + 1) : null);
      if (targetTok != null) {
        OptionalInt regIdx = resolveEffectiveRegister(targetTok.text(), scope);
        if (regIdx.isPresent() && (regIdx.getAsInt() < 8 || regIdx.getAsInt() > 13)) {
          diagnostics.add(
              SyntaxDiagnostic.error(
                  uri,
                  lineNum,
                  tokens.get(startIdx + 1).getStartIndex(),
                  tokens.get(tokens.size() - 1).getEndIndex(),
                  "INVALID_OPERAND",
                  String.format(
                      "Instruction '%s' only accepts registers R8-R13 (found '%s')",
                      opcodeToken.text(), targetTok.text())));
          return;
        }
      }
    }

    // 3. Check immediate operand bounds
    if (startIdx + 1 < tokens.size()
        && tokens.get(startIdx + 1).type == TokenType.TOK_HASH
        && topLevelCommas == 0) {
      if (!spec.acceptsImmediate() && !"shl".equals(mnemonic)) {
        diagnostics.add(
            SyntaxDiagnostic.error(
                uri,
                lineNum,
                tokens.get(startIdx + 1).getStartIndex(),
                tokens.get(tokens.size() - 1).getEndIndex(),
                "INVALID_OPERAND",
                String.format(
                    "Instruction '%s' does not accept an immediate operand; only register operands are supported",
                    opcodeToken.text())));
        return;
      }
      if (spec.acceptsImmediate() && spec.maxImmediate() <= 15) {
        Optional<ImmediateLiteral> parsed =
            ImmediateLiteral.parse(tokens, startIdx + 1, lineTokens.line());
        if (parsed.isPresent() && parsed.get().isConstant()) {
          ImmediateLiteral imm = parsed.get();
          int val = imm.signedValue();
          if ("link".equals(mnemonic)) {
            if (val < 1 || val > 4) {
              diagnostics.add(
                  SyntaxDiagnostic.error(
                      uri,
                      lineNum,
                      imm.startColumn(),
                      imm.endColumn(),
                      "IMMEDIATE_OUT_OF_RANGE",
                      String.format(
                          "Immediate operand '#%s' for 'link' is out of range (#1 to #4).",
                          imm.rawText())));
              return;
            }
          } else if (val < spec.minImmediate() || val > spec.maxImmediate()) {
            diagnostics.add(
                SyntaxDiagnostic.error(
                    uri,
                    lineNum,
                    imm.startColumn(),
                    imm.endColumn(),
                    "IMMEDIATE_OUT_OF_RANGE",
                    String.format(
                        "Immediate operand '#%s' for '%s' is out of 4-bit range (#0 to #15 / #$0 to #$F). Load larger constants into a register via 'ibt' or 'iwt' first.",
                        imm.rawText(), opcodeToken.text())));
            return;
          }
        }
      }
    }

    // Check 8-bit bounds on ibt Rn, #imm (values outside -128..255; -128..-1 unmasked is checked by
    // SfxInvariantAuditor as IBT_NEGATIVE_IMMEDIATE)
    if ("ibt".equals(mnemonic) && topLevelCommas == 1) {
      int hashIdx = findHashAfterComma(tokens, startIdx + 1);
      if (hashIdx >= 0) {
        Optional<ImmediateLiteral> parsed =
            ImmediateLiteral.parse(tokens, hashIdx, lineTokens.line());
        if (parsed.isPresent() && parsed.get().isConstant() && !parsed.get().isByteMasked()) {
          ImmediateLiteral imm = parsed.get();
          if (imm.signedValue() > 255 || imm.signedValue() < -128) {
            diagnostics.add(
                SyntaxDiagnostic.error(
                    uri,
                    lineNum,
                    imm.startColumn(),
                    imm.endColumn(),
                    "IMMEDIATE_OUT_OF_RANGE",
                    String.format(
                        "Immediate operand '#%s' for 'ibt' does not fit in 8 bits (-128 to 255). Use 'iwt' for 16-bit constants.",
                        imm.rawText())));
            return;
          }
        }
      }
    }

    // 4. Grammar / pattern check via Instructions catalog (pure predicate, no token mutation)
    List<Token> subTokensList = tokens.subList(startIdx, tokens.size());
    Tokens subTokens = new Tokens(lineTokens.line(), subTokensList);

    boolean anyMatches =
        Arrays.stream(Instructions.values())
            .filter(i -> i.instruction.instruction.equalsIgnoreCase(mnemonic))
            .anyMatch(i -> i.matches(subTokens, scope != null ? scope::isRegisterLabel : null));

    if (anyMatches) {
      return;
    }

    List<Token> operandTokens = new ArrayList<>();
    for (int i = startIdx + 1; i < tokens.size(); i++) {
      Token t = tokens.get(i);
      if (t.type != TokenType.TOK_COMMA && !";".equals(t.text())) {
        operandTokens.add(t);
      }
    }

    if (spec.isZeroArg()) {
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

    if (spec.minArgs() == 2) {
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
      } else if (topLevelCommas == 0 && operandTokens.size() < 2) {
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

  private static OptionalInt resolveEffectiveRegister(String text, Scope scope) {
    OptionalInt direct = SuperFxIsa.parsePhysicalRegister(text);
    if (direct.isPresent()) {
      return direct;
    }
    if (scope != null) {
      String precolored = scope.resolvePrecoloredRegister(text);
      if (precolored != null) {
        return SuperFxIsa.parsePhysicalRegister(precolored);
      }
    }
    return OptionalInt.empty();
  }

  private static int countTopLevelCommas(List<Token> tokens, int fromIdx) {
    int depth = 0;
    int commas = 0;
    for (int i = fromIdx; i < tokens.size(); i++) {
      TokenType t = tokens.get(i).type;
      if (t == TokenType.TOK_LPAREN) {
        depth++;
      } else if (t == TokenType.TOK_RPAREN) {
        depth--;
      } else if (t == TokenType.TOK_COMMA && depth <= 0) {
        commas++;
      }
    }
    return commas;
  }

  private static int findHashAfterComma(List<Token> tokens, int fromIdx) {
    int depth = 0;
    for (int i = fromIdx; i < tokens.size(); i++) {
      TokenType t = tokens.get(i).type;
      if (t == TokenType.TOK_LPAREN) {
        depth++;
      } else if (t == TokenType.TOK_RPAREN) {
        depth--;
      } else if (t == TokenType.TOK_COMMA && depth <= 0) {
        return (i + 1 < tokens.size() && tokens.get(i + 1).type == TokenType.TOK_HASH) ? i + 1 : -1;
      }
    }
    return -1;
  }

  private void validateFunctionDeclaration(
      List<Token> fnTokens, URI uri, int lineNum, List<SyntaxDiagnostic> diagnostics) {
    var decl = FunctionSyntaxHelper.parseFunction(fnTokens);
    if (decl.hasErrors()) {
      for (Token t : decl.errorTokens()) {
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

  private void validateCallStatement(
      List<Token> callTokens, URI uri, int lineNum, List<SyntaxDiagnostic> diagnostics) {
    var call = FunctionSyntaxHelper.parseCall(callTokens);
    if (call.hasErrors()) {
      for (Token t : call.errorTokens()) {
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

  private void validateReturnStatement(
      List<Token> returnTokens, URI uri, int lineNum, List<SyntaxDiagnostic> diagnostics) {
    var ret = FunctionSyntaxHelper.parseReturn(returnTokens);
    if (ret.hasErrors()) {
      for (Token t : ret.errorTokens()) {
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

  private void validateEndFunctionStatement(
      List<Token> endTokens, URI uri, int lineNum, List<SyntaxDiagnostic> diagnostics) {
    for (Token t : FunctionSyntaxHelper.parseEndFunction(endTokens)) {
      diagnostics.add(
          SyntaxDiagnostic.error(
              uri,
              lineNum,
              t.getStartIndex(),
              t.getEndIndex(),
              "EXTRA_TOKENS",
              t.message != null ? t.message : ("Unexpected token after endfunction: " + t.text())));
    }
  }
}
