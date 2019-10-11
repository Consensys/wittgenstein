package net.consensys.wittgenstein.protocols.ethpow;

import net.consensys.wittgenstein.core.BlockChainNetwork;
import net.consensys.wittgenstein.core.NodeBuilder;
import net.consensys.wittgenstein.core.RegistryNetworkLatencies;
import net.consensys.wittgenstein.core.RegistryNodeBuilders;

/**
 * Implementation of the algo proposed by Ittay Eyal and Emin Gun Sirer in
 * https://www.cs.cornell.edu/~ie53/publications/btcProcFC.pdf (algorithm 1, page 6)
 */
public class ETHSelfishMiner2 extends ETHMiner {
  private ETHPoW.POWBlock privateMinerBlock;
  private ETHPoW.POWBlock otherMinersHead = genesis;

  public ETHSelfishMiner2(
      BlockChainNetwork<ETHPoW.POWBlock, ETHMiner> network,
      NodeBuilder nb,
      int hashPower,
      ETHPoW.POWBlock genesis) {
    super(network, nb, hashPower, genesis);
  }

  private int privateHeight() {
    return privateMinerBlock == null ? 0 : privateMinerBlock.height;
  }

  @Override
  protected boolean sendMinedBlock(ETHPoW.POWBlock mined) {
    return false;
  }

  protected boolean includeUncle(ETHPoW.POWBlock uncle) {
    return true;
  }

  @Override
  protected void onMinedBlock(ETHPoW.POWBlock mined) {
    if (privateMinerBlock != null && mined.height <= privateMinerBlock.height) {
      throw new IllegalStateException(
          "privateMinerBlock=" + privateMinerBlock + ", mined=" + mined);
    }
    privateMinerBlock = mined;

    int deltaP = privateHeight() - (otherMinersHead.height - 1);
    if (deltaP == 0 && depth(privateMinerBlock) == 2) {
      otherMinersHead = best(otherMinersHead, privateMinerBlock);
      sendAllMined();
    }

    startNewMining(privateMinerBlock);
  }

  @Override
  protected void onReceivedBlock(ETHPoW.POWBlock rcv) {
    otherMinersHead = best(otherMinersHead, rcv);
    if (otherMinersHead != rcv) {
      // Nothing to do if their head doesn't change.
      return;
    }

    if (head == rcv) {
      // They won => We move to their chain
      sendAllMined();
      startNewMining(head);
    } else {
      // We're ahead.
      ETHPoW.POWBlock toSend = privateMinerBlock;
      while (toSend.parent != null
          && toSend.height >= rcv.height
          && toSend.parent.totalDifficulty.compareTo(rcv.totalDifficulty) > 0) {
        toSend = toSend.parent;
      }

      while (toSend != null && toSend.producer == this && minedToSend.contains(toSend)) {
        otherMinersHead = best(otherMinersHead, toSend);
        sendBlock(toSend);
        toSend = toSend.parent;
      }
    }
  }

  public static void main(String... args) {
    final int runs = 1;
    final int hours = 518;
    final String bdlName = RegistryNodeBuilders.name(RegistryNodeBuilders.Location.RANDOM, true, 0);

    for (RegistryNetworkLatencies.Type type :
        new RegistryNetworkLatencies.Type[] {
          RegistryNetworkLatencies.Type.FIXED, RegistryNetworkLatencies.Type.UNIFORM
        }) {
      for (int time : new int[] {2000, 4000, 8000}) {
        final String nlName = RegistryNetworkLatencies.name(type, time);
        final double[] pows = new double[] {0.01, 0.1, 0.2, 0.25, 0.3, 0.35, 0.40, 0.45, 0.50};

        // Merge the two results with:
        //  paste -d ',' bad.txt good.txt | awk -F "," '{ print $2 ", " $5 ", " $3 ", " $4 ", " $12
        // ", " $11 ", "  $6 ", " $13 ", " $7 ", " $14 }'
        ETHMiner.tryMiner(bdlName, nlName, ETHSelfishMiner2.class, pows, hours, runs);
        // ETHMiner.tryMiner(bdlName, nlName, ETHMiner.class, pows, hours, runs);
      }
    }
  }
}
