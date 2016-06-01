import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import sun.reflect.generics.reflectiveObjects.NotImplementedException;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

abstract class Instruction implements Cloneable {

    public abstract String execute(State state);

    @Override
    public abstract String toString();

    @NotNull
    @Contract(value = "_ -> !null", pure = true)
    public abstract Instruction offset(int offset);

    abstract boolean optimize(InstructionBlock.Position position, Function<Integer, Guarantee> preBlockGuarantees,
            Predicate<Integer> postBlockIgnores);

    @Contract(value = "_, _, _ -> !null", pure = true)
    abstract Guarantee guarantees(@NotNull InstructionBlock.Position position, int request,
            @NotNull Function<Integer, Guarantee> preBlockGuarantees);

    abstract boolean ignores(InstructionBlock.Position position, int request, Predicate<Integer> postBlockIgnores);

    private static final class SetValue extends StableInstruction {

        @Override
        boolean optimize(@NotNull InstructionBlock.Position position, @NotNull Function<Integer, Guarantee> preBlockGuarantees,
                Predicate<Integer> postBlockIgnores) {
            if (position.guarantees(offset, preBlockGuarantees).equalsValue(value)
                    || position.ignores(offset, postBlockIgnores)) {
                position.replaceBefore(position.next);
                return true;
            } else return false;
        }

        private final byte value;
        private final int offset;

        SetValue(byte value, int offset) {
            this.value = value;
            this.offset = offset;
        }

        @Override
        public String execute(@NotNull State state) {
            state.tape[state.pointer + offset] = value;
            return "";
        }

        @NotNull
        @Override
        public String toString() {
            return "set " + (value + 256) % 256 + (offset != 0 ? ", " + (offset > 0 ? ">" : "<") + Math.abs(offset)
                    : "");
        }

        @NotNull
        @Override
        public Instruction offset(int offset) {
            return new SetValue(value, this.offset + offset);
        }

        @Override
        Guarantee guarantees(@NotNull InstructionBlock.Position position, int request,
                @NotNull Function<Integer, Guarantee> preBlockGuarantees) {
            if (request == offset) {
                return Guarantee.constant(value);
            } else {
                return position.guarantees(request, preBlockGuarantees);
            }
        }

        @Override
        boolean ignores(@NotNull InstructionBlock.Position position, int request, Predicate<Integer> postBlockIgnores) {
            return request == offset || position.ignores(request, postBlockIgnores);
        }
    }

    static final class Shift extends ConstantInstruction {
        final int amount;

        Shift(int amount) {
            this.amount = amount;
        }

        @Override
        public String toString() {
            return String.format("shift %d", amount);
        }

        @Override
        boolean ignores(@NotNull InstructionBlock.Position position, int request, Predicate<Integer> postBlockIgnores) {
            return position.ignores(request - amount, postBlockIgnores);
        }

        @Override
        public boolean optimize(@NotNull InstructionBlock.Position position,
                Function<Integer, Guarantee> preBlockGuarantees,
                Predicate<Integer> postBlockIgnores) {
            if (amount == 0) {
                position.replaceBefore(position.next);
                return true;
            } else if (position.next != null) {
                InstructionBlock.Position instPos = position;
                while (instPos.next != null && !(instPos.next.instruction instanceof Shift)) {
                    instPos = instPos.next;
                    instPos.offset(amount);
                }
                if (instPos.next != null) {
                    InstructionBlock.Position opt = new InstructionBlock.Position(new Shift(
                            this.amount + ((Shift) instPos.next.instruction).amount), position.block);
                    instPos.next.replaceAfter(opt);
                    instPos.next.replaceBefore(opt);
                } else {
                    InstructionBlock.Position opt = new InstructionBlock.Position(new Shift(this.amount), position.block);
                    opt.previous = instPos;
                    instPos.next = opt;
                    position.block.last = opt;
                }
                position.replaceBefore(position.next);
                return true;
            } else return false;
        }

        @Override
        Guarantee guarantees(@NotNull InstructionBlock.Position position, int request,
                @NotNull Function<Integer, Guarantee> preBlockGuarantees) {
            return position.guarantees(request + amount, preBlockGuarantees);
        }

        @NotNull
        @Override
        public Shift offset(int offset) {
            return this;
        }

        @Override
        public String execute(@NotNull State state) {
            state.pointer += amount;
            return "";
        }
    }

    private static final class Copy extends StableInstruction {
        private final int from;
        private final int to;
        private final byte multiplier;

