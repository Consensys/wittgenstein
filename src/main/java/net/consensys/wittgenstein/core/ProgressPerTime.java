package net.consensys.wittgenstein.core;

import net.consensys.wittgenstein.core.utils.StatsHelper;
import net.consensys.wittgenstein.tools.Graph;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * This class runs a scenario for a protocol
 */
public class ProgressPerTime {
  private final Protocol protocol;
  private final String configDesc;
  private final String yAxisDesc;
  private final StatsHelper.StatsGetter statsGetter;
  private final int roundCount;

  public ProgressPerTime(Protocol template, String configDesc, String yAxisDesc,
      StatsHelper.StatsGetter statsGetter, int roundCount) {
    this.protocol = template.copy();
    this.configDesc = configDesc;
    this.yAxisDesc = yAxisDesc;
    this.statsGetter = statsGetter;
    this.roundCount = roundCount;
  }

  public void run(Predicate<Protocol> contIf) {

    Map<String, ArrayList<Graph.Series>> rawResults = new HashMap<>();
    for (String field : statsGetter.fields()) {
      rawResults.put(field, new ArrayList<>());
    }

    for (int r = 0; r < roundCount; r++) {
      Protocol p = protocol.copy();
      p.network().rd.setSeed(r);
      p.init();

      System.out.println("round=" + r + ", " + p + " " + configDesc);

      Map<String, Graph.Series> rawResult = new HashMap<>();
      for (String field : statsGetter.fields()) {
        Graph.Series gs = new Graph.Series();
        rawResult.put(field, gs);
        rawResults.get(field).add(gs);
      }

      List<? extends Node> liveNodes =
          p.network().allNodes.stream().filter(n -> !n.down).collect(Collectors.toList());

      long startAt = System.currentTimeMillis();
      StatsHelper.Stat s;
      do {
        p.network().runMs(10);
        s = statsGetter.get(liveNodes);
        for (String field : statsGetter.fields()) {
          rawResult.get(field).addLine(new Graph.ReportLine(p.network().time, s.get(field)));
        }
        if (p.network().time % 10000 == 0) {
          System.out.println("time goes by... time=" + (p.network().time / 1000) + ", stats=" + s);
        }
      } while (contIf.test(p));
      long endAt = System.currentTimeMillis();

      System.out.println("bytes sent: " + StatsHelper.getStatsOn(liveNodes, Node::getBytesSent));
      System.out
          .println("bytes rcvd: " + StatsHelper.getStatsOn(liveNodes, Node::getBytesReceived));
      System.out.println("msg sent: " + StatsHelper.getStatsOn(liveNodes, Node::getMsgSent));
      System.out.println("msg rcvd: " + StatsHelper.getStatsOn(liveNodes, Node::getMsgReceived));
      System.out.println("done at: " + StatsHelper.getStatsOn(liveNodes, Node::getDoneAt));
      System.out.println("Simulation execution time: " + ((endAt - startAt) / 1000) + "s");
    }

    Protocol forString = protocol.copy();
    forString.init();
    Graph graph = new Graph(forString + " " + configDesc, "time in ms", yAxisDesc);

    for (String field : statsGetter.fields()) {
      Graph.StatSeries s = Graph.statSeries(field, rawResults.get(field));
      graph.addSerie(s.min);
      graph.addSerie(s.max);
      graph.addSerie(s.avg);
    }

    try {
      graph.save(new File("/tmp/graph.png"));
    } catch (IOException e) {
      System.err.println("Can't generate the graph: " + e.getMessage());
    }
  }
}
