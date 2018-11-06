package net.consensys.wittgenstein.core;

import net.consensys.wittgenstein.core.utils.StatsHelper;
import net.consensys.wittgenstein.tools.Graph;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class ProgressPerTime {
  private final Protocol template;
  private final String configDesc;
  private final String yAxisDesc;
  private final StatsHelper.SimpleStatsGetter statsGetter;

  public ProgressPerTime(Protocol template, String configDesc, String yAxisDesc,
      StatsHelper.SimpleStatsGetter statsGetter) {
    this.template = template;
    this.configDesc = configDesc;
    this.yAxisDesc = yAxisDesc;
    this.statsGetter = statsGetter;
  }

  public void run(Predicate<Protocol> contIf) {
    Graph graph = new Graph(template + " " + configDesc, "time in ms", yAxisDesc);
    Graph.Series series1min = new Graph.Series("worse node");
    Graph.Series series1max = new Graph.Series("best node");
    Graph.Series series1avg = new Graph.Series("average");
    graph.addSerie(series1min);
    graph.addSerie(series1max);
    graph.addSerie(series1avg);

    System.out.println(template + " " + configDesc);
    template.init();
    List<? extends Node> liveNodes =
        template.network().allNodes.stream().filter(n -> !n.down).collect(Collectors.toList());

    long startAt = System.currentTimeMillis();
    StatsHelper.SimpleStats s;
    do {
      template.network().runMs(10);
      s = statsGetter.get(liveNodes);
      series1min.addLine(new Graph.ReportLine(template.network().time, s.min));
      series1max.addLine(new Graph.ReportLine(template.network().time, s.max));
      series1avg.addLine(new Graph.ReportLine(template.network().time, s.avg));
      if (template.network().time % 10000 == 0) {
        System.out.println(" " + s.avg);
      }
    } while (contIf.test(template));
    long endAt = System.currentTimeMillis();

    try {
      graph.save(new File("/tmp/graph.png"));
    } catch (IOException e) {
      System.err.println("Can't generate the graph: " + e.getMessage());
    }

    System.out.println("bytes sent: " + StatsHelper.getStatsOn(liveNodes, Node::getBytesSent));
    System.out.println("bytes rcvd: " + StatsHelper.getStatsOn(liveNodes, Node::getBytesReceived));
    System.out.println("msg sent: " + StatsHelper.getStatsOn(liveNodes, Node::getMsgSent));
    System.out.println("msg rcvd: " + StatsHelper.getStatsOn(liveNodes, Node::getMsgReceived));
    System.out.println("done at: " + StatsHelper.getStatsOn(liveNodes, Node::getDoneAt));
    System.out.println("Simulation execution time: " + ((endAt - startAt) / 1000) + "s");
  }

}
