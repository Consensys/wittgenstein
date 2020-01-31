package net.consensys.wittgenstein.protocols;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import net.consensys.wittgenstein.core.*;
import net.consensys.wittgenstein.core.messages.Message;
import net.consensys.wittgenstein.core.utils.StatsHelper;

@SuppressWarnings("WeakerAccess")
public class Snowflake implements Protocol {

  private Network<SnowflakeNode> network;
  final NodeBuilder nb;
  private static final int COLOR_NB = 2;
  SnowflakeParameters params;

  public static class SnowflakeParameters extends WParameters {
    private final int NODES_AV;
    /**
     * M is the number of rounds. "Finally, the node decides the color it ended up with at time m []
     * we will show that m grows logarithmically with n."
     */
    private final int M;

    /** K is the sample size you take */
    private final int K;

    /** A stands for the alpha threshold */
    private final double A;

    private double AK;

    private final int B;
    final String nodeBuilderName;
    final String networkLatencyName;

    public SnowflakeParameters(
        int nodeAv,
        int M,
        int K,
        double A,
        int B,
        String nodeBuilderName,
        String networkLatencyName) {
      this.NODES_AV = nodeAv;
      this.M = M;
      this.K = K;
      this.A = A;
      this.B = B;
      this.AK = A * K;
      this.nodeBuilderName = nodeBuilderName;
      this.networkLatencyName = networkLatencyName;
    }

    // For json
    @SuppressWarnings("unused")
    public SnowflakeParameters() {
      this(100, 4, 7, 4, 7, null, null);
    }
  }

  public Snowflake(SnowflakeParameters params) {
    this.params = params;
    this.network = new Network<>();
    this.nb = RegistryNodeBuilders.singleton.getByName(params.nodeBuilderName);
    this.network.setNetworkLatency(
        RegistryNetworkLatencies.singleton.getByName(params.networkLatencyName));
  }

  @Override
  public Snowflake copy() {
    return new Snowflake(params);
  }

  @Override
  public void init() {
    for (int i = 0; i < params.NODES_AV; i++) {
      network.addNode(new SnowflakeNode(network.rd, nb));
    }
    SnowflakeNode uncolored1 = network().getNodeById(0);
    SnowflakeNode uncolored2 = network().getNodeById(1);
    uncolored1.myColor = 1;
    uncolored1.sendQuery(1);

    uncolored2.myColor = 2;
    uncolored2.sendQuery(1);
  }

  @Override
  public Network<SnowflakeNode> network() {
    return network;
  }

  static class Query extends Message<SnowflakeNode> {
    final int id;
    final int color;

    Query(int id, int color) {
      this.id = id;
      this.color = color;
    }

    @Override
    public void action(Network<SnowflakeNode> network, SnowflakeNode from, SnowflakeNode to) {
      to.onQuery(this, from);
    }
  }

  static class AnswerQuery extends Message<SnowflakeNode> {
    final Query originalQuery;
    final int color;

    AnswerQuery(Query originalQuery, int color) {
      this.originalQuery = originalQuery;
      this.color = color;
    }

    @Override
    public void action(Network<SnowflakeNode> network, SnowflakeNode from, SnowflakeNode to) {
      to.onAnswer(originalQuery.id, color);
    }
  }

  class SnowflakeNode extends Node {

    int myColor = 0;
    int myQueryNonce;
    int cnt = 0;
    final Map<Integer, Answer> answerIP = new HashMap<>();

    SnowflakeNode(Random rd, NodeBuilder nb) {
      super(rd, nb);
    }

    List<SnowflakeNode> RandomRemotes() {
      List<SnowflakeNode> res = new ArrayList<>(params.K);

      while (res.size() != params.K) {
        int r = network.rd.nextInt(params.NODES_AV);
        if (r != nodeId && !res.contains(network.getNodeById(r))) {
          res.add(network.getNodeById(r));
        }
      }

      return res;
    }

    private int otherColor() {
      return myColor == 1 ? 2 : 1;
    }

    void onQuery(Query qa, SnowflakeNode from) {
      if (myColor == 0) {
        myColor = qa.color;
        sendQuery(1);
      }
      network.send(new AnswerQuery(qa, myColor), this, from);
    }

