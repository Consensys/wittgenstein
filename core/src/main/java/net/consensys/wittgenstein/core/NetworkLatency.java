package net.consensys.wittgenstein.core;

import java.util.*;
import net.consensys.wittgenstein.core.utils.GeneralizedParetoDistribution;
import net.consensys.wittgenstein.tools.CSVLatencyReader;

/**
 * Latency is sometimes the round-trip-time (RTT) sometimes the time for a one-way trip. Here it's
 * the time for a one-way.
 */
@SuppressWarnings("WeakerAccess")
public abstract class NetworkLatency {
  /** @param delta - a random number between 0 & 99. Used to randomize the result */
  protected abstract int getExtendedLatency(Node from, Node to, int delta);

  @Override
  public String toString() {
    return this.getClass().getSimpleName();
  }

  protected void checkDelta(int delta) {
    if (delta < 0 || delta > 99) {
      throw new IllegalArgumentException("delta=" + delta);
    }
  }

  protected int getLatency(Node from, Node to, int delta) {
    if (from == to) {
      return 1;
    }
    int base = from.extraLatency + to.extraLatency;
    base += getExtendedLatency(from, to, delta);
    return Math.max(1, base);
  }

  /**
   * @see <a href="https://pdfs.semanticscholar.org/ff13/5d221678e6b542391c831e87fca56e830a73.pdf"/>
   *     Latency vs. distance: y = 0.022x + 4.862 It's a roundtrip, so we have to divide by two to
   *     get a single packet time.
   * @see <a href="https://www.jstage.jst.go.jp/article/ipsjjip/22/3/22_435/_pdf"/>variance with
   *     ARIMA
   * @see <a href="https://core.ac.uk/download/pdf/19439477.pdf"/>generalized Pareto distribution: -
   *     location μ [ms] = −0,3 - scale σ [ms] = 0.35 - shape ξ [-] = 1.4 "The constant delays ts
   *     and tp has been subtracted" "A single model isapplicable to all considered lengths of
   *     packets." It's for an ADSLv2+ system, so they don't take into account the distance. Again
   *     it's a roundtrip time.
   *     <p>
   */
  public static class NetworkLatencyByDistanceWJitter extends NetworkLatency {
    final GeneralizedParetoDistribution gpd = new GeneralizedParetoDistribution(1.4, -0.3, 0.35);

    /** We consider that the worse case is half of the earth perimeter. */
    private double distToMile(int dist) {
      final double earthPerimeter = 24_860;
      final double pointValue = (earthPerimeter / 2) / Node.MAX_DIST;
      return pointValue * dist;
    }

    public double getJitter(int delta) {
      return gpd.inverseF(delta / 100.0);
    }

    public double getFixedLatency(int dist) {
      return distToMile(dist) * 0.022 + 4.862;
    }

    @Override
    public int getExtendedLatency(Node from, Node to, int delta) {
      checkDelta(delta);
      double raw = getFixedLatency(from.dist(to)) + getJitter(delta);
      return (int) (raw / 2);
    }
  }

  /**
   * Latencies observed end of January 2019 between AWS region / nano systems See as well
   * https://www.cloudping.co : this web site gives the latest ping measured. Ping can vary a lot
   * other time. We include the variation from NetworkLatencyByDistance.
   *
   * <p>
   *
   * <p>Some data can be wrong sometimes. We set a minimum value of 1.
   *
   * @see NodeBuilder.NodeBuilderWithCity
   */
  public static class AwsRegionNetworkLatency extends NetworkLatency {
    private static HashMap<String, Integer> regionPerCity = new HashMap<>();
    private NetworkLatencyByDistanceWJitter var = new NetworkLatencyByDistanceWJitter();

    static {
      regionPerCity.put("Oregon", 0);
      regionPerCity.put("Virginia", 1);
      regionPerCity.put("Mumbai", 2);
      regionPerCity.put("Seoul", 3);
      regionPerCity.put("Singapore", 4);
      regionPerCity.put("Sydney", 5);
      regionPerCity.put("Tokyo", 6);
      regionPerCity.put("Canada central", 7);
      regionPerCity.put("Frankfurt", 8);
      regionPerCity.put("Ireland", 9);
      regionPerCity.put("London", 10);
    }

    public static List<String> cities() {
      // We need to sort to ensure test repeatability
      List<String> cities = new ArrayList<>(regionPerCity.keySet());
      Collections.sort(cities);
      return cities;
    }

