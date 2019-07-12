package net.consensys.wittgenstein.core.utils;

import java.lang.reflect.Field;

public class Strings {

  public static String toString(Object o) {
    StringBuilder sb = new StringBuilder();

    for (Field f : o.getClass().getDeclaredFields()) {
      try {
        f.setAccessible(true);
        String v = "" + f.get(o);
        if (sb.length() != 0) {
          sb.append(", ");
        }
        sb.append(f.getName()).append("=").append(v);
      } catch (IllegalAccessException ignore) {
      }
    }

    return o.getClass().getSimpleName() + "{" + sb.toString() + "}";
  }
}
