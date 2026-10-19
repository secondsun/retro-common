# Retro-Common

Lexical analysis, tokenization, instruction matching, and symbol indexing for 6502, 65816, SuperFX, and X-GSU macro assembly.

## Language

**Tokenized File**:
The line-indexed representation of source code lines, lexical tokens, and source metadata.
_Avoid_: Parsed AST, token buffer, parse tree

**Syntax Diagnostic**:
A structured record representing a syntax error, warning, or lint finding with exact character ranges, severity, and rule identifier.
_Avoid_: Error block, parse failure, scanner error

**Block Structure Validator**:
The analyzer that verifies lexical block open/close pairing (such as `function`/`endfunction`, `.if`/`.endif`, `.proc`/`.endproc`, and `for`/`endfor`), flagging unclosed blocks, orphan closers, and mismatched nesting.
_Avoid_: Scope checker, block matcher

**Instruction Arity Validator**:
The analyzer that checks operand counts and syntactic structure against defined opcode signatures.
_Avoid_: Opcode parser, instruction checker

