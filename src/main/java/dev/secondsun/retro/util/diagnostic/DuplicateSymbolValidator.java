package dev.secondsun.retro.util.diagnostic;

import dev.secondsun.retro.util.Token;
import dev.secondsun.retro.util.TokenType;
import dev.secondsun.retro.util.vo.TokenizedFile;
import dev.secondsun.retro.util.vo.Tokens;
import java.net.URI;
import java.util.*;

/**
 * Validates symbol uniqueness across lexical scopes, detecting duplicate label, function,
 * and procedure definitions.
 */
public class DuplicateSymbolValidator {

    public List<SyntaxDiagnostic> validate(TokenizedFile file) {
        if (file == null) {
            return List.of();
        }

        List<SyntaxDiagnostic> diagnostics = new ArrayList<>();
        URI uri = file.uri();

        // Scope stack tracking: global -> function/proc/scope
        // For each scope level, map symbolName -> Location of first definition
        Deque<Map<String, Integer>> scopeDefinitions = new ArrayDeque<>();
        scopeDefinitions.push(new HashMap<>()); // File/global scope

        // Map for local cheap labels (@label) reset upon non-local label or new scope
        Map<String, Integer> currentCheapLabels = new HashMap<>();

        int totalLines = file.textLines();
        for (int lineIdx = 0; lineIdx < totalLines; lineIdx++) {
            Tokens lineTokens = file.getLine(lineIdx);
            if (lineTokens == null || lineTokens.tokens().isEmpty()) {
                continue;
            }

            int lineNum = lineIdx + 1; // 1-based
            List<Token> tokens = lineTokens.tokens();
            Token first = tokens.get(0);
            String firstText = first.text().toLowerCase(Locale.ROOT);

            // Scope closures: pop definition map
            if (first.type == TokenType.TOK_ENDFUNCTION
                    || "endfunction".equals(firstText)
                    || first.type == TokenType.TOK_ENDPROC
                    || ".endproc".equals(firstText)
                    || first.type == TokenType.TOK_ENDSCOPE
                    || ".endscope".equals(firstText)) {
                if (scopeDefinitions.size() > 1) {
                    scopeDefinitions.pop();
                }
                currentCheapLabels.clear();
                continue;
            }

            // Scope openers with names: function, .proc, .scope
            if (first.type == TokenType.TOK_FUNCTION || "function".equals(firstText)) {
                if (tokens.size() > 1 && tokens.get(1).type == TokenType.TOK_IDENT) {
                    Token nameToken = tokens.get(1);
                    recordGlobalOrParentDefinition(nameToken, "function", uri, lineNum, scopeDefinitions, diagnostics);
                }
                scopeDefinitions.push(new HashMap<>());
                currentCheapLabels.clear();
                continue;
            }

            if (first.type == TokenType.TOK_PROC || ".proc".equals(firstText)) {
                if (tokens.size() > 1 && tokens.get(1).type == TokenType.TOK_IDENT) {
                    Token nameToken = tokens.get(1);
                    recordDefinition(nameToken, "procedure", uri, lineNum, scopeDefinitions.peek(), diagnostics);
                }
                scopeDefinitions.push(new HashMap<>());
                currentCheapLabels.clear();
                continue;
            }

            if (first.type == TokenType.TOK_SCOPE || ".scope".equals(firstText)) {
                if (tokens.size() > 1 && tokens.get(1).type == TokenType.TOK_IDENT) {
                    Token nameToken = tokens.get(1);
                    recordDefinition(nameToken, "scope", uri, lineNum, scopeDefinitions.peek(), diagnostics);
                }
                scopeDefinitions.push(new HashMap<>());
                currentCheapLabels.clear();
                continue;
            }

            // Label definitions: "name:" or "@local:"
            if (tokens.size() > 1 && tokens.get(1).type == TokenType.TOK_COLON) {
                Token labelToken = tokens.get(0);
                String labelText = labelToken.text();
                if (labelText.startsWith("@") || labelToken.type == TokenType.TOK_LOCAL_IDENT) {
                    // Cheap local label
                    Integer prevLine = currentCheapLabels.get(labelText);
                    if (prevLine != null) {
                        diagnostics.add(SyntaxDiagnostic.error(
                                uri,
                                lineNum,
                                labelToken.getStartIndex(),
                                labelToken.getEndIndex(),
                                "DUPLICATE_SYMBOL",
                                String.format(
                                        "Duplicate local label '%s' already defined at line %d", labelText, prevLine)));
                    } else {
                        currentCheapLabels.put(labelText, lineNum);
                    }
                } else if (labelToken.type == TokenType.TOK_IDENT) {
                    // Standard label: resets local cheap label namespace
                    currentCheapLabels.clear();
                    recordDefinition(labelToken, "label", uri, lineNum, scopeDefinitions.peek(), diagnostics);
                }
            } else if (first.text().endsWith(":") && first.text().length() > 1) {
                String labelText = first.text().substring(0, first.text().length() - 1);
                if (labelText.startsWith("@")) {
                    Integer prevLine = currentCheapLabels.get(labelText);
                    if (prevLine != null) {
                        diagnostics.add(SyntaxDiagnostic.error(
                                uri,
                                lineNum,
                                first.getStartIndex(),
                                first.getEndIndex(),
                                "DUPLICATE_SYMBOL",
                                String.format(
                                        "Duplicate local label '%s' already defined at line %d", labelText, prevLine)));
                    } else {
                        currentCheapLabels.put(labelText, lineNum);
                    }
                } else {
                    currentCheapLabels.clear();
                    Integer prevLine = scopeDefinitions.peek().get(labelText);
                    if (prevLine != null) {
                        diagnostics.add(SyntaxDiagnostic.error(
                                uri,
                                lineNum,
                                first.getStartIndex(),
                                first.getEndIndex(),
                                "DUPLICATE_SYMBOL",
                                String.format("Duplicate label '%s' already defined at line %d", labelText, prevLine)));
                    } else {
                        scopeDefinitions.peek().put(labelText, lineNum);
                    }
                }
            }
        }

        return diagnostics;
    }

    private void recordDefinition(
            Token token,
            String kind,
            URI uri,
            int lineNum,
            Map<String, Integer> currentScope,
            List<SyntaxDiagnostic> diagnostics) {

        String name = token.text();
        Integer prevLine = currentScope.get(name);
        if (prevLine != null) {
            diagnostics.add(SyntaxDiagnostic.error(
                    uri,
                    lineNum,
                    token.getStartIndex(),
                    token.getEndIndex(),
                    "DUPLICATE_SYMBOL",
                    String.format("Duplicate %s '%s' already defined at line %d", kind, name, prevLine)));
        } else {
            currentScope.put(name, lineNum);
        }
    }

    private void recordGlobalOrParentDefinition(
            Token token,
            String kind,
            URI uri,
            int lineNum,
            Deque<Map<String, Integer>> scopeDefinitions,
            List<SyntaxDiagnostic> diagnostics) {

        Map<String, Integer> targetScope = scopeDefinitions.peek();
        recordDefinition(token, kind, uri, lineNum, targetScope, diagnostics);
    }
}