    /**
     * 1: procedure snowflakeLoop(u, col0 ∈ {R, B, ⊥}) 2: col := col0, cnt := 0 3: while undecided
     * do 4: if col = ⊥ then continue 5: K := sample(N \ u, k) 6: P := [query(v, col) for v ∈ K] 7:
     * for col' ∈ {R, B} do 8: if P.count(col') ≥ α · k then 9: if col' != col then 10: col := col'
     * , cnt := 0 11: else 12: if ++cnt > β then accept(col)
     *
     * <p>What's not very clear is what happens during the process: are we returning the color in
     * progress.
     */
    void onAnswer(int queryId, int color) {
      Answer asw = answerIP.get(queryId);
      asw.colorsFound[color]++;
      // in this case we assume that messages received correspond to the query answers
      if (asw.answerCount() == params.K) {
        answerIP.remove(queryId);
        if (asw.colorsFound[otherColor()] > params.AK) {
          myColor = otherColor();
          cnt = 0;
        } else {
          if (asw.colorsFound[myColor] > params.AK) {
            cnt++;
          }
        }
        if (cnt <= params.B) {
          sendQuery(asw.round + 1);
        }
      }
    }

    void sendQuery(int countInM) {
      Query q = new Query(++myQueryNonce, myColor);
      answerIP.put(q.id, new Answer(countInM));
      network.send(q, this, RandomRemotes());
    }

    @Override
    public String toString() {
      return "SanFerminNode{"
          + "nodeId="
          + nodeId
          + ", thresholdAt="
          + params.K
          + ", doneAt="
          + doneAt
          + ", msgReceived="
          + msgReceived
          + ", msgSent="
          + msgSent
          + ", KBytesSent="
          + bytesSent / 1024
          + ", KBytesReceived="
          + bytesReceived / 1024
          + '}';
    }
  }

  static class Answer {
    final int round;
    private final int[] colorsFound = new int[COLOR_NB + 1];

    Answer(int round) {
      this.round = round;
    }

    private int answerCount() {
      int sum = 0;
      for (int i : colorsFound) {
        sum += i;
      }
      return sum;
    }
  }

  void play() {

    String desc = "";

    // sl.network.setNetworkLatency(nl);
    StatsHelper.StatsGetter stats =
        new StatsHelper.StatsGetter() {
          final List<String> fields = List.of("avg");

          @Override
          public List<String> fields() {
            return fields;
          }

          @Override
          public StatsHelper.Stat get(List<? extends Node> liveNodes) {

            int[] colors = getDominantColor(liveNodes);
            System.out.println(
                "Colored nodes by the numbers: "
                    + colors[0]
                    + " remain uncolored "
                    + colors[1]
                    + " are red "
                    + colors[2]
                    + " are blue.");
            return StatsHelper.getStatsOn(liveNodes, n -> colors[((SnowflakeNode) n).myColor]);
          }
        };
    ProgressPerTime ppt =
        new ProgressPerTime(
            this, desc, "Number of y-Colored Nodes", stats, 10, null, 10, TimeUnit.MILLISECONDS);

    Predicate<Protocol> contIf =
        p1 -> {
          int[] colors;
          for (Node n : p1.network().allNodes) {
            SnowflakeNode gn = (SnowflakeNode) n;
            colors = getDominantColor(p1.network().allNodes);
            if ((gn.cnt < params.B && colors[1] != 100)
                || (gn.cnt < params.B && colors[2] != 100)) {
              return true;
            }
          }

          return false;
        };
    ppt.run(contIf);
  }

  private static int[] getDominantColor(List<? extends Node> ps) {
    int[] colors = new int[3];
    for (Node n : ps) {
      SnowflakeNode sn = (SnowflakeNode) n;
      colors[sn.myColor]++;
    }
    return colors;
  }

  @Override
  public String toString() {
    return "Snowflake{"
        + "nodes="
        + params.NODES_AV
        + ", latency="
        + network.networkLatency
        + ", M="
        + params.M
        + ", AK="
        + params.AK
        + ", B="
        + params.B
        + '}';
  }

  public static void main(String... args) {
    new Snowflake(new SnowflakeParameters(100, 5, 7, 4.0 / 7.0, 3, null, null)).play();
  }
}
