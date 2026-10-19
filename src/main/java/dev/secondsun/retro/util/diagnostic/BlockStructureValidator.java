package dev.secondsun.retro.util.diagnostic;

import dev.secondsun.retro.util.Token;
import dev.secondsun.retro.util.TokenType;
import dev.secondsun.retro.util.vo.TokenizedFile;
import dev.secondsun.retro.util.vo.Tokens;
import java.net.URI;
import java.util.*;

/**
 * Validates pairing, nesting, and termination of lexical block constructs across unassembled source files.
 * Supports both high-level X-GSU DSL blocks (function/endfunction, for/endfor) and ca65 directives
 * (.if/.endif, .proc/.endproc, .scope/.endscope, .struct/.endstruct, .union/.endunion, .enum/.endenum,
 * .macro/.endmacro, .repeat/.endrep).
 */
public class BlockStructureValidator {

    public enum BlockKind {
        FUNCTION("function", "endfunction"),
        FOR("for", "endfor"),
        IF(".if", ".endif"),
        PROC(".proc", ".endproc"),
        SCOPE(".scope", ".endscope"),
        STRUCT(".struct", ".endstruct"),
        UNION(".union", ".endunion"),
        ENUM(".enum", ".endenum"),
        MACRO(".macro", ".endmacro"),
        REPEAT(".repeat", ".endrep");

        private final String openerName;
        private final String closerName;

        BlockKind(String openerName, String closerName) {
            this.openerName = openerName;
            this.closerName = closerName;
        }

        public String openerName() {
            return openerName;
        }

        public String closerName() {
            return closerName;
        }
    }

    public record OpenBlock(BlockKind kind, String name, int line, int columnStart, int columnEnd) {}

    public List<SyntaxDiagnostic> validate(TokenizedFile file) {
        if (file == null) {
            return List.of();
        }

        List<SyntaxDiagnostic> diagnostics = new ArrayList<>();
        Deque<OpenBlock> stack = new ArrayDeque<>();
        URI uri = file.uri();

        int totalLines = file.textLines();
        for (int lineIdx = 0; lineIdx < totalLines; lineIdx++) {
            Tokens lineTokens = file.getLine(lineIdx);
            if (lineTokens == null || lineTokens.tokens().isEmpty()) {
                continue;
            }

            int lineNum = lineIdx + 1; // 1-based
            List<Token> tokens = lineTokens.tokens();
            Token first = tokens.get(0);

            // Handle labels preceding directives/keywords (e.g. "my_proc: .proc" or "label: function")
            int keywordIdx = 0;
            if (tokens.size() > 1
                    && (tokens.get(1).type == TokenType.TOK_COLON
                            || first.text().endsWith(":"))) {
                keywordIdx = (tokens.get(1).type == TokenType.TOK_COLON) ? 2 : 1;
                if (keywordIdx >= tokens.size()) {
                    continue;
                }
                first = tokens.get(keywordIdx);
            }

            String firstText = first.text().toLowerCase(Locale.ROOT);
            TokenType type = first.type;

            // 1. Closers
            BlockKind closedKind = identifyCloser(first, firstText, type, tokens, keywordIdx);
            if (closedKind != null) {
                if (stack.isEmpty()) {
                    diagnostics.add(SyntaxDiagnostic.error(
                            uri,
                            lineNum,
                            first.getStartIndex(),
                            first.getEndIndex(),
                            "ORPHAN_BLOCK_CLOSER",
                            String.format("Unexpected '%s' with no matching block opener", first.text())));
                } else {
                    OpenBlock top = stack.pop();
                    if (top.kind() != closedKind) {
                        diagnostics.add(SyntaxDiagnostic.error(
                                uri,
                                lineNum,
                                first.getStartIndex(),
                                first.getEndIndex(),
                                "MISMATCHED_BLOCK_CLOSER",
                                String.format(
                                        "Mismatched block closer: found '%s', but expected '%s' to close '%s%s' opened at line %d",
                                        first.text(),
                                        top.kind().closerName(),
                                        top.kind().openerName(),
                                        top.name().isEmpty() ? "" : (" " + top.name()),
                                        top.line())));
                    }
                }
                continue;
            }

            // 2. Conditionals branch: .else / .elseif
            if (isConditionalBranch(first, firstText, type)) {
                if (stack.isEmpty() || stack.peek().kind() != BlockKind.IF) {
                    diagnostics.add(SyntaxDiagnostic.error(
                            uri,
                            lineNum,
                            first.getStartIndex(),
                            first.getEndIndex(),
                            "ORPHAN_CONDITIONAL_BRANCH",
                            String.format("Directive '%s' without preceding '.if' conditional block", first.text())));
                }
                continue;
            }

            // 3. Openers
            OpenBlock opened = identifyOpener(first, firstText, type, tokens, keywordIdx, lineNum);
            if (opened != null) {
                stack.push(opened);
            }
        }

        // EOF check: any remaining open blocks on stack are unclosed
        while (!stack.isEmpty()) {
            OpenBlock unclosed = stack.pop();
            diagnostics.add(SyntaxDiagnostic.error(
                    uri,
                    unclosed.line(),
                    unclosed.columnStart(),
                    unclosed.columnEnd(),
                    "UNCLOSED_BLOCK",
                    String.format(
                            "Unclosed block: '%s%s' opened at line %d has no matching '%s' before end of file",
                            unclosed.kind().openerName(),
                            unclosed.name().isEmpty() ? "" : (" " + unclosed.name()),
                            unclosed.line(),
                            unclosed.kind().closerName())));
        }

        return diagnostics;
    }

