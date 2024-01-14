package dev.secondsun.retro.util.instruction;

public class GSUInstruction {



    public GSUInstruction(String instruction, String... argumentDefinitions) {
        for (int i = 0; i < argumentDefinitions.length; i++) {
            var argument = argumentDefinitions[i];
            if (argument.startsWith("R")) {
                //parse register
            } else if (argument.startsWith("#")) {
                //parse number
                //n/
                //x
                //p
            } else if (argument.startsWith("(")) {
                //parse address
            } else if (argument.equals("e")) {
                //parse offset
            }
            else {
                throw new IllegalStateException("Illegal argument definition");
            }

        }

    }
    public static final String instructions = """
            ADC	Rn
            ADC	#n
            ADD	Rn
            ADD	#n
            ALT1	
            ALT2	
            ALT3	
            AND	Rn
            AND	#n
            ASR	
            BCC	e
            BCS	e
            BEQ	e
            BGE	e
            BIC	Rn
            BIC	#n
            BLT	e
            BMI	e
            BNE	e
            BPL	e
            BRA	e
            BVC	e
            BVS	e
            CACHE	
            CMODE	
            CMP	Rn
            COLOR	
            DEC	Rn
            DIV2	
            FMULT	
            FROM	Rn
            GETB	
            GETBH	
            GETBL	
            GETBS	
            GETC	
            HIB	
            IBT	"Rn, #pp"
            INC	Rn
            IWT	"Rn, #xx"
            JMP	Rn
            LDB	Rm
            LDW	Rm
            LEA	"Rn, xx"
            LINK	#n
            LJMP	Rn
            LM	"Rn, (xx)"
            LMS	"Rn, (yy)"
            LMULT	
            LOB	
            LOOP	
            LSR	
            MERGE	
            MOVE	"Rn, Rn'"
            MOVES	"Rn, Rn'"
            MULT	Rn
            MULT	#n
            NOP	
            NOT	
            OR	Rn
            OR	#n
            PLOT	
            RAMB	
            ROL	
            ROMB	
            ROR	
            RPIX	
            SBC	Rn
            SBK	
            SEX	
            SM	"Rn, (xx)"
            SMS	"Rn, (yy)"
            STB	Rm
            STOP	
            STW	Rm
            SUB	Rn
            SUB	#n
            SWAP	
            TO	Rn
            UMULT	Rn
            UMULT	#n
            WITH	"Rn"
            XOR	Rn
            XOR	#n
    """;
}
