# Test Suite for Retro Utilities

This directory contains unit and integration tests for the `retro-common` utility library. The tests in this directory ensure the reliability of the assembly scanning, symbol management, and instruction
processing logic. They validate that the tools can correctly interpret CA65-style assembly and Super FX (GSU) specific
instructions, maintaining consistency across refactors and feature additions.

## Files

- **FirstTest.java**: Integration tests that verify the basic tokenization workflow and the `ProjectService`'s ability
  to initialize and read file contents within a project structure.
- **GrammarTest.java**: Focuses on the core `CA65Scanner` logic, ensuring that basic assembly instructions and
  identifiers are correctly tokenized into their respective types.
- **GSUInstructionTest.java**: Validates the identification and attribute tagging of Super FX (GSU) instructions,
  ensuring that complex instruction patterns are recognized by the `GsuInstructionAttributeAdder`.
- **SymbolsTest.java**: A comprehensive suite testing symbol definition identification, lexical scoping, symbol
  lookup/referencing, and the handling of "dirty" symbols.
- **UtilTest.java**: Unit tests for general-purpose utility methods, such as path manipulation and string trimming for
  auto-completion.
