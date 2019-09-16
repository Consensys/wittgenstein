package net.consensys.wittgenstein.protocols.handeleth2;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.util.*;
import net.consensys.wittgenstein.core.json.ListNodeConverter;

public class HLevel {
  private final transient HNode hNode;

  final int level;
  final int size;

  // peers, sorted in emission order
  @JsonSerialize(converter = ListNodeConverter.class)
  final List<HNode> peers;

  // The peers when we have all signatures for this level.
  final BitSet waitedSigs = new BitSet(); // 1 for the signatures we should get at this level

  // The aggregate signatures verified in this level
  final BitSet lastAggVerified = new BitSet();

  // The merge of the individual & the last agg verified
  final BitSet totalIncoming = new BitSet();

  // The individual signatures verified in this level
  final BitSet verifiedIndSignatures = new BitSet();

  // The aggregate signatures to verify in this level
  // final List<HandelEth2.SigToVerify> toVerifyAgg = new ArrayList<>();

  // The individual signatures received
  final BitSet toVerifyInd = new BitSet();

  // The list of peers who told us they had finished this level.
  public BitSet finishedPeers = new BitSet();

  // The signatures we're sending for this level
  final BitSet totalOutgoing = new BitSet();

  // all our peers are complete: no need to send anything for this level
  public boolean outgoingFinished = false;

  /**
   * We're going to contact all nodes, one after the other. That's our position in the peers' list.
   */
  int posInLevel = 0;

  /** Build a level 0 object. At level 0 we need (and have) only our own signature. */
  HLevel(HNode hNode) {
    this.hNode = hNode;
    level = 0;
    size = 1;
    outgoingFinished = true;
    lastAggVerified.set(hNode == null ? 0 : hNode.nodeId);
    verifiedIndSignatures.set(hNode == null ? 0 : hNode.nodeId);
    totalIncoming.set(hNode == null ? 0 : hNode.nodeId);
    peers = Collections.emptyList();
  }

  // For json
  HLevel() {
    this(null);
  }

  /** Build a level on top of the previous one. */
  /*
    HLevel(HLevel previousLevel, BitSet allPreviousNodes) {
      this.hNode = previousLevel.hNode;
      level = previousLevel.level + 1;

      // Signatures needed to finish the current level are:
      //  sigs of the previous level + peers of the previous level.
      //  If we have all this we have finished this level
      waitedSigs.or(allSigsAtLevel(this.level));
      waitedSigs.andNot(allPreviousNodes);
      totalOutgoing.set(hNode.nodeId);
      size = waitedSigs.cardinality();
      peers = new ArrayList<>(size);
    }
  */
  /**
   * That's the number of signatures we have if we have all of them. It's also the number of
   * signatures we're going to send.
   */
  public int expectedSigs() {
    return size;
  }

