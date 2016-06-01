import java.util.List;

/**
 * Created by stjjensen1 on 5/23/2016.
 */
final class FromReturn {
    final List<Instruction> inst;
    final String remainder;

    FromReturn(List<Instruction> inst, String remainder) {
        this.inst = inst;
        this.remainder = remainder;
    }
}
