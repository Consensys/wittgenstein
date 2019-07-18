package net.consensys.wittgenstein.core;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import net.consensys.wittgenstein.core.utils.StatsHelper;
import net.consensys.wittgenstein.tools.Graph;

/** This class runs a scenario for a protocol */
public class ProgressPerTime {
  private final Protocol protocol;
  private final String configDesc;
  private final String yAxisDesc;
  private final StatsHelper.StatsGetter statsGetter;
  private final int roundCount;
  private final OnSingleRunEnd endCallback;
  private final int statEachXms;
  private final TimeUnit timeUnit;

  public ProgressPerTime(
      Protocol template,
      String configDesc,
      String yAxisDesc,
      StatsHelper.StatsGetter statsGetter,
      int roundCount,
      OnSingleRunEnd endCallback,
      int statEachXms,
      TimeUnit timeUnit) {
    if (roundCount <= 0) {
      throw new IllegalArgumentException(
          "roundCount must be greater than 0. roundCount=" + roundCount);
    }

    this.protocol = template.copy();
    this.configDesc = configDesc;
    this.yAxisDesc = yAxisDesc;
    this.statsGetter = statsGetter;
    this.roundCount = roundCount;
    this.endCallback = endCallback;
    this.statEachXms = statEachXms;
    this.timeUnit = timeUnit == null ? TimeUnit.MILLISECONDS : timeUnit;
  }

  public interface OnSingleRunEnd {
    void end(Protocol p);
  }

  public void run(Predicate<? extends Protocol> contIf) {

    Map<String, ArrayList<Graph.Series>> rawResults = new HashMap<>();
    for (String field : statsGetter.fields()) {
      rawResults.put(field, new ArrayList<>());
    }

    long bytesSentSum = 0;
    long bytesRcvSum = 0;
    long msgSentSum = 0;
    long msgRcvSum = 0;
    long doneAtSum = 0;

    for (int r = 0; r < roundCount; r++) {
      long startAt = System.currentTimeMillis();

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

      List<? extends Node> liveNodes;
      StatsHelper.Stat s;
      do {
        p.network().runMs(statEachXms);
        liveNodes =
            p.network().allNodes.stream().filter(n -> !n.isDown()).collect(Collectors.toList());
        s = statsGetter.get(liveNodes);
        for (String field : statsGetter.fields()) {
          rawResult.get(field).addLine(new Graph.ReportLine(p.network().time, s.get(field)));
        }
        if (p.network().time % 10000 == 0) {
          System.out.println("time goes by... time=" + (p.network().time / 1000) + ", stats=" + s);
        }
      } while (((Predicate) contIf).test(p));
      long endAt = System.currentTimeMillis();

      if (endCallback != null) {
        endCallback.end(p);
      }
      StatsHelper.SimpleStats bytesSent = StatsHelper.getStatsOn(liveNodes, Node::getBytesSent);
      StatsHelper.SimpleStats bytesRcv = StatsHelper.getStatsOn(liveNodes, Node::getBytesReceived);
      StatsHelper.SimpleStats msgSent = StatsHelper.getStatsOn(liveNodes, Node::getMsgSent);
      StatsHelper.SimpleStats msgRcv = StatsHelper.getStatsOn(liveNodes, Node::getMsgReceived);
      StatsHelper.SimpleStats doneAt = StatsHelper.getStatsOn(liveNodes, Node::getDoneAt);
      System.out.println("bytes sent: " + bytesSent);
      System.out.println("bytes rcvd: " + bytesRcv);
      System.out.println("msg sent: " + msgSent);
      System.out.println("msg rcvd: " + msgRcv);
      System.out.println("done at: " + doneAt);
      System.out.println("Simulation execution time: " + ((endAt - startAt) / 1000) + "s");

      bytesSentSum += bytesSent.avg;
      bytesRcvSum += bytesRcv.avg;
      msgSentSum += msgSent.avg;
      msgRcvSum += msgRcv.avg;
      doneAtSum += doneAt.avg;
    }

    if (roundCount > 1) {
      System.out.println("\nAverage on the " + roundCount + " rounds");
      System.out.println("bytes sent: " + (bytesSentSum / roundCount));
      System.out.println("bytes rcvd: " + (bytesRcvSum / roundCount));
      System.out.println("msg sent: " + (msgSentSum / roundCount));
      System.out.println("msg rcvd: " + (msgRcvSum / roundCount));
      System.out.println("done at: " + (doneAtSum / roundCount));
    }

    protocol.init();
    Graph graph =
        new Graph(
            protocol + " " + configDesc, "time in " + timeUnit.toString().toLowerCase(), yAxisDesc);

    for (String field : statsGetter.fields()) {
      Graph.StatSeries s = Graph.statSeries(field, rawResults.get(field));
      graph.addSerie(s.min);
      graph.addSerie(s.max);
      graph.addSerie(s.avg);
    }
    graph.cleanSeries();

    try {
      graph.save(new File("graph.png"));
    } catch (IOException e) {
      System.err.println("Can't generate the graph: " + e.getMessage());
      throw new IllegalStateException(e);
    }
  }
}
