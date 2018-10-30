package net.consensys.wittgenstein.protocol;

import net.consensys.wittgenstein.core.*;
import net.consensys.wittgenstein.core.utils.MoreMath;
import net.consensys.wittgenstein.core.utils.StatsHelper;
import net.consensys.wittgenstein.tools.Graph;
import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * A p2p protocol for BLS signature aggregation.
 * <p>
 * A node: Sends its states to all its direct peers whenever it changes Keeps the list of the states
 * of its direct peers Sends, every x milliseconds, to one of its peers a set of missing signatures
 * Runs in parallel a task to validate the signatures sets it has received. Send only validated
 * signatures to its peers.
 */
@SuppressWarnings("WeakerAccess")
public class P2PSignature implements Protocol {

  /**
   * The number of nodes in the network
   */
  final int nodeCount;

  /**
   * The number of signatures to reach to finish the protocol.
   */
  final int threshold;

  /**
   * The typical number of peers a peer has. At least 3.
   */
  final int connectionCount;

  /**
   * The time it takes to do a pairing for a node.
   */
  final int pairingTime;

  /**
   * The protocol sends a set of sigs every 'sigsSendPeriod' milliseconds
   */
  final int sigsSendPeriod;

  /**
   * @see P2PSigNode#sendSigs for the two strategies on aggregation.
   */
  final boolean doubleAggregateStrategy;

  /**
   * If true the nodes send their state to the peers they are connected with. If false they don't.
   */
  final boolean withState = true;

  /**
   * Use san fermin in parallel with gossiping.
   */
  final boolean sanFermin;


  /**
   *
   */
  enum SendSigsStrategy {
    all, // send all signatures, not taking the state into account
    dif, // send just the diff (we need the state of the other nodes for this)
    cmp_all, // send all the signatures, but compress them
    cmp_diff // compress, but take into account what was already sent.
  }


  /**
   * For the compression scheme: we can use log 2 (the default or other values).
   */
  final int sigRange;


  final SendSigsStrategy sendSigsStrategy;


  final P2PNetwork network;
  final Node.NodeBuilder nb;

  public P2PSignature(int nodeCount, int threshold, int connectionCount, int pairingTime,
      int sigsSendPeriod, boolean doubleAggregateStrategy, boolean sanFermin,
      SendSigsStrategy sendSigsStrategy, int sigRange) {
    this.nodeCount = nodeCount;
    this.threshold = threshold;
    this.connectionCount = connectionCount;
    this.pairingTime = pairingTime;
    this.sigsSendPeriod = sigsSendPeriod;
    this.doubleAggregateStrategy = doubleAggregateStrategy;
    this.sanFermin = sanFermin;
    this.sendSigsStrategy = this.sanFermin ? SendSigsStrategy.cmp_all : sendSigsStrategy;
    this.sigRange = sigRange;
    this.network = new P2PNetwork(connectionCount);
    this.nb = new Node.NodeBuilderWithRandomPosition(network.rd);
  }

  static class State extends Network.Message<P2PSigNode> {
    final BitSet desc;
    final P2PSigNode who;

    public State(P2PSigNode who) {
      this.desc = (BitSet) who.verifiedSignatures.clone();
      this.who = who;
    }

    /**
     * By convention, all the last bits are implicitly set to zero, so we don't always have to
     * transport the full state.
     */
    @Override
    public int size() {
      return Math.max(1, desc.length() / 8);
    }

    @Override
    public void action(P2PSigNode from, P2PSigNode to) {
      to.onPeerState(this);
    }
  }

