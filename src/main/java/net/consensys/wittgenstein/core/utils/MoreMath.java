package net.consensys.wittgenstein.core.utils;

public class MoreMath {

  public static int log2(int n) {
    if (n <= 0) {
      throw new IllegalArgumentException();
    }
    return 31 - Integer.numberOfLeadingZeros(n);
  }

}
