package net.consensys.wittgenstein.core;

import static net.consensys.wittgenstein.core.Node.MAX_X;
import static net.consensys.wittgenstein.core.Node.MAX_Y;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import net.consensys.wittgenstein.core.geoinfo.CityInfo;
import net.consensys.wittgenstein.core.geoinfo.Geo;

@SuppressWarnings("WeakerAccess")
public class NodeBuilder implements Cloneable {
  /** Last node id allocated. */
  private int nodeIds = 0;
  /** Used to calculate a hash */
  private final MessageDigest digest;
  /** List of the aspects we can add to the node (speed, latency, ...) */
  public final List<Node.Aspect> aspects = new ArrayList<>();

  /** Unique reference shared by all nodes when they need to allocate a unique id. */
  private final AtomicInteger uIntId = new AtomicInteger();

  public NodeBuilder() {
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException();
    }
  }

  /**
   * Same node builder with the node ids reset to zero, allowing to construct another network with
   * the same parameters.
   */
  public NodeBuilder copy() {
    try {
      NodeBuilder nb = (NodeBuilder) this.clone();
      nb.nodeIds = 0;
      return nb;
    } catch (CloneNotSupportedException e) {
      throw new IllegalStateException(e);
    }
  }

  int allocateNodeId() {
    return nodeIds++;
  }

  protected int getX(int rdInt) {
    return 1;
  }

  protected int getY(int rdInt) {
    return 1;
  }

  protected String getCityName(int rdInt) {
    return Node.DEFAULT_CITY;
  }

  /** Many protocols wants a hash of the node id. */
  protected byte[] getHash(int nodeId) {
    return digest.digest(ByteBuffer.allocate(4).putInt(nodeId).array());
  }

  public AtomicInteger getUniqueIntIdReference() {
    return uIntId;
  }

  public static class NodeBuilderWithRandomPosition extends NodeBuilder {

    public NodeBuilderWithRandomPosition() {}

    @Override
    public int getX(int rdInt) {
      // Math abs can return a negative value so we use long to save us
      // https://stackoverflow.com/questions/5444611/math-abs-returns-wrong-value-for-integer-min-value
      long r = rdInt >> 16;
      r = Math.abs(r);
      return (int) (r % MAX_X + 1);
    }

    @Override
    public int getY(int rdInt) {
      long r = rdInt << 16;
      r = Math.abs(r);
      return (int) (r % MAX_Y + 1);
    }
  }

  public static class NodeBuilderWithCity extends NodeBuilder {
    final List<String> cities;
    final Map<String, CityInfo> citiesInfo;

    public NodeBuilderWithCity(List<String> cities, Geo geoInfo) {
      this.cities = cities.stream().map(String::toUpperCase).collect(Collectors.toList());

      citiesInfo =
          geoInfo.citiesPosition().entrySet().stream()
              .filter(x -> this.cities.contains(x.getKey().toUpperCase()))
              .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    @Override
    protected String getCityName(int rdInt) {
      return getRandomCityInfo(rdInt);
    }

    public Map<String, CityInfo> getCitiesInfo() {
      return this.citiesInfo;
    }

    int[] getPos(int rdInt) {
      String city = getCityName(rdInt);
      CityInfo cityInfo = citiesInfo.get(city);
      return Objects.requireNonNullElseGet(
          new int[] {cityInfo.mercX, cityInfo.mercY}, () -> new int[] {1, 1});
    }

    // Weighted Random Selection algorithm
    private String getRandomCityInfo(int rdInt) {
      int size = cities.size();
      int rand = Math.abs(rdInt) % size;
      float p = (float) rand / size;

      for (Map.Entry<String, CityInfo> cityInfo : citiesInfo.entrySet()) {
        if (p <= cityInfo.getValue().cumulativeProbability) {
          return cityInfo.getKey();
        }
      }
      return null;
    }

    protected int getX(int rdInt) {
      return getPos(rdInt)[0];
    }

    protected int getY(int rdInt) {
      return getPos(rdInt)[1];
    }
  }
}
