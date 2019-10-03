package net.consensys.wittgenstein.protocols;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;
import net.consensys.wittgenstein.core.*;
import net.consensys.wittgenstein.core.messages.Message;
import net.consensys.wittgenstein.core.utils.MoreMath;
import net.consensys.wittgenstein.core.utils.StatsHelper;
import net.consensys.wittgenstein.tools.Graph;

/**
 * San Fermin's protocol adapted to BLS signature aggregation.
 *
 * <p>San Fermin is a protocol for distributed aggregation of a large data set over a large set of
 * nodes. It imposes a special binomial tree structures on the communication patterns of the node so
 * that each node only needs to contact O(log(n)) other nodes to get the final aggregated result.
 * SanFerminNode{nodeId=1000000001, doneAt=4860, sigs=874, msgReceived=272, msgSent=275,
 * KBytesSent=13, KBytesReceived=13, outdatedSwaps=0}
 */
@SuppressWarnings("WeakerAccess")
public class SanFerminCappos implements Protocol {
  SanFerminParameters params;
  final Network<SanFerminNode> network;
  final NodeBuilder nb;

  public List<SanFerminNode> getAllNodes() {
    return allNodes;
  }

  /**
   * allNodes represent the full list of nodes present in the system. NOTE: This assumption that a
   * node gets access to the full list can be dismissed, as they do in late sections in the paper.
   * For a first version and sake of simplicity, full knowledge of the peers is assumed here. A
   * partial knowledge graph can be used, where nodes will send requests to unknown ID "hoping" some
   * unknown peers yet can answer. This technique works because it uses Pastry ID allocation
   * mechanism. Here no underlying DHT or p2p special structure is assumed.
   */
  public List<SanFerminNode> allNodes;

  public static class SanFerminParameters extends WParameters {
    /** The number of nodes in the network */
    final int nodeCount;

    /**
     * The time it takes to do a pairing for a node i.e. simulation of the most heavy computation
     */
    final int pairingTime;

    /** Size of a BLS signature (can be aggregated or not) */
    final int signatureSize;

    /** Do we print logging information from nodes or not */
    boolean verbose;

    /** how many candidate do we try to reach at the same time for a given level */
    int candidateCount;

    /**
     * Threshold is the ratio of number of actual contributions vs number of expected contributions
     * in a given range level
     */
    int threshold;

    /** timeout after which to pass on to the next level in ms */
    int timeout;

    final String nodeBuilderName;
    final String networkLatencyName;

    public List<SanFerminNode> finishedNodes;

    public SanFerminParameters() {
      this.nodeCount = 32768 / 16;
      this.pairingTime = 2;
      this.signatureSize = 48;
      this.candidateCount = 50;
      this.threshold = 32768 / 32;
      this.timeout = 150;
      this.nodeBuilderName = null;
      this.networkLatencyName = null;
    }

    public SanFerminParameters(
        int nodeCount,
        int threshold,
        int pairingTime,
        int signatureSize,
        int timeout,
        int candidateCount,
        String nodeBuilderName,
        String networkLatencyName) {
      this.nodeCount = nodeCount;
      this.pairingTime = pairingTime;
      this.signatureSize = signatureSize;
      this.candidateCount = candidateCount;
      this.threshold = threshold;
      this.timeout = timeout;
      this.nodeBuilderName = nodeBuilderName;
      this.networkLatencyName = networkLatencyName;
    }
  }

  public SanFerminCappos(SanFerminParameters params) {
    this.params = params;
    this.network = new Network<>();
    this.nb = RegistryNodeBuilders.singleton.getByName(params.nodeBuilderName);
    this.network.setNetworkLatency(
        RegistryNetworkLatencies.singleton.getByName(params.networkLatencyName));
  }

  @Override
  public Network<SanFerminNode> network() {
    return network;
  }

  /** init makes each node starts swapping with each other when the network starts */
  @Override
  public void init() {
    this.allNodes = new ArrayList<>(params.nodeCount);
    for (int i = 0; i < params.nodeCount; i++) {
      final SanFerminNode n = new SanFerminNode(this.nb);
      this.allNodes.add(n);
      this.network.addNode(n);
    }

    // compute candidate set once all peers have been created
    for (SanFerminNode n : allNodes) n.helper = new SanFerminHelper<>(n, allNodes, this.network.rd);

    params.finishedNodes = new ArrayList<>();
    for (SanFerminNode n : allNodes) network.registerTask(n::goNextLevel, 1, n);
  }