        Copy(int from, int to, int multiplier) {
            this(from, to, (byte) multiplier);
        }

        Copy(int from, int to) {
            this(from, to, 1);
        }


        @Override
        boolean ignores(@NotNull InstructionBlock.Position position, int request, Predicate<Integer> postBlockIgnores) {
            if (request == from) {
                if (multiplier != 0) {
                    return false;
                }
            }
            return position.ignores(request, postBlockIgnores);
        }

        @Override
        Guarantee guarantees(@NotNull InstructionBlock.Position position, int request,
                @NotNull Function<Integer, Guarantee> preBlockGuarantees) {
            if (request == to) {
                return position.guarantees(to, preBlockGuarantees).plus(position.guarantees(from, preBlockGuarantees).times(multiplier));
            } else
                return position.guarantees(request, preBlockGuarantees);
        }

        @Override
        boolean optimize(@NotNull InstructionBlock.Position position, @NotNull Function<Integer, Guarantee> preBlockGuarantees,
                Predicate<Integer> postBlockIgnores) {
            if (position.next != null) {
                Instruction next = position.next.instruction;
                if (next instanceof Write && multiplier == 1 && ((Write) next).multiplier == 1
                        && ((Write) next).to == from && ((Write) next).from == to
                        && position.next.ignores(to, postBlockIgnores)) {
                    Instruction newCopy = new Copy(to, from);
                    InstructionBlock.Position copyPos = new InstructionBlock.Position(newCopy, position.block);
                    position.replaceBefore(copyPos);
                    position.next.replaceAfter(copyPos);
                    return true;
                }
            }
            Guarantee fromGuarantee = position.guarantees(from, preBlockGuarantees);
            if (fromGuarantee.isConstant()) {
                Add add = new Add((byte) (fromGuarantee.getValue() * multiplier), to);
                InstructionBlock.Position addPos = new InstructionBlock.Position(add, position.block);
                position.replace(addPos);
                return true;
            }
            Guarantee toGuarantee = position.guarantees(to, preBlockGuarantees);
            if (toGuarantee.equalsValue(0)) {
                Write write = new Write(from, to, multiplier);
                InstructionBlock.Position writePos = new InstructionBlock.Position(write, position.block);
                position.replace(writePos);
                return true;
            }
            if (fromGuarantee.equals(toGuarantee)) {
                InstructionBlock.Position newPos = new InstructionBlock.Position(new Write(from, to,
                        multiplier + 1), position.block);
                position.replace(newPos);
                return true;
            }
            return false;
        }

        Copy(int from, int to, byte multiplier) {
            this.from = from;
            this.to = to;
            this.multiplier = multiplier;
        }

        @Override
        public String execute(@NotNull State state) {
            if (state.tape[state.pointer + from] != 0)
                state.tape[state.pointer + to] += state.tape[state.pointer + from] * multiplier;
            return "";
        }

        @NotNull
        @Override
        public String toString() {
            return "copy " + from + (multiplier == 1 ? "" : "*" + multiplier) + ", " + to;
        }

        @NotNull
        @Override
        public Copy offset(int offset) {
            return new Copy(from + offset, to + offset, multiplier);
        }
    }

    private static final class Write extends StableInstruction {
        private final int from;
        private final int to;
        private final byte multiplier;

        Write(int from, int to, byte multiplier) {
            this.from = from;
            this.to = to;
            this.multiplier = multiplier;
        }

        Write(int from, int to, int multiplier) {
            this(from, to, (byte) multiplier);
        }

        @Override
        Guarantee guarantees(@NotNull InstructionBlock.Position position, int request,
                @NotNull Function<Integer, Guarantee> preBlockGuarantees) {
            if (request == to) {
                return position.guarantees(from, preBlockGuarantees).times(multiplier);
            } else
                return position.guarantees(request, preBlockGuarantees);
        }

        @Override
        boolean ignores(@NotNull InstructionBlock.Position position, int request, Predicate<Integer> postBlockIgnores) {
            return request == to || request != from && position.ignores(request, postBlockIgnores);
        }

        @Override
        public String execute(@NotNull State state) {
            state.tape[state.pointer + to] = (byte) (state.tape[state.pointer + from] * multiplier);
            return "";
        }

        @NotNull
        @Override
        public String toString() {
            return "write " + from + (multiplier == 1 ? "" : "*" + multiplier) + ", " + to;
        }

