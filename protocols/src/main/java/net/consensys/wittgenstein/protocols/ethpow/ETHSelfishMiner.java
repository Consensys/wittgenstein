package net.consensys.wittgenstein.protocols.ethpow;

import net.consensys.wittgenstein.core.BlockChainNetwork;
import net.consensys.wittgenstein.core.NodeBuilder;
import net.consensys.wittgenstein.core.RegistryNetworkLatencies;
import net.consensys.wittgenstein.core.RegistryNodeBuilders;

/**
 * Implementation of the algo proposed by Ittay Eyal and Emin Gun Sirer in
 * https://www.cs.cornell.edu/~ie53/publications/btcProcFC.pdf (algorithm 1, page 6)
 */
public class ETHSelfishMiner extends ETHMiner {
  private ETHPoW.POWBlock privateMinerBlock;
  private ETHPoW.POWBlock otherMinersHead = genesis;

  public ETHSelfishMiner(
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

  @Override
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

    // The previous delta between the two chains
    int deltaP = privateHeight() - (otherMinersHead.height - 1);

    // In Ethereum difficulty changes all the time, the block kept does
    //  not depend on network order issue but on the (total) difficulty. So we
    //  could optimize this attack by not looking only at the depth
    //  but at the real total difficulty.

    if (deltaP <= 0) {
      // They won => We move to their chain
      sendAllMined();
      startNewMining(head);
    } else {
      ETHPoW.POWBlock toSend;
      if (deltaP == 1) {
        // Same height
        toSend = privateMinerBlock;
      } else if (deltaP == 2) {
        // We're ahead by 1, by sending all of our branch we should win
        toSend = privateMinerBlock;
      } else {
        // We're far ahead, so we just try to win by sending a competing block

        // Most of the time toSend.height wil equal rcv.height but there could some exception
        //  (1) if you receive two blocks for the same height from different other miners
        // The original paper.doesn't care because the second block will be ignored by a bitcoin
        // node
        //    but in ethereum it can happen if the second block has a greater total difficulty.
        //  (2) if you receive a block but haven't yet received the parent block.
        toSend = privateMinerBlock;
        while (minedToSend.contains(toSend.parent) && toSend.height > rcv.height) {
          toSend = toSend.parent;
          assert toSend != null;
        }
        if (toSend.height != rcv.height) {
          ETHPoW.POWBlock f = toSend;
          while (f.height != rcv.height) {
            f = f.parent;
          }
          int c = f.totalDifficulty.compareTo(rcv.totalDifficulty);
          if (c < 0) {
            return;
          }
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
    final int runs = 5;
    final int hours = 518;
    final String bdlName = RegistryNodeBuilders.name(RegistryNodeBuilders.Location.RANDOM, true, 0);
    final double[] pows = new double[] {0.01, 0.1, 0.2, 0.25, 0.3, 0.35, 0.40, 0.45, 0.50};

    for (int time : new int[] {0, 1000, 2000, 4000}) {
      final String nlName =
          RegistryNetworkLatencies.name(RegistryNetworkLatencies.Type.FIXED, time);
      // Merge the two results with:
      //  paste -d ',' bad.txt good.txt | awk -F "," '{ print $2 "," $5 "," $3 "," $4 "," $12 ", "
      // $11 ","  $6 "," $13 "," $7 "," $14 }'
      ETHMiner.tryMiner(bdlName, nlName, ETHSelfishMiner.class, pows, hours, runs);
      ETHMiner.tryMiner(bdlName, nlName, ETHMiner.class, pows, hours, runs);
    }

    final String nlName =
        RegistryNetworkLatencies.name(RegistryNetworkLatencies.Type.UNIFORM, 2000);
    ETHMiner.tryMiner(bdlName, nlName, ETHSelfishMiner.class, pows, hours, runs);
    ETHMiner.tryMiner(bdlName, nlName, ETHMiner.class, pows, hours, runs);
  }
}
