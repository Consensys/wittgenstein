package net.consensys.wittgenstein.core;

import net.consensys.wittgenstein.tools.CSVLatencyReader;
import java.util.HashMap;
import java.util.Map;

@SuppressWarnings("WeakerAccess")
public class RegistryNodeBuilders {
  private Map<String, NodeBuilder> registry = new HashMap<>();

  public static final String RANDOM_POSITION =
      NodeBuilder.NodeBuilderWithRandomPosition.class.getSimpleName() + "_constant_speed";
  public static final String AWS_SITE = "aws_sites_constant_speed";
  public static final String AWS_WITH_1THIRD_TOR = "aws_with_1third_tor";
  public static final String AWS_WITH_HALF_TOR = "aws_with_half_tor";
  public static final String ALL_CITIES = "all_cities_constant_speed";

  public RegistryNodeBuilders() {
    CSVLatencyReader lr = new CSVLatencyReader();
    registry.put(RANDOM_POSITION, new NodeBuilder.NodeBuilderWithRandomPosition());
    registry.put(AWS_SITE,
        new NodeBuilder.NodeBuilderWithCity(NetworkLatency.AwsRegionNetworkLatency.cities()));
    registry.put(ALL_CITIES, new NodeBuilder.NodeBuilderWithCity(lr.cities()));

    NodeBuilder nb =
        new NodeBuilder.NodeBuilderWithCity(NetworkLatency.AwsRegionNetworkLatency.cities());
    nb.aspects.add(new Node.ExtraLatencyAspect(.33));
    registry.put(AWS_WITH_1THIRD_TOR, nb);


    nb = new NodeBuilder.NodeBuilderWithCity(NetworkLatency.AwsRegionNetworkLatency.cities());
    nb.aspects.add(new Node.ExtraLatencyAspect(.5));
    registry.put(AWS_WITH_HALF_TOR, nb);

  }

  public NodeBuilder getByName(String name) {
    if (name == null || name.trim().isEmpty()) {
      name = RANDOM_POSITION;
    }

    NodeBuilder c = registry.get(name);
    if (c == null) {
      throw new IllegalArgumentException(name + " not in the registry (" + registry + ")");
    }
    return c.copy();
  }
}