        @NotNull
        @Override
        public Write offset(int offset) {
            return new Write(from + offset, to + offset, multiplier);
        }

        @Override
        boolean optimize(@NotNull InstructionBlock.Position position, @NotNull Function<Integer, Guarantee> preBlockGuarantees,
                Predicate<Integer> postBlockIgnores) {
            if (multiplier == 0) {
                InstructionBlock.Position newPos = new InstructionBlock.Position(new SetValue((byte) 0, to), position.block);
                position.replace(newPos);
                return true;
            }
            Guarantee tog = position.guarantees(to, preBlockGuarantees);
            Guarantee fromg = position.guarantees(from, preBlockGuarantees);
            if (tog.equals(fromg) && multiplier == 1 || position.ignores(to, postBlockIgnores)) {
                position.replaceBefore(position.next);
                return true;
            } else return false;
        }

    }

    static final class Add extends StableInstruction {
        final byte amount;
        private final int offset;

        Add(byte amount, int offset) {
            this.amount = amount;
            this.offset = offset;
        }

        @Override
        Guarantee guarantees(@NotNull InstructionBlock.Position position, int request,
                @NotNull Function<Integer, Guarantee> preBlockGuarantees) {
            if (request == offset) {
                return position.guarantees(offset, preBlockGuarantees).plus(Guarantee.constant(amount));
            } else
                return position.guarantees(request, preBlockGuarantees);
        }

        @Override
        boolean ignores(@NotNull InstructionBlock.Position position, int request, Predicate<Integer> postBlockIgnores) {
            return position.ignores(request, postBlockIgnores);
        }

        @NotNull
        @Override
        public String toString() {
            return (amount >= 0 ? "add" : "sub") + " " + Math.abs(amount) + (offset != 0 ? ", " + (offset > 0 ? ">"
                    : "<") + Math.abs(offset) : "");
        }

        @Override
        public boolean optimize(@NotNull InstructionBlock.Position position,
                @NotNull Function<Integer, Guarantee> preBlockGuarantees,
                Predicate<Integer> postBlockIgnores) {
            Guarantee prevValue = position.guarantees(offset, preBlockGuarantees);
            if (prevValue.isConstant()) {
                SetValue newSet = new SetValue((byte) (prevValue.getValue() + amount), offset);
                InstructionBlock.Position newPos = new InstructionBlock.Position(newSet, position.block);
                position.replaceAfter(newPos);
                position.replaceBefore(newPos);
                return true;
            } else if (position.next != null)
                if (position.next.instruction instanceof Add)
                    if (((Add) position.next.instruction).offset == offset) {
                        Instruction opt = new Add((byte) (amount + ((Add) position.next.instruction).amount), offset);
                        InstructionBlock.Position newPos = new InstructionBlock.Position(opt, position.block);
                        position.next.replaceAfter(newPos);
                        position.replaceBefore(newPos);
                        return true;
                    } else return false;
                else return false;
            else return false;
        }

        @NotNull
        @Override
        public Add offset(int offset) {
            return new Add(amount, this.offset + offset);
        }

        @Override
        public String execute(@NotNull State state) {
            state.tape[state.pointer + offset] += amount;
            return "";
        }

    }

    private static final class Null extends StableInstruction {
        @Override
        public String execute(State state) {

            return "";
        }

        @Override
        boolean ignores(@NotNull InstructionBlock.Position position, int request, Predicate<Integer> postBlockIgnores) {
            return position.ignores(request, postBlockIgnores);
        }

        @Override
        public boolean optimize(@NotNull InstructionBlock.Position position,
                Function<Integer, Guarantee> preBlockGuarantees,
                Predicate<Integer> postBlockIgnores) {
            position.replaceBefore(position.next);
            return true;
        }

        @NotNull
        @Override
        public Null offset(int offset) {
            return this;
        }

        @Override
        Guarantee guarantees(@NotNull InstructionBlock.Position position, int request,
                @NotNull Function<Integer, Guarantee> preBlockGuarantees) {
            return position.guarantees(request, preBlockGuarantees);
        }

        @Override
        public String toString() {
            return "NULL";
        }
    }

    static abstract class Control extends Instruction {
        final InstructionBlock block;

        Control(InstructionBlock block) {
            this.block = block;
        }
    }

    private static class If extends Control {

        private final int offset;

        If(int offset) {
            this(offset, new InstructionBlock(b -> preBlockGuarantees -> preBlockGuarantees, postBlockIgnores -> postBlockIgnores));
        }

