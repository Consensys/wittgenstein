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
  private final int AK = 4;
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
      count++;
    }
  }
  static class SendQuery extends Network.Message<SlushNode> {

    @Override
    public void action(SlushNode from, SlushNode to) {
      to.sendQuery();
    }
  }

  static class AnswerQuery extends Network.Message<SlushNode> {
    final Query queryAnswer;

    public AnswerQuery(Query query){
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
    final HashMap<Integer, Query> answerIP = new HashMap<>();

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
    void sendAnswer(Query qa){++myQueryNonce;answerIP.put(myQueryNonce,qa);}

    int otherColor() {
      return myColor == 1 ? 2 : 1;
    }

    /**
     * Upon receiving a query, an uncolored node adopts the color in the query, responds with that
     * color, and initiates its own query, whereas a colored node simply responds with its current
     * color.
     */
    public void receiveQuery(Query qa,SlushNode to) {
      if (myColor == 0) {
        myColor = qa.color;
        //sendQuery(to,qa.color,qa.answerId);
        //network.send(new SendQuery(),this,to);
        sendQuery();
      }
      //Send your color
      network.send(new AnswerQuery(qa),this,to);
    }
    /*
     * Once the querying node collects k responses, it checks if a fraction ≥ αk are for the same
     * color, where α > 0.5 is a protocol parameter. If the αk threshold is met and the sampled
     * color differs from the node’s own color, the node flips to that color.
     */
    public void receiveQueryAnswer(Query qaa) {
      Query asw = answerIP.get(qaa.answerId);
      asw.colorsFound[qaa.color]++;
      // in this case we assume that messages received correspond to the query answers
      if (asw.answerCount() == K) {
        answerIP.remove(qaa.answerId);
        if (asw.colorsFound[otherColor()] > AK) {
          myColor = otherColor();
        }
        count++;
      }
      if(count < M){
        sendQuery();
      }
    }

    void sendQuery() {
      ++myQueryNonce;
      Query asw = new Query(this.myColor,this.myId);
      answerIP.put(myQueryNonce, asw);
      for (int r : getRandomRemotes()) {
        network.send(new SendQuery(),this,nodes[r]);
      }
    }

  }

  class Query {

    final int color;
    final int answerId;
    private final int[] colorsFound = new int[COLOR_NB + 1];

    Query(int color, int answerId){
      this.color = color;
      this.answerId = answerId;
    }

    int answerCount() {
      int sum = 0;
      for (int i : colorsFound)
        sum += i;
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
      SlushNode uncolored = sp.network().getNodeById(0);
      List<Integer> kNodes = uncolored.getRandomRemotes();
      System.out.println(kNodes.get(0));
      int cnt = 0;
      while (uncolored.getMsgReceived() < K) {
        sp.network.send(new SendQuery(), uncolored, sp.network.getNodeById(kNodes.get(cnt)));
        cnt++;
      }

      System.out.println("Finished");

    //}


  }
}
