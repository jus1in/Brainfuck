import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import sun.reflect.generics.reflectiveObjects.NotImplementedException;

import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;

class InstructionBlock implements Cloneable, Iterable<Instruction> {

    boolean print = false;

    private final Function<InstructionBlock, Function<Function<Integer, Guarantee>, Function<Integer, Guarantee>>> guarantees;
    private final Function<Predicate<Integer>, Predicate<Integer>> ignores;
    @Nullable Position first;
    @Nullable Position last;

    boolean ignores(int request, Predicate<Integer> postBlockIgnores) {
        if (first != null) {
            return first.instruction.ignores(first, request, postBlockIgnores);
        }
        else return ignores.apply(postBlockIgnores).test(request);
    }

    InstructionBlock(@NotNull List<Instruction> list) {
        this(list, b -> g -> g, g -> g);
    }

    InstructionBlock(Function<InstructionBlock, Function<Function<Integer, Guarantee>, Function<Integer, Guarantee>>> guarantees, Function<Predicate<Integer>, Predicate<Integer>> ignores) {
        this.guarantees = guarantees;
        this.ignores = ignores;
    }

    private InstructionBlock(@NotNull Iterable<Instruction> list, Function<InstructionBlock, Function<Function<Integer, Guarantee>, Function<Integer, Guarantee>>> guarantees, Function<Predicate<Integer>, Predicate<Integer>> ignores) {
        this(guarantees, ignores);
        this.addAll(list);
    }

    void addAll(@NotNull Iterable<Instruction> list) {
        list.forEach(this::add);
    }

    @Contract(pure = true)
    Guarantee guarantees(int request, @NotNull Function<Integer, Guarantee> guarantees) {
        if (last != null) {
            return last.instruction.guarantees(last, request, guarantees);
        } else
            return guarantees.apply(request);
    }

    private boolean add(Instruction inst) {
        Position newPos = new Position(inst, this);
        if (last == null) {
            last = newPos;
            first = last;
        } else {
            last.next = newPos;
            last.next.previous = last;
            last = last.next;
        }
        return true;
    }

    @NotNull
    static FromReturn from(@NotNull String input) {
        List<Instruction> instructions = new ArrayList<>();
        while (!input.isEmpty()) {
            if (input.charAt(0) == '[') {
                input = input.substring(1);
                Instruction.WhileLoop whileLoop = new Instruction.WhileLoop(0);
                instructions.add(whileLoop);

                FromReturn fr = InstructionBlock.from(input);
                whileLoop.block.addAll(fr.inst);
                input = fr.remainder.substring(1);
            } else if (input.charAt(0) == ']') {
                break;
            } else {
                Instruction next;
                switch (input.charAt(0)) {
                    case '+':
                        next = new Instruction.Add((byte) 1, 0);
                        break;
                    case '-':
                        next = new Instruction.Add((byte) -1, 0);
                        break;
                    case '>':
                        next = new Instruction.Shift(1);
                        break;
                    case '<':
                        next = new Instruction.Shift(-1);
                        break;
                    case '.':
                        next = new Instruction.Out(0);
                        break;
                    case ',':
                        next = new Instruction.Read(0);
                        break;
                    default:
                        throw new NotImplementedException();
                }
                instructions.add(next);
                input = input.substring(1);
            }
        }
        return new FromReturn(instructions, input);
    }

    void offset(int offset) {
        Position pos = first;
        while (pos != null) {
            pos.offset(offset);
            pos = pos.next;
        }
    }

    @NotNull
    @Override
    @Contract(pure = true)
    public Iterator<Instruction> iterator() {
        return new Iterator<Instruction>() {

            @Nullable Position position = first;

            @Override
            public boolean hasNext() {
                return position != null;
            }

            @Override
            public Instruction next() {
                if (position == null) {
                    throw new NoSuchElementException();
                }
                Instruction inst = position.instruction;
                position = position.next;
                return inst;
            }
        };
    }

    public int size() {
        Position pos = first;
        int index = 0;
        while (pos != null) {
            pos = pos.next;
            index++;
        }
        return index;
    }

    boolean isEmpty() {
        assert (first == null) == (last == null);
        return first == null;
    }

    static class Position {
        @Nullable Position previous;
        @Nullable Position next;
        @NotNull Instruction instruction;
        @NotNull final InstructionBlock block;

        Position(@NotNull Instruction instruction, @NotNull InstructionBlock block) {
            this.instruction = instruction;
            this.block = block;
        }

