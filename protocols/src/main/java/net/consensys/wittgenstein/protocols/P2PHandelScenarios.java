package net.consensys.wittgenstein.protocols;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import net.consensys.wittgenstein.core.NetworkLatency;
import net.consensys.wittgenstein.core.Node;
import net.consensys.wittgenstein.core.RegistryNodeBuilders;
import net.consensys.wittgenstein.core.RunMultipleTimes;
import net.consensys.wittgenstein.core.utils.StatsHelper;
import net.consensys.wittgenstein.tools.Graph;

public class P2PHandelScenarios {

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

  private BasicStats run(int rounds, P2PHandel.P2PHandelParameters params) {
    List<StatsHelper.StatsGetter> stats =
        List.of(new StatsHelper.DoneAtStatGetter(), new StatsHelper.MsgReceivedStatGetter());
    RunMultipleTimes<P2PHandel> rmt =
        new RunMultipleTimes<>(new P2PHandel(params), rounds, 0, stats, null);
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
      for (int n = 1024; n <= 1024 * 8; n *= 2) {
        P2PHandel.P2PHandelParameters params = defaultParams(n, errors[i], null, null, null);

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

  public static void sigsPerTime() {
    String nl = NetworkLatency.NetworkLatencyByDistanceWJitter.class.getSimpleName();
    String nb = RegistryNodeBuilders.name(RegistryNodeBuilders.Location.RANDOM, true, 0);
    int nodeCt = 1024;
    List<Graph.Series> rawResultsMin = new ArrayList<>();
    List<Graph.Series> rawResultsMax = new ArrayList<>();
    List<Graph.Series> rawResultsAvg = new ArrayList<>();

    P2PHandel psTemplate =
        new P2PHandel(
            new P2PHandel.P2PHandelParameters(
                nodeCt,
                nodeCt * 0,
                nodeCt,
                15,
                3,
                50,
                true,
                P2PHandel.SendSigsStrategy.all,
                2,
                nb,
                nl));

    String desc =
        "signingNodeCount="
            + nodeCt
            + ", totalNodes="
            + (psTemplate.params.signingNodeCount + psTemplate.params.relayingNodeCount)
            + ", gossip "
            + ", gossip period="
            + psTemplate.params.sigsSendPeriod
            + ", compression="
            + psTemplate.params.sendSigsStrategy;
    System.out.println(nl + " " + desc);
    Graph graph =
        new Graph(
            "number of signatures per time (" + desc + ")", "time in ms", "number of signatures");
    Graph medianGraph =
        new Graph(
            "average number of signatures per time (" + desc + ")",
            "time in ms",
            "number of signatures");

    int lastSeries = 3;
    StatsHelper.SimpleStats s;

    for (int i = 0; i < lastSeries; i++) {
      Graph.Series curMin = new Graph.Series("signatures count - worse node" + i);
      Graph.Series curMax = new Graph.Series("signatures count - best node" + i);
      Graph.Series curAvg = new Graph.Series("signatures count - average" + i);
      rawResultsAvg.add(curAvg);
      rawResultsMin.add(curMin);
      rawResultsMax.add(curMax);

      P2PHandel ps1 = psTemplate.copy();
      ps1.network.rd.setSeed(i);
      ps1.init();

      do {
        ps1.network.runMs(10);
        s =
            StatsHelper.getStatsOn(
                ps1.network.allNodes,
                n -> ((P2PHandel.P2PSigNode) n).verifiedSignatures.cardinality());
        curMin.addLine(new Graph.ReportLine(ps1.network.time, s.min));
        curMax.addLine(new Graph.ReportLine(ps1.network.time, s.max));
        curAvg.addLine(new Graph.ReportLine(ps1.network.time, s.avg));
      } while (s.min != ps1.params.signingNodeCount);
      graph.addSerie(curMin);
      graph.addSerie(curMax);
      graph.addSerie(curAvg);

      System.out.println(
          "bytes sent: " + StatsHelper.getStatsOn(ps1.network.allNodes, Node::getBytesSent));
      System.out.println(
          "bytes rcvd: " + StatsHelper.getStatsOn(ps1.network.allNodes, Node::getBytesReceived));
      System.out.println(
          "msg sent: " + StatsHelper.getStatsOn(ps1.network.allNodes, Node::getMsgSent));
      System.out.println(
          "msg rcvd: " + StatsHelper.getStatsOn(ps1.network.allNodes, Node::getMsgReceived));
      System.out.println(
          "done at: " + StatsHelper.getStatsOn(ps1.network.allNodes, Node::getDoneAt));
    }

    try {
      graph.save(new File("graph_ind.png"));
    } catch (IOException e) {
      System.err.println("Can't generate the graph: " + e.getMessage());
    }

    Graph.Series seriesAvgmax =
        Graph.statSeries("Signatures count average - best node", rawResultsMax).avg;
    Graph.Series seriesAvgavg =
        Graph.statSeries("Signatures count average - average", rawResultsAvg).avg;
    medianGraph.addSerie(seriesAvgmax);
    medianGraph.addSerie(seriesAvgavg);

    try {
      medianGraph.save(new File("graph_time_avg.png"));
    } catch (IOException e) {
      System.err.println("Can't generate the graph: " + e.getMessage());
    }
  }

  public static void sigsPerStrategy() {
    int nodeCt = 1000;

    String nl = NetworkLatency.NetworkLatencyByDistanceWJitter.class.getSimpleName();
    String nb = RegistryNodeBuilders.name(RegistryNodeBuilders.Location.RANDOM, true, 0);
    P2PHandel ps1 =
        new P2PHandel(
            new P2PHandel.P2PHandelParameters(
                nodeCt, 0, nodeCt, 15, 3, 20, true, P2PHandel.SendSigsStrategy.all, 1, nb, nl));

    P2PHandel ps2 =
        new P2PHandel(
            new P2PHandel.P2PHandelParameters(
                nodeCt, 0, nodeCt, 15, 3, 20, false, P2PHandel.SendSigsStrategy.all, 1, nb, nl));

    Graph graph = new Graph("number of sig per time", "time in ms", "sig count");
    Graph.Series series1avg = new Graph.Series("sig count - full aggregate strategy");
    Graph.Series series2avg = new Graph.Series("sig count - single aggregate");
    graph.addSerie(series1avg);
    graph.addSerie(series2avg);

    ps1.init();
    ps2.init();

    StatsHelper.SimpleStats s1;
    StatsHelper.SimpleStats s2;
    do {
      ps1.network.runMs(10);
      ps2.network.runMs(10);
      s1 =
          StatsHelper.getStatsOn(
              ps1.network.allNodes,
              n -> ((P2PHandel.P2PSigNode) n).verifiedSignatures.cardinality());
      s2 =
          StatsHelper.getStatsOn(
              ps2.network.allNodes,
              n -> ((P2PHandel.P2PSigNode) n).verifiedSignatures.cardinality());
      series1avg.addLine(new Graph.ReportLine(ps1.network.time, s1.avg));
      series2avg.addLine(new Graph.ReportLine(ps2.network.time, s2.avg));
    } while (s1.min != nodeCt);

    try {
      graph.save(new File("graph_strat.png"));
    } catch (IOException e) {
      System.err.println("Can't generate the graph: " + e.getMessage());
    }
  }

  private static P2PHandel.P2PHandelParameters defaultParams(
      int nodes,
      Double deadRatio,
      Integer connectionCount_,
      Double tor,
      RegistryNodeBuilders.Location loc) {

    int ts = (int) (nodes * 0.99);

    String nb = RegistryNodeBuilders.name(RegistryNodeBuilders.Location.CITIES, true, 0);
    String nl = NetworkLatency.NetworkLatencyByCityWJitter.class.getSimpleName();

    int connectionCount = connectionCount_ == null ? 13 : connectionCount_;

    return new P2PHandel.P2PHandelParameters(
        nodes,
        0,
        ts,
        connectionCount,
        4,
        20,
        false,
        P2PHandel.SendSigsStrategy.cmp_diff,
        2,
        nb,
        nl);
  }

  public static void main(String... args) {
    P2PHandelScenarios scenario = new P2PHandelScenarios();
    scenario.logErrors(null);
  }
}
