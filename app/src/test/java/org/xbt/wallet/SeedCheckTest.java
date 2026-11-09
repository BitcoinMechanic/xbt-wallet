package org.xbt.wallet;
import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;
public class SeedCheckTest {
    @Test public void checksEveryWordInOrder() {
        assertTrue(SeedCheck.matches("alpha beta gamma", Arrays.asList("Alpha", " beta ", "gamma")));
        assertFalse(SeedCheck.matches("alpha beta gamma", Arrays.asList("beta", "alpha", "gamma")));
        assertFalse(SeedCheck.matches("alpha beta gamma", Arrays.asList("alpha", "beta")));
        assertFalse(SeedCheck.matches("alpha beta gamma", Arrays.asList("alpha", "", "gamma")));
        assertFalse(SeedCheck.matches(null, Arrays.asList("alpha")));
    }
}