  /**
   * Calculate the number of signatures we have to include is we apply a compression strategy
   * Strategy is: - we divide the bitset in ranges of size sigRange - all the signatures at the
   * beginning of a range are aggregated, until one of them is not available.
   * <p>
   * Example for a range of size 4:</br>
   * 1101 0111 => we have 5 sigs instead of 6</br>
   * 1111 1110 => we have 2 sigs instead of 7</br>
   * 0111 0111 => we have 6 sigs </br>
   * <p>
   * Note that we don't aggregate consecutive ranges, because we would not be able to merge bitsets
   * later.</br>
   * For example, still with a range of for, with two nodes:</br>
   * node 1: 1111 1111 0000 => 2 sigs, and not 1</br>
   * node 2: 0000 1111 1111 => again, 2 sigs and not 1</br>
   * <p>
   * By keeping the two aggregated signatures node 1 & node 2 can exchange aggregated signatures.
   * 1111 1111 => 1 0001 1111 1111 0000 => 3 0001 1111 1111 1111 => 2 </>
   *
   * @return the number of signatures to include
   */
  int compressedSize(BitSet sigs) {
    if (sigs.length() == nodeCount) {
      // Shortcuts: if we have all sigs, then we just send
      //  an aggregated signature
      return 1;
    }

    int firstOneAt = -1;
    int sigCt = 0;
    int pos = -1;
    boolean compressing = false;
    boolean wasCompressing = false;
    while (++pos <= sigs.length() + 1) {
      if (!sigs.get(pos)) {
        compressing = false;
        sigCt -= mergeRanges(firstOneAt, pos);
        firstOneAt = -1;
      } else if (compressing) {
        if ((pos + 1) % sigRange == 0) {
          // We compressed the whole range, but now we're starting a new one...
          compressing = false;
          wasCompressing = true;
        }
      } else {
        sigCt++;
        if (pos % sigRange == 0) {
          compressing = true;
          if (!wasCompressing) {
            firstOneAt = pos;
          } else {
            wasCompressing = false;
          }
        }
      }
    }

    return sigCt;
  }

  /**
   * Merging can be combined, so this function is recursive. For example, for a range size of 2, if
   * we have 11 11 11 11 11 11 11 11 11 11 11 => 11 sigs w/o merge.</br>
   * This should become 3 after merge: the first 8, then the second set of two blocks
   */
  private int mergeRanges(int firstOneAt, int pos) {
    if (firstOneAt < 0) {
      return 0;
    }
    // We start only at the beginning of a range
    if (firstOneAt % (sigRange * 2) != 0) {
      firstOneAt += (sigRange * 2) - (firstOneAt % (sigRange * 2));
    }

    int rangeCt = (pos - firstOneAt) / sigRange;
    if (rangeCt < 2) {
      return 0;
    }

    int max = MoreMath.log2(rangeCt);
    while (max > 0) {
      int sizeInBlocks = (int) Math.pow(2, max);
      int size = sizeInBlocks * sigRange;
      if (firstOneAt % size == 0) {
        return (sizeInBlocks - 1) + mergeRanges(firstOneAt + size, pos);
      }
      max--;
    }

    return 0;
  }

  static class SendSigs extends Network.Message<P2PSigNode> {
    final BitSet sigs;
    final int size;

    public SendSigs(BitSet sigs) {
      this(sigs, sigs.cardinality());
    }

    public SendSigs(BitSet sigs, int sigCount) {
      this.sigs = (BitSet) sigs.clone();
      // Size = bit field + the signatures included
      this.size = sigs.length() / 8 + sigCount * 48;
    }


    @Override
    public int size() {
      return size;
    }


    @Override
    public void action(P2PSigNode from, P2PSigNode to) {
      to.onNewSig(sigs);
    }
  }


  public class P2PSigNode extends P2PNode {
    final BitSet verifiedSignatures = new BitSet(nodeCount);
    final Set<BitSet> toVerify = new HashSet<>();
    final Map<Integer, State> peersState = new HashMap<>();

    boolean done = false;
    long doneAt = 0;

    P2PSigNode() {
      super(nb);
      verifiedSignatures.set(nodeId, true);
    }

    public BitSet sanFerminPeers(int round) {
      if (round < 1) {
        throw new IllegalArgumentException("round=" + round);
      }
      BitSet res = new BitSet(nodeCount);
      int cMask = (1 << round) - 1;
      int start = (cMask | nodeId) ^ cMask;
      int end = nodeId | cMask;
      end = Math.min(end, nodeCount - 1);
      res.set(start, end + 1);
      res.set(nodeId, false);

      return res;
    }


    /**
     * Asynchronous, so when we receive a state it can be an old one.
     */
    void onPeerState(State state) {
      int newCard = state.desc.cardinality();
      State old = peersState.get(state.who.nodeId);

      if (newCard < threshold && (old == null || old.desc.cardinality() < newCard)) {
        peersState.put(state.who.nodeId, state);
      }
    }

