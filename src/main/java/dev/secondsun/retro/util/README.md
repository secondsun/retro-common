# Retro Common Utilities

This directory contains the core utility classes and services for the `retro-common` project. These utilities provide
support for scanning, tokenizing, and analyzing CA65 and GSU (Super FX) assembly source code.

## Files

- **CA65Scanner.java**: The primary lexer/scanner for CA65 assembly code. It converts raw source text into a stream of
  tokens.
- **FileService.java**: A service for managing file access and reading. It handles search paths for include files and
  integrates with the `CA65Scanner` to provide tokenized file content.
- **GsuInstructionAttributeAdder.java**: A utility that identifies and marks GSU (Super FX) instructions within a list
  of tokens.
- **ProjectService.java**: A high-level service for managing an assembly project. It handles directory scanning, file
  inclusion, and symbol extraction across multiple files.
- **SymbolService.java**: Manages symbol definitions (labels, macros, procs, etc.) extracted from the source code. It
  maps symbol names to their `Location`.
- **Token.java**: Represents a single lexical token produced by the `CA65Scanner`. It stores the token's type, text,
  location, and associated metadata or attributes.
- **TokenAttribute.java**: An enumeration of attributes that can be assigned to tokens, such as whether a token is a GSU
  instruction, a jump, or an error.
- **TokenType.java**: An enumeration of all possible token types recognized by the scanner (e.g., keywords, operators,
  literals).
- **Util.java**: A collection of static helper methods for string manipulation, URI normalization, and comment removal.

## Subdirectories

- **instruction/**: Utilities specifically for matching and validating GSU (Super FX) instructions and their arguments.
- **vo/**: Value objects used throughout the utility package, such as `TokenizedFile` and `Tokens`.
