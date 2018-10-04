package net.consensys.wittgenstein.core.utils;

import java.util.Arrays;

public class Kademlia {

  /**
   * Calculates the XOR distance between two values. Taken from pantheon code.
   */
  public static int distance(byte[] v1b, byte[] v2b) {
    assert (v1b.length == v2b.length);

    if (Arrays.equals(v1b, v2b)) {
      return 0;
    }

    int distance = v1b.length * 8;
    for (int i = 0; i < v1b.length; i++) {
      byte xor = (byte) (0xff & (v1b[i] ^ v2b[i]));
      if (xor == 0) {
        distance -= 8;
      } else {
        int p = 7;
        while (((xor >> p--) & 0x01) == 0) {
          distance--;
        }
        break;
      }
    }
    return distance;
  }

}