    /**
     * If the state has changed we send a message to all. If we're done, we update all our peers: it
     * will be our last message.
     */
    void updateVerifiedSignatures(BitSet sigs) {
      int oldCard = verifiedSignatures.cardinality();
      verifiedSignatures.or(sigs);
      int newCard = verifiedSignatures.cardinality();

      if (newCard > oldCard) {
        if (withState) {
          sendStateToPeers();
        }

        if (sanFermin) {
          int r = 2;
          while (r < 30 && r < MoreMath.log2(nodeCount)) {
            BitSet nodesAtRound = sanFerminPeers(r);
            nodesAtRound.and(sigs);
            if (nodesAtRound.length() != 0) {
              // In the sigs we've just verified we have one or more of the sigs of this round
              // We now need to check if we completed the set.
              nodesAtRound = sanFerminPeers(r);
              nodesAtRound.and(verifiedSignatures);
              if (nodesAtRound.equals(sanFerminPeers(r))) {
                // Ok, we're going to contact some of the nodes of the upper level
                //  We're going to select these nodes randomly

                BitSet nextRound = sanFerminPeers(r + 1);
                nextRound.andNot(nodesAtRound);

                //We contact two nodes.
                List<Node> dest = randomSubset(nextRound, 2);

                // here we can send:
                // - all the signatures -- good for fault tolerance, bad for message size
                // - only the aggregated signature for this san fermin range
                // - all the signatures we can add on top of this aggregated san fermin sig
                // on the early tests sending all results seems more efficient. But
                // if we suppose that only small messages are supported, then
                //  we can send only the San Fermin ones to make it fit into a UDP message
                // SendSigs ss = new SendSigs(verifiedSignatures, compressedSize(verifiedSignatures));
                SendSigs ss = new SendSigs(sanFerminPeers(r), 1);
                network.send(ss, network.time + 1, this, dest);
              }
            }
            r++;
          }

        }

        if (!done && verifiedSignatures.cardinality() >= threshold) {
          doneAt = network.time;
          done = true;
          while (!peersState.isEmpty()) {
            sendSigs();
          }
        }
      }
    }

    private List<Node> randomSubset(BitSet nodes, int nodeCt) {
      List<Node> res = new ArrayList<>();
      int pos = 0;
      do {
        int cur = nodes.nextSetBit(pos);
        if (cur >= 0) {
          res.add(network.getNodeById(cur));
          pos = cur + 1;
        } else {
          break;
        }
      } while (true);

      for (Node n : peers) {
        res.remove(n);
      }

      if (res.size() > nodeCt) {
        Collections.shuffle(res, network.rd);
        return res.subList(0, nodeCt);
      } else {
        return res;
      }
    }

    void sendStateToPeers() {
      State s = new State(this);
      network.send(s, this, peers);
    }


    /**
     * Nothing much to do when we receive a sig set: we just add it to our toVerify list.
     */
    void onNewSig(BitSet sigs) {
      toVerify.add(sigs);
    }


    /**
     * We select a peer which needs some signatures we have. We also remove it from out list once we
     * sent it a signature set.
     */
    void sendSigs() {
      State found = null;
      BitSet toSend = null;
      Iterator<State> it = peersState.values().iterator();
      while (it.hasNext() && found == null) {
        State cur = it.next();
        toSend = (BitSet) verifiedSignatures.clone();
        toSend.andNot(cur.desc);
        int v1 = toSend.cardinality();

        if (v1 > 0) {
          found = cur;
          it.remove();
        }
      }

      if (!withState) {
        found = new State((P2PSigNode) peers.get(network.rd.nextInt(peers.size())));
      }


      if (found != null) {
        SendSigs ss;
        if (sendSigsStrategy == SendSigsStrategy.dif) {
          ss = new SendSigs(toSend);
        } else if (sendSigsStrategy == SendSigsStrategy.cmp_all) {
          ss = new SendSigs((BitSet) verifiedSignatures.clone(),
              compressedSize(verifiedSignatures));
        } else if (sendSigsStrategy == SendSigsStrategy.cmp_diff) {
          int s1 = compressedSize(verifiedSignatures);
          int s2 = compressedSize(toSend);
          ss = new SendSigs((BitSet) verifiedSignatures.clone(), Math.min(s1, s2));
        } else {
          ss = new SendSigs((BitSet) verifiedSignatures.clone());
        }
        network.send(ss, delayToSend(ss.sigs), this, found.who);
      }
    }

