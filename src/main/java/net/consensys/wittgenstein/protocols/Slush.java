package net.consensys.wittgenstein.protocols;

import net.consensys.wittgenstein.core.*;
import net.consensys.wittgenstein.core.messages.Message;
import net.consensys.wittgenstein.core.utils.StatsHelper;
import java.util.*;
import java.util.function.Predicate;

public class Slush implements Protocol {
  private final int NODES_AV;
  private final Network<SlushNode> network = new Network<>();
  final NodeBuilder nb;
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
  private final double A;
  private double AK;

  public Slush(int NODES_AV, int M, int K, double A) {
    this.NODES_AV = NODES_AV;
    this.M = M;
    this.K = K;
    this.A = A;
    this.AK = K * A;
    this.nb = new NodeBuilder.NodeBuilderWithRandomPosition();
  }

  @Override
  public void init() {
    for (int i = 0; i < NODES_AV; i++) {
      network.addNode(new SlushNode(network.rd, nb));
    }
    SlushNode uncolored1 = network().getNodeById(0);
    SlushNode uncolored2 = network().getNodeById(1);
    uncolored1.myColor = 1;
    uncolored1.sendQuery(1);

    uncolored2.myColor = 2;
    uncolored2.sendQuery(1);
  }

  @Override
  public Network<SlushNode> network() {
    return network;
  }

  @Override
  public Slush copy() {
    return new Slush(NODES_AV, M, K, A);
  }

  static class Query extends Message<SlushNode> {
    final int id;
    final int color;

    Query(int id, int color) {
      this.id = id;
      this.color = color;
    }

    @Override
    public void action(Network<SlushNode> network, SlushNode from, SlushNode to) {
      to.onQuery(this, from);
    }
  }

  static class AnswerQuery extends Message<SlushNode> {
    final Query originalQuery;
    final int color;

    AnswerQuery(Query originalQuery, int color) {
      this.originalQuery = originalQuery;
      this.color = color;
    }

    @Override
    public void action(Network<SlushNode> network, SlushNode from, SlushNode to) {
      to.onAnswer(originalQuery.id, color);
    }
  }

  class SlushNode extends Node {
    int myColor = 0;
    int myQueryNonce;
    int round = 0;
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

        if (round < M) {
          round++;
          sendQuery(asw.round + 1);
        }
      }
    }

    void sendQuery(int countInM) {
      Query q = new Query(++myQueryNonce, myColor);
      answerIP.put(q.id, new Answer(countInM));
      network.send(q, this, getRandomRemotes());
    }

    @Override
    public String toString() {
      return "SlushNode{" + "nodeId=" + nodeId + ", thresholdAt=" + K + ", doneAt=" + doneAt
          + ", msgReceived=" + msgReceived + ", msgSent=" + msgSent + ", KBytesSent="
          + bytesSent / 1024 + ", KBytesReceived=" + bytesReceived / 1024 + '}';
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
    StatsHelper.StatsGetter stats = new StatsHelper.StatsGetter() {
      final List<String> fields = List.of("avg");

      @Override
      public List<String> fields() {
        return fields;
      }

      @Override
      public StatsHelper.Stat get(List<? extends Node> liveNodes) {
        int[] colors = getDominantColor(liveNodes);
        System.out.println("Colored nodes by the numbers: " + colors[0] + " remain uncolored "
            + colors[1] + " are red " + colors[2] + " are blue.");
        return StatsHelper.getStatsOn(liveNodes, n -> colors[((SlushNode) n).myColor]);
      }
    };
    ProgressPerTime ppt =
        new ProgressPerTime(this, desc, "Number of y-Colored Nodes", stats, 10, null);

    Predicate<Protocol> contIf = p1 -> {
      int[] colors;
      for (Node n : p1.network().allNodes) {
        SlushNode gn = (SlushNode) n;
        colors = getDominantColor(p1.network().allNodes);
        if ((gn.round < this.M && colors[1] != 100) || (gn.round < this.M && colors[2] != 100)) {
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
      SlushNode sn = (SlushNode) n;
      colors[sn.myColor]++;
    }
    return colors;
  }


  @Override
  public String toString() {
    return "Slush{" + "Nodes=" + NODES_AV + ", latency=" + network.networkLatency + ", M=" + M
        + ", AK=" + AK + '}';
  }

  public static void main(String... args) {
    new Slush(100, 5, 7, 4.0 / 7.0).play();
  }

}