  /** The list of nodes we're waiting signatures from in this level */
  /*
    public List<HNode> expectedNodes() {
      List<Handel.HNode> expected = new ArrayList<>(size);

      for (int pos, cur = waitedSigs.nextSetBit(0);
           cur >= 0;
           pos = cur + 1, cur = waitedSigs.nextSetBit(pos)) {
        expected.add(hNode.handelEth2.network() .getNodeById(cur));
      }
      return expected;
    }
  */
  /** We start a level if we reached the time out or if we have all the signatures. */
  /*  boolean isOpen() {
    if (outgoingFinished) {
      return false;
    }

    if (hNode.handelEth2.network() .time >= (level - 1) * handelEth2.params.levelWaitTime) {
      return true;
    }

    if (outgoingComplete()) {
      return true;
    }

    return false;
  }

  void doCycle() {
    if (!isOpen()) {
      return;
    }

    List<Handel.HNode> dest = getRemainingPeers(1);
    if (!dest.isEmpty()) {
      Handel.SendSigs ss = new Handel.SendSigs(totalOutgoing, this);
      network.send(ss, Handel.HNode.this, dest.get(0));
    }
  }

  List<Handel.HNode> getRemainingPeers(int peersCt) {
    List<Handel.HNode> res = new ArrayList<>(peersCt);

    int start = posInLevel;
    while (peersCt > 0 && !outgoingFinished) {

      Handel.HNode p = peers.get(posInLevel++);
      if (posInLevel >= peers.size()) {
        posInLevel = 0;
      }

      if (!finishedPeers.get(p.nodeId) && !hNode. blacklist.get(p.nodeId)) {
        res.add(p);
        peersCt--;
      } else {
        if (posInLevel == start) {
          outgoingFinished = true;
        }
      }
    }

    return res;
  }

  void buildEmissionList(List<Handel.HNode>[] emissions) {
    if (!peers.isEmpty()) {
      throw new IllegalStateException();
    }
    for (List<Handel.HNode> ranks : emissions) {
      if (ranks != null && !ranks.isEmpty()) {
        if (ranks.size() > 1) {
          Collections.shuffle(ranks, network.rd);
        }
        peers.addAll(ranks);
      }
    }
  }

  public boolean incomingComplete() {
    return waitedSigs.equals(totalIncoming);
  }

  public boolean outgoingComplete() {
    return totalOutgoing.cardinality() == size;
  }

  int sizeIfIncluded(Handel.SigToVerify sig) {
    BitSet c = (BitSet) sig.sig.clone();
    if (!c.intersects(totalIncoming)) {
      c.or(totalIncoming);
    }
    c.or(verifiedIndSignatures);

    return c.cardinality();
  }

  Handel.SigToVerify createSuicideByzantineSig(int maxRank) {
    boolean reset = false;
    for (int i = suicideBizAfter; i < peers.size(); i++) {
      Handel.HNode p = peers.get(i);
      if (p.isDown() && !blacklist.get(p.nodeId)) {
        if (!reset) {
          suicideBizAfter = i;
          reset = true;
        }
        if (receptionRanks[p.nodeId] < maxRank) {
          return new Handel.SigToVerify(p.nodeId, level, receptionRanks[p.nodeId], waitedSigs, true);
        }
      }
    }

    if (!reset) {
      // No byzantine nodes left in this level
      suicideBizAfter = -1;
    }

    return null;
  }*/

  /**
   * This method uses a window that has a variable size depending on whether the node has received
   * invalid contributions or not. Within the window, it evaluates with a scoring function. Outside
   * it evaluates with the rank.
   */
  /*
  public Handel.SigToVerify bestToVerify() {
    if (toVerifyAgg.isEmpty()) {
      return null;
    }
    if (currWindowSize < 1) {
      throw new IllegalStateException();
    }

    int windowIndex =
      Collections.min(toVerifyAgg, Comparator.comparingInt(Handel.SigToVerify::getRank)).rank;

    if (suicideBizAfter >= 0) {
      Handel.SigToVerify bSig = createSuicideByzantineSig(windowIndex + currWindowSize);
      if (bSig != null) {
        toVerifyAgg.add(bSig);
        sigQueueSize++;
        return bSig;
      }
    }

    int curSignatureSize = totalIncoming.cardinality();
    Handel.SigToVerify bestOutside = null; // best signature outside the window - rank based decision
    Handel.SigToVerify bestInside = null; // best signature inside the window - ranking
    int bestScoreInside = 0; // score associated to the best sig. inside the window

    int removed = 0;
    List<Handel.SigToVerify> curatedList = new ArrayList<>();
    for (Handel.SigToVerify stv : toVerifyAgg) {
      int s = sizeIfIncluded(stv);
      if (!blacklist.get(stv.from) && s > curSignatureSize) {
        // only add signatures that can result in a better aggregate signature
        // select the high priority one from the low priority on
        curatedList.add(stv);
        if (stv.rank <= windowIndex + currWindowSize) {

          int score = evaluateSig(this, stv.sig);
          if (score > bestScoreInside) {
            bestScoreInside = score;
            bestInside = stv;
          }
        } else {
          if (bestOutside == null || stv.rank < bestOutside.rank) {
            bestOutside = stv;
          }
        }
      } else {
        removed++;
      }
    }

    if (removed > 0) {
      replaceToVerifyAgg(curatedList);
    }

    Handel.SigToVerify toVerify;
    if (bestInside != null) {
      toVerify = bestInside;
    } else if (bestOutside != null) {
      toVerify = bestOutside;
    } else {
      return null;
    }

    return toVerify;
  }

  private void replaceToVerifyAgg(List<Handel.SigToVerify> curatedList) {
    int oldSize = toVerifyAgg.size();
    toVerifyAgg.clear();
    toVerifyAgg.addAll(curatedList);
    int newSize = toVerifyAgg.size();
    sigQueueSize -= oldSize;
    sigQueueSize += newSize;
    if (sigQueueSize < 0) {
      throw new IllegalStateException("sigQueueSize=" + sigQueueSize);
    }
  }*/
}
