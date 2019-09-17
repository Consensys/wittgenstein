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

  private final Map<Integer, Attestation> incoming;
  private final Map<Integer, BitSet> indIncoming;
  private final Map<Integer, Attestation> outgoing;

  private int incomingCardinality = 0;
  private int outgoingCardinality = 0;

  // The aggregate signatures to verify in this level
  final List<AggToVerify> toVerifyAgg = new ArrayList<>();

  // all our peers are complete: no need to send anything for this level
  public boolean outgoingFinished = false;

  /**
   * We're going to contact all nodes, one after the other. That's our position in the peers' list.
   */
  private int posInLevel = 0;

  /** Build a level 0 object. At level 0 we need (and have) only our own signature. */
  HLevel(HNode hNode, Attestation l0) {
    this.hNode = hNode;
    level = 0;
    peers = Collections.emptyList(); // no peers at level 0
    size = 1; // only our own sig;
    incoming = Collections.singletonMap(l0.hash, l0);
    outgoing = Collections.emptyMap();
    outgoingFinished = true; // nothing to send.
    indIncoming = Collections.singletonMap(l0.hash, new BitSet());
    indIncoming.get(l0.hash).set(hNode.nodeId);
  }

  /** Build a level on top of the previous one. */
  HLevel(HLevel previousLevel, List<HNode> peers) {
    this.hNode = previousLevel.hNode;
    this.level = previousLevel.level + 1;
    this.size = 1 << (level - 1);
    this.peers = peers;
    if (peers.size() != size) {
      throw new IllegalStateException("size=" + size + ", peers.size()=" + peers.size());
    }

    this.incoming = new HashMap<>();
    this.outgoing = new HashMap<>();
    this.indIncoming = new HashMap<>();
  }

  void doCycle(int ownhash, BitSet finishedPeers) {
    if (!isOpen()) {
      return;
    }

    List<HNode> dest = getRemainingPeers(finishedPeers, 1);
    if (!dest.isEmpty()) {
      SendAggregation ss =
          new SendAggregation(level, ownhash, isIncomingComplete(), outgoing.values());
      this.hNode.handelEth2.network().send(ss, this.hNode, dest.get(0));
    }
  }

  /** We start a level if we reached the time out or if we have all the signatures. */
  boolean isOpen() {
    if (outgoingFinished) {
      return false;
    }

    if (hNode.handelEth2.network().time >= (level - 1) * hNode.handelEth2.params.levelWaitTime) {
      return true;
    }

    if (isOutgoingComplete()) {
      return true;
    }

    return false;
  }

  /**
   * @return the next 'peersCt' peers to contact. Skips the nodes blacklisted or already full for
   *     this level. If there are no peers left, sets 'outgoingFinished' to true.
   */
  List<HNode> getRemainingPeers(BitSet finishedPeers, int peersCt) {
    List<HNode> res = new ArrayList<>(peersCt);

    int start = posInLevel;
    while (peersCt > 0 && !outgoingFinished) {

      HNode p = peers.get(posInLevel++);
      if (posInLevel >= peers.size()) {
        posInLevel = 0;
      }

      if (!finishedPeers.get(p.nodeId) && !hNode.blacklist.get(p.nodeId)) {
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
  /*
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

  */

  /** @return the size the resulting aggregation if we merge the signature to our current best. */
  private int sizeIfMerged(AggToVerify sig) {
    Map<Integer, Attestation> aggMap = new HashMap<>(incoming);

    int size = 0;
    for (Attestation av : sig.attestations) {
      Attestation our = aggMap.remove(av.hash);
      if (our == null) {
        size += av.who.cardinality();
      } else if (!our.who.intersects(av.who)) {
        size += av.who.cardinality();
      } else {
        BitSet merged = (BitSet) indIncoming.get(our.hash).clone();
        merged.or(av.who);
        size += Math.max(merged.cardinality(), our.who.cardinality());
      }
    }

    for (Attestation our : aggMap.values()) {
      size += our.who.cardinality();
    }

    return size;
  }

  /**
   * Merged the incoming aggregation into our current best, and update the 'incomingCardinality'
   * field accordingly.
   */
  void mergeIncoming(AggToVerify aggv) {
    // Add the individual contributions to the list
    BitSet indivs = indIncoming.computeIfAbsent(aggv.ownHash, x -> new BitSet());
    indivs.set(aggv.from);

    incomingCardinality = 0;

    // Merge the aggregate contributions when possible. Take the best one when it's not possible
    for (Attestation av : aggv.attestations) {
      Attestation our = incoming.get(av.hash);
      if (our == null) {
        incoming.put(av.hash, av);
        incomingCardinality += av.who.cardinality();
      } else if (!our.who.intersects(av.who)) {
        our = new Attestation(our, av.who);
        incoming.replace(our.hash, our);
        incomingCardinality += our.who.cardinality();
      } else if (av.who.cardinality() > our.who.cardinality()) {
        our = new Attestation(our, av.who);
        our.who.or(indIncoming.get(our.hash));
        incoming.replace(our.hash, our);
        incomingCardinality += our.who.cardinality();
      }
    }
  }

  boolean isIncomingComplete() {
    return incomingCardinality == size;
  }

  /** @return true if we have all the signatures we're supposed to send for this level. */
  public boolean isOutgoingComplete() {
    return outgoingCardinality == size;
  }

  /**
   * This method uses a window that has a variable size depending on whether the node has received
   * invalid contributions or not. Within the window, it evaluates with a scoring function. Outside
   * it evaluates with the rank.
   */
  public AggToVerify bestToVerify(int currWindowSize, BitSet blacklist) {
    if (currWindowSize < 1) {
      throw new IllegalStateException();
    }

    if (toVerifyAgg.isEmpty()) {
      return null;
    }

    int windowIndex =
        Collections.min(toVerifyAgg, Comparator.comparingInt(AggToVerify::getRank)).rank;

    AggToVerify bestOutside = null; // best signature outside the window - rank based decision
    AggToVerify bestInside = null; // best signature inside the window - ranking
    int bestScoreInside = 0; // score associated to the best sig. inside the window

    List<AggToVerify> curatedList = new ArrayList<>();

    for (AggToVerify stv : toVerifyAgg) {
      int s = sizeIfMerged(stv);
      if (blacklist.get(stv.from) && s > incomingCardinality) {
        // only add signatures that can result in a better aggregate signature
        // select the high priority one from the low priority on
        curatedList.add(stv);
        if (stv.rank <= windowIndex + currWindowSize) {

          int score = s;
          if (score > bestScoreInside) {
            bestScoreInside = score;
            bestInside = stv;
          }
        } else {
          if (bestOutside == null || stv.rank < bestOutside.rank) {
            bestOutside = stv;
          }
        }
      }
    }

    if (curatedList.size() != toVerifyAgg.size()) {
      replaceToVerifyAgg(curatedList);
    }

    AggToVerify toVerify;
    if (bestInside != null) {
      toVerify = bestInside;
    } else if (bestOutside != null) {
      toVerify = bestOutside;
    } else {
      return null;
    }

    return toVerify;
  }

  private void replaceToVerifyAgg(List<AggToVerify> curatedList) {
    toVerifyAgg.clear();
    toVerifyAgg.addAll(curatedList);
  }
}
