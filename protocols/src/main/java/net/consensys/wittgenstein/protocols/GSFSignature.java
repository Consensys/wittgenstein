package net.consensys.wittgenstein.protocols;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.io.File;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import net.consensys.wittgenstein.core.*;
import net.consensys.wittgenstein.core.json.ListNodeConverter;
import net.consensys.wittgenstein.core.messages.Message;
import net.consensys.wittgenstein.core.utils.MoreMath;
import net.consensys.wittgenstein.core.utils.StatsHelper;
import net.consensys.wittgenstein.tools.NodeDrawer;

/**
 * A p2p protocol for BLS signature aggregation.
 *
 * <p>A node runs San Fermin and communicates the results a la gossip. So it's a Gossiping San
 * Fermin Runs in parallel a task to validate the signatures sets it has received.
 */
@SuppressWarnings("WeakerAccess")
public class GSFSignature implements Protocol {
  GSFSignatureParameters params;
  final Network<GSFNode> network = new Network<>();
  NodeBuilder nb;

  public static class GSFSignatureParameters extends WParameters {
    /** The number of nodes in the network */
    final int nodeCount;

    /** The number of signatures to reach to finish the protocol. */
    final int threshold;

    /** The minimum time it takes to do a pairing for a node. */
    final int pairingTime;

    public final int timeoutPerLevelMs;
    public final int periodDurationMs;
    final int acceleratedCallsCount;

    final int nodesDown;
    final String nodeBuilderName;
    final String networkLatencyName;

    // Used for json / http server
    @SuppressWarnings("unused")
    public GSFSignatureParameters() {
      this.nodeCount = 32768 / 32;
      this.threshold = (int) (nodeCount * (0.99));
      this.pairingTime = 3;
      this.timeoutPerLevelMs = 50;
      this.periodDurationMs = 10;
      this.acceleratedCallsCount = 10;
      this.nodesDown = 0;
      this.nodeBuilderName = null;
      this.networkLatencyName = null;
    }

    public GSFSignatureParameters(
        int nodeCount,
        int threshold,
        int pairingTime,
        int timeoutPerLevelMs,
        int periodDurationMs,
        int acceleratedCallsCount,
        int nodesDown,
        String nodeBuilderName,
        String networkLatencyName) {
      if (nodesDown >= nodeCount
          || nodesDown < 0
          || threshold > nodeCount
          || (nodesDown + threshold > nodeCount)) {
        throw new IllegalArgumentException("nodeCount=" + nodeCount + ", threshold=" + threshold);
      }
      this.nodeCount = nodeCount;
      this.threshold = threshold;
      this.pairingTime = pairingTime;
      this.timeoutPerLevelMs = timeoutPerLevelMs;
      this.periodDurationMs = periodDurationMs;
      this.acceleratedCallsCount = acceleratedCallsCount;
      this.nodesDown = nodesDown;
      this.nodeBuilderName = nodeBuilderName;
      this.networkLatencyName = networkLatencyName;
    }

    public GSFSignatureParameters(
        int nodeCount,
        double ratioThreshold,
        int pairingTime,
        int timeoutPerLevelMs,
        int periodDurationMs,
        int acceleratedCallsCount,
        double ratioNodesDown,
        String nodeBuilderName,
        String networkLatencyName) {
      this(
          nodeCount,
          (int) (ratioThreshold * nodeCount),
          pairingTime,
          timeoutPerLevelMs,
          periodDurationMs,
          acceleratedCallsCount,
          (int) (ratioNodesDown * nodeCount),
          nodeBuilderName,
          networkLatencyName);
    }
  }

  public GSFSignature(GSFSignatureParameters params) {
    this.params = params;
    this.nb = RegistryNodeBuilders.singleton.getByName(params.nodeBuilderName);
    this.network.setNetworkLatency(
        RegistryNetworkLatencies.singleton.getByName(params.networkLatencyName));
  }

  @Override
  public String toString() {
    return "GSFSignature, "
        + "nodes="
        + params.nodeCount
        + ", threshold="
        + params.threshold
        + ", pairing="
        + params.pairingTime
        + "ms, level waitTime="
        + params.timeoutPerLevelMs
        + "ms, period="
        + params.periodDurationMs
        + "ms, acceleratedCallsCount="
        + params.acceleratedCallsCount
        + ", dead nodes="
        + params.nodesDown
        + ", builder="
        + params.nodeBuilderName;
  }

