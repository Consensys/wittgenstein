package net.consensys.wittgenstein.core;

import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.Map;

public class RegistryNetworkLatencies {
  private Map<String, NetworkLatency> registry = new HashMap<>();

  public static RegistryNetworkLatencies singleton = new RegistryNetworkLatencies();

  public enum Type {
    FIXED,
    UNIFORM
  }

  public static String name(Type t, int fixed) {
    switch (t) {
      case FIXED:
        return NetworkLatency.NetworkFixedLatency.class.getSimpleName() + "(" + fixed + ")";
      case UNIFORM:
        return NetworkLatency.NetworkUniformLatency.class.getSimpleName() + "(" + fixed + ")";
    }
    throw new IllegalStateException();
  }

  public RegistryNetworkLatencies() {
    for (int f : new int[] {0, 100, 200, 500, 1000, 2000, 4000, 8000}) {
      registry.put(name(Type.FIXED, f), new NetworkLatency.NetworkFixedLatency(f));
      registry.put(name(Type.UNIFORM, f), new NetworkLatency.NetworkUniformLatency(f));
    }
  }

  public NetworkLatency getByName(String name) {
    if (name == null) {
      name = NetworkLatency.NetworkLatencyByDistanceWJitter.class.getSimpleName();
    }

    NetworkLatency nl = registry.get(name);
    if (nl != null) {
      return nl;
    }

    String ref = NetworkLatency.NetworkLatencyByDistanceWJitter.class.getName();
    String cut =
        ref.substring(
            0,
            ref.length()
                - NetworkLatency.NetworkLatencyByDistanceWJitter.class.getSimpleName().length());
    String full = cut + name;
    try {
      Class<?> cn = Class.forName(full);
      Constructor<?> c = cn.getConstructor();
      return (NetworkLatency) c.newInstance();
    } catch (Exception e) {
      throw new IllegalArgumentException(e);
    }
  }
}
