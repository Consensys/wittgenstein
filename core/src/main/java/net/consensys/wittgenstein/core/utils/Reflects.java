package net.consensys.wittgenstein.core.utils;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;

@SuppressWarnings({"unchecked", "WeakerAccess"})
public class Reflects {

  /** Create an object with dummy values if there is no default constructor. */
  public static <T> T newInstance(Class<T> cls) {
    Constructor<T> c = (Constructor<T>) cls.getConstructors()[0];

    List<Object> params = new ArrayList<>();
    for (Class<?> pType : c.getParameterTypes()) {
      params.add(getDefaultValue(pType));
    }

    try {
      return c.newInstance(params.toArray());
    } catch (Exception e) {
      throw new IllegalArgumentException(e);
    }
  }

  public static <T> T getDefaultValue(Class<T> clazz) {
    return (T) Array.get(Array.newInstance(clazz, 1), 0);
  }

  public static Class<?> forName(String fullName) {
    if (fullName == null || fullName.isEmpty()) {
      throw new IllegalArgumentException("class name is null or empty");
    }
    try {
      return Class.forName(fullName);
    } catch (ClassNotFoundException e) {
      throw new IllegalArgumentException(e);
    }
  }

  public static <T> T newInstance(Constructor<T> constructor, Object... args) {
    try {
      return constructor.newInstance(args);
    } catch (Exception e) {
      throw new IllegalArgumentException(
          "Constructor not valid " + constructor + " error message" + e);
    }
  }

  /*
  public static List<Package> wittgensteinPackages(){
    List<Package> res = new ArrayList<>();
    Reflects.class.getClassLoader().getDefinedPackages()
  }*/
}
