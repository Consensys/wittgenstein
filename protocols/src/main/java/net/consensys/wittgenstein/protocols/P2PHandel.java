package net.consensys.wittgenstein.protocols;

import static net.consensys.wittgenstein.protocols.P2PHandelScenarios.sigsPerTime;

import java.util.*;
import net.consensys.wittgenstein.core.*;
import net.consensys.wittgenstein.core.messages.Message;
import net.consensys.wittgenstein.core.utils.MoreMath;

/**
 * A p2p protocol for BLS signature aggregation.
 *
 * <p>A node: Sends its states to all its direct peers whenever it changes Keeps the list of the
 * states of its direct peers Sends, every x milliseconds, to one of its peers a set of missing
 * signatures Runs in parallel a task to validate the signatures sets it has received. Send only
 * validated signatures to its peers.
 */
@SuppressWarnings("WeakerAccess")
public class P2PHandel implements Protocol {
  P2PHandelParameters params;
  final P2PNetwork<P2PHandelNode> network;
  final NodeBuilder nb;

  public enum SendSigsStrategy {
    /** Send all signatures, not taking the state into account */
    all,
    /** send just the diff (we need the state of the other nodes for this) */
    dif,
    /** send all the signatures, but compress them */
    cmp_all,
    /** compress, but sends the compress diff if it's smaller. */
    cmp_diff
  }

  public static class P2PHandelParameters extends WParameters {

    /** The number of nodes in the network participating in signing */
    final int signingNodeCount;

    /** The number of nodes participating without signing. */
    final int relayingNodeCount;

    /** The number of signatures to reach to finish the protocol. */
    final int threshold;

    /** The typical number of peers a peer has. At least 3. */
    final int connectionCount;

    /** The time it takes to do a pairing for a node. */
    final int pairingTime;

    /** The protocol sends a set of sigs every 'sigsSendPeriod' milliseconds */
    final int sigsSendPeriod;

    /** @see P2PHandelNode#checkSigs1 for the two strategies on aggregation. */
    final boolean doubleAggregateStrategy;

    /**
     * If true the nodes send their state to the peers they are connected with. If false they don't.
     */
    final boolean withState = false;

    final String nodeBuilderName;
    final String networkLatencyName;

    final SendSigsStrategy sendSigsStrategy;

    @SuppressWarnings("unused")
    public P2PHandelParameters() {
      this.signingNodeCount = 100;
      this.relayingNodeCount = 20;
      this.threshold = 99;
      this.connectionCount = 40;
      this.pairingTime = 100;
      this.sigsSendPeriod = 1000;
      this.doubleAggregateStrategy = true;
      this.sendSigsStrategy = SendSigsStrategy.dif;
      this.nodeBuilderName = null;
      this.networkLatencyName = null;
    }

    public P2PHandelParameters(
        int signingNodeCount,
        int relayingNodeCount,
        int threshold,
        int connectionCount,
        int pairingTime,
        int sigsSendPeriod,
        boolean doubleAggregateStrategy,
        SendSigsStrategy sendSigsStrategy,
        int sigRange,
        String nodeBuilderName,
        String networkLatencyName) {
      this.signingNodeCount = signingNodeCount;
      this.relayingNodeCount = relayingNodeCount;
      this.threshold = threshold;
      this.connectionCount = connectionCount;
      this.pairingTime = pairingTime;
      this.sigsSendPeriod = sigsSendPeriod;
      this.doubleAggregateStrategy = doubleAggregateStrategy;
      this.sendSigsStrategy = sendSigsStrategy;
      this.nodeBuilderName = nodeBuilderName;
      this.networkLatencyName = networkLatencyName;
    }
  }

  public P2PHandel(P2PHandelParameters params) {
    this.params = params;
    this.network = new P2PNetwork<>(params.connectionCount, false);
    this.nb = RegistryNodeBuilders.singleton.getByName(params.nodeBuilderName);
    this.network.setNetworkLatency(
        RegistryNetworkLatencies.singleton.getByName(params.networkLatencyName));
  }

  static class State extends Message<P2PHandelNode> {
    final BitSet desc;
    final P2PHandelNode who;

    public State(P2PHandelNode who) {
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
    public void action(Network<P2PHandelNode> network, P2PHandelNode from, P2PHandelNode to) {
      to.onPeerState(this);
    }
  }