    private BlockKind identifyCloser(Token token, String text, TokenType type, List<Token> tokens, int keywordIdx) {
        if (type == TokenType.TOK_ENDFUNCTION || "endfunction".equals(text)) {
            return BlockKind.FUNCTION;
        }
        if ("endfor".equals(text)) {
            return BlockKind.FOR;
        }
        if (type == TokenType.TOK_ENDIF || ".endif".equals(text)) {
            return BlockKind.IF;
        }
        if (type == TokenType.TOK_ENDPROC || ".endproc".equals(text)) {
            return BlockKind.PROC;
        }
        if (type == TokenType.TOK_ENDSCOPE || ".endscope".equals(text)) {
            return BlockKind.SCOPE;
        }
        if (type == TokenType.TOK_ENDSTRUCT || ".endstruct".equals(text)) {
            return BlockKind.STRUCT;
        }
        if (type == TokenType.TOK_ENDUNION || ".endunion".equals(text)) {
            return BlockKind.UNION;
        }
        if (type == TokenType.TOK_ENDENUM || ".endenum".equals(text)) {
            return BlockKind.ENUM;
        }
        if (type == TokenType.TOK_ENDMACRO || ".endmacro".equals(text) || ".endmac".equals(text)) {
            return BlockKind.MACRO;
        }
        if (type == TokenType.TOK_ENDREP || ".endrep".equals(text) || ".endrepeat".equals(text)) {
            return BlockKind.REPEAT;
        }
        return null;
    }

    private boolean isConditionalBranch(Token token, String text, TokenType type) {
        return type == TokenType.TOK_ELSE
                || ".else".equals(text)
                || type == TokenType.TOK_ELSEIF
                || ".elseif".equals(text);
    }