  public SanFerminCappos copy() {
    return new SanFerminCappos(params);
  }

  /**
   * SanFerminNode is a node that carefully selects the peers he needs to contact to get the final
   * aggregated result
   */
  public class SanFerminNode extends Node {
    /** The node's id in binary string form */
    public final String binaryId;

    @JsonIgnore private SanFerminHelper<SanFerminNode> helper;

    /**
     * This node needs to exchange with another node having a current common prefix length of
     * currentPrefixLength. A node starts by the highest possible prefix length and works towards a
     * prefix length of 0.
     */
    public int currentPrefixLength;

    /** Set of received signatures at each level. */
    HashMap<Integer, List<Integer>> signatureCache;
    /**
     * isSwapping indicates whether we are in a state where we can swap at the current level or not.
     * This is acting as a Lock, since between the time we receive a valid swap and the time we
     * aggregate it, we need to verify it. In the meantime, another valid swap can come and thus we
     * would end up swapping twice at a given level.
     */
    boolean isSwapping;

    /**
     * Integer field that simulate an aggregated signature badly. It keeps increasing as the node do
     * more swaps. It assumes each node has the value "1" and the aggregation operation is the
     * addition.
     */
    int aggValue;

    /** time when threshold of signature is reached */
    long thresholdAt;
    /** have we reached the threshold or not */
    boolean thresholdDone;

    /** Are we done yet or not */
    boolean done;

    public SanFerminNode(NodeBuilder nb) {
      super(network.rd, nb);
      this.binaryId = SanFerminHelper.toBinaryID(this, params.nodeCount);
      this.done = false;
      this.thresholdDone = false;
      this.aggValue = 1;
      this.isSwapping = false;
      // node should start at n-1 with N = 2^n
      // this counter gets decreased with `goNextLevel`.
      this.currentPrefixLength = MoreMath.log2(params.nodeCount);
      this.signatureCache = new HashMap<>();
    }

    /**
     * onSwap checks if it is a swap a the current level and from a candidate node. If it is, it
     * reply with his own swap, aggregates the value and move on to the next level. If it is not at
     * the current level, it replies with a cached value if it has, or save the value for later if
     * valid. If it is not from a candidate set, then it drops the message.
     */
    public void onSwap(SanFerminNode from, Swap swap) {
      boolean wantReply = swap.wantReply;
      if (done || swap.level != this.currentPrefixLength) {
        boolean isValueCached = this.signatureCache.containsKey(swap.level);
        if (wantReply && isValueCached) {
          print(
              "sending back CACHED signature at level " + swap.level + " to node " + from.binaryId);
          this.sendSwap(
              Collections.singletonList(from),
              swap.level,
              this.getBestCachedSig(swap.level),
              false);
        } else {
          // it's a value we might want to keep for later!
          boolean isCandidate = this.helper.isCandidate(from, swap.level);
          boolean isValidSig = true; // as always :)
          if (isCandidate && isValidSig) {
            // it is a good request we can save for later!
            this.putCachedSig(swap.level, swap.aggValue);
          }
        }
        return;
      }

      if (wantReply) {
        this.sendSwap(
            Collections.singletonList(from), swap.level, this.totalNumberOfSigs(swap.level), false);
      }

      // accept if it is a valid swap !
      boolean goodLevel = swap.level == currentPrefixLength;
      boolean isCandidate = this.helper.isCandidate(from, currentPrefixLength);
      boolean isValidSig = true; // as always :)
      if (isCandidate && goodLevel && isValidSig) {
        if (!isSwapping)
          transition(" received valid SWAP ", from.binaryId, swap.level, swap.aggValue);
      } else {
        print(" received  INVALID Swap" + "from " + from.binaryId + " at level " + swap.level);
        print("   ---> " + isValidSig + " - " + goodLevel + " - " + isCandidate);
      }
    }

