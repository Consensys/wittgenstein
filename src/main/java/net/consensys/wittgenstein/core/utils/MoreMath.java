package net.consensys.wittgenstein.core.utils;

public class MoreMath {

  public static int log2(int n) {
    if (n <= 0) {
      throw new IllegalArgumentException("n=" + n);
    }
    return 31 - Integer.numberOfLeadingZeros(n);
  }

  /** @return n rounded to the next power of 2. n itself if it's already rounded. */
  public static int roundPow2(int n) {
    int res = Integer.highestOneBit(n);
    if (res != n) {
      res = res << 1;
    }
    return res;
  }
}
