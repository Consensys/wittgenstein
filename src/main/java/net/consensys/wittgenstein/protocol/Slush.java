package net.consensys.wittgenstein.protocol;

import net.consensys.wittgenstein.core.*;
import net.consensys.wittgenstein.core.utils.StatsHelper;
import net.consensys.wittgenstein.tools.Graph;
import java.io.File;
import java.io.IOException;
import java.util.*;

public class Slush implements Protocol {
  private static final int NODES_AV = 100;
  private final Network<SlushNode> network = new Network<>();
  final Node.NodeBuilder nb;
  private static final int COLOR_NB = 2;

  /**
   * M is the number of rounds. "Finally, the node decides the color it ended up with at time m []
   * we will show that m grows logarithmically with n."
   */
  private final int M;

  /**
   * K is the sample size you take
   */
  private final int K;

  /**
   * A stands for the alpha threshold
   */
  private final float A;
  private final float AK;

  private Slush(int M, int K, float A) {
    this.M = M;
    this.K = K;
    this.A = A;
    this.AK = K * A;
    this.nb = new Node.NodeBuilderWithRandomPosition();
  }

  @Override
  public void init() {
    for (int i = 0; i < NODES_AV; i++) {
      network.addNode(new SlushNode(network.rd, nb));
    }
  }

  @Override
  public Network<SlushNode> network() {
    return network;
  }

  @Override
  public Slush copy() {
    return new Slush(M, K, A);
  }

  static class Query extends Network.Message<SlushNode> {
    final int id;
    final int color;

    Query(int id, int color) {
      this.id = id;
      this.color = color;
    }

    @Override
    public void action(SlushNode from, SlushNode to) {
      to.onQuery(this, from);
    }
  }

  static class AnswerQuery extends Network.Message<SlushNode> {
    final Query originalQuery;
    final int color;

    AnswerQuery(Query originalQuery, int color) {
      this.originalQuery = originalQuery;
      this.color = color;
    }

    @Override
    public void action(SlushNode from, SlushNode to) {
      to.onAnswer(originalQuery.id, color);
    }
  }

  class SlushNode extends Node {
    int myColor = 0;
    int myQueryNonce;
    final Map<Integer, Answer> answerIP = new HashMap<>();

    SlushNode(Random rd, NodeBuilder nb) {
      super(rd, nb);
    }

    List<SlushNode> getRandomRemotes() {
      List<SlushNode> res = new ArrayList<>(K);

      while (res.size() != K) {
        int r = network.rd.nextInt(NODES_AV);
        if (r != nodeId && !res.contains(network.getNodeById(r))) {
          res.add(network.getNodeById(r));
        }
      }

      return res;
    }

    private int otherColor() {
      return myColor == 1 ? 2 : 1;
    }

    /**
     * Upon receiving a query, an uncolored node adopts the color in the query, responds with that
     * color, and initiates its own query, whereas a colored node simply responds with its current
     * color.
     */
    void onQuery(Query qa, SlushNode from) {
      if (myColor == 0) {
        myColor = qa.color;
        sendQuery(1);
      }
      network.send(new AnswerQuery(qa, myColor), this, from);
    }

    /**
     * Once the querying node collects k responses, it checks if a fraction ≥ αk are for the same
     * color, where α > 0.5 is a protocol parameter. If the αk threshold is met and the sampled
     * color differs from the node’s own color, the node flips to that color.
     */
    void onAnswer(int queryId, int color) {
      Answer asw = answerIP.get(queryId);
      asw.colorsFound[color]++;
      // in this case we assume that messages received correspond to the query answers
      if (asw.answerCount() == K) {
        answerIP.remove(queryId);
        if (asw.colorsFound[otherColor()] > AK) {
          myColor = otherColor();
        }

        if (asw.round < M) {
          sendQuery(asw.round + 1);
        }
      }
    }

    void sendQuery(int countInM) {
      Query q = new Query(++myQueryNonce, myColor);
      answerIP.put(q.id, new Answer(countInM));
      network.send(q, this, getRandomRemotes());
    }
  }

  static class Answer {
    final int round;
    private final int[] colorsFound = new int[COLOR_NB + 1];

    Answer(int round) {
      this.round = round;
    }

    int answerCount() {
      int sum = 0;
      for (int i : colorsFound) {
        sum += i;
      }
      return sum;
    }
  }

  private static int[] getColorSum(Slush sp) {
    int[] colorsSum = new int[COLOR_NB + 1];
    for (SlushNode n : sp.network.allNodes) {
      colorsSum[n.myColor]++;
    }
    return colorsSum;
  }