  /**
   * Calculate the number of signatures we have to include is we apply a compression strategy
   * Strategy is: - we divide the bitset in ranges of size sigRange - all the signatures at the
   * beginning of a range are aggregated, until one of them is not available.
   *
   * <p>Example for a range of size 4:</br> 1101 0111 => we have 5 sigs instead of 6</br> 1111 1110
   * => we have 2 sigs instead of 7</br> 0111 0111 => we have 6 sigs </br>
   *
   * <p>Note that we don't aggregate consecutive ranges, because we would not be able to merge
   * bitsets later.</br> For example, still with a range of for, with two nodes:</br> node 1: 1111
   * 1111 0000 => 2 sigs, and not 1</br> node 2: 0000 1111 1111 => again, 2 sigs and not 1</br>
   *
   * <p>By keeping the two aggregated signatures node 1 & node 2 can exchange aggregated signatures.
   * 1111 1111 => 1 0001 1111 1111 0000 => 3 0001 1111 1111 1111 => 2 </>
   *
   * @return the number of signatures to include
   */
  int compressedSize(BitSet sigs) {
    if (sigs.length() == params.signingNodeCount) {
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
        if ((pos + 1) % 2 == 0) {
          // We compressed the whole range, but now we're starting a new one...
          compressing = false;
          wasCompressing = true;
        }
      } else {
        sigCt++;
        if (pos % 2 == 0) {
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
   * we have 11 11 11 11 11 11 11 11 11 11 11 => 11 sigs w/o merge.</br> This should become 3 after
   * merge: the first 8, then the second set of two blocks
   */
  private int mergeRanges(int firstOneAt, int pos) {
    if (firstOneAt < 0) {
      return 0;
    }
    // We start only at the beginning of a range
    if (firstOneAt % (2 * 2) != 0) {
      firstOneAt += (2 * 2) - (firstOneAt % (2 * 2));
    }

    int rangeCt = (pos - firstOneAt) / 2;
    if (rangeCt < 2) {
      return 0;
    }

    int max = MoreMath.log2(rangeCt);
    while (max > 0) {
      int sizeInBlocks = (int) Math.pow(2, max);
      int size = sizeInBlocks * 2;
      if (firstOneAt % size == 0) {
        return (sizeInBlocks - 1) + mergeRanges(firstOneAt + size, pos);
      }
      max--;
    }

    return 0;
  }

  static class SendSigs extends Message<P2PHandelNode> {
    final BitSet sigs;
    final int size;

    public SendSigs(BitSet sigs) {
      this(sigs, sigs.cardinality());
    }

    public SendSigs(BitSet sigs, int sigCount) {
      this.sigs = (BitSet) sigs.clone();
      this.size = Math.max(1, sigCount);
    }

    @Override
    public int size() {
      return size;
    }

    @Override
    public void action(Network<P2PHandelNode> network, P2PHandelNode from, P2PHandelNode to) {
      to.onNewSig(sigs);
    }
  }

  public class P2PHandelNode extends P2PNode<P2PHandelNode> {
    final BitSet verifiedSignatures = new BitSet(params.signingNodeCount);
    final Set<BitSet> toVerify = new HashSet<>();
    final Map<Integer, State> peersState = new HashMap<>();
    final boolean justRelay;

    P2PHandelNode(boolean justRelay) {
      super(network.rd, nb);
      this.justRelay = justRelay;
      if (!justRelay) {
        verifiedSignatures.set(nodeId, true);
      }
    }

    /** Asynchronous, so when we receive a state it can be an old one. */
    void onPeerState(State state) {
      int newCard = state.desc.cardinality();
      State old = peersState.get(state.who.nodeId);

      if (newCard < params.threshold && (old == null || old.desc.cardinality() < newCard)) {
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
        if (params.withState) {
          sendStateToPeers();
        }

        if (doneAt == 0 && verifiedSignatures.cardinality() >= params.threshold) {
          doneAt = network.time;
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

    /** Nothing much to do when we receive a sig set: we just add it to our toVerify list. */
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

      if (!params.withState) {
        found = new State(peers.get(network.rd.nextInt(peers.size())));
      }

      if (found != null) {
        SendSigs ss;
        if (params.sendSigsStrategy == SendSigsStrategy.dif) {
          ss = new SendSigs(toSend);
        } else if (params.sendSigsStrategy == SendSigsStrategy.cmp_all) {
          ss =
              new SendSigs((BitSet) verifiedSignatures.clone(), compressedSize(verifiedSignatures));
        } else if (params.sendSigsStrategy == SendSigsStrategy.cmp_diff) {
          int s1 = compressedSize(verifiedSignatures);
          int s2 = compressedSize(toSend);
          ss = new SendSigs((BitSet) verifiedSignatures.clone(), Math.min(s1, s2));
        } else {
          ss = new SendSigs((BitSet) verifiedSignatures.clone());
        }
        network.send(ss, this, found.who);
      }
    }

    public void checkSigs() {
      if (params.doubleAggregateStrategy) {
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
        network.registerTask(
            () -> P2PHandelNode.this.updateVerifiedSignatures(tBest),
            network.time + params.pairingTime * 2,
            P2PHandelNode.this);
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
          network.registerTask(
              () -> P2PHandelNode.this.updateVerifiedSignatures(tBest),
              network.time + params.pairingTime * 2,
              P2PHandelNode.this);
        }
      }
    }
  }

  @Override
  public void init() {
    Set<Integer> justRelay = new HashSet<>(params.relayingNodeCount);
    while (justRelay.size() < params.relayingNodeCount) {
      justRelay.add(network.rd.nextInt(params.signingNodeCount + params.relayingNodeCount));
    }

    for (int i = 0; i < params.signingNodeCount + params.relayingNodeCount; i++) {
      final P2PHandelNode n = new P2PHandelNode(justRelay.contains(i));
      network.addNode(n);
      if (params.withState) {
        network.registerTask(n::sendStateToPeers, 1, n);
      }
      network.registerConditionalTask(
          n::sendSigs,
          1,
          params.sigsSendPeriod,
          n,
          () -> !(n.peersState.isEmpty()),
          () -> n.doneAt == 0 || true);

      // We stop sending sigs when we have finished. Technically it could be an issue because
      //  we could own signatures we haven't distributed.

      network.registerConditionalTask(
          n::checkSigs, 1, params.pairingTime, n, () -> !n.toVerify.isEmpty(), () -> n.doneAt == 0);
    }

    network.setPeers();
  }

  @Override
  public Network<P2PHandelNode> network() {
    return network;
  }

  public P2PHandel copy() {
    return new P2PHandel(params);
  }

  public static void main(String... args) {
    sigsPerTime();
    // sigsPerStrategy();
  }
}
