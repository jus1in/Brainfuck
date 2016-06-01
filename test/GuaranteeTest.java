import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Created by stjjensen1 on 5/30/2016.
 */
public class GuaranteeTest {
    @Test
    public void constant() throws Exception {
        Guarantee constant = Guarantee.constant(15);
        assertTrue(constant.equalsValue(15));
        assertTrue(constant.inequalsValue(0));
    }

    @Test
    public void plus() throws Exception {
        assertEquals(Guarantee.constant(0).plus(new Guarantee.Unknown(this, 1)), new Guarantee.Unknown(this, 1));
    }

    @Test
    public void conditional() throws Exception {
        Guarantee conditional = Guarantee.constant(10).conditional(Guarantee.constant(15), Guarantee.constant(0));
        assertTrue(conditional.equalsValue(15));
        assertEquals(new Guarantee.Unknown(this, 0).conditional(new Guarantee.Unknown(this, 1), new Guarantee.Unknown(this, 1)), new Guarantee.Unknown(this, 1));
    }

    @Test
    public void times() throws Exception {

    }

}