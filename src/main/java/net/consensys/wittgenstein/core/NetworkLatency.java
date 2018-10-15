package net.consensys.wittgenstein.core;

import net.consensys.wittgenstein.core.utils.GeneralizedParetoDistribution;
import java.util.Random;

@SuppressWarnings("WeakerAccess")
public abstract class NetworkLatency {
  /**
   * @param delta - a random number between 0 & 99. Used to randomize the result
   */
  public abstract int getLatency(Node from, Node to, int delta);

  @Override
  public String toString() {
    return this.getClass().getSimpleName();
  }

  protected void checkDelta(int delta) {
    if (delta < 0 || delta > 99) {
      throw new IllegalArgumentException("delta=" + delta);
    }
  }

  /**
   * @see <a href="https://pdfs.semanticscholar.org/ff13/5d221678e6b542391c831e87fca56e830a73.pdf"/>
   *      Latency vs. distance: y = 0.022x + 4.862
   *      </p>
   * @see <a href="https://www.jstage.jst.go.jp/article/ipsjjip/22/3/22_435/_pdf"/> variance with
   *      ARIMA
   *      </p>
   * @see <a href="https://core.ac.uk/download/pdf/19439477.pdf"/> generalized Pareto distribution:
   *      - location μ [ms] = −0,3 - scale σ [ms] = 0.35 - shape ξ [-] = 1.4
   *      "The constant delays ts and tp has been subtracted"
   *      "A single model isapplicable to all considered lengths of packets." It's for an ADSLv2+
   *      system, so they don't take into account the distance.
   *      <p>
   *      Here are the results by quantile, not that great in our case. TODO: This does not seem to
   *      match our case. GPD could be good as a model, but we need other data to have the right
   *      model
   *      <p>
   *      0 -0.3 1 -0.29645751870684156 2 -0.2928281063750007 3 -0.2891087096885191 4
   *      -0.28529613509832147 5 -0.28138704083443744 [...] 38 -0.06180689273798645 39
   *      -0.05056584458776098 40 -0.03887366470983794 41 -0.026704268331683878 42
   *      -0.014029585091834773 43 -8.193702810404546E-4 44 0.012959005564730341 45
   *      0.027340791642455564 46 0.04236411981076765 47 0.058070299386147994 [...] 81
   *      2.0067305885282534 82 2.2077729124238217 83 2.43752479733014 84 2.7021609664135657 85
   *      3.009690552818672 86 3.370674235256015 87 3.7992995050751803 88 4.315038306782648 89
   *      4.94528535995752 90 5.729716078773951 91 6.7278063481168795 92 8.032504244579048 93
   *      9.796721337967568 94 12.288913064025722 95 16.02227008669991 96 22.09936448992795 97
   *      33.332094707414804 98 59.22203123687722 99 157.18933612004804
   *
   *      <p>
   *      TODO: we could take the message size into account....
   */
  public static class NetworkLatencyByDistance extends NetworkLatency {
    final GeneralizedParetoDistribution gpd = new GeneralizedParetoDistribution(1.4, -0.3, 0.35);

    /**
     * We consider that the worse case is half of the earth perimeter.
     */
    private double distToMile(int dist) {
      final double earthPerimeter = 24_860;
      double pointValue = (earthPerimeter / 2) / Node.MAX_DIST;
      return pointValue * dist;
    }

    public double getVariableLatency(int delta) {
      return gpd.inverseF(delta / 100.0);
    }

    public double getFixedLatency(int dist) {
      return distToMile(dist) * 0.022 + 4.862;
    }

    @Override
    public int getLatency(Node from, Node to, int delta) {
      checkDelta(delta);
      double raw = getFixedLatency(from.dist(to)) + getVariableLatency(delta);

      return (int) raw;
    }
  }

  public static class NetworkNoLatency extends NetworkLatency {
    public int getLatency(Node from, Node to, int delta) {
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
          continue; // todo
        }
        sum += proportions[i];
        int step = (values[i] - cur) / proportions[i];
        for (int ii = 0; ii < proportions[i]; ii++) {
          cur += step;
          longDistrib[li++] = cur;
        }
      }

      if (sum != 100)
        throw new IllegalArgumentException();
      if (li != 100)
        throw new IllegalArgumentException();
    }


    public MeasuredNetworkLatency(int[] distribProp, int[] distribVal) {
      setLatency(distribProp, distribVal);
    }

    @Override
    public int getLatency(Node from, Node to, int delta) {
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
          new StringBuilder("BlockChainNetwork latency: time to receive a message:\n");

      int cur = 0;
      for (int ms : new int[] {20, 40, 60, 80, 100, 150, 200, 300, 400, 500, 1000, 2000, 3000,
          50000, 10000, 20000}) {
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
        sb.append(s).append(" second").append(s > 1 ? "s: " : ": ").append(size).append(
            "%, cumulative ");

        sb.append(cur).append("%\n");
      }

      return sb.toString();
    }
  }

  public static class EthScanNetworkLatency extends NetworkLatency {
    /**
     * Distribution taken from: https://ethstats.net/ It should be read like this: 16% of the
     * messages will be received in 250ms or less
     * </p>
     * These figures are for blocks, so they represent a worse case. As well, some of the nodes may
     * be dead.
     */
    public static final int[] distribProp = {16, 18, 17, 12, 8, 5, 4, 3, 3, 1, 1, 2, 1, 1, 8};
    public static final int[] distribVal =
        {250, 500, 1000, 1250, 1500, 1750, 2000, 2250, 2500, 2750, 4500, 6000, 8500, 9750, 10000};
    final MeasuredNetworkLatency networkLatency =
        new NetworkLatency.MeasuredNetworkLatency(distribProp, distribVal);

    @Override
    public int getLatency(Node from, Node to, int delta) {
      return networkLatency.getLatency(from, to, delta);
    }

    @Override
    public String toString() {
      return "EthScanNetworkLatency{" + "networkLatency=" + networkLatency + '}';
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
    /**
     * @return a peer of node n that should be used to measure the latency
     */
    Node peer(Node n);
  }

  public static MeasuredNetworkLatency estimateLatency(Network net, final int rounds) {
    return estimateLatency(net, n -> {
      Random rd = new Random(0);
      Node res = n;
      while (res == n) {
        res = (Node) net.allNodes.get(rd.nextInt(net.allNodes.size()));
      }
      return res;
    }, rounds);
  }

  /**
   * Estimation for a p2p network, we only look at direct peers
   */
  public static MeasuredNetworkLatency estimateP2PLatency(P2PNetwork net, final int rounds) {
    return estimateLatency(net, n -> {
      Random rd = new Random(0);
      P2PNode pn = (P2PNode) n;
      Node res = n;
      while (res == n) {
        res = pn.peers.get(rd.nextInt(pn.peers.size()));
      }
      return res;
    }, rounds);
  }


  /**
   * Measure the latency and return the distribution found
   *
   * @param rounds - the number of messages to send, should be more than 100K
   */
  public static MeasuredNetworkLatency estimateLatency(Network net, PeerGetter pg,
      final int rounds) {
    int[] props = new int[50];
    int[] vals = new int[50];

    int pos = 0;
    for (int i = 10; i <= 200; i += 10)
      vals[pos++] = i;
    for (int i = 300; i <= 2000; i += 100)
      vals[pos++] = i;
    while (pos < vals.length)
      vals[pos] = vals[pos++ - 1] + 1000;

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
