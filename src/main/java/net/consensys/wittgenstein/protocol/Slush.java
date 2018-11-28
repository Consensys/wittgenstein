package net.consensys.wittgenstein.protocol;

import net.consensys.wittgenstein.core.Network;
import net.consensys.wittgenstein.core.NetworkLatency;
import net.consensys.wittgenstein.core.Node;
import net.consensys.wittgenstein.core.Protocol;
import java.util.*;

public class Slush implements Protocol {
  private static final int NODES_AV = 100;
  private final Network<SlushNode> network = new Network<>();
  final Node.NodeBuilder nb;
  private static final int COLOR_NB = 2;

  /**
   * m is the number of rounds
   */
  private final int M = 5;

  /**
   * K is the sample size you take
   */
  private static final int K = 7;

  /**
   * A stands for the alpha threshold
   */
  private static final int AK = 4;

  private Slush() {
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
  public Protocol copy() {
    return new Slush();
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

        if (asw.countInM < M) {
          sendQuery(asw.countInM + 1);
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
    final int countInM;
    private final int[] colorsFound = new int[COLOR_NB + 1];

    Answer(int countInM) {
      this.countInM = countInM;
    }

    int answerCount() {
      int sum = 0;
      for (int i : colorsFound) {
        sum += i;
      }
      return sum;
    }
  }


  public static void main(String... args) {
    NetworkLatency nl = new NetworkLatency.NetworkLatencyByDistance();

    System.out.println("" + nl);
    //for (int i = 0; i < 1000; i++) {
    //Instantiate protocol
    Slush sp = new Slush();
    // Set network latency
    sp.network.setNetworkLatency(nl);
    //Initialize with 100 nodes
    sp.init();
    SlushNode uncolored1 = sp.network().getNodeById(0);
    SlushNode uncolored2 = sp.network().getNodeById(1);

    uncolored1.myColor = 1;
    uncolored1.sendQuery(1);

    uncolored2.myColor = 2;
    uncolored2.sendQuery(1);

    sp.network.runMs(20000);
    System.out.println("Finished, " + sp.network.msgs.size() + " messages");

    System.out.println("N=" + NODES_AV + ", K=" + K + ", AK=" + AK + " M=" + sp.M);
    int[] res = new int[COLOR_NB + 1];
    for (SlushNode n : sp.network.allNodes) {
      res[n.myColor]++;
    }

    for (int i = 0; i < COLOR_NB + 1; i++) {
      System.out.println(i + ":" + res[i]);
    }
  }
}
