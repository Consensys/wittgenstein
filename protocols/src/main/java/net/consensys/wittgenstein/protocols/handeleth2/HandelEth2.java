package net.consensys.wittgenstein.protocols.handeleth2;

import static net.consensys.wittgenstein.core.Network.chooseBadNodes;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import net.consensys.wittgenstein.core.Network;
import net.consensys.wittgenstein.core.NodeBuilder;
import net.consensys.wittgenstein.core.Protocol;
import net.consensys.wittgenstein.core.RegistryNodeBuilders;
import net.consensys.wittgenstein.core.utils.MoreMath;

/**
 * Using the Handel protocol on Eth2. Some differences with the Handel protocol: (1) We ran multiple
 * aggregations at the same time in the same committee, but we run a single verification at a time.
 * (2) For an aggregation, we actually aggregate multiple different values, because different nodes
 * have different view on the current head. (3) Wee don't try to reach a threshold but we run the
 * aggregation for a few seconds to try to get all data. (4) because of this, the aggregation runs
 * longer, but the messages at the end are not adding much value. Hence instead of a fixed period
 * time we use an exponential period time.
 */
public class HandelEth2 implements Protocol {
  final HandelEth2Parameters params;
  private final Network<HNode> network = new Network<>();

  public HandelEth2(HandelEth2Parameters params) {
    this.params = params;
  }

  @Override
  public Network<HNode> network() {
    return network;
  }

  @Override
  public HandelEth2 copy() {
    return new HandelEth2(params);
  }

  @Override
  public void init() {
    NodeBuilder nb = RegistryNodeBuilders.singleton.getByName(params.nodeBuilderName);

    BitSet badNodes = chooseBadNodes(network.rd, params.nodeCount, params.nodesDown);

    for (int i = 0; i < params.nodeCount; i++) {
      int startAt =
          params.desynchronizedStart == 0 ? 0 : network.rd.nextInt(params.desynchronizedStart);
      final HNode n = new HNode(this, startAt, nb);
      if (badNodes.get(i)) {
        n.stop();
      }
      network.addNode(n);
    }

    setReceptionRanks();
    setEmissionRanks();

    for (HNode n : network.allNodes) {
      if (!n.isDown()) {
        network.registerPeriodicTask(
            n::startNewAggregation, n.deltaStart + 1, HandelEth2Parameters.PERIOD_TIME, n);
        network.registerPeriodicTask(
            n::dissemination, n.deltaStart + 1, params.periodDurationMs, n);
        network.registerPeriodicTask(n::verify, n.deltaStart + 1, n.nodePairingTime, n);
      }
    }
  }

  /** Get the list of node included in the bitset */
  private List<HNode> peers(BitSet bs) {
    List<HNode> res = new ArrayList<>(bs.cardinality());

    for (int pos, cur = bs.nextSetBit(0); cur >= 0; pos = cur + 1, cur = bs.nextSetBit(pos)) {
      res.add(network().getNodeById(cur));
    }

    return res;
  }

  /** Set the receptions ranks for all nodes. */
  private void setReceptionRanks() {
    List<HNode> all = new ArrayList<>(network().allNodes);
    for (HNode s : network().allNodes) {
      Collections.shuffle(all, network.rd);
      for (int i = 0; i < all.size(); i++) {
        s.receptionRanks[all.get(i).nodeId] = i;
      }
    }
  }

  /** @return the number of levels */
  int levelCount() {
    return MoreMath.log2(params.nodeCount);
  }

  /** Set the emission ranks from the receptions ranks. */
  @SuppressWarnings("unchecked")
  private void setEmissionRanks() {
    for (HNode sender : network.allNodes) {
      if (sender.isDown()) {
        // No need to build an emission list for a node that won't emit
        continue;
      }

      // We build the emission list by looking at the reverse of the
      //  reception list. Logic: we speak first to the nodes that listen to us first.
      List<HNode>[] ourRankInDest = new List[params.nodeCount];
      for (HNode receiver : network.allNodes) {
        int recRank = receiver.receptionRanks[sender.nodeId];

        // We need an array of lists because we can have have multiple senders at the same rank,
        // even if we expect that most of the time the size of the list will be one.
        // As well, the rank can be [0..nodeCount], eg. there will be some holes as inside
        //  a level there are more ranks than peers. That's not a problem as the important point
        //  is the order, not the value itself.
        List<HNode> levelList = ourRankInDest[recRank];

        if (levelList == null) {
          levelList = new ArrayList<>(1);
          ourRankInDest[recRank] = levelList;
        }

        levelList.add(receiver);
      }

      // We now build the peers' list that will be used by all AggregationProcess
      assert sender.peersPerLevel.isEmpty();
      sender.peersPerLevel.add(Collections.emptyList()); // level 0:
      for (int l = 1; l <= levelCount(); l++) {
        sender.peersPerLevel.add(new ArrayList<>());
      }

      // And now we fill it with ourRankInDest
      for (List<HNode> lr : ourRankInDest) {
        if (lr == null) {
          continue;
        }
        for (HNode n : lr) {
          if (n != sender) {
            int comLevel = sender.communicationLevel(n);
            sender.peersPerLevel.get(comLevel).add(n);
          }
        }
      }
    }
  }
}