    /**
     * We add a small delay to take into account the message size. This should likely be moved to
     * the framework.
     */
    int delayToSend(BitSet sigs) {
      return network.time + 1 + sigs.cardinality() / 100;
    }


    public void checkSigs() {
      if (doubleAggregateStrategy) {
        checkSigs2();
      } else {
        checkSigs1();
      }
    }


    /**
     * Strategy 1: we select the set of signatures which contains the most new signatures. As we
     * send a message to all our peers each time our state change we send more messages with this
     * strategy.
     */
    protected void checkSigs1() {
      BitSet best = null;
      int bestV = 0;
      Iterator<BitSet> it = toVerify.iterator();
      while (it.hasNext()) {
        BitSet o1 = it.next();
        BitSet oo1 = ((BitSet) o1.clone());
        oo1.andNot(verifiedSignatures);
        int v1 = oo1.cardinality();

        if (v1 == 0) {
          it.remove();
        } else {
          if (v1 > bestV) {
            bestV = v1;
            best = o1;
          }
        }
      }

      if (best != null) {
        toVerify.remove(best);
        final BitSet tBest = best;
        network.registerTask(() -> P2PSigNode.this.updateVerifiedSignatures(tBest),
            network.time + pairingTime * 2, P2PSigNode.this);
      }
    }

    /**
     * Strategy 2: we aggregate all signatures together and we test all of them. It's obviously
     * faster, but if someone sent us an invalid signature we have to validate again the signatures.
     * So if we don't need this scheme we should not use it, as it requires to implement a back-up
     * strategy as well.
     */
    protected void checkSigs2() {
      BitSet agg = null;
      for (BitSet o1 : toVerify) {
        if (agg == null) {
          agg = o1;
        } else {
          agg.or(o1);
        }
      }
      toVerify.clear();

      if (agg != null) {
        BitSet oo1 = ((BitSet) agg.clone());
        oo1.andNot(verifiedSignatures);

        if (oo1.cardinality() > 0) {
          // There is at least one signature we don't have yet
          final BitSet tBest = agg;
          network.registerTask(() -> P2PSigNode.this.updateVerifiedSignatures(tBest),
              network.time + pairingTime * 2, P2PSigNode.this);
        }
      }
    }



    @Override
    public String toString() {
      return "P2PSigNode{" + "nodeId=" + nodeId + " sendSigsStrategy=" + sendSigsStrategy
          + " sigRange=" + sigRange + ", doneAt=" + doneAt + ", sigs="
          + verifiedSignatures.cardinality() + ", msgReceived=" + msgReceived + ", msgSent="
          + msgSent + ", KBytesSent=" + bytesSent / 1024 + ", KBytesReceived="
          + bytesReceived / 1024 + '}';
    }
  }

  P2PSigNode init() {
    P2PSigNode last = null;
    for (int i = 0; i < nodeCount; i++) {
      final P2PSigNode n = new P2PSigNode();
      last = n;
      network.addNode(n);
      if (withState && !sanFermin) {
        network.registerTask(n::sendStateToPeers, 1, n);
      }
      network.registerConditionalTask(n::sendSigs, 1, sigsSendPeriod, n,
          () -> !(n.peersState.isEmpty()), () -> !n.done);
      network.registerConditionalTask(n::checkSigs, 1, pairingTime, n, () -> !n.toVerify.isEmpty(),
          () -> !n.done);
    }
    if (sanFermin) {
      for (int i = 0; i < nodeCount; i++) {
        final P2PSigNode n = (P2PSigNode) network.getNodeById(i);
        SendSigs sigs = new SendSigs(n.verifiedSignatures);
        int peerId = n.sanFerminPeers(1).length() - 1;
        network.send(sigs, 1, n, network.getNodeById(peerId));
      }
    }

    network.setPeers();

    return last;
  }