  static class SendSigs extends Message<GSFNode> {
    final BitSet sigs;
    final GSFNode from;
    final int level;
    final boolean levelFinished;
    final int size;
    final int received;

    public SendSigs(GSFNode from, BitSet sigs, GSFNode.SFLevel l) {
      this.sigs = (BitSet) sigs.clone();
      this.from = from;
      this.level = l.level;
      // Size = level + bit field + the signatures included + our own sig
      this.size = 1 + l.expectedSigs() / 8 + 96;
      this.levelFinished = l.verifiedSignatures.equals(l.waitedSigs);
      this.received = l.verifiedSignatures.cardinality();
    }

    @Override
    public int size() {
      return size;
    }

    @Override
    public void action(Network<GSFNode> network, GSFNode from, GSFNode to) {
      to.onNewSig(from, this);
    }
  }

  public class GSFNode extends Node {
    final ArrayList<SendSigs> toVerify = new ArrayList<>();
    final List<SFLevel> levels = new ArrayList<>();
    final BitSet verifiedSignatures = new BitSet(params.nodeCount);
    final int nodePairingTime = (int) (Math.max(1, params.pairingTime * speedRatio));

    boolean done = false;
    int sigChecked = 0;
    int sigQueueSize = 0;

    GSFNode() {
      super(network.rd, nb);
      verifiedSignatures.set(nodeId);
    }

    public void initLevel() {
      int roundedPow2NodeCount = MoreMath.roundPow2(params.nodeCount);
      BitSet allPreviousNodes = new BitSet(params.nodeCount);
      SFLevel last = new SFLevel();
      levels.add(last);
      for (int l = 1; Math.pow(2, l) <= roundedPow2NodeCount; l++) {
        allPreviousNodes.or(last.waitedSigs);
        last = new SFLevel(last, allPreviousNodes);
        levels.add(last);
      }
    }

    BitSet getLastFinishedLevel() {
      BitSet res = new BitSet();
      SFLevel sfl = levels.get(0);
      boolean done = false;
      while (!done) {
        if (sfl.waitedSigs.equals(sfl.verifiedSignatures)) {
          res.or(sfl.waitedSigs);
          if (sfl.level < levels.size() - 1) {
            sfl = levels.get(sfl.level + 1);
          } else {
            done = true;
          }
        } else {
          done = true;
        }
      }
      return res;
    }

    public void doCycle() {
      // We send the last finished level because if we are more advanced
      //  than our counterparty we can send the full set of signature without risking
      //  a collusion between the set: out set replaces its own set
      BitSet toSend = getLastFinishedLevel();
      boolean previousLevelReceiveOk = true;
      for (SFLevel sfl : levels) {
        sfl.doCycle(
            toSend, previousLevelReceiveOk); // We send what we collected at the previous levels
        previousLevelReceiveOk = sfl.hasReceivedAll();
        toSend.or(sfl.verifiedSignatures);
      }
    }

    /**
     * At a given level, we send the signatures received from the previous level and we expect the
     * equivalent from our peers of this level.<br>
     * If nodeId == 0<br>
     * l0: 0 => send to 1 <br>
     * l1: 0 1 => send to 2 3 <br>
     * l2: 0 1 2 3 => send to 4 5 6 7 <br>
     * l3: 0 1 2 3 4 5 6 7 => send to 8 9 10 11 12 13 14 15 <br>
     */
    public class SFLevel {
      final int level;

      @JsonSerialize(converter = ListNodeConverter.class)
      final List<GSFNode> peers;
      // The peers when we have all signatures for this level.
      final BitSet waitedSigs; // 1 for the signatures we should have at this level
      final BitSet verifiedSignatures = new BitSet(); // The signatures verified in this level
      final BitSet individualSignatures = new BitSet(); // The individual signatures received
      final BitSet indivVerifiedSig = new BitSet(); // The individual signatures verified
      final Map<GSFNode, Integer> received = new HashMap<>();

      /**
       * We're going to contact all nodes, one after the other. That's our position in the peers'
       * list.
       */
      int posInLevel = 0;

