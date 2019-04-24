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

  private Handel.HandelParameters defaultParams() {
    return defaultParams(null, null, null, null, null, null);
  }

  private Handel.HandelParameters defaultParams(Integer nodes, Double deadRatio, Double tor,
      Integer desynchronizedStart, Boolean byzantineSuicide, Boolean hiddenByzantine) {
    nodes = nodes != null ? nodes : 2048;
    deadRatio = deadRatio != null ? deadRatio : 0.10;
    tor = tor != null ? tor : 0;
    desynchronizedStart = desynchronizedStart != null ? desynchronizedStart : 0;
    hiddenByzantine = hiddenByzantine != null ? hiddenByzantine : false;
    byzantineSuicide = byzantineSuicide != null ? byzantineSuicide : false;

    double treshold = (1.0 - (deadRatio + 0.01));
    int priorityWindow = 0;

    return new Handel.HandelParameters(nodes, (int) (nodes * treshold), 4, 50, 20, 10,
        (int) (nodes * deadRatio), RegistryNodeBuilders.name(true, false, tor),
        NetworkLatency.AwsRegionNetworkLatency.class.getSimpleName(), desynchronizedStart,
        byzantineSuicide, hiddenByzantine, priorityWindow);
  }

  private BasicStats run(int rounds, Handel.HandelParameters params) {
    List<StatsHelper.StatsGetter> stats =
        List.of(new StatsHelper.DoneAtStatGetter(), new StatsHelper.MsgReceivedStatGetter());
    RunMultipleTimes<Handel> rmt =
        new RunMultipleTimes<>(new Handel(params), rounds, 0, stats, null);
    List<StatsHelper.Stat> res = rmt.run(Handel.newContIf());

    return new BasicStats(res.get(0).get("min"), res.get(0).get("avg"), res.get(0).get("max"),
        res.get(1).get("min"), res.get(1).get("avg"), res.get(1).get("max"));
  }

  private void log() {
    System.out.println("\nBehavior when the number of nodes increases - " + defaultParams());
    System.out.println(" We expect log performances and polylog number of messages.");

    for (int n = 128; n <= 4096; n *= 2) {
      Handel.HandelParameters params = defaultParams(n, null, null, null, null, null);
      BasicStats bs = run(5, params);
      System.out.println(n + " nodes: " + bs);
    }
  }

  private void tor() {
    int n = 2048;
    System.out.println("\nImpact of the ratio of nodes behind tor - "
        + defaultParams(n, null, null, null, null, null));

    for (double tor : RegistryNodeBuilders.tor()) {
      Handel.HandelParameters params = defaultParams(2048, null, tor, null, null, null);
      BasicStats bs = run(5, params);
      System.out.println(tor + " tor: " + bs);
    }
  }

  private void noSyncStart() {
    System.out.println("\nImpact of nodes not starting at the same time - " + defaultParams());

    for (int s : new int[] {0, 50, 100, 200, 400, 800}) {
      Handel.HandelParameters params = defaultParams(2048, null, 0.0, s, null, null);
      BasicStats bs = run(10, params);
      System.out.println(s + " delay: " + bs);
    }
  }

  private void byzantineSuicide() {
    int n = 2048;

    System.out.println("\nByzantine nodes are filling honest node's queues with bad signatures - "
        + defaultParams(n, null, null, null, true, null));


    for (int ni = 128; ni <= 2048; ni *= 2) {
      Handel.HandelParameters params = defaultParams(ni, null, null, null, true, null);
      BasicStats bs = run(5, params);
      System.out.println(ni + " nodes, " + params.nodesDown + " byzantines: " + bs);
    }

    for (double dr : new Double[] {0.0, .10, .20, .30, .40, .50}) {
      Handel.HandelParameters params;

      if (dr > 0) {
        params = defaultParams(n, dr, null, null, false, null);
        BasicStats bs = run(2, params);
        System.out.println(n + " nodes, " + dr + " fail-silent: " + bs);
      }

      params = defaultParams(n, dr, null, null, true, null);
      BasicStats bs = run(2, params);
      System.out.println(n + " nodes, " + dr + " byzantines: " + bs);
    }
  }

  private void hiddenByzantine() {
    int n = 1024;

    System.out.println("\nByzantine nodes are creating nearly useless signatures"
        + defaultParams(n, null, null, null, true, null));


    for (int ni = 128; ni <= n; ni *= 2) {
      Handel.HandelParameters params = defaultParams(ni, null, null, null, false, true);
      BasicStats bs = run(2, params);
      System.out.println(ni + " nodes, " + params.nodesDown + " byzantines: " + bs);
    }

    for (double dr : new Double[] {0.0, .10, .20, .30, .40, .50}) {
      Handel.HandelParameters params;

      if (dr > 0) {
        params = defaultParams(n, dr, null, null, false, false);
        BasicStats bs = run(5, params);
        System.out.println(n + " nodes, " + dr + " fail-silent: " + bs);
      }

      params = defaultParams(n, dr, null, null, false, true);
      BasicStats bs = run(5, params);
      System.out.println(n + " nodes, " + dr + " byzantines: " + bs);
    }
  }

  private void byzantineWindowEvaluation() {
    System.out.println("\nSEvaluation with priority list of different size;");
    int n = 1024;
    double[] deadRatios = new double[] {0, 0.25, 0.50};

    for (Boolean[] byzs : new Boolean[][] {new Boolean[] {false, false},
        new Boolean[] {true, false}, new Boolean[] {false, true}}) {
      for (int w : new int[] {20, 40, 80, 160}) {
        for (double dr : deadRatios) {
          Handel.HandelParameters params = defaultParams(n, dr, null, null, byzs[0], byzs[1]);
          params.priorityWindow = w;
          BasicStats bs = run(3, params);
          System.out.println("WindowEvaluation: Window: " + w + ", DeadRatio: " + dr + " suicideBiz="
              + byzs[0] + ", hiddenByz=" + byzs[1] + " => " + bs);
        }
      }
      System.out.println("\nSEvaluation with using ranking in the list *only*");
      for (double dr : deadRatios) {
        Handel.HandelParameters params = defaultParams(n, dr, null, null, byzs[0], byzs[1]);
        BasicStats bs = run(3, params);
        System.out.println("ByzantineSuicide: DeadRatio: " + dr + " suicideBiz=" + byzs[0] + "hiddenByz="
            + byzs[1] + " => " + bs);
      }
    }
  }

  public static void main(String... args) {
    HandelScenarios scenario = new HandelScenarios();
    //scenario.log();

    scenario.byzantineWindowEvaluation();
    //scenario.hiddenByzantine();
    // scenario.tor();
  }
}
