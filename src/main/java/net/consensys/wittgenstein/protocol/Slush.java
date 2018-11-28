package net.consensys.wittgenstein.protocol;

import com.sun.jdi.connect.Connector;
import net.consensys.wittgenstein.core.Network;
import net.consensys.wittgenstein.core.NetworkLatency;
import net.consensys.wittgenstein.core.Protocol;
import net.consensys.wittgenstein.core.Node;
import java.util.*;

public class Slush implements Protocol {

  private static final int NODES_AV = 100;
  private final Network<SlushNode> network = new Network<>();
  private static final Random rd = new Random();
  final Node.NodeBuilder nb;
  private static final int COLOR_NB = 2;
  private int count = 0;
  private int actionCt = 0;
  /*
  m is the number of rounds
   */
  private final int M = 5;
  /*
  K is the sample size you take
   */
  private static final int K = 7;
  /*
  A stands for the alpha treshold
   */
  private static final int AK = 4;
  private static final int BYZANTINE_NODES_NB = 10;
  private Slush.SlushNode[] nodes = new Slush.SlushNode[NODES_AV];

  public Slush() {
    this.nb = new Node.NodeBuilderWithRandomPosition();
  }

  @Override
  public void init() {
    int badNodes = BYZANTINE_NODES_NB;
    for (int i = 0; i < NODES_AV; i++) {
      network.addNode(new SlushNode(rd, nb, badNodes-- > 0 ? true : false, i));
      nodes[i] = network.getNodeById(i);
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

  class ReceiveQuery extends Network.Message<SlushNode> {

    @Override
    public void action(SlushNode from, SlushNode to) {
      //to.receiveQuery();
    }
  }

  static class SendQuery extends Network.Message<SlushNode> {
    final Query querySent;

    public SendQuery(Query query) {
      this.querySent = query;
    }

    @Override
    public void action(SlushNode from, SlushNode to) {
      to.sendQuery(querySent);
      //to.receiveQuery();
    }
  }

  static class AnswerQuery extends Network.Message<SlushNode> {
    final Query queryAnswer;

    public AnswerQuery(Query query) {
      this.queryAnswer = query;
    }

    @Override
    public void action(SlushNode from, SlushNode to) {
      to.sendAnswer(queryAnswer);
    }
  }

  class SlushNode extends Node {
    final int myId;
    int myColor = 0;
    int myQueryNonce;
    final HashMap<Integer, Answer> answerIP = new HashMap<>();

    public SlushNode(Random rd, NodeBuilder nb, boolean byzantine, int myId) {
      super(rd, nb, false);
      this.myId = myId;
    }

    List<Integer> getRandomRemotes() {
      List<Integer> res = new ArrayList<>(K);

      while (res.size() != K) {
        int r = rd.nextInt(NODES_AV);
        if (r != myId)
          res.add(r);
      }

      return res;
    }

    void sendAnswer(Query qa) {
      ++myQueryNonce;
      Answer aa = new Answer(qa.color, qa.answerId);
      answerIP.put(myQueryNonce, aa);
    }

    int otherColor() {
      return myColor == 1 ? 2 : 1;
    }

    /**
     * Upon receiving a query, an uncolored node adopts the color in the query, responds with that
     * color, and initiates its own query, whereas a colored node simply responds with its current
     * color.
     */
    public void receiveQuery(Query qa, SlushNode to) {
      if (myColor == 0) {
        myColor = qa.color;
        //sendQuery(to,qa.color,qa.answerId);
        network.send(new SendQuery(qa), this, to);
      }
      //Send your color
      Answer qaaa = new Answer(to.myColor, qa.answerId);
      network.send(new AnswerQuery(qaaa), to, this);

      // receiveQueryAnswer(qaaa);

    }

    /*
     * Once the querying node collects k responses, it checks if a fraction ≥ αk are for the same
     * color, where α > 0.5 is a protocol parameter. If the αk threshold is met and the sampled
     * color differs from the node’s own color, the node flips to that color.
     */
    public void receiveQueryAnswer(Query qaa) {
      Answer asw = answerIP.get(qaa.answerId);
      asw.colorsFound[qaa.color]++;
      // in this case we assume that messages received correspond to the query answers
      if (asw.answerCount() == K) {
        answerIP.remove(qaa.answerId);
        if (asw.colorsFound[otherColor()] > AK) {
          myColor = otherColor();
        }
        count++;
      }
      if (count < M) {
        sendQuery(qaa);
      }
      actionCt++;
    }

    void sendQuery(Query query) {
      ++myQueryNonce;
      Answer asw = new Answer(query.color, query.answerId);
      answerIP.put(myQueryNonce, asw);
      for (int r : getRandomRemotes()) {
        receiveQuery(asw, nodes[r]);
      }
    }
  }

  static class Query {

    final int color;
    final int answerId;

    Query(int color, int answerId) {
      this.color = color;
      this.answerId = answerId;
    }


  }

  static class Answer extends Query {

    private final int[] colorsFound = new int[COLOR_NB + 1];

    Answer(int color, int answerId) {
      super(color, answerId);
    }

    int answerCount() {
      int sum = 0;
      for (int i : colorsFound)
        sum += i;
      return sum;
    }
  }

  void stats() {
    int std = 0;
    int byz = 0;

    for (SlushNode n : nodes) {
      if (n.byzantine)
        byz++;
      else
        std++;

    }
    System.out.println("N=" + nodes.length + ", K=" + K + ", AK=" + AK);
    System.out.println("Nodes: standards=" + std + ", adversary=" + byz);


    int[] res = new int[COLOR_NB + 1];
    for (SlushNode n : nodes) {
      res[n.myColor]++;
    }

    System.out.println("actions count:" + actionCt);
    for (int i = 0; i < COLOR_NB + 1; i++) {
      System.out.println(i + ":" + res[i]);
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

    sp.network.send(new SendQuery(new Query(1, -1)), uncolored2, uncolored1);
    sp.network.send(new SendQuery(new Query(2, -1)), uncolored1, uncolored2);
    sp.network.runMs(10000);
    System.out.println("Finished");

    System.out.println("N=" + sp.nodes.length + ", K=" + K + ", AK=" + AK);
    int[] res = new int[COLOR_NB + 1];
    for (SlushNode n : sp.nodes) {
      res[n.myColor]++;
    }

    System.out.println("actions count:" + sp.actionCt);
    for (int i = 0; i < COLOR_NB + 1; i++) {
      System.out.println(i + ":" + res[i]);
    }
    //}

  }

}