    // That's ping time, we will need to divide by two to get the oneway latency.
    private int[][] latencies =
        new int[][] {
          new int[] {0, 81, 216, 126, 165, 138, 97, 64, 164, 131, 141}, // Oregon
          new int[] {
            0, 0, 182, 181, 232, 195, 167, 13, 88, 80, 75,
          }, // Virginia
          new int[] {0, 0, 0, 152, 62, 223, 123, 194, 111, 122, 113}, // Mumbai
          new int[] {0, 0, 0, 0, 97, 133, 35, 184, 259, 254, 264}, // Seoul
          new int[] {0, 0, 0, 0, 0, 169, 69, 218, 162, 174, 171}, // Singapore
          new int[] {0, 0, 0, 0, 0, 0, 105, 210, 282, 269, 271}, // Sydney
          new int[] {0, 0, 0, 0, 0, 0, 0, 156, 235, 222, 234}, // Tokyo
          new int[] {
            0, 0, 0, 0, 0, 0, 0, 0, 101, 78, 87,
          }, // Canada central
          new int[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 24, 13}, // Frankfurt
          new int[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 12}, // Ireland
        };

    private int getLatency(int reg1, int reg2, int delta) {
      if (reg1 == reg2) {
        // It's the same datacenter. 1 is pessimistic actually (0.5 would be better)
        //  but we can't do less.
        return 1;
      }
      int minReg = Math.min(reg1, reg2);
      int maxReg = Math.max(reg1, reg2);

      return Math.max(1, latencies[minReg][maxReg] / 2 + (int) var.getJitter(delta));
    }

    public int getExtendedLatency(Node from, Node to, int delta) {
      Integer reg1 = regionPerCity.get(from.cityName);
      Integer reg2 = regionPerCity.get(to.cityName);

      if (reg1 == null || reg2 == null) {
        throw new IllegalArgumentException(from + " or " + to + " not in our aws cities list");
      }

      return getLatency(reg1, reg2, delta);
    }
  }

  /**
   * Network latency depending on this city, taken from https://wondernetwork.com/ Latency between
   * two nodes in the same city is considered to be 1 (that's a little bit more than what you have
   * if you're in the same datacenter)
   */
  public static class NetworkLatencyByCity extends NetworkLatency {
    private final Map<String, Map<String, Float>> latencyMatrix;

    public NetworkLatencyByCity() {
      CSVLatencyReader csvLatencyReader = new CSVLatencyReader();
      this.latencyMatrix = csvLatencyReader.getLatencyMatrix();
    }

    public int getExtendedLatency(Node from, Node to, int delta) {
      if (from.nodeId == to.nodeId) {
        return 1;
      }

      String cityFrom = from.cityName;
      String cityTo = to.cityName;

      if (cityFrom.equals(Node.DEFAULT_CITY) || cityTo.equals(Node.DEFAULT_CITY)) {
        throw new IllegalStateException(
            "Can't use NetworkLatencyByCity model with default city location");
      }

      return Math.max(1, Math.round(0.5f * getLatency(cityFrom, cityTo)));
    }

    protected float getLatency(String cityFrom, String cityTo) {
      Map<String, Float> from = latencyMatrix.get(cityFrom);
      if (from == null) {
        throw new IllegalArgumentException("Can't find latencies for " + cityFrom);
      }
      Float res = from.get(cityTo);
      if (res == null) {
        res = latencyMatrix.get(cityTo).get(cityFrom);
      }
      return res;
    }
  }

  /**
   * Takes into account this cities from wondernetwork but as well add the link dependency
   * Round-trip inside a city is approximated to 10ms.
   */
  public static class NetworkLatencyByCityWJitter extends NetworkLatencyByCity {
    final GeneralizedParetoDistribution gpd = new GeneralizedParetoDistribution(1.4, -0.3, 0.35);

    public NetworkLatencyByCityWJitter() {}

    private double getJitter(int delta) {
      return gpd.inverseF(delta / 100.0);
    }

    @Override
    public int getExtendedLatency(Node from, Node to, int delta) {
      if (from.nodeId == to.nodeId) {
        return 1;
      }

      String cityFrom = from.cityName;
      String cityTo = to.cityName;
      if (cityFrom.equals(Node.DEFAULT_CITY) || cityTo.equals(Node.DEFAULT_CITY)) {
        throw new IllegalStateException(
            "Can't use NetworkLatencyByCity model with default city location");
      }

      double raw = getJitter(delta);
      if (cityFrom.equals(cityTo)) {
        // Latency inside a city depends on many factor. This is a reasonable approximation,
        //  maybe on the pessimistic side.
        raw += 10;
      } else {
        raw += getLatency(cityFrom, cityTo);
      }

      return Math.max(1, (int) Math.round(0.5 * raw));
    }
  }

