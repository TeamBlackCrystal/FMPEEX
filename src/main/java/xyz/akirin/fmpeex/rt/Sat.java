package xyz.akirin.fmpeex.rt;

/**
 * Runtime helper called from patched code where a {@code long} has to be handed to code that only takes {@code int}.
 */
public final class Sat {

    private Sat() {}

    /** Narrows to {@code int}, clamping instead of wrapping around. */
    public static int toInt(long value) {
        if (value > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        if (value < Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        return (int) value;
    }
}