  public static void sigsPerTime() {
    NetworkLatency.NetworkLatencyByDistance nl = new NetworkLatency.NetworkLatencyByDistance();
    int nodeCt = 300;
    List<Graph.Series> rawResultsMin = new ArrayList<>();
    List<Graph.Series> rawResultsMax = new ArrayList<>();
    List<Graph.Series> rawResultsAvg = new ArrayList<>();

    P2PSignature psTemplate =
        new P2PSignature(nodeCt, nodeCt, 15, 3, 50, true, false, SendSigsStrategy.cmp_diff, 2);
    psTemplate.network.setNetworkLatency(nl);

    String desc =
        "nodeCount=" + nodeCt + ", gossip " + (psTemplate.sanFermin ? " + San Fermin" : "alone")
            + ", gossip period=" + psTemplate.sigsSendPeriod
            + (!psTemplate.sanFermin ? ", compression=" + psTemplate.sendSigsStrategy : "");
    System.out.println(nl + " " + desc);
    Graph graph = new Graph("number of signatures per time (" + desc + ")", "time in ms",
        "number of signatures");
    Graph medianGraph = new Graph("average number of signatures per time (" + desc + ")",
        "time in ms", "number of signatures");

    int lastSeries = 3;
    StatsHelper.SimpleStats s;

    for (int i = 0; i < lastSeries; i++) {
      Graph.Series curMin = new Graph.Series("signatures count - worse node" + i);
      Graph.Series curMax = new Graph.Series("signatures count - best node" + i);
      Graph.Series curAvg = new Graph.Series("signatures count - average" + i);
      rawResultsAvg.add(curAvg);
      rawResultsMin.add(curMin);
      rawResultsMax.add(curMax);

      P2PSignature ps1 = psTemplate.copy();
      ps1.network.setNetworkLatency(nl);
      ps1.network.rd.setSeed(i);
      ps1.init();

      do {
        ps1.network.runMs(10);
        s = StatsHelper.getStatsOn(ps1.network.allNodes,
            n -> ((P2PSigNode) n).verifiedSignatures.cardinality());
        curMin.addLine(new Graph.ReportLine(ps1.network.time, s.min));
        curMax.addLine(new Graph.ReportLine(ps1.network.time, s.max));
        curAvg.addLine(new Graph.ReportLine(ps1.network.time, s.avg));
      } while (s.min != ps1.nodeCount);
      graph.addSerie(curMin);
      graph.addSerie(curMax);
      graph.addSerie(curAvg);

      System.out.println(
          "bytes sent: " + StatsHelper.getStatsOn(ps1.network.allNodes, Node::getBytesSent));
      System.out.println(
          "bytes rcvd: " + StatsHelper.getStatsOn(ps1.network.allNodes, Node::getBytesReceived));
      System.out
          .println("msg sent: " + StatsHelper.getStatsOn(ps1.network.allNodes, Node::getMsgSent));
      System.out.println(
          "msg rcvd: " + StatsHelper.getStatsOn(ps1.network.allNodes, Node::getMsgReceived));
      System.out.println(
          "done at: " + StatsHelper.getStatsOn(ps1.network.allNodes, n -> ((P2PSigNode) n).doneAt));
    }

    try {
      graph.save(new File("/tmp/graph_ind.png"));
    } catch (IOException e) {
      System.err.println("Can't generate the graph: " + e.getMessage());
    }

    Graph.Series seriesAvgmax =
        Graph.averageSeries("Signatures count average - best node", rawResultsMax);
    Graph.Series seriesAvgavg =
        Graph.averageSeries("Signatures count average - average", rawResultsAvg);
    medianGraph.addSerie(seriesAvgmax);
    medianGraph.addSerie(seriesAvgavg);

    try {
      medianGraph.save(new File("/tmp/graph_avg.png"));
    } catch (IOException e) {
      System.err.println("Can't generate the graph: " + e.getMessage());
    }
  }

