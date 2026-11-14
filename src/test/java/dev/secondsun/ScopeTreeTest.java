package dev.secondsun;

import static org.junit.jupiter.api.Assertions.*;

import dev.secondsun.retro.util.CA65Scanner;
import dev.secondsun.retro.util.diagnostic.SyntaxDiagnostic;
import dev.secondsun.retro.util.diagnostic.SyntaxValidator;
import dev.secondsun.retro.util.vo.DialectProfile;
import dev.secondsun.retro.util.vo.ScopeType;
import dev.secondsun.retro.util.vo.TokenizedFile;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScopeTreeTest {

  private final CA65Scanner scanner = new CA65Scanner();
  private final SyntaxValidator validator = new SyntaxValidator();

  @Test
  void inlineFunctionHasFullScopeParameterAndAnonymousLabelParity() {
    TokenizedFile tf =
        scanner.tokenize(
            """
            ;; Clamps value to zero.
            inline function clamp_zero val : out
                register tmp
            :   moves val, val
                bpl :+
                ibt out, #0
                bra :++
            :   move out, val
            :   return out
            endfunction
            """,
            URI.create("file:///inline_fn.sgs"));

    List<SyntaxDiagnostic> diags = validator.validate(tf);
    assertTrue(diags.isEmpty(), "Expected zero diagnostics on inline function, got: " + diags);

    var fnScopeOpt =
        tf.scopeTree().allScopes().stream()
            .filter(s -> s.type() == ScopeType.FUNCTION && "clamp_zero".equals(s.name()))
            .findFirst();
    assertTrue(fnScopeOpt.isPresent(), "Expected FUNCTION scope for clamp_zero");
    var fnScope = fnScopeOpt.get();
    assertTrue(fnScope.isInlineFunction());
    assertTrue(fnScope.definitions().containsKey("val"));
    assertTrue(fnScope.definitions().containsKey("out"));

    var fnDef =
        tf.scopeTree().allDefinitions().stream()
            .filter(d -> "clamp_zero".equals(d.name()))
            .findFirst();
    assertTrue(fnDef.isPresent());
    assertTrue(fnDef.get().cleanDoc().contains("Clamps value to zero."));
  }

  @Test
  void singleLineLabelProcAndMacroIsolationWorkWithoutFalseDuplicates() {
    TokenizedFile tf =
        scanner.tokenize(
            """
            .macro emit_helper
            local_lbl:
                nop
            .endmacro

            my_proc: .proc
            local_lbl:
                nop
            .endproc
            """,
            URI.create("file:///proc_macro.sgs"));

    List<SyntaxDiagnostic> diags = validator.validate(tf);
    assertTrue(diags.isEmpty(), "Expected zero diagnostics, got: " + diags);
    assertTrue(
        tf.scopeTree().allScopes().stream()
            .anyMatch(s -> s.type() == ScopeType.PROC && "my_proc".equals(s.name())));
  }

  @Test
  void conditionalArmsAllowMutuallyExclusiveDefinitionsWhileCatchingTrueDuplicates() {
    TokenizedFile validIfElse =
        scanner.tokenize(
            """
            .if PAL_BUILD
                FRAME_RATE = 50
            init_video:
                nop
            .elseif NTSC_DEBUG
                FRAME_RATE = 60
            init_video:
                nop
            .else
                FRAME_RATE = 60
            init_video:
                nop
            .endif
            """,
            URI.create("file:///cond_ok.sgs"));

    List<SyntaxDiagnostic> okDiags = validator.validate(validIfElse);
    assertTrue(
        okDiags.isEmpty(),
        "Mutually exclusive .if/.elseif/.else arms must not emit DUPLICATE_SYMBOL, got: "
            + okDiags);

    TokenizedFile duplicateInsideArm =
        scanner.tokenize(
            """
            .if PAL_BUILD
                FRAME_RATE = 50
                FRAME_RATE = 25
            .endif
            """,
            URI.create("file:///cond_dup_intra.sgs"));
    assertTrue(
        validator.validate(duplicateInsideArm).stream()
            .anyMatch(d -> "DUPLICATE_SYMBOL".equals(d.code())));

    TokenizedFile duplicateAgainstOuter =
        scanner.tokenize(
            """
            FRAME_RATE = 60
            .if PAL_BUILD
                FRAME_RATE = 50
            .endif
            """,
            URI.create("file:///cond_dup_outer.sgs"));
    assertTrue(
        validator.validate(duplicateAgainstOuter).stream()
            .anyMatch(d -> "DUPLICATE_SYMBOL".equals(d.code())));
  }

  @Test
  void dialectProfileSkipsGsuOperandChecksOn65816AssemblyWhileValidatingSuperFxSegments() {
    TokenizedFile cpu65816 =
        scanner.tokenize(
            """
            .p816
            .a16
            reset_handler:
                lda #$1234
                and #$FF00
                sbc #$0001
                cmp #$0080
                inc
                lsr a
                jmp reset_handler
            """,
            URI.create("file:///snes_main.s"));

    assertEquals(DialectProfile.CPU_65816, cpu65816.scopeTree().dialectAtLine(3));
    List<SyntaxDiagnostic> cpuDiags = validator.validate(cpu65816);
    assertTrue(
        cpuDiags.isEmpty(),
        "65816 .s code must emit zero false-positive GSU errors, got: " + cpuDiags);

    TokenizedFile mixedSFile =
        scanner.tokenize(
            """
            .p816
            host_entry:
                lda #$01
                rtl

            .segment "GSUCODE"
            gsu_entry:
                sbc #5
                stop
            """,
            URI.create("file:///mixed.s"));

    assertEquals(DialectProfile.CPU_65816, mixedSFile.scopeTree().dialectAtLine(2));
    assertEquals(DialectProfile.SUPERFX, mixedSFile.scopeTree().dialectAtLine(7));
    List<SyntaxDiagnostic> mixedDiags = validator.validate(mixedSFile);
    assertEquals(1, mixedDiags.size(), "Only GSUCODE sbc #5 should fail: " + mixedDiags);
    assertEquals("INVALID_OPERAND", mixedDiags.get(0).code());
    assertEquals(8, mixedDiags.get(0).line());
  }
}
