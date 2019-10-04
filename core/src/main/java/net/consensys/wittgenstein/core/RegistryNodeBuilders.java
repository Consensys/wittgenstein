package net.consensys.wittgenstein.core;

import java.util.HashMap;
import java.util.Map;
import net.consensys.wittgenstein.core.geoinfo.Geo;
import net.consensys.wittgenstein.core.geoinfo.GeoAWS;
import net.consensys.wittgenstein.core.geoinfo.GeoAllCities;
import net.consensys.wittgenstein.tools.CSVLatencyReader;

public class RegistryNodeBuilders {
  private Map<String, NodeBuilder> registry = new HashMap<>();

  public enum Location {
    AWS,
    CITIES,
    RANDOM
  }

  public static RegistryNodeBuilders singleton = new RegistryNodeBuilders();

  public static String name(Location loc, boolean speedConstant, double tor) {
    String site = loc == Location.AWS ? "AWS" : loc == Location.RANDOM ? "RANDOM" : "CITIES";
    String speed = speedConstant ? "CONSTANT" : "GAUSSIAN";
    return (site + "_speed=" + speed + "_tor=" + (tor + "000").substring(0, 4)).toUpperCase();
  }

  public static Double[] tor() {
    return new Double[] {0.0, 0.01, 0.10, 0.20, .33, .5, .6, .8, 1.0};
  }

  public static Location[] locations() {
    return new Location[] {Location.AWS, Location.CITIES, Location.RANDOM};
  }

  private RegistryNodeBuilders() {
    CSVLatencyReader lr = new CSVLatencyReader();
    Geo geoAWS = new GeoAWS();
    Geo geoAllCities = new GeoAllCities();

    for (Location loc : locations()) {
      for (boolean speedConstant : new Boolean[] {true, false}) {
        for (double tor : tor()) {
          NodeBuilder nb;
          switch (loc) {
            case AWS:
              nb =
                  new NodeBuilder.NodeBuilderWithCity(
                      NetworkLatency.AwsRegionNetworkLatency.cities(), geoAWS);
              break;
            case CITIES:
              nb = new NodeBuilder.NodeBuilderWithCity(lr.cities(), geoAllCities);
              break;
            case RANDOM:
              nb = new NodeBuilder.NodeBuilderWithRandomPosition();
              break;
            default:
              throw new IllegalStateException();
          }
          if (!speedConstant) {
            nb.aspects.add(new Node.SpeedRatioAspect(new Node.UniformSpeed()));
          }
          if (tor > 0.001) {
            nb.aspects.add(new Node.ExtraLatencyAspect(tor));
          }
          registry.put(name(loc, speedConstant, tor), nb);
        }
      }
    }
  }

  public NodeBuilder getByName(String name) {
    if (name == null || name.trim().isEmpty()) {
      name = name(Location.RANDOM, true, 0);
    }

    NodeBuilder c = registry.get(name);
    if (c == null) {
      throw new IllegalArgumentException(name + " not in the registry (" + registry + ")");
    }
    return c.copy();
  }
}
