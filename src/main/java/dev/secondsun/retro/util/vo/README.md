# Value Objects (VO) for Retro Utilities

This directory contains Value Objects used by the retro-common utility classes to represent source code structures and
assembler-specific metadata. These objects are designed to be lightweight data carriers that facilitate the tokenization
and processing of assembly source files.

## Files

- **DotKeywords.java**: An enumeration of assembler directives and keywords that begin with a dot (e.g., `.IF`, `.PROC`,
  `.BYTE`). It maps these string representations to their corresponding `TokenType`, primarily used for parsing
  CA65-style assembly.
- **Location.java**: A record representing a specific position (file, line, start index, end index) within the source
    code.
- **TokenizedFile.java**: Represents a complete source file that has been processed into tokens. It maintains a mapping
  of line numbers to `Tokens` objects and provides metadata such as the file's URI and total line count.
- **Tokens.java**: A Java record that associates a single line of raw source text with its corresponding list of `Token`
  objects. It serves as the granular unit of a `TokenizedFile`.
