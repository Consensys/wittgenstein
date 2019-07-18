package net.consensys.wittgenstein.core.utils;

import java.util.BitSet;

public class BitSetUtils {

  /** @return true if the 'small' bitset is included in the big one. */
  public static boolean include(BitSet big, BitSet small) {
    BitSet b = (BitSet) small.clone();
    b.or(big);

    return b.equals(big);
  }
}
