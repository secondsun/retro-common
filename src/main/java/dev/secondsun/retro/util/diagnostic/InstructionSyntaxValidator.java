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

    private static final Set<String> ZERO_ARG_INSTRUCTIONS = Set.of(
            "nop", "stop", "cache", "cmode", "color", "div2", "fmult", "lmult", "lob", "hib", "loop", "lsr", "not",
            "plot", "ramb", "romb", "rol", "ror", "rpix", "sbk", "sex", "swap", "alt1", "alt2", "alt3", "merge");

    private static final Set<String> TWO_ARG_INSTRUCTIONS =
            Set.of("iwt", "ibt", "move", "moves", "lm", "sm", "lms", "sms");

    public List<SyntaxDiagnostic> validate(TokenizedFile file) {
        if (file == null) {
            return List.of();
        }

        List<SyntaxDiagnostic> diagnostics = new ArrayList<>();
        URI uri = file.uri();

        int totalLines = file.textLines();
        for (int lineIdx = 0; lineIdx < totalLines; lineIdx++) {
            Tokens lineTokens = file.getLine(lineIdx);
            if (lineTokens == null || lineTokens.tokens().isEmpty()) {
                continue;
            }

            int lineNum = lineIdx + 1; // 1-based
            List<Token> tokens = lineTokens.tokens();

            // 1. Check for scanner lexical errors in any token
            for (Token token : tokens) {
                if (token.type == TokenType.TOK_ERROR) {
                    String msg = token.message != null && !token.message.isBlank()
                            ? token.message
                            : "Lexical error: " + token.text();
                    diagnostics.add(SyntaxDiagnostic.error(
                            uri, lineNum, token.getStartIndex(), token.getEndIndex(), "LEXICAL_ERROR", msg));
                }
            }

            // Identify leading label if present
            int startIdx = 0;
            if (tokens.size() > 1
                    && (tokens.get(1).type == TokenType.TOK_COLON
                            || tokens.get(0).text().endsWith(":"))) {
                startIdx = (tokens.get(1).type == TokenType.TOK_COLON) ? 2 : 1;
                if (startIdx >= tokens.size()) {
                    continue;
                }
            }

            Token first = tokens.get(startIdx);
            String firstText = first.text().toLowerCase(Locale.ROOT);

            // 2. High-level DSL macro validation (function, call, return, endfunction)
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

            // 3. SuperFX GSU Instruction arity & syntax
            if (GSUInstruction.isInstruction(first)) {
                validateGsuInstruction(tokens, startIdx, lineTokens, uri, lineNum, diagnostics);
            }
        }

        return diagnostics;
    }

    private void validateGsuInstruction(
            List<Token> tokens,
            int startIdx,
            Tokens lineTokens,
            URI uri,
            int lineNum,
            List<SyntaxDiagnostic> diagnostics) {

        Token opcodeToken = tokens.get(startIdx);
        String mnemonic = opcodeToken.text().toLowerCase(Locale.ROOT);

        // Sub-tokens starting from the opcode
        List<Token> subTokensList = tokens.subList(startIdx, tokens.size());
        Tokens subTokens = new Tokens(lineTokens.line(), subTokensList);

        boolean anyMatches = Arrays.stream(Instructions.values())
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
                diagnostics.add(SyntaxDiagnostic.error(
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
                diagnostics.add(SyntaxDiagnostic.error(
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
                diagnostics.add(SyntaxDiagnostic.error(
                        uri,
                        lineNum,
                        last.getStartIndex(),
                        last.getEndIndex(),
                        "MISSING_OPERAND",
                        String.format(
                                "Instruction '%s' requires 2 operands separated by comma, found 1",
                                opcodeToken.text())));
            } else {
                diagnostics.add(SyntaxDiagnostic.error(
                        uri,
                        lineNum,
                        tokens.get(startIdx + 1).getStartIndex(),
                        tokens.get(tokens.size() - 1).getEndIndex(),
                        "INVALID_OPERAND",
                        String.format("Invalid operands for instruction '%s'", opcodeToken.text())));
            }
            return;
        }

        // Single operand instructions (branches, jumps, arithmetic with register/immediate, to/from/with)
        if (operandTokens.isEmpty()) {
            diagnostics.add(SyntaxDiagnostic.error(
                    uri,
                    lineNum,
                    opcodeToken.getStartIndex(),
                    opcodeToken.getEndIndex(),
                    "MISSING_OPERAND",
                    String.format(
                            "Instruction '%s' requires an operand (register, immediate, or label)",
                            opcodeToken.text())));
        } else {
            diagnostics.add(SyntaxDiagnostic.error(
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
                    diagnostics.add(SyntaxDiagnostic.error(
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
                    diagnostics.add(SyntaxDiagnostic.error(
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
                    diagnostics.add(SyntaxDiagnostic.error(
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
                diagnostics.add(SyntaxDiagnostic.error(
                        uri,
                        lineNum,
                        t.getStartIndex(),
                        t.getEndIndex(),
                        "EXTRA_TOKENS",
                        "Unexpected token after endfunction: " + t.text()));
            }
        }
    }
}
