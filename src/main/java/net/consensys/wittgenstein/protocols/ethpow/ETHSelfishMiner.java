package net.consensys.wittgenstein.protocols.ethpow;

import net.consensys.wittgenstein.core.BlockChainNetwork;
import net.consensys.wittgenstein.core.NetworkLatency;
import net.consensys.wittgenstein.core.NodeBuilder;
import net.consensys.wittgenstein.core.RegistryNodeBuilders;


/**
 *
 * Implementation of the algo proposed by Ittay Eyal and Emin Gun Sirer in
 * https://www.cs.cornell.edu/~ie53/publications/btcProcFC.pdf (algorithm 1, page 6)
 */
public class ETHSelfishMiner extends ETHMiner {
  private ETHPoW.POWBlock privateMinerBlock;
  private ETHPoW.POWBlock otherMinersHead = genesis;

  public ETHSelfishMiner(BlockChainNetwork<ETHPoW.POWBlock, ETHMiner> network, NodeBuilder nb,
      int hashPower, ETHPoW.POWBlock genesis) {
    super(network, nb, hashPower, genesis);
  }

  private int privateHeight() {
    return privateMinerBlock == null ? 0 : privateMinerBlock.height;
  }

  @Override
  protected boolean sendMinedBlock(ETHPoW.POWBlock mined) {
    return false;
  }

  @Override
  protected void onMinedBlock(ETHPoW.POWBlock mined) {
    privateMinerBlock = privateMinerBlock == null ? mined : best(privateMinerBlock, mined);

    if (privateMinerBlock != mined) {
      throw new IllegalStateException(
          "privateMinerBlock=" + privateMinerBlock + ", mined=" + mined);
    }

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
      // Nothing to do if the head doesn't change.
      return;
    }

    // The previous delta between the two chains
    int deltaP = privateHeight() - (otherMinersHead.height - 1);

    if (deltaP <= 0) {
      // They won => We move to their chain
      otherMinersHead = best(otherMinersHead, privateMinerBlock);
      sendAllMined();
      startNewMining(head);
    } else {
      ETHPoW.POWBlock toSend;
      if (deltaP == 1) {
        // Tie => We going to send our secret block to try to win.
        toSend = privateMinerBlock;
      } else if (deltaP == 2) {
        // We're ahead, we're sending a block to move them to our chain.
        toSend = privateMinerBlock.parent;
      } else {
        // We're far ahead, just sending enough so they
        toSend = privateMinerBlock;
        while (minedToSend.contains(toSend.parent)) {
          toSend = toSend.parent;
          assert toSend != null;
        }
      }

      while (toSend != null && toSend.producer == this && minedToSend.contains(toSend)) {
        otherMinersHead = best(otherMinersHead, toSend);
        sendBlock(toSend);
        toSend = toSend.parent;
      }
    }
  }

  public static void main(String... args) {
    final String nlName = NetworkLatency.NetworkNoLatency.class.getSimpleName();
    final String bdlName = RegistryNodeBuilders.name(RegistryNodeBuilders.Location.RANDOM, true, 0);
    final double[] pows = new double[] {0.01, 0.1, 0.2, 0.25, 0.3, 0.35, 0.40, 0.45, 0.50};

    final int runs = 4;
    final int hours = 500;

    // Merge the two results with:
    //  paste -d ',' bad.txt good.txt | awk -F "," '{ print $2 ", " $5 ", " $3 ", " $4 ", " $12 ", " $11 ", "  $6 ", " $13 ", " $7 ", " $14 }'
    ETHMiner.tryMiner(bdlName, nlName, ETHSelfishMiner.class, pows, hours, runs);
    ETHMiner.tryMiner(bdlName, nlName, ETHMiner.class, pows, hours, runs);
  }
}