  public static class NetworkFixedLatency extends NetworkLatency {
    final int fixedLatency;

    public NetworkFixedLatency(int fixedLatency) {
      this.fixedLatency = Math.max(1, fixedLatency);
    }

    public int getExtendedLatency(Node from, Node to, int delta) {
      return fixedLatency;
    }

    public String toString() {
      return "fixedLatency:" + fixedLatency;
    }
  }

  /**
   * A latency following the uniform law. Will never happen in practice but useful to analyse some
   * protocols under simple conditions.
   */
  public static class NetworkUniformLatency extends NetworkLatency {
    final int maxLatency;

    public NetworkUniformLatency(int maxLatency) {
      this.maxLatency = Math.max(1, maxLatency);
    }

    public int getExtendedLatency(Node from, Node to, int delta) {
      return (int) ((delta / 99.0) * maxLatency);
    }

    public String toString() {
      return "NetworkUniformLatency:" + maxLatency;
    }
  }

  public static class NetworkNoLatency extends NetworkLatency {
    public int getExtendedLatency(Node from, Node to, int delta) {
      return 1;
    }
  }

  public static class MeasuredNetworkLatency extends NetworkLatency {
    final int[] longDistrib = new int[100];

    /**
     * @param proportions - the proportions in percentage. Must sumup to 100
     * @param values - the value for the corresponding index in the proportion table.
     */
    private void setLatency(int[] proportions, int[] values) {
      int li = 0;
      int cur = 0;
      int sum = 0;
      for (int i = 0; i < proportions.length; i++) {
        if (proportions[i] == 0) {
          cur = values[i];
          continue;
        }
        sum += proportions[i];
        int step = (values[i] - cur) / proportions[i];
        for (int ii = 0; ii < proportions[i]; ii++) {
          cur += step;
          longDistrib[li++] = cur;
        }
      }

      if (sum != 100) throw new IllegalArgumentException();
      if (li != 100) throw new IllegalArgumentException();
    }

    public MeasuredNetworkLatency(int[] distribProp, int[] distribVal) {
      setLatency(distribProp, distribVal);
    }

    @Override
    public int getExtendedLatency(Node from, Node to, int delta) {
      checkDelta(delta);
      return longDistrib[delta];
    }

    /**
     * Print the latency distribution: - the first 50ms, 10ms by 10ms - then, until 500ms: each
     * 100ms - then each second
     */
    @Override
    public String toString() {
      StringBuilder sb =
          new StringBuilder("MeasuredNetworkLatency latency: time to receive a message:\n");

      int cur = 0;
      for (int ms :
          new int[] {
            20, 40, 60, 80, 100, 150, 200, 300, 400, 500, 1000, 2000, 3000, 50000, 10000, 20000
          }) {
        int size = 0;
        boolean print = false;
        while (cur < longDistrib.length && longDistrib[cur] < ms) {
          size++;
          cur++;
          print = true;
        }
        if (print) {
          sb.append(ms).append("ms ").append(size).append("%, cumulative ");
          sb.append(cur).append("%\n");
        }
      }

      for (int s = 1; cur < 100; s++) {
        int size = 0;
        while (cur < longDistrib.length && longDistrib[cur] < s * 1000) {
          size++;
          cur++;
        }
        sb.append(s)
            .append(" second")
            .append(s > 1 ? "s: " : ": ")
            .append(size)
            .append("%, cumulative ");

        sb.append(cur).append("%\n");
      }

      return sb.toString();
    }
  }

  /**
   * Distribution taken from: https://ethstats.net/ It should be read like this: 16% of the messages
   * will be received in 250ms or less These figures are for blocks, so they represent a worse case.
   * As well, some of the nodes may be dead.
   */
  public static class EthScanNetworkLatency extends NetworkLatency {
    public static final int[] distribProp = {16, 18, 17, 12, 8, 5, 4, 3, 3, 1, 1, 2, 1, 1, 8};
    public static final int[] distribVal = {
      250, 500, 1000, 1250, 1500, 1750, 2000, 2250, 2500, 2750, 4500, 6000, 8500, 9750, 10000
    };
    final MeasuredNetworkLatency networkLatency =
        new NetworkLatency.MeasuredNetworkLatency(distribProp, distribVal);

