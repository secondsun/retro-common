# GSU Instruction Utilities

This directory contains classes and enums used for parsing and matching GSU (Super FX) instructions from tokenized
source code. It provides a structured way to identify instructions and validate their arguments.

## Files

- **ArgumentMatcher.java**: A sealed interface and a set of implementations for matching different types of instruction
  operands, such as registers (e.g., `R1`), immediate values (e.g., `#$01`), memory addresses (e.g., `(R1)`), and
  labels.
- **GSUInstruction.java**: Represents a specific GSU instruction template. It holds the instruction name and a list of
  `ArgumentMatcher`s required to validate the instruction's arguments in a sequence of tokens.
- **Instructions.java**: An enumeration of all supported GSU instructions. It provides a central lookup table for
  mapping instruction names to their corresponding `GSUInstruction` definitions and categorizes instructions (e.g., jump
  instructions).
