import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by stjjensen1 on 5/24/2016.
 */
abstract class Guarantee {


    boolean equalsValue(int i) {
        return equalsValue((byte) i);
    }

    boolean inequalsValue(byte i) {
        return false;
    }

    byte getValue() {
        throw new NonConstantException();
    }

    private static class NonConstantException extends RuntimeException {
    }

    @NotNull
    static Guarantee constant(int value) {
        return constant((byte) value);
    }

    @NotNull
    static Guarantee constant(byte value) {
        return new Constant(value);
    }

    boolean isConstant() {
        return false;
    }

    @NotNull Guarantee plus(@NotNull Guarantee other) {
        Map<Guarantee, Integer> in = new HashMap<>();
        in.put(this, 1);
        in.put(other, 1);

        Map<Guarantee, Integer> p = new HashMap<>();
        byte c = 0;

        List<Map.Entry<Guarantee, Integer>> toAdd = new ArrayList<>(in.entrySet());

        while (!toAdd.isEmpty()) {
            Map.Entry<Guarantee, Integer> e = toAdd.remove(0);
            Guarantee g = e.getKey();
            int v = e.getValue();

            if (g.isConstant()) {
                c += g.getValue();
            } else if (g instanceof Sum) {
                toAdd.addAll(((Sum) g).parts.entrySet());
                c += ((Sum) g).constant;
            } else {
                p.put(g, p.getOrDefault(g, 0) + v);
                if (p.get(g) == 0) {
                    p.remove(g);
                }
            }
        }
        if (p.isEmpty()) {
            return Guarantee.constant(c);
        }
        if (c == 0 && p.size() == 1) {
            Guarantee element = p.keySet().iterator().next();
            if (p.get(element) == 1) {
                return element;
            }
        }

        return new Sum(p, c);
    }

    @NotNull Guarantee conditional(@NotNull Guarantee ifTrue, @NotNull Guarantee ifFalse) {
        if (this.equalsValue(0))
            return ifFalse;
        if (this.inequalsValue(0))
            return ifTrue;
        if (ifTrue.equals(ifFalse))
            return ifTrue;
        return new Conditional(this, ifTrue, ifFalse);
    }

    @NotNull Guarantee times(int n) {
        switch (n) {
            case 1:
                return this;
            case 0:
                return Guarantee.constant(0);
            default:
                return new Sum(this, n);
        }
    }

    private static class Sum extends Guarantee {

        @NotNull
        final Map<Guarantee, Integer> parts;
        final byte constant;

        @Override
        public int hashCode() {
            return parts.entrySet().stream().mapToInt(e -> e.getKey().hashCode() * e.getValue()).sum();
        }

        Sum(@NotNull Map<Guarantee, Integer> parts, byte constant) {
            this.parts = parts;
            this.constant = constant;
        }

        Sum(@NotNull Guarantee a, @NotNull Guarantee b, boolean subtract) {

            Map<Guarantee, Integer> in = new HashMap<>();
            in.put(a, 1);
            in.put(b, subtract ? -1 : 1);

            Map<Guarantee, Integer> p = new HashMap<>();
            byte c = 0;

            List<Map.Entry<Guarantee, Integer>> toAdd = new ArrayList<>(in.entrySet());

            while (!toAdd.isEmpty()) {
                Map.Entry<Guarantee, Integer> e = toAdd.remove(0);
                Guarantee g = e.getKey();
                int v = e.getValue();

                if (g.isConstant()) {
                    c += g.getValue();
                } else if (g instanceof Sum) {
                    toAdd.addAll(((Sum) g).parts.entrySet());
                    c += ((Sum) g).constant;
                } else {
                    p.put(g, p.getOrDefault(g, 0) + v);
                    if (p.get(g) == 0) {
                        p.remove(g);
                    }
                }
            }

            parts = Collections.unmodifiableMap(p);
            constant = c;
        }

        Sum(@NotNull Guarantee guarantee, int n) {

            Map<Guarantee, Integer> p = new HashMap<>();
            byte c = 0;

            if (n != 0)
                if (guarantee.isConstant()) {
                    c = (byte) (guarantee.getValue() * n);
                } else if (guarantee instanceof Sum) {
                    Sum sum = (Sum) guarantee;
                    for (Guarantee g : sum.parts.keySet()) {
                        p.put(g, sum.parts.get(g) * n);
                        c = (byte) (sum.constant * n);
                    }
                } else {
                    p.put(guarantee, n);
                }
            parts = Collections.unmodifiableMap(p);
            constant = c;
        }

        @Override
        public boolean equalsValue(byte i) {
            return constant == i && parts.isEmpty();
        }

        @Override
        byte getValue() {
            if (isConstant())
                return constant;
            else
                return super.getValue();
        }

        @Override
        public boolean equals(Object o) {
            if (o instanceof Guarantee)
                if (isConstant()) {
                    if (((Guarantee) o).equalsValue(getValue())) {
                        return true;
                    }
                } else if (o instanceof Sum) {
                    if (((Sum) o).parts.equals(parts)) {
                        if (((Sum) o).constant == constant) {
                            return true;
                        }
                    }
                }
            return false;
        }
    }

    boolean inequalsValue(int i) {
        return inequalsValue((byte) i);
    }

    static class Unknown extends Guarantee {

        @NotNull private final Object position;
        private final int request;

        Unknown(@NotNull Object position, int request) {
            this.position = position;
            this.request = request;
        }

        @Override
        public int hashCode() {
            return position.hashCode() + request;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Unknown && ((Unknown) o).position == position && ((Unknown) o).request == request;
        }
    }

    public boolean equalsValue(byte i) {
        return false;
    }

    private static class Constant extends Guarantee {

        @Override
        byte getValue() {
            return value;
        }

        private final byte value;

        @Override
        boolean isConstant() {
            return true;
        }

        @Override
        public int hashCode() {
            return value;
        }

        Constant(byte value) {
            this.value = value;
        }

        @Override
        boolean inequalsValue(byte i) {
            return value != i;
        }

        @Override
        public boolean equalsValue(byte i) {
            return value == i;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Guarantee && ((Guarantee) o).equalsValue(value);
        }
    }

    private static class Conditional extends Guarantee {
        @NotNull
        private final Guarantee conditional;
        @NotNull
        private final Guarantee ifTrue;
        @NotNull
        private final Guarantee ifFalse;

        Conditional(@NotNull Guarantee conditional, @NotNull Guarantee ifTrue, @NotNull Guarantee ifFalse) {
            this.conditional = conditional;
            this.ifTrue = ifTrue;
            this.ifFalse = ifFalse;
        }

        @Override
        boolean inequalsValue(byte i) {
            return ifTrue.inequalsValue(i) && ifFalse.inequalsValue(i);
        }

        @Override
        public boolean equals(Object o) {
            if (o instanceof Conditional) {
                Conditional other = (Conditional) o;
                if (conditional.equals(other.conditional) && ifTrue.equals(other.ifTrue)
                        && ifFalse.equals(other.ifFalse)) {
                    return true;
                }
            }
            return false;
        }
    }
}
