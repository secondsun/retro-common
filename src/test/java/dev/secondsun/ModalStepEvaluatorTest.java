package dev.secondsun;

import static org.junit.jupiter.api.Assertions.*;

import dev.secondsun.retro.util.CA65Scanner;
import dev.secondsun.retro.util.sfx.MacroEffectSummary;
import dev.secondsun.retro.util.sfx.ModalStepEvaluator;
import dev.secondsun.retro.util.sfx.ModalStepEvaluator.ModalHazard.HazardKind;
import dev.secondsun.retro.util.sfx.ModalStepEvaluator.ModalState;
import dev.secondsun.retro.util.sfx.ModalStepEvaluator.StepKind;
import dev.secondsun.retro.util.vo.TokenizedFile;
import java.net.URI;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ModalStepEvaluatorTest {

  private final CA65Scanner scanner = new CA65Scanner();

  @Test
  void zeroByteLabelsAndAltPrefixesPreserveModalRegisters() {
    TokenizedFile tf =
        scanner.tokenize(
            """
            with r4
            loop_head:
            alt1
            adc r5
            """,
            URI.create("file:///modal.sgs"));

    ModalState s0 = ModalState.DEFAULT;
    var r0 = ModalStepEvaluator.step(s0, tf.getLine(0).tokens(), 0, null);
    assertEquals(StepKind.MODAL_PREFIX, r0.kind());
    assertEquals("r4", r0.nextState().sourceReg());
    assertEquals("r4", r0.nextState().destReg());

    // Zero-byte label must preserve active with r4!
    var r1 = ModalStepEvaluator.step(r0.nextState(), tf.getLine(1).tokens(), 1, null);
    assertEquals(StepKind.NO_OP_PRESERVE_MODAL, r1.kind());
    assertEquals("r4", r1.nextState().sourceReg());
    assertEquals("r4", r1.nextState().destReg());

    // alt1 composes with active with r4 without resetting Sreg/Dreg!
    var r2 = ModalStepEvaluator.step(r1.nextState(), tf.getLine(2).tokens(), 2, null);
    assertEquals(StepKind.MODAL_PREFIX, r2.kind());
    assertEquals(1, r2.nextState().altMode());
    assertEquals("r4", r2.nextState().sourceReg());
    assertEquals("r4", r2.nextState().destReg());

    // adc r5 consumes (Sreg=r4, Dreg=r4, alt=1) and resets to DEFAULT
    var r3 = ModalStepEvaluator.step(r2.nextState(), tf.getLine(3).tokens(), 3, null);
    assertEquals(StepKind.INSTRUCTION, r3.kind());
    assertTrue(r3.effect().reads().containsAll(Set.of("r4", "r5")));
    assertEquals(Set.of("r4"), r3.effect().writes());
    assertTrue(r3.nextState().isDefault());
  }

  @Test
  void moveOverwritesPrecedingModalPrefixAndReportsHazard() {
    TokenizedFile tf =
        scanner.tokenize(
            """
            to r3
            move r1, r2
            """,
            URI.create("file:///move_hazard.sgs"));

    var step0 = ModalStepEvaluator.step(ModalState.DEFAULT, tf.getLine(0).tokens(), 0, null);
    var step1 = ModalStepEvaluator.step(step0.nextState(), tf.getLine(1).tokens(), 1, null);

    assertEquals(StepKind.INSTRUCTION, step1.kind());
    assertEquals(Set.of("r2"), step1.effect().reads());
    assertEquals(Set.of("r1"), step1.effect().writes());
    assertEquals(1, step1.hazards().size());
    assertEquals(HazardKind.OVERWRITTEN_BY_MOVE, step1.hazards().get(0).kind());
    assertTrue(step1.nextState().isDefault());
  }

  @Test
  void plotAndLmultRecordImplicitHardwareRegisterEffects() {
    TokenizedFile tf =
        scanner.tokenize(
            """
            plot
            with r5
            lmult
            """,
            URI.create("file:///implicit.sgs"));

    var plotStep = ModalStepEvaluator.step(ModalState.DEFAULT, tf.getLine(0).tokens(), 0, null);
    assertTrue(plotStep.effect().reads().containsAll(Set.of("r1", "r2")));
    assertTrue(plotStep.effect().writes().contains("r1"), "plot auto-increments R1");

    var withStep = ModalStepEvaluator.step(plotStep.nextState(), tf.getLine(1).tokens(), 1, null);
    var lmultStep = ModalStepEvaluator.step(withStep.nextState(), tf.getLine(2).tokens(), 2, null);
    assertTrue(lmultStep.effect().reads().containsAll(Set.of("r5", "r6")));
    assertTrue(lmultStep.effect().writes().containsAll(Set.of("r5", "r4")));
  }

  @Test
  void derivesMacroEffectSummaryAutomaticallyFromMacroBodies() {
    TokenizedFile tf =
        scanner.tokenize(
            """
            .define stackPointer r10

            .macro gsu_stack_push src
                dec stackPointer
                dec stackPointer
                from src
                stw (stackPointer)
            .endmacro

            .macro load_indirect dst, ptr
                to dst
                ldw (ptr)
            .endmacro

            .macro condensed_lmult dst, lhs, rhs
                move r6, lhs
                from rhs
                to dst
                lmult
            .endmacro
            """,
            URI.create("file:///macros.i"));

    var scopeTree = tf.scopeTree();

    Optional<MacroEffectSummary> pushOpt = scopeTree.macroSummary("gsu_stack_push");
    assertTrue(pushOpt.isPresent());
    MacroEffectSummary push = pushOpt.get();
    assertEquals(-2, push.stackDelta());
    assertEquals(Set.of(0), push.upwardReadParams());
    assertTrue(push.writtenParams().isEmpty());
    assertTrue(push.physicalClobbers().contains("r10"));

    Optional<MacroEffectSummary> loadOpt = scopeTree.macroSummary("load_indirect");
    assertTrue(loadOpt.isPresent());
    MacroEffectSummary load = loadOpt.get();
    assertEquals(Set.of(1), load.upwardReadParams());
    assertEquals(Set.of(0), load.writtenParams());
    assertEquals(Set.of(1), load.addressParams(), "ptr used in (ptr) must be marked address param");

    Optional<MacroEffectSummary> lmultOpt = scopeTree.macroSummary("condensed_lmult");
    assertTrue(lmultOpt.isPresent());
    MacroEffectSummary lmult = lmultOpt.get();
    assertEquals(Set.of(1, 2), lmult.upwardReadParams());
    assertEquals(Set.of(0), lmult.writtenParams());
    assertTrue(lmult.physicalClobbers().containsAll(Set.of("r6", "r4")));
  }
}
