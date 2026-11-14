package dev.secondsun;

import static org.junit.jupiter.api.Assertions.*;

import dev.secondsun.retro.util.CA65Scanner;
import dev.secondsun.retro.util.diagnostic.SyntaxDiagnostic;
import dev.secondsun.retro.util.diagnostic.SyntaxValidator;
import dev.secondsun.retro.util.sfx.ModalStepEvaluator;
import dev.secondsun.retro.util.sfx.SuperFxIsa;
import dev.secondsun.retro.util.vo.TokenizedFile;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

class SuperFxIsaTest {

  private final CA65Scanner scanner = new CA65Scanner();
  private final SyntaxValidator validator = new SyntaxValidator();

  private TokenizedFile parseSfx(String code) {
    return scanner.tokenize(code, URI.create("file:///test.sgs"));
  }

  private List<SyntaxDiagnostic> validateSfx(String code) {
    return validator.validate(parseSfx(code));
  }

  @Test
  void catalogCoversPseudoMovesAndExtendedOpcodes() {
    for (String op :
        List.of(
            "move", "moves", "moveb", "movew", "lea", "shl", "iwt", "ibt", "lm", "sm", "lms", "sms",
            "ldw", "stw", "ldb", "stb", "sbc", "cmp", "getb", "getbh", "getbl", "getbs", "asr",
            "plot", "rpix", "sbk", "lmult", "fmult")) {
      assertTrue(SuperFxIsa.isMnemonic(op), "Missing SuperFxIsa opcode: " + op);
    }
  }

  @Test
  void byteWeightsMatchAssembledSuperFxEncodings() {
    TokenizedFile tf =
        parseSfx(
            """
            move r1, r2
            moves r1, r2
            iwt r4, #$1234
            lm r3, ($1000)
            sm ($1000), r3
            lms r3, ($0010)
            sms ($0010), r3
            ibt r2, #$10
            bne target
            move r5, #$0010
            move r5, #$1234
            target:
            nop
            """);

    assertEquals(2, lineWeight(tf, 0)); // move r1, r2 -> 2B
    assertEquals(4, lineWeight(tf, 1)); // moves r1, r2 -> 4B
    assertEquals(3, lineWeight(tf, 2)); // iwt -> 3B
    assertEquals(4, lineWeight(tf, 3)); // lm -> 4B
    assertEquals(4, lineWeight(tf, 4)); // sm -> 4B
    assertEquals(3, lineWeight(tf, 5)); // lms -> 3B
    assertEquals(3, lineWeight(tf, 6)); // sms -> 3B
    assertEquals(2, lineWeight(tf, 7)); // ibt -> 2B
    assertEquals(2, lineWeight(tf, 8)); // branch -> 2B
    assertEquals(2, lineWeight(tf, 9)); // move r5, #16 -> 2B (ibt)
    assertEquals(3, lineWeight(tf, 10)); // move r5, #$1234 -> 3B (iwt)
  }

  private int lineWeight(TokenizedFile tf, int lineIdx) {
    var tokens = tf.getLine(lineIdx).tokens();
    int opIdx = ModalStepEvaluator.findOpcodeIndex(tokens);
    return SuperFxIsa.byteWeight(tokens, opIdx, tf.getRawLines().get(lineIdx));
  }

  @Test
  void sbcRejectsImmediateOperandWhileRegisterFormPasses() {
    List<SyntaxDiagnostic> bad = validateSfx("    sbc #5\n");
    assertTrue(
        bad.stream().anyMatch(d -> "INVALID_OPERAND".equals(d.code())),
        "Expected sbc #5 to fail with INVALID_OPERAND, got: " + bad);

    List<SyntaxDiagnostic> good = validateSfx("    sbc r3\n");
    assertTrue(good.isEmpty(), "Expected sbc r3 to pass, got: " + good);
  }

  @Test
  void zeroArgInstructionsRejectExtraOperands() {
    for (String line : List.of("getb r0", "getbh r1", "getbl r2", "getbs r3", "asr r1")) {
      List<SyntaxDiagnostic> diags = validateSfx("    " + line + "\n");
      assertTrue(
          diags.stream().anyMatch(d -> "EXTRA_OPERAND".equals(d.code())),
          "Expected EXTRA_OPERAND for '" + line + "', got: " + diags);
    }
  }

  @Test
  void indirectRamAndDynamicJumpRegisterBoundsAreEnforced() {
    assertTrue(
        validateSfx("    ldw (r12)\n").stream()
            .anyMatch(
                d ->
                    "REGISTER_OUT_OF_RANGE".equals(d.code())
                        || "INVALID_OPERAND".equals(d.code())));
    assertTrue(
        validateSfx("    stw (r15)\n").stream()
            .anyMatch(
                d ->
                    "REGISTER_OUT_OF_RANGE".equals(d.code())
                        || "INVALID_OPERAND".equals(d.code())));
    assertTrue(validateSfx("    ldw (r11)\n").isEmpty());

    assertTrue(
        validateSfx("    jmp r2\n").stream()
            .anyMatch(
                d ->
                    "REGISTER_OUT_OF_RANGE".equals(d.code())
                        || "INVALID_OPERAND".equals(d.code())));
    assertTrue(validateSfx("    jmp r8\n").isEmpty());
    assertTrue(validateSfx("    ljmp r13\n").isEmpty());
  }

  @Test
  void lmsSmsShortAddressAndIbtBoundsAreEnforced() {
    assertFalse(validateSfx("    lms r1, ($0200)\n").isEmpty());
    assertFalse(validateSfx("    sms ($0001), r1\n").isEmpty());
    assertTrue(validateSfx("    lms r1, ($01FE)\n").isEmpty());

    assertTrue(
        validateSfx("    ibt r0, #300\n").stream()
            .anyMatch(d -> "IMMEDIATE_OUT_OF_RANGE".equals(d.code())));
    assertTrue(validateSfx("    ibt r0, #<(-16)\n").isEmpty());
  }
}