      /** Number of message to send for each new update. */
      int remainingCalls;

      /**
       * Build a level 0 object. At level 0 need (and have) only our own signature. We have only one
       * peer to contact.
       */
      public SFLevel() {
        level = 0;
        waitedSigs = new BitSet();
        waitedSigs.set(nodeId);
        verifiedSignatures.set(nodeId);
        peers = Collections.emptyList();
        remainingCalls = 0;
      }

      /** Build a level on top of the previous one. */
      public SFLevel(SFLevel previousLevel, BitSet allPreviousNodes) {
        this.level = previousLevel.level + 1;

        // Signatures needed to finish the current level are:
        //  sigs of the previous level + peers of the previous level.
        //  If we have all this we have finished this level
        waitedSigs = allSigsAtLevel(this.level);
        waitedSigs.andNot(allPreviousNodes);
        peers = randomSubset(waitedSigs, Integer.MAX_VALUE);
        remainingCalls = peers.size();
      }

      /**
       * That's the number of signatures we have if we have all of them. It's also the number of
       * signatures we're going to send.
       */
      int expectedSigs() {
        return waitedSigs.cardinality();
      }

      /** We start a level if we reached the time out or if we have all the signatures. */
      boolean hasStarted(BitSet toSend, boolean previousLevelReceiveOk) {
        if (network.time >= (level) * params.timeoutPerLevelMs) {
          return true;
        }

        if (toSend.cardinality() >= expectedSigs()) {
          // Equals or greater because we can send more sigs
          //  if the next level is completed already
          return true;
        }

        // The idea here is not to wait for a timeout if
        //  our peers at this level have already finished on their side, even if
        //  some nodes from older levels are not finished yet
        // see https://github.com/ConsenSys/handel/issues/34
        /*if (previousLevelReceiveOk)
          return true;
        */

        return false;
      }

      void doCycle(BitSet toSend, boolean previousLevelReceiveOk) {
        if (remainingCalls == 0 || !hasStarted(toSend, previousLevelReceiveOk)) {
          return;
        }

        List<GSFNode> dest = getRemainingPeers(1);
        if (!dest.isEmpty()) {
          SendSigs ss = new SendSigs(GSFNode.this, toSend, this);
          network.send(ss, GSFNode.this, dest.get(0));
        }
      }

      List<GSFNode> getRemainingPeers(int peersCt) {
        List<GSFNode> res = new ArrayList<>(peersCt);

        int start = posInLevel;
        while (peersCt > 0 && remainingCalls > 0) {
          remainingCalls--;

          GSFNode p = peers.get(posInLevel++);
          if (posInLevel >= peers.size()) {
            posInLevel = 0;
          }

          Integer count = received.get(p);
          if (count == null || true) {
            res.add(p);
            peersCt--;
          } else {
            if (posInLevel == start) {
              remainingCalls = 0;
            }
          }
        }

        return res;
      }

      public boolean hasReceivedAll() {
        BitSet wanted = (BitSet) waitedSigs.clone();
        wanted.and(verifiedSignatures);
        return wanted.cardinality() >= .8 * expectedSigs();
      }
    }

    /** @return all the signatures you should have when this round is finished. */
    public BitSet allSigsAtLevel(int round) {
      if (round < 1) {
        throw new IllegalArgumentException("round=" + round);
      }
      BitSet res = new BitSet(params.nodeCount);
      int cMask = (1 << round) - 1;
      int start = (cMask | nodeId) ^ cMask;
      int end = nodeId | cMask;
      end = Math.min(end, params.nodeCount - 1);
      res.set(start, end + 1);
      res.set(nodeId, false);

      return res;
    }

    boolean include(BitSet large, BitSet small) {
      BitSet a = (BitSet) large.clone();
      a.and(small);
      return a.equals(small);
    }

