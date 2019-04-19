package net.consensys.wittgenstein.core.utils;

import java.util.Random;

public class Utils {

  public static void shuffle(Object[] arr, Random rnd) {
    int size = arr.length;
    for (int i = size; i > 1; i--) {
      swap(arr, i - 1, rnd.nextInt(i));
    }
  }

  private static void swap(Object[] arr, int i, int j) {
    Object tmp = arr[i];
    arr[i] = arr[j];
    arr[j] = tmp;
  }
}
