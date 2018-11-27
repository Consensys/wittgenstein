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
  // private Slush.SlushNode[] nodes = new Slush.SlushNode[NODES_AV];

  public Slush() {
    this.nb = new Node.NodeBuilderWithRandomPosition();
  }

  @Override
  public void init() {
    int badNodes = BYZANTINE_NODES_NB;
    for (int i = 0; i < NODES_AV; i++) {
      network.addNode(new SlushNode(rd, nb, badNodes-- > 0 ? true : false, i));
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
      to.sendQuery(from,from.myColor,from.myId);
    }
  }

  static class AnswerQuery extends Network.Message<SlushNode> {

    @Override
    public void action(SlushNode from, SlushNode to) {
      to.answerQuery(from);
    }
  }

  class SlushNode extends Node {
    final int myId;
    int myColor = 0;
    int myQueryNonce;

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

    int otherColor() {
      return myColor == 1 ? 2 : 1;
    }

    /**
     * Upon receiving a query, an uncolored node adopts the color in the query, responds with that
     * color, and initiates its own query, whereas a colored node simply responds with its current
     * color.
     */
    public void receiveQuery(Query qa) {
      if (myColor == 0) {
        myColor = qa.color;
        //sendQuery(qa.color,qa.answerId);
      }


    }

    /*
        1) Initialy a node starts in an uncolored state
        2) Upon receiving a transaction from a client an uncolored node updates
        its own color to the one carried in the transaction And initiates a query.
     */
    public void receiveQueryAnswer(Query qaa) {
      if (myColor == 0) {
        myColor = qaa.color;
      }
      // in this case we assume that messages received correspond to the query answers
      while (this.msgReceived <= K) {

      }

    }

    public void sendQuery(SlushNode to,int color, int answerId) {
        network.send(new SendQuery(),to,this);
    }

    public void answerQuery(SlushNode from) {
      network.send(new AnswerQuery(), this, from);
    }
  }

  class Query {

    final int color;
    final int answerId;

    Query(int color, int answerId){
      this.color = color;
      this.answerId = answerId;
    }
  }
  /*
  class QueryAnswer {
    private final int[] colorsFound = new int[COLOR_NB + 1];

    int answerCount() {
      int sum = 0;
      for (int i : colorsFound)
        sum += i;
      return sum;
    }
  }

  abstract class Action {
    protected final SlushNode orig;
    protected final SlushNode dest;

    abstract void run();

    Action(SlushNode orig, SlushNode dest) {
      this.orig = orig;
      this.dest = dest;
    }
  }

  class QueryAnswerAction extends Action {
    final int color;
    final int answerId;
    final QueryAction cause;


    @Override
    public void run() {
      if (dest != null)
        dest.receiveQueryAnswer(this);
    }

    QueryAnswerAction(QueryAction cause, int color) {
      super(cause.dest, cause.orig);
      this.cause = cause;
      this.answerId = cause.answerId;
      this.color = color;
    }
  }

  class QueryAction extends Action {
    final int answerId;
    final int color;

    @Override
    public void run() {
      dest.receiveQuery(this);
    }

    public QueryAction(SlushNode orig, SlushNode dest, int answerId, int color) {
      super(orig, dest);
      this.answerId = answerId;
      this.color = color;
    }

  }

  private LinkedList<Action> inProgress = new LinkedList<>();
*/
  public static void main(String... args) {

    NetworkLatency nl = new NetworkLatency.NetworkLatencyByDistance();

    System.out.println("" + nl);
    for (int i = 0; i < 1000; i++) {
      //Instantiate protocol
      Slush sp = new Slush();
      // Set network latency
      sp.network.setNetworkLatency(nl);
      //Initialize with 100 nodes
      sp.init();
      SlushNode uncolored = sp.network().getNodeById(0);
      List<Integer> kNodes = uncolored.getRandomRemotes();
      int cnt = 0;
      while (uncolored.getMsgReceived() < K) {
        sp.network.send(new SendQuery(), uncolored, sp.network.getNodeById(kNodes.get(cnt)));
        cnt++;
      }



    }


  }
}
