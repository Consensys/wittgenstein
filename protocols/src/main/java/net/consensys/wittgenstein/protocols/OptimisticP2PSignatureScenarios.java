package net.consensys.wittgenstein.protocols;

import java.util.List;
import net.consensys.wittgenstein.core.NetworkLatency;
import net.consensys.wittgenstein.core.RegistryNodeBuilders;
import net.consensys.wittgenstein.core.RunMultipleTimes;
import net.consensys.wittgenstein.core.utils.StatsHelper;
import net.consensys.wittgenstein.protocols.OptimisticP2PSignature.OptimisticP2PSignatureParameters;

@SuppressWarnings("SameParameterValue")
public class OptimisticP2PSignatureScenarios {

  static class BasicStats {
    final int doneAtMin;
    final int doneAtAvg;
    final int doneAtMax;

    final int msgRcvMin;
    final int msgRcvAvg;
    final int msgRcvMax;

    BasicStats(
        long doneAtMin,
        long doneAtAvg,
        long doneAtMax,
        long msgRcvMin,
        long msgRcvAvg,
        long msgRcvMax) {
      this.doneAtMin = (int) doneAtMin;
      this.doneAtAvg = (int) doneAtAvg;
      this.doneAtMax = (int) doneAtMax;
      this.msgRcvMin = (int) msgRcvMin;
      this.msgRcvAvg = (int) msgRcvAvg;
      this.msgRcvMax = (int) msgRcvMax;
    }

    @Override
    public String toString() {
      return "; doneAtAvg=" + doneAtAvg + "; msgRcvAvg=" + msgRcvAvg;
    }
  }

  private BasicStats run(int rounds, OptimisticP2PSignatureParameters params) {
    List<StatsHelper.StatsGetter> stats =
        List.of(new StatsHelper.DoneAtStatGetter(), new StatsHelper.MsgReceivedStatGetter());
    RunMultipleTimes<OptimisticP2PSignature> rmt =
        new RunMultipleTimes<>(new OptimisticP2PSignature(params), rounds, 0, stats, null);
    List<StatsHelper.Stat> res = rmt.run(RunMultipleTimes.contUntilDone());

    return new BasicStats(
        res.get(0).get("min"),
        res.get(0).get("avg"),
        res.get(0).get("max"),
        res.get(1).get("min"),
        res.get(1).get("avg"),
        res.get(1).get("max"));
  }

  private void logErrors(Double errorRate) {
    boolean printed = false;

    double[] errors = new double[] {00};
    if (errorRate != null) {
      errors = new double[] {errorRate};
    }

    for (int i = 0; i < errors.length; i++) {
      int e = (int) (errors[i] * 100);
    }

    for (int i = 0; i < errors.length; i++) {
      double e = errors[i];
      for (int n = 128; n <= 4096; n *= 2) {
        OptimisticP2PSignatureParameters params = defaultParams(n, errors[i], null, null, null);

        if (!printed) {
          System.out.println("\nBehavior when the number of nodes increases - " + params);
          printed = true;
        }

        BasicStats bs = run(n > 9000 ? 2 : n < 1000 ? 1 : 1, params);
        System.out.println(n + " nodes: " + e + bs);
        System.out.flush();
      }
    }
  }

  private static OptimisticP2PSignatureParameters defaultParams(
      int nodes,
      Double deadRatio,
      Integer connectionCount,
      Double tor,
      RegistryNodeBuilders.Location loc) {

    int ts = (int) (nodes * 0.99);

    String nb = RegistryNodeBuilders.name(RegistryNodeBuilders.Location.CITIES, true, 0);
    String nl = NetworkLatency.NetworkLatencyByCityWJitter.class.getSimpleName();

    return new OptimisticP2PSignatureParameters(nodes, ts, 3, 4, nb, nl);
  }

  public static void main(String... args) {
    OptimisticP2PSignatureScenarios scenario = new OptimisticP2PSignatureScenarios();
    scenario.logErrors(null);
  }
}
