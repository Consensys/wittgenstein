package net.consensys.wittgenstein.core;

import java.lang.reflect.Constructor;

public class RegistryNetworkLatencies {

  public static RegistryNetworkLatencies singleton = new RegistryNetworkLatencies();


  public RegistryNetworkLatencies() {}

  public NetworkLatency getByName(String name) {
    if (name == null) {
      name = "NetworkLatencyByDistance";
    }

    String ref = NetworkLatency.NetworkLatencyByDistance.class.getName();
    String cut = ref.substring(0, ref.length() - "NetworkLatencyByDistance".length());
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
