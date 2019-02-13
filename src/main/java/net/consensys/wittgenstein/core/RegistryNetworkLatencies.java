package net.consensys.wittgenstein.core;

public class RegistryNetworkLatencies {

  public NetworkLatency getByName(String name) {
    if (name == null) {
      name = "NetworkLatencyByDistance";
    }

    String ref = NetworkLatency.NetworkLatencyByDistance.class.getName();
    String cut = ref.substring(0, ref.length() - "NetworkLatencyByDistance".length());
    String full = cut + name;
    try {
      Class<?> cn = Class.forName(full);
      return (NetworkLatency) cn.newInstance();
    } catch (Exception e) {
      throw new IllegalArgumentException(e);
    }
  }
}