    /**
     * If the state has changed we send a message to all. If we're done, we update all our peers: it
     * will be our last message.
     */
    void updateVerifiedSignatures(GSFNode from, int level, BitSet sigs) {
      SFLevel sfl = levels.get(level);

      if (sigs.cardinality() == 1) {
        sfl.indivVerifiedSig.set(from.nodeId);
      }
      sigs.or(sfl.indivVerifiedSig);

      // These lines remove Olivier's optimisation
      // sigs = (BitSet) sigs.clone();
      // sigs.and(sfl.waitedSigs);

      boolean resetRemaining = false;
      if (sigs.cardinality() > sfl.expectedSigs()) {
        // The sender sent us signatures from its next levels as well.
        // It means that it's actually signatures from our first levels
        // For example, level==5, but the remote node sent us [1-5] plus maybe [6-]
        for (int i = 1; i < levels.size() && include(sigs, levels.get(i).waitedSigs); i++) {
          SFLevel l = levels.get(i);
          if (!l.verifiedSignatures.equals(l.waitedSigs)) {
            l.verifiedSignatures.or(l.waitedSigs);
            verifiedSignatures.or(l.waitedSigs);
            resetRemaining = true;
          }
          if (resetRemaining) {
            l.remainingCalls = l.peers.size();
          }
        }
        sigs = (BitSet) sfl.waitedSigs.clone();
      }

      if (sfl.verifiedSignatures.cardinality() > 0 && !sigs.intersects(sfl.verifiedSignatures)) {
        // If the new sigs do not intersect with the previous ones we can aggregate then.
        // Note that technically we could do much more, i.e. searching the history of
        // sigs we have that could intersect
        sigs.or(sfl.verifiedSignatures);
      }

      if (sigs.cardinality() > sfl.verifiedSignatures.cardinality() || resetRemaining) {

        for (int i = sfl.level; i < levels.size(); i++) {
          // We have some new sigs. This is interesting for our level and all the
          //  levels above. So we reset the remainingCalls variable.
          levels.get(i).remainingCalls = levels.get(i).peers.size();
        }

        // We're going to update our common set only if we have more sigs.
        // It's a replacement, not a completion.
        sfl.verifiedSignatures.andNot(sfl.waitedSigs);
        sfl.verifiedSignatures.or(sigs);

        verifiedSignatures.andNot(sfl.waitedSigs);
        verifiedSignatures.or(sigs);

        if (params.acceleratedCallsCount > 0) {
          // See if we completed this level. Does it finish something?
          // If so, we're going to send immediately an update to 'acceleratedCallsCount' nodes
          // It makes the protocol faster for the branches that are complete.
          BitSet bestToSend = getLastFinishedLevel();
          while (include(bestToSend, sfl.waitedSigs) && sfl.level < levels.size() - 1) {
            sfl = levels.get(sfl.level + 1);
            SendSigs sendSigs = new SendSigs(GSFNode.this, bestToSend, sfl);
            List<GSFNode> peers = sfl.getRemainingPeers(params.acceleratedCallsCount);
            if (!peers.isEmpty()) {
              network.send(sendSigs, this, peers);
            }
          }
        }
        if (doneAt == 0 && verifiedSignatures.cardinality() >= params.threshold) {
          doneAt = network.time;

          // todo: in a byzantine context we need to continue longer to send what we
          //  have to all nodes
          // done = true;
        }
      }
    }

    private List<GSFNode> randomSubset(BitSet nodes, int nodeCt) {
      List<GSFNode> res = new ArrayList<>(nodes.cardinality());
      for (int pos, cur = nodes.nextSetBit(0);
          cur >= 0;
          pos = cur + 1, cur = nodes.nextSetBit(pos)) {
        res.add(network.getNodeById(cur));
      }

      Collections.shuffle(res, network.rd);
      if (res.size() > nodeCt) {
        return res.subList(0, nodeCt);
      } else {
        return res;
      }
    }

