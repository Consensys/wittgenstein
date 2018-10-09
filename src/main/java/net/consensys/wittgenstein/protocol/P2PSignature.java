package net.consensys.wittgenstein.protocol;

import net.consensys.wittgenstein.core.*;
import java.util.*;

/**
 * A p2p protocol for BLS signature aggregation.
 * <p>
 * A node: Sends its states to all its direct peers whenever it changes Keeps the list of the states
 * of its direct peers Sends, every x milliseconds, to one of its peers a set of missing signatures
 * Runs in parallel a task to validate the signatures sets it has received.
 */
@SuppressWarnings("WeakerAccess")
public class P2PSignature {
  /**
   * The nuumber of nodes in the network
   */
  final int nodeCount;

  /**
   * The number of signatures to reach to finish the protocol.
   */
  final int threshold;

  /**
   * The typical number of peers a peer has. It can be less (but at least 3) or more.
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
   *
   */
  enum SendSigsStrategy {
    all,
    dif,
    cmp
  }


  final int sigRange;


  final SendSigsStrategy sendSigsStrategy;


  final P2PNetwork network;
  final Node.NodeBuilder nb;

  public P2PSignature(int nodeCount, int threshold, int connectionCount, int pairingTime,
      int sigsSendPeriod, boolean doubleAggregateStrategy, SendSigsStrategy sendSigsStrategy,
      int sigRange) {
    this.nodeCount = nodeCount;
    this.threshold = threshold;
    this.connectionCount = connectionCount;
    this.pairingTime = pairingTime;
    this.sigsSendPeriod = sigsSendPeriod;
    this.doubleAggregateStrategy = doubleAggregateStrategy;
    this.sendSigsStrategy = sendSigsStrategy;
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

    @Override
    public int size() {
      return desc.size() / 8;
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
   * Example for a range of size 4: 1101 0111 => we have 5 sigs instead of 6 1111 1110 => we have 2
   * sigs instead of 7 0111 0111 => we have 6 sigs
   * <p>
   * Note that we don't aggregate consecutive ranges, because we would not be able to merge bitsets
   * later. For example, still with a range of for, with two nodes: node 1: 1111 1111 0000 => 2
   * sigs, and not 1 node 2: 0000 1111 1111 => again, 2 sigs and not 1
   * <p>
   * By keeping the two aggregated signatures node 1 & node 2 can exchange aggregated signatures.
   */
  int compressedSize(BitSet sigs) {
    int sigCt = 0;
    int pos = -1;
    boolean compressing = false;
    while (++pos < sigs.size()) {
      if (!sigs.get(pos)) {
        compressing = false;
      } else if (compressing) {
        if ((pos + 1) % sigRange == 0) {
          // We compressed the whole range, but now we're starting a new one...
          compressing = false;
        }
      } else {
        sigCt++;
        if (pos % sigRange == 0) {
          compressing = true;
        }
      }
    }
    return sigCt;
  }


  static class SendSigs extends Network.Message<P2PSigNode> {
    final BitSet sigs;
    final int size;

    public SendSigs(BitSet sigs) {
      this(sigs, sigs.cardinality());
    }

    public SendSigs(BitSet sigs, int sigCount) {
      this.sigs = sigs;
      // Size = bit field + the signatures included
      this.size = sigs.size() / 8 + sigCount * 48;
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
        sendStateToPeers();

        if (!done && verifiedSignatures.cardinality() >= threshold) {
          doneAt = network.time;
          done = true;
          while (!peersState.isEmpty()) {
            sendSigs();
          }
        }
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
        toSend = (BitSet) cur.desc.clone();
        toSend.flip(0, nodeCount);
        toSend.and(verifiedSignatures);
        int v1 = toSend.cardinality();

        if (v1 > 0) {
          found = cur;
          it.remove();
        }
      }

      if (found != null) {
        SendSigs ss;
        if (sendSigsStrategy == SendSigsStrategy.dif) {
          ss = new SendSigs(toSend);
        } else if (sendSigsStrategy == SendSigsStrategy.cmp) {
          ss = new SendSigs((BitSet) verifiedSignatures.clone(),
              compressedSize(verifiedSignatures));
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
     * Strategy 2: we aggregate all signatures together
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
      network.registerTask(n::sendStateToPeers, 1, n);
      network.registerConditionalTask(n::sendSigs, 1, sigsSendPeriod, n,
          () -> !(n.peersState.isEmpty()), () -> !n.done);
      network.registerConditionalTask(n::checkSigs, 1, pairingTime, n, () -> !n.toVerify.isEmpty(),
          () -> !n.done);
    }

    network.setPeers();

    return last;
  }

  public static void main(String... args) {
    NetworkLatency nl = new NetworkLatency.NetworkLatencyByDistance();
    System.out.println("" + nl);

    boolean printLat = false;
    for (int cnt : new int[] {15}) {
      for (int sendPeriod : new int[] {20, 100}) {
        for (int nodeCt : new int[] {1000, 10000}) {
          for (int r : new int[] {1, 2, 4, 6}) {
            P2PSignature p2ps = new P2PSignature(nodeCt, nodeCt / 2 + 1, cnt, 3, sendPeriod, true,
                r < 2 ? SendSigsStrategy.all : SendSigsStrategy.cmp, r);
            p2ps.network.setNetworkLatency(nl);
            P2PSigNode observer = p2ps.init();

            if (!printLat) {
              System.out.println("NON P2P " + NetworkLatency.estimateLatency(p2ps.network, 100000));
              System.out
                  .println("\nP2P " + NetworkLatency.estimateP2PLatency(p2ps.network, 100000));
              printLat = true;
            }
            p2ps.network.rd.setSeed(0);

            p2ps.network.run(5);
            System.out.println("peers=" + cnt + ", sendPeriod=" + sendPeriod + " " + observer);
          }
        }
      }
    }
  }
}

/*
P2P BlockChainNetwork latency: time to receive a message:
10ms 0%, cumulative 0%
20ms 0%, cumulative 0%
30ms 1%, cumulative 1%
40ms 1%, cumulative 2%
50ms 2%, cumulative 4%
100ms 14%, cumulative 18%
200ms 60%, cumulative 78%
300ms 21%, cumulative 99%
400ms 0%, cumulative 99%
500ms 1%, cumulative 100%

peers=15, sendPeriod=20 P2PSigNode{nodeId=999 sendSigsStrategy=all sigRange=1, doneAt=724, sigs=520, msgReceived=345, msgSent=143, KBytesSent=378, KBytesReceived=458}
peers=15, sendPeriod=20 P2PSigNode{nodeId=999 sendSigsStrategy=cmp sigRange=2, doneAt=724, sigs=520, msgReceived=345, msgSent=143, KBytesSent=292, KBytesReceived=343}
peers=15, sendPeriod=20 P2PSigNode{nodeId=999 sendSigsStrategy=cmp sigRange=4, doneAt=724, sigs=520, msgReceived=345, msgSent=143, KBytesSent=299, KBytesReceived=350}
peers=15, sendPeriod=20 P2PSigNode{nodeId=999 sendSigsStrategy=cmp sigRange=6, doneAt=724, sigs=520, msgReceived=345, msgSent=143, KBytesSent=303, KBytesReceived=373}
peers=15, sendPeriod=20 P2PSigNode{nodeId=9999 sendSigsStrategy=all sigRange=1, doneAt=1116, sigs=5136, msgReceived=1224, msgSent=826, KBytesSent=10967, KBytesReceived=12175}
peers=15, sendPeriod=20 P2PSigNode{nodeId=9999 sendSigsStrategy=cmp sigRange=2, doneAt=1116, sigs=5136, msgReceived=1224, msgSent=826, KBytesSent=8423, KBytesReceived=9248}
peers=15, sendPeriod=20 P2PSigNode{nodeId=9999 sendSigsStrategy=cmp sigRange=4, doneAt=1116, sigs=5136, msgReceived=1224, msgSent=826, KBytesSent=8758, KBytesReceived=9465}
peers=15, sendPeriod=20 P2PSigNode{nodeId=9999 sendSigsStrategy=cmp sigRange=6, doneAt=1116, sigs=5136, msgReceived=1224, msgSent=826, KBytesSent=9263, KBytesReceived=10072}
peers=15, sendPeriod=100 P2PSigNode{nodeId=999 sendSigsStrategy=all sigRange=1, doneAt=1204, sigs=504, msgReceived=183, msgSent=58, KBytesSent=338, KBytesReceived=426}
peers=15, sendPeriod=100 P2PSigNode{nodeId=999 sendSigsStrategy=cmp sigRange=2, doneAt=1204, sigs=504, msgReceived=183, msgSent=58, KBytesSent=253, KBytesReceived=315}
peers=15, sendPeriod=100 P2PSigNode{nodeId=999 sendSigsStrategy=cmp sigRange=4, doneAt=1204, sigs=504, msgReceived=183, msgSent=58, KBytesSent=262, KBytesReceived=322}
peers=15, sendPeriod=100 P2PSigNode{nodeId=999 sendSigsStrategy=cmp sigRange=6, doneAt=1204, sigs=504, msgReceived=183, msgSent=58, KBytesSent=278, KBytesReceived=341}
peers=15, sendPeriod=100 P2PSigNode{nodeId=9999 sendSigsStrategy=all sigRange=1, doneAt=2688, sigs=5059, msgReceived=903, msgSent=843, KBytesSent=11017, KBytesReceived=11487}
peers=15, sendPeriod=100 P2PSigNode{nodeId=9999 sendSigsStrategy=cmp sigRange=2, doneAt=2688, sigs=5059, msgReceived=903, msgSent=843, KBytesSent=8516, KBytesReceived=8803}
peers=15, sendPeriod=100 P2PSigNode{nodeId=9999 sendSigsStrategy=cmp sigRange=4, doneAt=2688, sigs=5059, msgReceived=903, msgSent=843, KBytesSent=8762, KBytesReceived=9054}
peers=15, sendPeriod=100 P2PSigNode{nodeId=9999 sendSigsStrategy=cmp sigRange=6, doneAt=2688, sigs=5059, msgReceived=903, msgSent=843, KBytesSent=9296, KBytesReceived=9596}

 */