  public static void sigsPerNodeCount() {
    List<P2PSignature> pss = new ArrayList<>();
    for (int nodeCt : new int[] {50, 100, 200, 300, 400, 500}) {//, 1000, 1500, 2000, 2500, 3000, 4000, 5000,
      // 10000, 15000, 20000}) {
      NetworkLatency.NetworkLatencyByDistance nl = new NetworkLatency.NetworkLatencyByDistance();
      P2PSignature ps1 =
          new P2PSignature(nodeCt, nodeCt, 15, 3, 20, true, false, SendSigsStrategy.all, 1);
      ps1.network.setNetworkLatency(nl);
      pss.add(ps1);
    }

    Graph graph = new Graph("number of sig per time", "number of nodes", "time in ms");
    Graph.Series series = new Graph.Series("all nodes time");
    graph.addSerie(series);

    for (P2PSignature ps1 : pss) {
      ps1.network.rd.setSeed(1);
      ps1.init();
      ps1.network.setMsgDiscardTime(1000);

      StatsHelper.SimpleStats s;
      do {
        ps1.network.runMs(10);
        s = StatsHelper.getStatsOn(ps1.network.allNodes,
            n -> ((P2PSigNode) n).verifiedSignatures.cardinality());
      } while (s.min != ps1.nodeCount);
      series.addLine(new Graph.ReportLine(ps1.nodeCount, ps1.network.time));
    }

    try {
      graph.save(new File("/tmp/graph.png"));
    } catch (IOException e) {
      System.err.println("Can't generate the graph: " + e.getMessage());
    }
  }

  public P2PSignature copy() {
    return new P2PSignature(nodeCount, threshold, connectionCount, pairingTime, sigsSendPeriod,
        doubleAggregateStrategy, sanFermin, sendSigsStrategy, sigRange);
  }

  public static void sigsPerStrategy() {
    int nodeCt = 1000;

    NetworkLatency.NetworkLatencyByDistance nl = new NetworkLatency.NetworkLatencyByDistance();
    P2PSignature ps1 =
        new P2PSignature(nodeCt, nodeCt, 15, 3, 20, true, false, SendSigsStrategy.all, 1);
    ps1.network.setNetworkLatency(nl);

    P2PSignature ps2 =
        new P2PSignature(nodeCt, nodeCt, 15, 3, 20, false, false, SendSigsStrategy.all, 1);
    ps2.network.setNetworkLatency(nl);

    Graph graph = new Graph("number of sig per time", "time in ms", "sig count");
    Graph.Series series1avg = new Graph.Series("sig count - full aggregate strategy");
    Graph.Series series2avg = new Graph.Series("sig count - single aggregate");
    graph.addSerie(series1avg);
    graph.addSerie(series2avg);

    ps1.init();
    ps2.init();

    StatsHelper.SimpleStats s1;
    StatsHelper.SimpleStats s2;
    do {
      ps1.network.runMs(10);
      ps2.network.runMs(10);
      s1 = StatsHelper.getStatsOn(ps1.network.allNodes,
          n -> ((P2PSigNode) n).verifiedSignatures.cardinality());
      s2 = StatsHelper.getStatsOn(ps2.network.allNodes,
          n -> ((P2PSigNode) n).verifiedSignatures.cardinality());
      series1avg.addLine(new Graph.ReportLine(ps1.network.time, s1.avg));
      series2avg.addLine(new Graph.ReportLine(ps2.network.time, s2.avg));
    } while (s1.min != nodeCt);

    try {
      graph.save(new File("/tmp/graph.png"));
    } catch (IOException e) {
      System.err.println("Can't generate the graph: " + e.getMessage());
    }
  }



  public static void main(String... args) {
    NetworkLatency nl = new NetworkLatency.NetworkLatencyByDistance();
    System.out.println("" + nl);

    if (true) {
      sigsPerTime();
      return;
    }

    boolean printLat = false;
    for (int cnt : new int[] {15}) {
      for (int sendPeriod : new int[] {20, 100}) {
        for (int nodeCt : new int[] {1000, 10000}) {
          for (int r : new int[] {1, 2, 4, 6, 8, 12, 14, 16}) {
            P2PSignature p2ps = new P2PSignature(nodeCt, (int) (nodeCt * 0.67), cnt, 3, sendPeriod,
                true, false, r < 2 ? SendSigsStrategy.all : SendSigsStrategy.cmp_all, r);
            p2ps.network.setNetworkLatency(nl);
            P2PSigNode observer = p2ps.init();

            if (!printLat) {
              System.out.println("NON P2P " + NetworkLatency.estimateLatency(p2ps.network, 100000));
              System.out
                  .println("\nP2P " + NetworkLatency.estimateP2PLatency(p2ps.network, 100000));
              printLat = true;
            }
            p2ps.network.rd.setSeed(0);

            p2ps.network.run(10);
            System.out.println("peers=" + cnt + ", sendPeriod=" + sendPeriod + " " + observer);
          }
        }
      }
    }
  }
}
