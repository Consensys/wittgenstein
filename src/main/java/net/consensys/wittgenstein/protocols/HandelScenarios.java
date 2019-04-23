package net.consensys.wittgenstein.protocols;

import net.consensys.wittgenstein.core.NetworkLatency;
import net.consensys.wittgenstein.core.RegistryNodeBuilders;
import net.consensys.wittgenstein.core.RunMultipleTimes;
import net.consensys.wittgenstein.core.utils.StatsHelper;
import java.util.List;

public class HandelScenarios {

  static class BasicStats {
    final int doneAtMin;
    final int doneAtAvg;
    final int doneAtMax;

    final int msgRcvMin;
    final int msgRcvAvg;
    final int msgRcvMax;


    BasicStats(long doneAtMin, long doneAtAvg, long doneAtMax, long msgRcvMin, long msgRcvAvg,
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
      return "doneAtMin=" + doneAtMin + ", doneAtAvg=" + doneAtAvg + ", doneAtMax=" + doneAtMax
          + ", msgRcvMin=" + msgRcvMin + ", msgRcvAvg=" + msgRcvAvg + ", msgRcvMax=" + msgRcvMax;
    }
  }

  private Handel.HandelParameters defaultParams(int nodes, Double deadRatio, Double tor,
      Integer desynchronizedStart, Boolean byzantineSuicide) {
    deadRatio = deadRatio != null ? deadRatio : 0.10;
    tor = tor != null ? tor : 0;
    desynchronizedStart = desynchronizedStart != null ? desynchronizedStart : 0;
    byzantineSuicide = byzantineSuicide != null ? byzantineSuicide : false;

    double treshold = (1.0 - (deadRatio + 0.01));

    return new Handel.HandelParameters(nodes, (int) (nodes * treshold), 4, 50, 20, 10,
        (int) (nodes * deadRatio), RegistryNodeBuilders.name(true, false, tor),
        NetworkLatency.AwsRegionNetworkLatency.class.getSimpleName(), desynchronizedStart,
        byzantineSuicide);
  }

  private BasicStats run(int rounds, Handel.HandelParameters params) {
    List<StatsHelper.StatsGetter> stats =
        List.of(new StatsHelper.DoneAtStatGetter(), new StatsHelper.MsgReceivedStatGetter());
    RunMultipleTimes<Handel> rmt =
        new RunMultipleTimes<>(new Handel(params), rounds, 30000, stats, null);
    List<StatsHelper.Stat> res = rmt.run(Handel.newContIf());

    return new BasicStats(res.get(0).get("min"), res.get(0).get("avg"), res.get(0).get("max"),
        res.get(1).get("min"), res.get(1).get("avg"), res.get(1).get("max"));
  }

  private void log() {
    System.out.println("\nBehavior when the number of nodes increases.");
    System.out.println(" We expect log performances and polylog number of messages.");

    for (int n = 128; n <= 4096; n *= 2) {
      Handel.HandelParameters params = defaultParams(n, null, null, null, null);
      BasicStats bs = run(5, params);
      System.out.println(n + " nodes: " + bs);
    }
  }

  private void tor() {
    System.out.println("\nImpact of the ratio of nodes behind tor.");

    for (double tor : RegistryNodeBuilders.tor()) {
      Handel.HandelParameters params = defaultParams(2048, null, tor, null, null);
      BasicStats bs = run(5, params);
      System.out.println(tor + " tor: " + bs);
    }
  }

  private void noSyncStart() {
    System.out.println("\nImpact of nodes not starting at the same time");

    for (int s : new int[] {0, 50, 100, 200, 400, 800}) {
      Handel.HandelParameters params = defaultParams(2048, null, 0.0, s, null);
      BasicStats bs = run(10, params);
      System.out.println(s + " delay: " + bs);
    }
  }

  private void byzantineSuicide() {
    System.out.println("\nByzantine nodes are filling honest node's queues with bad signatures");

    for (int n = 128; n <= 4096; n *= 2) {
      Handel.HandelParameters params = defaultParams(n, null, null, null, true);
      BasicStats bs = run(5, params);
      System.out.println(n + " nodes, usual byzantines: " + bs);
    }

    for (double dr : new Double[] {0.0, .10, .20, .30, .40, .50}) {
      Handel.HandelParameters params = defaultParams(2048, dr, null, null, true);
      BasicStats bs = run(5, params);
      System.out.println(dr + " byzantines: " + bs);
    }
  }

  public static void main(String... args) {
    HandelScenarios scenario = new HandelScenarios();
    scenario.log();
    scenario.byzantineSuicide();
    // scenario.tor();
  }
}
