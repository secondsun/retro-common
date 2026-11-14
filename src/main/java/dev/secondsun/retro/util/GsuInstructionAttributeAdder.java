package dev.secondsun.retro.util;

import java.util.List;

/**
 * Legacy attribute helper retained as a safe no-op for backward compatibility. {@link CA65Scanner}
 * now tags SuperFX mnemonics during immutable token construction.
 */
public class GsuInstructionAttributeAdder {

  public void applyAttributes(List<Token> tokens) {
    // No-op: Token instances are immutable and tagged by CA65Scanner.
  }
}