  private void play1() {
    Slush sl = new Slush(5, 7, 4 / 7);
    String desc =
        "Slush Protocol color metastasis by time periods in ms with K=" + K + " rounds M= " + sl.M;

    StatsHelper.StatsGetter stats = new StatsHelper.StatsGetter() {
      final List<String> fields = new StatsHelper.SimpleStats(0, 0, 0).fields();

      @Override
      public List<String> fields() {
        return fields;
      }

      @Override
      public StatsHelper.Stat get(List<? extends Node> liveNodes) {
        return StatsHelper.getStatsOn(liveNodes, n -> ((SlushNode) n).myColor);
      }
    };
    ProgressPerTime ppt = new ProgressPerTime(sl, desc, "Number of y-Colored Nodes", stats, 10);
  }

  private void play2() {
    NetworkLatency nl = new NetworkLatency.NetworkLatencyByDistance();
    List<Graph.Series> rawResultsCol1 = new ArrayList<>();
    List<Graph.Series> rawResultsCol2 = new ArrayList<>();
    List<Graph.Series> rawResultsUnCol = new ArrayList<>();

    System.out.println("" + nl);

    //Instantiate protocol
    Slush spTemplate = new Slush(5, 7, 4 / 7);
    //ProgressPerTime ppt = new ProgressPerTime(spTemplate,)
    // Set network latency
    spTemplate.network.setNetworkLatency(nl);
    String desc = "Slush Protocol color metastasis by time periods in ms with K=" + K
        + " rounds M= " + spTemplate.M;
    Graph graph = new Graph(desc, "time in ms", "number of colored nodes");
    Graph medianGraph = new Graph("average number of colored nodes per time (" + desc + ")",
        "time in ms", "number of nodes colored by color");

    //Initialize with 100 nodes
    spTemplate.init();

    for (int i = 0; i < 10; i++) {
      Slush sp = spTemplate.copy();
      sp.network.setNetworkLatency(nl);
      sp.network.rd.setSeed(i);
      sp.init();
      Graph.Series curCol1 = new Graph.Series("Color 1 count - series " + i);
      Graph.Series curCol2 = new Graph.Series("Color 2 count - seriess " + i);
      Graph.Series curUnCol = new Graph.Series("Uncolored Nodes count -  series " + i);
      rawResultsCol1.add(curCol1);
      rawResultsCol2.add(curCol2);
      rawResultsUnCol.add(curUnCol);

      SlushNode uncolored1 = sp.network().getNodeById(0);
      SlushNode uncolored2 = sp.network().getNodeById(1);
      int t = 0;
      uncolored1.myColor = 1;
      uncolored1.sendQuery(1);

      uncolored2.myColor = 2;
      uncolored2.sendQuery(1);
      do {
        int[] colors = new int[COLOR_NB + 1];

        colors = getColorSum(sp);
        sp.network.runMs(10);
        //Need a function that calculates the sum of all colored nodes
        curUnCol.addLine(new Graph.ReportLine(sp.network.time, colors[0]));
        curCol1.addLine(new Graph.ReportLine(sp.network.time, colors[1]));
        curCol2.addLine(new Graph.ReportLine(sp.network.time, colors[2]));

        //System.out.println("Finished, " + sp.network.msgs.size() + " messages");
        t++;
      } while (t < sp.network.allNodes.size() + 50);
      System.out.println("Simulation number " + i);
      graph.addSerie(curCol1);
      graph.addSerie(curCol2);
      graph.addSerie(curUnCol);
      //add each series to list so average can be calculated
      //add simulation loop

      System.out.println("N=" + NODES_AV + ", K=" + K + ", AK=" + AK + ", M=" + sp.M);
      int[] res = new int[COLOR_NB + 1];
      for (SlushNode n : sp.network.allNodes) {
        res[n.myColor]++;
      }

      for (int j = 0; j < COLOR_NB + 1; j++) {
        System.out.println(j + ":" + res[j]);
      }
    }

    try {
      graph.save(new File("/tmp/graph1.png"));
    } catch (IOException e) {
      System.err.println("Can't generate the graph: " + e.getMessage());
    }

    Graph.Series seriesCol1 =
        Graph.statSeries("Number of Nodes color 1 - average", rawResultsCol1).avg;
    Graph.Series seriesCol2 =
        Graph.statSeries("Number of Nodes color 2 - average", rawResultsCol2).avg;
    Graph.Series seriesUnCol =
        Graph.statSeries("Number of uncolored Nodes  - average", rawResultsUnCol).avg;
    medianGraph.addSerie(seriesUnCol);
    medianGraph.addSerie(seriesCol1);
    medianGraph.addSerie(seriesCol2);

    try {
      medianGraph.save(new File("/tmp/graph_avg.png"));
    } catch (IOException e) {
      System.err.println("Can't generate the graph: " + e.getMessage());
    }
  }

  public static void main(String... args) {
    new Slush(5, 7, 4 / 7).play1();
    new Slush(5, 7, 4 / 7).play2();

  }

}
