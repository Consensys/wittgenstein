package net.consensys.wittgenstein.core;

/**
 * Calculates the network throughput given the latency. It's an optimistic evaluation as we don't
 * take into account the overlapping messages.
 */
public abstract class NetworkThroughput {
  public abstract int delay(Node from, Node to, int delta, int msgSize);

  /**
   * Use the equation given by:
   * https://cseweb.ucsd.edu/classes/wi01/cse222/papers/mathis-tcpmodel-ccr97.pdf
   *
   * <p>There are more complicated ones but this one is commonly used. See
   * https://www.netcraftsmen.com/tcp-performance-and-the-mathis-equation/
   * https://www.slac.stanford.edu/comp/net/wan-mon/thru-vs-loss.html
   */
  public static class MathisNetworkThroughput extends NetworkThroughput {
    private final NetworkLatency nl;
    private final int mss = 1460; // maximum segment size
    private final int windowSize;

    /**
     * Using the optimistic numbers here. It's rather imprecise, because most of the loss come from
     * bursts. Note that the paper proposes to use a Pareto distribution.
     * https://pdfs.semanticscholar.org/297a/0cc3af443542710d69010676918a0271f2e3.pdf
     */
    private final double loss = 0.004;

    private double div = Math.sqrt(loss);

    public MathisNetworkThroughput(NetworkLatency nl) {
      this(nl, 87380 * 1024); // 87380; // default on linux
    }

    public MathisNetworkThroughput(NetworkLatency nl, int windowSizeBytes) {
      this.nl = nl;
      this.windowSize = 8 * windowSizeBytes;
    }

    /** Mathis: rate < (MSS/RTT)*(1/sqrt(Loss)) */
    @Override
    public int delay(Node from, Node to, int delta, int msgSize) {
      int st = nl.getLatency(from, to, delta);

      if (msgSize < mss) {
        return st;
      }

      double rtt = st * 2;
      double bandwith = (mss * 8) / (rtt * div);
      double wMax = windowSize / rtt;
      double avBandwith = Math.min(bandwith, wMax);

      return (int) ((8 * msgSize) / avBandwith + st);
    }
  }
}