    private OpenBlock identifyOpener(
            Token token, String text, TokenType type, List<Token> tokens, int keywordIdx, int lineNum) {
        // High-level function
        if (type == TokenType.TOK_FUNCTION || "function".equals(text)) {
            String name = (keywordIdx + 1 < tokens.size())
                    ? tokens.get(keywordIdx + 1).text()
                    : "";
            return new OpenBlock(BlockKind.FUNCTION, name, lineNum, token.getStartIndex(), token.getEndIndex());
        }

        // Hardware / macro loop: for or forR
        if ("for".equals(text) || "forr".equals(text)) {
            String param = (keywordIdx + 1 < tokens.size())
                    ? tokens.get(keywordIdx + 1).text()
                    : "";
            return new OpenBlock(BlockKind.FOR, param, lineNum, token.getStartIndex(), token.getEndIndex());
        }

        // Conditional directives (.if, .ifblank, .ifnblank, .ifdef, .ifndef, .ifconst, .ifnconst, .ifref, .ifnref,
        // .ifp02, etc.)
        if (isIfOpener(token, text, type)) {
            return new OpenBlock(BlockKind.IF, "", lineNum, token.getStartIndex(), token.getEndIndex());
        }

        // Procedure
        if (type == TokenType.TOK_PROC || ".proc".equals(text)) {
            String name = (keywordIdx + 1 < tokens.size())
                    ? tokens.get(keywordIdx + 1).text()
                    : "";
            return new OpenBlock(BlockKind.PROC, name, lineNum, token.getStartIndex(), token.getEndIndex());
        }

        // Lexical scope
        if (type == TokenType.TOK_SCOPE || ".scope".equals(text)) {
            String name = (keywordIdx + 1 < tokens.size())
                    ? tokens.get(keywordIdx + 1).text()
                    : "";
            return new OpenBlock(BlockKind.SCOPE, name, lineNum, token.getStartIndex(), token.getEndIndex());
        }

        // Struct
        if (type == TokenType.TOK_STRUCT || ".struct".equals(text)) {
            String name = (keywordIdx + 1 < tokens.size())
                    ? tokens.get(keywordIdx + 1).text()
                    : "";
            return new OpenBlock(BlockKind.STRUCT, name, lineNum, token.getStartIndex(), token.getEndIndex());
        }

        // Union
        if (type == TokenType.TOK_UNION || ".union".equals(text)) {
            String name = (keywordIdx + 1 < tokens.size())
                    ? tokens.get(keywordIdx + 1).text()
                    : "";
            return new OpenBlock(BlockKind.UNION, name, lineNum, token.getStartIndex(), token.getEndIndex());
        }

        // Enum
        if (type == TokenType.TOK_ENUM || ".enum".equals(text)) {
            String name = (keywordIdx + 1 < tokens.size())
                    ? tokens.get(keywordIdx + 1).text()
                    : "";
            return new OpenBlock(BlockKind.ENUM, name, lineNum, token.getStartIndex(), token.getEndIndex());
        }

        // Macro definition
        if (type == TokenType.TOK_MACRO || ".macro".equals(text) || ".mac".equals(text)) {
            String name = (keywordIdx + 1 < tokens.size())
                    ? tokens.get(keywordIdx + 1).text()
                    : "";
            return new OpenBlock(BlockKind.MACRO, name, lineNum, token.getStartIndex(), token.getEndIndex());
        }

        // Repeat loop
        if (type == TokenType.TOK_REPEAT || ".repeat".equals(text)) {
            String count = (keywordIdx + 1 < tokens.size())
                    ? tokens.get(keywordIdx + 1).text()
                    : "";
            return new OpenBlock(BlockKind.REPEAT, count, lineNum, token.getStartIndex(), token.getEndIndex());
        }

        return null;
    }

    private boolean isIfOpener(Token token, String text, TokenType type) {
        if (type == TokenType.TOK_IF
                || type == TokenType.TOK_IFBLANK
                || type == TokenType.TOK_IFNBLANK
                || type == TokenType.TOK_IFDEF
                || type == TokenType.TOK_IFNDEF
                || type == TokenType.TOK_IFCONST
                || type == TokenType.TOK_IFNCONST
                || type == TokenType.TOK_IFREF
                || type == TokenType.TOK_IFNREF
                || type == TokenType.TOK_IFP02
                || type == TokenType.TOK_IFP4510
                || type == TokenType.TOK_IFP816
                || type == TokenType.TOK_IFPC02
                || type == TokenType.TOK_IFPDTV
                || type == TokenType.TOK_IFPSC02) {
            return true;
        }
        return text.startsWith(".if");
    }
}