    /**
     * Evaluate the interest to verify a signature by setting a score The higher the score the more
     * interesting the signature is. 0 means the signature is not interesting and can be discarded.
     */
    private int evaluateSig(SFLevel l, BitSet sig) {
      int newTotal = 0; // The number of signatures in our new best
      int addedSigs =
          0; // The number of sigs we add with our new best compared to the existing one. Can be
      // negative
      int combineCt =
          0; // The number of sigs in our new best that come from combining it with individual sigs

      if (l.verifiedSignatures.cardinality() >= l.expectedSigs()) {
        return 0;
      }

      BitSet withIndiv = (BitSet) l.indivVerifiedSig.clone();
      withIndiv.or(sig);

      if (l.verifiedSignatures.cardinality() == 0) {
        // the best is the new multi-sig combined with the ind. sigs
        newTotal = sig.cardinality();
        addedSigs = newTotal;
        combineCt = 0;
      } else {
        if (sig.intersects(l.verifiedSignatures)) {
          // We can't merge, it's a replace
          newTotal = withIndiv.cardinality();
          addedSigs = newTotal - l.verifiedSignatures.cardinality();
          combineCt = newTotal;
        } else {
          // We can merge our current best and the new ms. We also add individual
          //  signatures that we previously verified
          withIndiv.or(l.verifiedSignatures);
          newTotal = withIndiv.cardinality();
          addedSigs = newTotal - l.verifiedSignatures.cardinality();
          combineCt = newTotal;
        }
      }

      if (addedSigs <= 0) {
        if (sig.cardinality() == 1 && !sig.intersects(l.indivVerifiedSig)) {
          return 1;
        }
        return 0;
      }

      if (newTotal == l.expectedSigs()) {
        // This completes a level! That's the best options for us. We give
        //  a greater value to the first levels/
        return 1000000 - l.level * 10;
      }

      // It adds value, but does not complete a level. We
      //  favorize the older level but take into account the number of sigs we receive as well.
      return 100000 - l.level * 100 + addedSigs;
    }

    /** Nothing much to do when we receive a sig set: we just add it to our toVerify list. */
    void onNewSig(GSFNode from, SendSigs ssigs) {
      SFLevel l = levels.get(ssigs.level);

      if (ssigs.levelFinished) {
        l.received.put(from, 1);
      }

      toVerify.add(ssigs);

      // We add the individual signature as a way to resist byzantine attacks
      if (!l.individualSignatures.get(from.nodeId)) {
        BitSet indiv = new BitSet();
        indiv.set(from.nodeId);
        SendSigs si = new SendSigs(from, indiv, l);
        toVerify.add(si);
        l.individualSignatures.set(from.nodeId);
      }
      sigQueueSize = toVerify.size();
    }

    public void checkSigs() {
      SendSigs best = null;
      int score = 0;
      Iterator<SendSigs> it = toVerify.iterator();
      while (it.hasNext()) {
        SendSigs cur = it.next();
        SFLevel l = levels.get(cur.level);
        int ns = evaluateSig(l, cur.sigs);
        if (ns > score) {
          score = ns;
          best = cur;
        } else if (ns == 0) {
          it.remove();
        }
      }

      if (best != null) {
        toVerify.remove(best);
        sigChecked++;
        sigQueueSize = toVerify.size();
        final SendSigs tBest = best;
        network.registerTask(
            () -> GSFNode.this.updateVerifiedSignatures(tBest.from, tBest.level, tBest.sigs),
            network.time + nodePairingTime,
            GSFNode.this);
      }
    }

    @Override
    public String toString() {
      return "GSFNode{"
          + "nodeId="
          + nodeId
          + ", doneAt="
          + doneAt
          + ", sigs="
          + verifiedSignatures.cardinality()
          + ", msgReceived="
          + msgReceived
          + ", msgSent="
          + msgSent
          + ", KBytesSent="
          + bytesSent / 1024
          + ", KBytesReceived="
          + bytesReceived / 1024
          + '}';
    }
  }

  @Override
  public GSFSignature copy() {
    return new GSFSignature(params);
  }

  public void init() {
    for (int i = 0; i < params.nodeCount; i++) {
      final GSFNode n = new GSFNode();
      network.addNode(n);
    }

    for (int setDown = 0; setDown < params.nodesDown; ) {
      int down = network.rd.nextInt(params.nodeCount);
      Node n = network.allNodes.get(down);
      if (!n.isDown() && down != 1) {
        // We keep the node 1 up to help on debugging
        n.stop();
        setDown++;
      }
    }

    for (GSFNode n : network.allNodes) {
      if (!n.isDown()) {
        n.initLevel();
        network.registerPeriodicTask(n::doCycle, 1, params.periodDurationMs, n);
        network.registerConditionalTask(
            n::checkSigs, 1, n.nodePairingTime, n, () -> !n.toVerify.isEmpty(), () -> !n.done);
      }
    }
  }