        If(int offset, InstructionBlock instructions) {
            super(instructions);
            this.offset = offset;
        }

        @NotNull
        @Override
        public String toString() {
            return "if " + offset + " {\n" + block.toString().replaceAll("(^|\\n)(?=.)", "$1  ") + "}";
        }

        @Override
        boolean ignores(@NotNull InstructionBlock.Position position, int request, Predicate<Integer> postBlockIgnores) {
            return request != offset && block.ignores(request, i -> position.ignores(i, postBlockIgnores))
                    && position.ignores(request, postBlockIgnores);
        }

        @NotNull
        @Override
        public If offset(int offset) {
            InstructionBlock newBlock = block.clone();
            newBlock.offset(offset);
            return new If(this.offset + offset, newBlock);
        }

        @Override
        public boolean optimize(@NotNull InstructionBlock.Position position,
                @NotNull Function<Integer, Guarantee> preBlockGuarantees,
                Predicate<Integer> postBlockIgnores) {
            boolean optimized = false;

            if (block.optimize())
                optimized = true;

            if (block.isEmpty()) {
                position.replaceBefore(position.next);
                return true;
            }

            Guarantee g = position.guarantees(offset, preBlockGuarantees);
            if (g.equalsValue(0)) {
                position.replaceBefore(position.next);
                optimized = true;
            } else if (g.inequalsValue(0)) {
                InstructionBlock.Position pos = new InstructionBlock.Position(new Null(), position.block);
                InstructionBlock.Position first = pos;
                for (Instruction in : block) {
                    pos.next = new InstructionBlock.Position(in, position.block);
                    pos.next.previous = pos;
                    pos = pos.next;
                }
                position.replaceBefore(first);
                position.replaceAfter(pos);
                return true;
            } else {
                InstructionBlock optimizedBlock = block.optimized(b -> (request) -> position.guarantees(request, preBlockGuarantees), (request1) -> position.ignores(request1, postBlockIgnores));
                if (optimizedBlock != null) {
                    If newIf = new If(offset, optimizedBlock);
                    InstructionBlock.Position pos = new InstructionBlock.Position(newIf, position.block);
                    position.replaceAfter(pos);
                    position.replaceBefore(pos);
                    return true;
                }

            }
            return optimized;
        }

        @NotNull
        @Override
        Guarantee guarantees(@NotNull InstructionBlock.Position position, int request,
                @NotNull Function<Integer, Guarantee> preBlockGuarantees) {
            return position.guarantees(offset, preBlockGuarantees)
                    .conditional(block.guarantees(request, i -> position.guarantees(i, preBlockGuarantees)),
                            position.guarantees(request, preBlockGuarantees));
        }

        @Override
        public String execute(@NotNull State state) {
            if (state.tape[state.pointer + offset] != 0) {
                return block.execute(state);
            }
            return "";
        }
    }

    static class WhileLoop extends Control {

        static boolean testing = false;
        final int offset;


        WhileLoop(int offset) {
            this(offset, new InstructionBlock(b -> preBlockGuarantees -> i -> {
                Guarantee unknown;
                if (i == 0) {
                    unknown = new Guarantee.Unknown(b, i) {
                        @Override
                        boolean inequalsValue(byte i) {
                            return i == 0;
                        }
                    };
                } else {
                    unknown = new Guarantee.Unknown(b, i);
                }
                if (!Instruction.WhileLoop.testing) {
                    Instruction.WhileLoop.testing = true;
                    Guarantee through = b.guarantees(i, preBlockGuarantees);
                    Instruction.WhileLoop.testing = false;
                    if (through.equals(unknown)) {
                        return preBlockGuarantees.apply(i);
                    }
                }
                return unknown;
            }, i -> d -> false));
        }

        WhileLoop(int offset, InstructionBlock optimizedBlock) {
            super(optimizedBlock);
            this.offset = offset;
        }

        @NotNull
        @Override
        Guarantee guarantees(@NotNull InstructionBlock.Position position, int request,
                @NotNull Function<Integer, Guarantee> preBlockGuarantees) {
            if (request == offset)
                return Guarantee.constant(0);
            else {
                return position.guarantees(offset, preBlockGuarantees).conditional(block.guarantees(request, i -> position.guarantees(i - offset, preBlockGuarantees)),
                        position.guarantees(request, preBlockGuarantees));
            }
        }

