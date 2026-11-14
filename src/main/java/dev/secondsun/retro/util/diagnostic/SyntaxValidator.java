package dev.secondsun.retro.util.diagnostic;

import dev.secondsun.retro.util.vo.TokenizedFile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Pure, stateless syntax validator coordinating block-balancing, instruction arity, and duplicate
 * symbol checks without mutating {@link TokenizedFile}.
 */
public class SyntaxValidator {

  private final BlockStructureValidator blockValidator;
  private final InstructionSyntaxValidator instructionValidator;
  private final DuplicateSymbolValidator duplicateSymbolValidator;

  public SyntaxValidator() {
    this.blockValidator = new BlockStructureValidator();
    this.instructionValidator = new InstructionSyntaxValidator();
    this.duplicateSymbolValidator = new DuplicateSymbolValidator();
  }

  public SyntaxValidator(
      BlockStructureValidator blockValidator,
      InstructionSyntaxValidator instructionValidator,
      DuplicateSymbolValidator duplicateSymbolValidator) {
    this.blockValidator = blockValidator;
    this.instructionValidator = instructionValidator;
    this.duplicateSymbolValidator = duplicateSymbolValidator;
  }

  /**
   * Validates {@code file} and returns an immutable sorted list of detected syntax diagnostics.
   *
   * @param file the tokenized source file
   * @return sorted, unmodifiable list of all detected diagnostics
   */
  public List<SyntaxDiagnostic> validate(TokenizedFile file) {
    if (file == null) {
      return List.of();
    }

    List<SyntaxDiagnostic> all = new ArrayList<>();
    all.addAll(blockValidator.validate(file));
    all.addAll(instructionValidator.validate(file));
    all.addAll(duplicateSymbolValidator.validate(file));

    all.sort(
        Comparator.comparingInt(SyntaxDiagnostic::line)
            .thenComparingInt(SyntaxDiagnostic::columnStart));

    return Collections.unmodifiableList(all);
  }
}