    /**
     * tryNextNodes simply picks the next eligible candidate from the list and send a swap request
     * to it. It attaches a timeout to the request. If no SwapReply has been received before
     * timeout, tryNextNodes() will be called again.
     */
    private void tryNextNodes(List<SanFerminNode> candidates) {
      if (candidates.size() == 0) {
        // when malicious actors are introduced or some nodes are
        // failing this case can happen. In that case, the node
        // should go to the next level since he has nothing better to
        // do. The final aggregated signature will miss this level
        // but it may still reach the threshold and can be completed
        // later on, through the help of a bit field.
        print(" is OUT (no more " + "nodes to pick)");
        return;
      }
      candidates.stream()
          .filter(n -> !helper.isCandidate(n, currentPrefixLength))
          .forEach(
              n -> {
                System.out.println(
                    "currentPrefixlength="
                        + currentPrefixLength
                        + " vs helper.currentLevel="
                        + helper.currentLevel);
                throw new IllegalStateException();
              });

      print(
          " send Swaps to "
              + candidates.stream().map(n -> n.binaryId).collect(Collectors.joining(" - ")));
      this.sendSwap(
          candidates,
          this.currentPrefixLength,
          this.totalNumberOfSigs(this.currentPrefixLength + 1),
          true);

      int currLevel = this.currentPrefixLength;
      network.registerTask(
          () -> {
            // If we are still waiting on an answer for this level, we
            // try a new one.
            if (!SanFerminNode.this.done && SanFerminNode.this.currentPrefixLength == currLevel) {
              print("TIMEOUT of SwapRequest at level " + currLevel);
              // that means we haven't got a successful reply for that
              // level so we try another node
              List<SanFerminNode> nextNodes =
                  this.helper.pickNextNodes(this.currentPrefixLength, params.candidateCount);
              tryNextNodes(nextNodes);
            }
          },
          network.time + params.timeout,
          SanFerminNode.this);
    }

    /**
     * goNextLevel reduces the required length of common prefix of one, computes the new set of
     * potential nodes and sends an invitation to the "next" one. There are many ways to select the
     * order of which node to choose. In case the number of nodes is 2^n, there is a 1-to-1 mapping
     * that exists so that each node has exactly one unique node to swap with. In case it's not,
     * there are going to be some nodes who will be unable to continue the protocol since the
     * "chosen" node will likely already have swapped. See `pickNextNode` for more information.
     */
    private void goNextLevel() {

      if (done) {
        return;
      }

      boolean enoughSigs = totalNumberOfSigs(this.currentPrefixLength) >= params.threshold;
      boolean noMoreSwap = this.currentPrefixLength == 0;

      if (enoughSigs && !thresholdDone) {
        print(" --- THRESHOLD REACHED --- ");
        thresholdDone = true;
        thresholdAt = network.time + params.pairingTime * 2;
      }

      if (noMoreSwap && !done) {
        print(" --- FINISHED ---- protocol");
        doneAt = network.time + params.pairingTime * 2;
        params.finishedNodes.add(this);
        done = true;
        return;
      }
      this.currentPrefixLength--;
      this.isSwapping = false;

      if (signatureCache.containsKey(currentPrefixLength)) {
        print(
            " FUTURe value at new level"
                + currentPrefixLength
                + " "
                + "saved. Moving on directly !");
        // directly go to the next level !
        goNextLevel();
        return;
      }
      List<SanFerminNode> newNodes =
          this.helper.pickNextNodes(currentPrefixLength, params.candidateCount);
      this.tryNextNodes(newNodes);
    }

    private void sendSwap(List<SanFerminNode> nodes, int level, int value, boolean wantReply) {
      Swap r = new Swap(level, value, wantReply);
      network.send(r, SanFerminNode.this, nodes);
    }

    public int totalNumberOfSigs(int level) {
      return this.signatureCache.entrySet().stream()
              .filter(entry -> entry.getKey() >= level)
              .map(Map.Entry::getValue)
              .map(list -> list.stream().max(Comparator.naturalOrder()).get())
              .reduce(0, Integer::sum)
          + 1; // +1 for own sig
    }

    /**
     * Transition prevents any more aggregation at this level, and launch the "verification routine"
     * and move on to the next level. The first three parameters are only here for logging purposes.
     */
    private void transition(String type, String fromId, int level, int toAggregate) {
      this.isSwapping = true;
      network.registerTask(
          () -> {
            print(" received " + type + " lvl=" + level + " from " + fromId);
            this.putCachedSig(level, toAggregate);
            this.goNextLevel();
          },
          network.time + params.pairingTime,
          this);
    }

    private int getBestCachedSig(int level) {
      List<Integer> cached = this.signatureCache.getOrDefault(level, new ArrayList<>());
      int max = cached.stream().reduce(Integer::max).get();
      return max;
    }

