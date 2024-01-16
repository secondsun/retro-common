package dev.secondsun.retro.util;

import dev.secondsun.retro.util.instruction.GSUInstruction;

import java.util.List;

public class GsuInstructionAttributeAdder {

    public void applyAttributes(List<Token> tokens) {

        var firstToken = tokens.getFirst();
        if (GSUInstruction.isInstruction(firstToken)) {
            GSUInstruction.mark(firstToken);
        }

    }

}
