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


  public static RegistryNodeBuilders singleton = new RegistryNodeBuilders();

  public static String name(boolean aws, boolean speedConstant, double tor) {
    String site = aws ? "AWS" : "RANDOM";
    String speed = speedConstant ? "CONSTANT" : "GAUSSIAN";

    return (site + "_speed=" + speed + "_tor=" + (tor + "000").substring(0, 4)).toUpperCase();
  }

  public static Double[] tor() {
    return new Double[] {0.0, 0.10, 0.20, .33, .5, .6, .8};
  }

  private RegistryNodeBuilders() {
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

    for (boolean aws : new Boolean[] {true, false}) {
      for (boolean speedConstant : new Boolean[] {true, false}) {
        for (double tor : tor()) {
          if (aws) {
            nb = new NodeBuilder.NodeBuilderWithCity(
                NetworkLatency.AwsRegionNetworkLatency.cities());
          } else {
            nb = new NodeBuilder.NodeBuilderWithRandomPosition();
          }
          if (!speedConstant) {
            nb.aspects.add(new Node.SpeedRatioAspect(new Node.GaussianSpeed()));
          }
          if (tor > 0.001) {
            nb.aspects.add(new Node.ExtraLatencyAspect(tor));
          }
          registry.put(name(aws, speedConstant, tor), nb);
        }
      }
    }
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