        @Override
        boolean ignores(@NotNull InstructionBlock.Position position, int request, Predicate<Integer> postBlockIgnores) {
            return request != offset
                    && block.ignores(request, i -> position.ignores(i + offset, postBlockIgnores))
                    && position.ignores(request, i -> position.ignores(i, postBlockIgnores));

        }

        @NotNull
        @Override
        public String toString() {
            return "while " + offset + " {\n" + block.toString().replaceAll("(^|\\n)(?=.)", "$1  ") + "}";
        }

        @NotNull
        @Override
        public WhileLoop offset(int offset) {
            return new WhileLoop(this.offset + offset, block.clone());
        }

        public boolean optimize(@NotNull InstructionBlock.Position position,
                @NotNull Function<Integer, Guarantee> preBlockGuarantees,
                Predicate<Integer> postBlockIgnores) {
            boolean optimized = false;

            while (block.optimize()) {
                optimized = true;
            }

            boolean copyOptimizable = true;
            int loopDiff = 0;
            Set<Integer> touched = new HashSet<>();
            if (block.first != null) {
                for (Instruction inst : block) {
                    if (inst instanceof Add) {
                        Add add = (Add) inst;
                        if (add.offset == 0) {
                            loopDiff += add.amount;
                        }
                        if (touched.contains(add.offset)) {
                            copyOptimizable = false;
                        }
                    } else if ((inst instanceof SetValue)) {
                        SetValue set = (SetValue) inst;
                        if (set.offset == 0) {
                            copyOptimizable = false;
                        }
                        touched.add(set.offset);
                    } else {
                        copyOptimizable = false;
                    }
                }
                if (loopDiff != -1) {
                    copyOptimizable = false;
                }
            } else {
                copyOptimizable = false;
            }
            if (copyOptimizable) {
                InstructionBlock.Position replace = new InstructionBlock.Position(new Null(), position.block);
                InstructionBlock.Position curr = replace;
                for (Instruction inst : block) {
                    Instruction nextInstruction;
                    if (inst instanceof Add) {
                        Add add = (Add) inst;
                        if (add.offset == 0)
                            continue;
                        nextInstruction = new Copy(offset, add.offset + offset, add.amount);
                        curr.next = new InstructionBlock.Position(nextInstruction, position.block);
                        curr.next.previous = curr;
                        curr = curr.next;
                    } else if (inst instanceof SetValue) {
                        SetValue set = (SetValue) inst;
                        If ifInstruction = new If(offset);
                        curr.next = new InstructionBlock.Position(ifInstruction, position.block);
                        curr.next.previous = curr;
                        ifInstruction.block.first = new InstructionBlock.Position(new SetValue(set.value,
                                set.offset + offset), ifInstruction.block);
                        curr = curr.next;
                        assert curr != null;
                    } else {
                        throw new NotImplementedException();
                    }
                }
                curr.next = new InstructionBlock.Position(new SetValue((byte) 0, offset), position.block);
                curr.next.previous = curr;
                curr = curr.next;
                position.replaceAfter(curr);
                position.replaceBefore(replace);
                return true;
            } else if (position.guarantees(offset, preBlockGuarantees).equalsValue(0)) {
                position.replaceBefore(position.next);
                return true;
            } else if (block.guarantees(0, i -> position.guarantees(i + offset, preBlockGuarantees)).equalsValue(0)) {
                If ifInst = new If(offset);
                InstructionBlock.Position ifPos = new InstructionBlock.Position(ifInst, position.block);
                ifInst.block.addAll(block);
                ifInst.block.offset(offset);
                InstructionBlock.Position setPos = new InstructionBlock.Position(new SetValue((byte) 0, offset), position.block);
                ifPos.next = setPos;
                setPos.previous = ifPos;
                position.replaceBefore(ifPos);
                position.replaceAfter(setPos);
                return true;
            } else {
                if (position.guarantees(offset, preBlockGuarantees).inequalsValue(0)) {
                    InstructionBlock.Position pos = new InstructionBlock.Position(new Null(), position.block);
                    InstructionBlock.Position first = pos;
                    for (Instruction in : block) {
                        pos.next = new InstructionBlock.Position(in.offset(offset), position.block);
                        pos.next.previous = pos;
                        pos = pos.next;
                    }
                    Control newWhile = new WhileLoop(offset);
                    newWhile.block.addAll(this.block);
                    pos.next = new InstructionBlock.Position(newWhile, position.block);
                    pos.next.previous = pos;
                    position.replaceBefore(first);
                    position.replaceAfter(pos.next);
                    return true;
                }
                return optimized;
            }
        }