    @Override
    public int getExtendedLatency(Node from, Node to, int delta) {
      return networkLatency.getLatency(from, to, delta);
    }

    @Override
    public String toString() {
      return "EthScanNetworkLatency{" + "networkLatency=" + networkLatency + '}';
    }
  }

  /**
   * Taken from https://fc18.ifca.ai/preproceedings/75.pdf (Decentralization in Bitcoin and Ethereum
   * Networks) All authors are attached to the Initiative for Cryptocurrencies and Contracts (IC3),
   * hence the name.
   *
   * <p>The paper gives these numbers (proportion, milliseconds): 10% 92 33% 125 50% 152 67% 200 90%
   * 276
   *
   * <p>The paper does not give any number on latency variation. As well it's unclear if it's a RTT
   * or a single message latency. As they explain they used 'ping' to calculate the time, and as
   * ping gives the RTT time, we consider that's what they measured.
   *
   * <p>This latency should only be used with full random position.
   */
  public static class IC3NetworkLatency extends NetworkLatency {
    protected static final int S10 = 92;
    protected static final int SW = 350;

    @Override
    public int getExtendedLatency(Node from, Node to, int delta) {
      double dist = from.dist(to);
      double surface = dist * dist * Math.PI;
      double totalSurface = Node.MAX_X * Node.MAX_Y;
      int position = (int) ((surface * 100) / totalSurface);

      if (position <= 10) return S10 / 2;
      if (position <= 33) return 125 / 2;
      if (position <= 50) return 152 / 2;
      if (position <= 67) return 200 / 2;
      if (position <= 90) return 276 / 2;
      return SW / 2; // The table in the paper does not show any number
    }
  }

  private static void addToStats(int lat, int[] props, int[] vals) {
    int p = 0;
    while (p < props.length - 1 && vals[p] < lat) {
      p++;
    }
    props[p]++;
  }

  interface PeerGetter {
    /** @return a peer of node n that should be used to measure the latency */
    Node peer(Node n);
  }

  public static MeasuredNetworkLatency estimateLatency(Network net, final int rounds) {
    return estimateLatency(
        net,
        n -> {
          Random rd = new Random(0);
          Node res = n;
          while (res == n) {
            res = (Node) net.allNodes.get(rd.nextInt(net.allNodes.size()));
          }
          return res;
        },
        rounds);
  }

  /** Estimation for a p2p network, we only look at direct peers */
  public static MeasuredNetworkLatency estimateP2PLatency(P2PNetwork<?> net, final int rounds) {
    return estimateLatency(
        net,
        n -> {
          Random rd = new Random(0);
          P2PNode<?> pn = (P2PNode) n;
          Node res = n;
          while (res == n) {
            res = pn.peers.get(rd.nextInt(pn.peers.size()));
          }
          return res;
        },
        rounds);
  }

  /**
   * Measure the latency and return the distribution found
   *
   * @param rounds - the number of messages to send, should be more than 100K
   */
  public static MeasuredNetworkLatency estimateLatency(
      Network net, PeerGetter pg, final int rounds) {
    int[] props = new int[50];
    int[] vals = new int[50];

    int pos = 0;
    for (int i = 10; i <= 200; i += 10) vals[pos++] = i;
    for (int i = 300; i <= 2000; i += 100) vals[pos++] = i;
    while (pos < vals.length) vals[pos] = vals[pos++ - 1] + 1000;

    int nodeCt = net.allNodes.size();
    int roundsCt = rounds;
    while (roundsCt > 0) {
      Node n1 = (Node) net.allNodes.get(net.rd.nextInt(nodeCt));
      Node n2 = pg.peer(n1);
      if (n1 != n2) {
        roundsCt--;
        int delay = net.networkLatency.getLatency(n1, n2, net.rd.nextInt(100));
        addToStats(delay, props, vals);
      }
    }
    int tot = 0;
    for (int i = 0; i < props.length; i++) {
      props[i] = Math.round((100f * props[i]) / rounds);
      tot += props[i];
    }
    while (tot != 100) {
      int gap = 100 - tot;
      tot = 0;
      for (int i = 0; i < props.length; i++) {
        if (gap > 0 && props[i] > 0) {
          props[i]++;
          gap--;
        } else if (gap < 0 && props[i] > 1) {
          props[i]--;
          gap++;
        }
        tot += props[i];
      }
    }

    return new MeasuredNetworkLatency(props, vals);
  }
}
