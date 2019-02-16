package net.consensys.wittgenstein.core;

import net.consensys.wittgenstein.tools.CSVLatencyReader;
import java.util.HashMap;
import java.util.Map;

public class RegistryNodeBuilders {
  private Map<String, NodeBuilder> registry = new HashMap<>();

  public static final String RANDOM_POSITION =
      NodeBuilder.NodeBuilderWithRandomPosition.class.getSimpleName() + "_constant_speed";
  public static final String AWS_SITE = "aws_sites_constant_speed";
  public static final String ALL_CITIES = "all_cities_constant_speed";

  public RegistryNodeBuilders() {
    CSVLatencyReader lr = new CSVLatencyReader();
    registry.put(RANDOM_POSITION, new NodeBuilder.NodeBuilderWithRandomPosition());
    registry.put(AWS_SITE,
        new NodeBuilder.NodeBuilderWithCity(NetworkLatency.AwsRegionNetworkLatency.cities()));
    registry.put(ALL_CITIES, new NodeBuilder.NodeBuilderWithCity(lr.cities()));
  }

  public NodeBuilder getByName(String name) {
    if (name == null) {
      name = NodeBuilder.NodeBuilderWithRandomPosition.class.getSimpleName();
    }

    NodeBuilder c = registry.get(name);
    if (c == null) {
      throw new IllegalArgumentException(name + " not in the registry (" + registry + ")");
    }
    return c.copy();
  }
}