    private void putCachedSig(int level, int value) {
      List<Integer> list = this.signatureCache.getOrDefault(level, new ArrayList<>());
      list.add(value);
      this.signatureCache.put(level, list);
      boolean enoughSigs = totalNumberOfSigs(this.currentPrefixLength) >= params.threshold;

      if (enoughSigs && !thresholdDone) {
        print(" --- THRESHOLD REACHED --- ");
        thresholdDone = true;
        thresholdAt = network.time + params.pairingTime * 2;
      }
    }

    public long getDoneAt() {
      return doneAt;
    }

    /** simple helper method to print node info + message */
    private void print(String s) {
      if (params.verbose)
        System.out.println(
            "t="
                + network.time
                + ", id="
                + this.binaryId
                + ", lvl="
                + this.currentPrefixLength
                + ", sent="
                + this.msgSent
                + " -> "
                + s);
    }

    @Override
    public String toString() {
      return "SanFerminNode{"
          + "nodeId="
          + binaryId
          + ", thresholdAt="
          + thresholdAt
          + ", doneAt="
          + doneAt
          + ", sigs="
          + totalNumberOfSigs(-1)
          + ", msgReceived="
          + msgReceived
          + ", "
          + "msgSent="
          + msgSent
          + ", KBytesSent="
          + bytesSent / 1024
          + ", KBytesReceived="
          + bytesReceived / 1024
          + '}';
    }
  }

  class Swap extends Message<SanFerminNode> {

    boolean wantReply; // indicate that the other needs a reply to this
    // Swap
    final int level;
    int aggValue; // see Reply.aggValue
    // String data -- no need to specify it, but only in the size() method

    public Swap(int level, int aggValue, boolean reply) {
      this.level = level;
      this.wantReply = reply;
      this.aggValue = aggValue;
    }

    @Override
    public void action(Network<SanFerminNode> network, SanFerminNode from, SanFerminNode to) {
      to.onSwap(from, this);
    }

    @Override
    public int size() {
      // uint32 + sig size
      return 4 + params.signatureSize;
    }
  }

  public static void sigsPerTime() {
    String nl = NetworkLatency.NetworkLatencyByDistanceWJitter.class.getSimpleName();
    String nb = RegistryNodeBuilders.name(RegistryNodeBuilders.Location.RANDOM, true, 0);
    int nodeCt = 32768 / 2;

    SanFerminCappos ps1 =
        new SanFerminCappos(new SanFerminParameters(nodeCt, nodeCt / 2, 2, 48, 150, 50, nb, nl));

    Graph graph = new Graph("number of sig per time", "time in ms", "sig count");
    Graph.Series series1min = new Graph.Series("sig count - worse node");
    Graph.Series series1max = new Graph.Series("sig count - best node");
    Graph.Series series1avg = new Graph.Series("sig count - avg");
    graph.addSerie(series1min);
    graph.addSerie(series1max);
    graph.addSerie(series1avg);

    ps1.init();

    StatsHelper.SimpleStats s;
    final long limit = 6000;
    do {
      ps1.network.runMs(10);
      s =
          StatsHelper.getStatsOn(
              ps1.allNodes,
              n -> {
                SanFerminNode sfn = ((SanFerminNode) n);
                return sfn.totalNumberOfSigs(-1);
              });
      series1min.addLine(new Graph.ReportLine(ps1.network.time, s.min));
      series1max.addLine(new Graph.ReportLine(ps1.network.time, s.max));
      series1avg.addLine(new Graph.ReportLine(ps1.network.time, s.avg));
    } while (ps1.network.time < limit);

    try {
      graph.save(new File("graph.png"));
    } catch (IOException e) {
      System.err.println("Can't generate the graph: " + e.getMessage());
    }

    System.out.println("bytes sent: " + StatsHelper.getStatsOn(ps1.allNodes, Node::getBytesSent));
    System.out.println(
        "bytes rcvd: " + StatsHelper.getStatsOn(ps1.allNodes, Node::getBytesReceived));
    System.out.println("msg sent: " + StatsHelper.getStatsOn(ps1.allNodes, Node::getMsgSent));
    System.out.println("msg rcvd: " + StatsHelper.getStatsOn(ps1.allNodes, Node::getMsgReceived));
    System.out.println(
        "done at: "
            + StatsHelper.getStatsOn(
                ps1.network.allNodes,
                n -> {
                  long val = n.getDoneAt();
                  return val == 0 ? limit : val;
                }));
  }

  public static void main(String... args) {
    sigsPerTime();
  }
}