  @Override
  public Network<GSFNode> network() {
    return network;
  }

  static class GFSNodeStatus implements NodeDrawer.NodeStatus {
    final GSFSignatureParameters params;

    GFSNodeStatus(GSFSignatureParameters params) {
      this.params = params;
    }

    @Override
    public int getMax() {
      return params.nodeCount;
    }

    @Override
    public int getMin() {
      return 1;
    }

    @Override
    public int getVal(Node n) {
      return ((GSFNode) n).verifiedSignatures.cardinality();
    }

    @Override
    public boolean isSpecial(Node n) {
      return n.extraLatency > 0;
    }
  }

  private static Predicate<Protocol> newConfIf() {
    return p1 -> {
      GSFSignature p = (GSFSignature) p1;
      for (GSFNode n : p.network().allNodes) {
        // All up nodes must have reached the threshold, so if one live
        //  node has not reached it we continue
        if (!n.isDown() && n.verifiedSignatures.cardinality() < p.params.threshold) {
          return true;
        }
      }
      return false;
    };
  }

  private static GSFSignature newProtocol() {
    int nodeCt = 32768 / 8;
    double deadR = 0.10;
    double tsR = .85;

    String nb = RegistryNodeBuilders.name(RegistryNodeBuilders.Location.AWS, false, 0.33);
    String nl = NetworkLatency.AwsRegionNetworkLatency.class.getSimpleName();

    int ts = (int) (tsR * nodeCt);
    int dead = (int) (deadR * nodeCt);
    GSFSignatureParameters params =
        new GSFSignatureParameters(nodeCt, ts, 4, 50, 20, 10, dead, nb, nl);
    return new GSFSignature(params);
  }

  public static void drawImgs() {
    GSFSignature p = newProtocol();
    Predicate<Protocol> contIf = newConfIf();

    p.init();
    int freq = 10;
    try (NodeDrawer nd =
        new NodeDrawer(new GFSNodeStatus(p.params), new File("/tmp/handel_anim.gif"), freq)) {
      int i = 0;
      do {
        System.out.println("Drawing simulation step " + i);
        p.network.runMs(freq);

        nd.drawNewState(p.network.time, TimeUnit.MILLISECONDS, p.network.liveNodes());
        if (i % 100 == 0) {
          nd.writeLastToGif(new File("/tmp/img_" + i + ".gif"));
        }
        i++;

      } while (contIf.test(p));
    }
  }

  public static void sigsPerTime() {
    GSFSignature p = newProtocol();

    StatsHelper.StatsGetter sg =
        new StatsHelper.StatsGetter() {
          final List<String> fields = new StatsHelper.SimpleStats(0, 0, 0).fields();

          @Override
          public List<String> fields() {
            return fields;
          }

          @Override
          public StatsHelper.Stat get(List<? extends Node> liveNodes) {
            return StatsHelper.getStatsOn(
                liveNodes, n -> ((GSFNode) n).verifiedSignatures.cardinality());
          }
        };

    ProgressPerTime.OnSingleRunEnd cb =
        p12 -> {
          StatsHelper.SimpleStats ss =
              StatsHelper.getStatsOn(
                  p12.network().liveNodes(), n -> (int) ((GSFNode) n).speedRatio);
          System.out.println("min/avg/max speedRatio=" + ss.min + "/" + ss.avg + "/" + ss.max);

          ss = StatsHelper.getStatsOn(p12.network().liveNodes(), n -> ((GSFNode) n).sigChecked);
          System.out.println("min/avg/max sigChecked=" + ss.min + "/" + ss.avg + "/" + ss.max);

          ss =
              StatsHelper.getStatsOn(
                  p12.network().liveNodes(),
                  n -> ((GSFNode) n).sigQueueSize / ((GSFNode) n).sigChecked);
          System.out.println("min/avg/max queueSize=" + ss.min + "/" + ss.avg + "/" + ss.max);
        };

    ProgressPerTime ppt =
        new ProgressPerTime(p, "", "number of signatures", sg, 1, cb, 10, TimeUnit.MILLISECONDS);

    Predicate<Protocol> contIf = newConfIf();
    ppt.run(contIf);
  }

  public static void main(String... args) {
    // sigsPerTime();
    drawImgs();
  }
}
