package net.consensys.wittgenstein.protocols;

import java.util.*;
import net.consensys.wittgenstein.core.*;
import net.consensys.wittgenstein.core.messages.Message;
import net.consensys.wittgenstein.core.utils.MoreMath;

/**
 * A p2p protocol for BLS signature aggregation.
 *
 * <p>A node: Sends its states to all its direct peers whenever it changes Keeps the list of the
 * states of its direct peers Sends, every x milliseconds, to one of its peers a set of missing
 * signatures. Runs in parallel a task to validate the signatures sets it has received. Send only
 * validated signatures to its peers. We suppose we use a single core to verify the signature, i.e.
 * we verify the signature one by one (but there is an option to aggregate before verifying if we
 * received multiple signatures. In any case, all signatures received from the same host can
 * reasonably be verified altogether.
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
    final boolean sendState;

    final String nodeBuilderName;
    final String networkLatencyName;

    /** Send all sigs, diff, compress, compress or diff */
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
      this.sendState = false;
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
        boolean sendState,
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
      this.sendState = sendState;
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
   * bitsets later.</br> For example, still with a range of 4, with two nodes:</br> node 1: 1111
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
      to.onNewSig(from, sigs);
    }
  }

  public class P2PHandelNode extends P2PNode<P2PHandelNode> {
    final BitSet verifiedSignatures = new BitSet(params.signingNodeCount);
    final Set<BitSet> toVerify = new HashSet<>();
    final Map<Integer, BitSet> peersState = new HashMap<>();
    final boolean justRelay;

    P2PHandelNode(boolean justRelay) {
      super(network.rd, nb);
      this.justRelay = justRelay;
      if (!justRelay) {
        verifiedSignatures.set(nodeId, true);
      }
    }

    @Override
    public void start() {
      super.start();
      for (P2PNode p : peers) {
        // We initialize the state of our peers.
        // We don't know if our peer is a validator or not, nor its validator id, so
        // we can't initialize it with its own signature.
        peersState.put(p.nodeId, new BitSet());
      }
    }

    /** Asynchronous, so when we receive a state it can be an old one. */
    void onPeerState(State state) {
      peersState.get(state.who.nodeId).or(state.desc);
    }

    /**
     * If the state has changed we send a message to all. If we're done, we update all our peers: it
     * will be our last message. We consider here that this last message will always arrive, to
     * there is no risk to have a node still waiting for a tx.
     */
    void updateVerifiedSignatures(BitSet sigs) {
      int oldCard = verifiedSignatures.cardinality();
      verifiedSignatures.or(sigs);
      int newCard = verifiedSignatures.cardinality();

      if (newCard > oldCard) {
        if (doneAt == 0 && verifiedSignatures.cardinality() >= params.threshold) {
          doneAt = network.time;
          sendFinalSigToPeers();
        } else if (doneAt == 0 && params.sendState) {
          sendStateToPeers();
        }
      }
    }

    void sendFinalSigToPeers() {
      // When we're done we send the valid aggregation to all our peers.
      // It's a simplification to ensure the protocol ends.
      List<P2PHandelNode> dest = new ArrayList<>();
      for (P2PHandelNode p : peers) {
        if (peersState.get(p.nodeId).cardinality() < params.threshold) {
          dest.add(p);
          peersState.get(p.nodeId).or(verifiedSignatures);
        }
      }
      // The final signature has a size of 1, as we can aggregate it fully.
      network().send(new SendSigs(verifiedSignatures, 1), this, dest);
    }

    void sendStateToPeers() {
      State s = new State(this);
      network.send(s, this, peers);
    }

    /** Called when we receive a set of signature from our peers */
    void onNewSig(Node from, BitSet sigs) {
      // We update our vision of our peer.
      peersState.get(from.nodeId).or(sigs);
      // We add what it sent us to our verification list.
      toVerify.add(sigs);
    }

    /**
     * We select a peer which needs some signatures we have. We also remove it from out list once we
     * sent it a signature set.
     */
    void sendSigs() {
      if (doneAt > 0) {
        return;
      }

      P2PHandelNode dest = bestDest();
      if (dest == null) { // Nobody needs anything from us right now.
        return;
      }

      BitSet toSend = diff(dest);

      // We update our vision of our peer: we consider it will receive our message and
      //  update the state accordingly
      peersState.get(dest.nodeId).or(verifiedSignatures);

      SendSigs ss = createSendSigs(toSend);
      network.send(ss, this, dest);
    }

    private BitSet diff(P2PHandelNode peer) {
      BitSet needed = (BitSet) verifiedSignatures.clone();
      needed.andNot(peersState.get(peer.nodeId));
      return needed;
    }

    /**
     * We select the best destination, eg. the largest diff. This opens a lot of doors for an
     * attacker: by lying on its state it will be prioritized vs. the other peers. Note that with
     * honest peers, we minimize the number of message but increase the number of signatures sent.
     */
    private P2PHandelNode bestDest() {
      P2PHandelNode dest = null;
      int destSize = 0;
      for (P2PHandelNode p : peers) {
        int size = diff(p).cardinality();
        if (size > destSize) {
          dest = p;
          destSize = size;
        }
      }
      return dest;
    }

    /**
     * We select randomly our destination among our peers. We could do something in the middle as
     * well eg.
     */
    private P2PHandelNode randomDest() {
      return peers.get(network.rd.nextInt(peers.size()));
    }

    /** The only difficulty here is to calculate the actual number of signatures we're sending. */
    private SendSigs createSendSigs(BitSet toSend) {
      if (params.sendSigsStrategy == SendSigsStrategy.dif) {
        return new SendSigs(toSend);
      } else if (params.sendSigsStrategy == SendSigsStrategy.cmp_all) {
        return new SendSigs(
            (BitSet) verifiedSignatures.clone(), compressedSize(verifiedSignatures));
      } else if (params.sendSigsStrategy == SendSigsStrategy.cmp_diff) {
        // Sometimes it's better to send more signatures because they compress better.
        // There could be even smarter strategies, where you look at all subsets.
        int s1 = compressedSize(verifiedSignatures);
        int s2 = compressedSize(toSend);
        return new SendSigs((BitSet) verifiedSignatures.clone(), Math.min(s1, s2));
      } else {
        return new SendSigs((BitSet) verifiedSignatures.clone());
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
    // Some nodes are not participating in the aggregation, they just relay messages.
    // This allow the aggregator nodes to hide themselves among standard nodes, even if
    //  it's should be simple to do the distinction in real life.
    Set<Integer> justRelay = new HashSet<>(params.relayingNodeCount);
    while (justRelay.size() < params.relayingNodeCount) {
      justRelay.add(network.rd.nextInt(params.signingNodeCount + params.relayingNodeCount));
    }

    for (int i = 0; i < params.signingNodeCount + params.relayingNodeCount; i++) {
      final P2PHandelNode n = new P2PHandelNode(justRelay.contains(i));
      network.addNode(n);
      if (params.sendState) {
        // If nodes exchange their state that's an extra type of message.
        network.registerTask(n::sendStateToPeers, 1, n);
      }

      // All nodes will be sending their updated aggregation periodically.
      network.registerPeriodicTask(n::sendSigs, 1, params.sigsSendPeriod, n);

      // We also check signatures before sending them.
      network.registerConditionalTask(
          n::checkSigs, 1, params.pairingTime, n, () -> !n.toVerify.isEmpty(), () -> n.doneAt == 0);
    }

    network.setPeers();
  }

  @Override
  public Network<P2PHandelNode> network() {
    return network;
  }

  @Override
  public P2PHandel copy() {
    return new P2PHandel(params);
  }
}