        @Override
        public String execute(@NotNull State state) {
            String out = "";
            state.pointer += offset;
            while (state.tape[state.pointer] != 0) {
                out += block.execute(state);
            }
            state.pointer -= offset;
            return out;
        }
    }

    static class Read extends StableInstruction {
        private final int offset;

        Read(int offset) {
            this.offset = offset;
        }

        @Override
        public String execute(@NotNull State state) {
            try {
                state.tape[state.pointer + offset] = (byte) System.in.read();
            } catch (IOException e) {
                e.printStackTrace();
            }
            return "";
        }

        @NotNull
        @Override
        public String toString() {
            return "read " + offset;
        }

        @NotNull
        @Override
        public Instruction offset(int offset) {
            return new Read(this.offset + offset);
        }

        @Override
        boolean optimize(InstructionBlock.Position position, Function<Integer, Guarantee> preBlockGuarantees,
                Predicate<Integer> postBlockIgnores) {
            return false;
        }

        @Override
        Guarantee guarantees(@NotNull InstructionBlock.Position position, int request,
                @NotNull Function<Integer, Guarantee> preBlockGuarantees) {
            if (request == offset) {
                return new Guarantee.Unknown(this, request);
            }
            return position.guarantees(request, preBlockGuarantees);
        }

        @Override
        boolean ignores(@NotNull InstructionBlock.Position position, int request, Predicate<Integer> postBlockIgnores) {
            return request == offset || position.ignores(request, postBlockIgnores);
        }
    }

    static abstract class ConstantInstruction extends Instruction {

    }

    static abstract class StableInstruction extends ConstantInstruction {

    }

    static class Out extends StableInstruction {
        private final int offset;

        Out(int offset) {
            this.offset = offset;
        }

        @Override
        Guarantee guarantees(@NotNull InstructionBlock.Position position, int request,
                @NotNull Function<Integer, Guarantee> preBlockGuarantees) {
            return position.guarantees(request, preBlockGuarantees);
        }

        @Override
        boolean optimize(@NotNull InstructionBlock.Position position, @NotNull Function<Integer, Guarantee> preBlockGuarantees,
                Predicate<Integer> postBlockIgnores) {
            Guarantee val = position.guarantees(offset, preBlockGuarantees);
            if (val.isConstant()) {
                byte value = val.getValue();
                Print print = new Print(Character.toString((char) (int) value));
                InstructionBlock.Position printPos = new InstructionBlock.Position(print, position.block);
                position.replaceAfter(printPos);
                position.replaceBefore(printPos);
                return true;
            } else return false;
        }

        @Override
        boolean ignores(@NotNull InstructionBlock.Position position, int request, Predicate<Integer> postBlockIgnores) {
            return request != offset && position.ignores(request, postBlockIgnores);
        }

        @NotNull
        @Override
        public String toString() {
            return "out " + offset;
        }

        @NotNull
        @Override
        public Out offset(int offset) {
            return new Out(this.offset + offset);
        }

        @Override
        public String execute(@NotNull State state) {
            return Character.toString((char) ((state.tape[state.pointer + offset] + 256) % 256));
        }

    }

    private static class Print extends StableInstruction {

        final String string;

        @Override
        boolean optimize(@NotNull InstructionBlock.Position position, Function<Integer, Guarantee> preBlockGuarantees,
                Predicate<Integer> postBlockIgnores) {
            if (position.next != null && position.next.instruction instanceof Print) {
                Print newPrint = new Print(string + ((Print) position.next.instruction).string);
                InstructionBlock.Position pos = new InstructionBlock.Position(newPrint, position.block);
                position.next.replaceAfter(pos);
                position.replaceBefore(pos);
                return true;
            } else return false;
        }

        @Override
        Guarantee guarantees(@NotNull InstructionBlock.Position position, int request,
                @NotNull Function<Integer, Guarantee> preBlockGuarantees) {
            return position.guarantees(request, preBlockGuarantees);
        }

        @Override
        boolean ignores(@NotNull InstructionBlock.Position position, int request, Predicate<Integer> postBlockIgnores) {
            return position.ignores(request, postBlockIgnores);
        }

        Print(String string) {
            this.string = string;
        }

        @Override
        public String execute(State state) {
            return string;
        }

        @NotNull
        @Override
        public String toString() {
            return "print \"" + string.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
        }

        @NotNull
        @Override
        public Print offset(int offset) {
            return this;
        }
    }
}