        void offset(int offset) {
            instruction = instruction.offset(offset);
        }

        boolean optimize() {
            return optimize(i -> new Guarantee.Unknown(block, i), i -> false);
        }

        boolean optimize(Function<Integer, Guarantee> preBlockGuarantees, Predicate<Integer> postBlockIgnores) {
            return instruction.optimize(this, preBlockGuarantees, postBlockIgnores);
        }

        void replace(Position other) {
            replaceBefore(other);
            replaceAfter(other);
        }

        void replaceBefore(@Nullable Position other) {
            if (previous == null) {
                block.first = other;
            } else {
                previous.next = other;
            }
            if (other != null) {
                other.previous = previous;
            }
        }

        void replaceAfter(@Nullable Position other) {
            if (next == null) {
                block.last = other;
            } else {
                next.previous = other;
            }
            if (other != null) {
                other.next = next;
            }
        }


        Guarantee guarantees(int request, @NotNull Function<Integer, Guarantee> preBlockGuarantees) {
            try {
                if (previous != null) {
                    return previous.instruction.guarantees(previous, request, preBlockGuarantees);
                } else {
                    return block.guarantees.apply(block).apply(preBlockGuarantees).apply(request);
                }
            } catch (StackOverflowError e) {
                return new Guarantee.Unknown(this, request);
            }
        }

        boolean ignores(int request, Predicate<Integer> postBlockIgnores) {
            try {
                if (next != null) {
                    return next.instruction.ignores(next, request, postBlockIgnores);
                } else {
                    return block.ignores.apply(postBlockIgnores).test(request);
                }
            } catch (StackOverflowError e) {
                return false;
            }
        }

    }

    boolean stable() {
        int pointerDiff = 0;
        for (Instruction inst : this) {
            if (!(inst instanceof Instruction.StableInstruction)) {
                if (inst instanceof Instruction.Shift) {
                    pointerDiff += ((Instruction.Shift) inst).amount;
                } else {
                    return false;
                }
            }
        }
        return pointerDiff == 0;
    }

    private static int i = 0;

    String execute(State state) {
        String out = "";
        Position pos = first;
        while (pos != null) {
            out += pos.instruction.execute(state);
            pos = pos.next;
        }
        return out;
    }


    @NotNull
    @Contract(pure = true)
    private Position getPosition(int index) {
        Position pos = first;
        for (int i = 0; i < index && pos != null; i++) {
            pos = pos.next;
        }
        if (pos == null)
            throw new IndexOutOfBoundsException();
        return pos;
    }

    boolean optimize() {
        Position pos = first;
        boolean optimized = false;
        int index = 0;
        while (pos != null) {
            if (pos.optimize()) {
                if (this.print) {
                    System.out.println(i + ": ");
                    if (i % 1000000 == 0) {
                        execute(new State(700));
                        System.out.println();
                    }
                    i++;
                }
                index = Math.max(index - 2, 0);
                pos = getPosition(index);
                optimized = true;
            } else {
                pos = pos.next;
                index++;
            }
        }
        return optimized;
    }

    @NotNull
    @Override
    protected InstructionBlock clone() {
        InstructionBlock clone = new InstructionBlock(guarantees, ignores);
        clone.addAll(this);
        return clone;
    }

    @Nullable
    @Contract(pure = true)
    InstructionBlock optimized(@NotNull Function<InstructionBlock, Function<Integer, Guarantee>> preBlockGuarantees, Predicate<Integer> postBlockIgnores) {
        boolean optimized = false;
        int index = 0;
        InstructionBlock other = this.clone();
        other.print = this.print;
        Position pos = other.first;
        int p = 0;
        while (pos != null) {
            if (pos.optimize(preBlockGuarantees.apply(other), postBlockIgnores)) {
                if (this.print) {
                    System.out.println(Brainfuck.j + ", " + i + ", " + p + ": ");
                    if (i % 1000000 == 0) {
                        other.execute(new State(700));
                        System.out.println();
                    }
                    i++; p++;
                }
                index = Math.max(index - 2, 0);
                pos = other.getPosition(index);
                optimized = true;
            } else {
                pos = pos.next;
                index++;
            }
        }
        return optimized ? other : null;
    }

    @NotNull
    @Override
    public String toString() {
        String s = "";
        Position pos = first;
        while (pos != null) {
            s += pos.instruction.toString() + "\n";
            pos = pos.next;
        }
        return s;
    }


}
