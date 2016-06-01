import org.jetbrains.annotations.NotNull;

/**
 * Created by stjjensen1 on 5/16/2016.
 */
class State {

    @NotNull
    final byte[] tape;
    int pointer = 0;

    State(int size) {
        tape = new byte[size];
    }
}
