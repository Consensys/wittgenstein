package net.consensys.wittgenstein.protocols.ethpow;

import net.consensys.wittgenstein.core.BlockChainNetwork;
import net.consensys.wittgenstein.core.NodeBuilder;


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
}
