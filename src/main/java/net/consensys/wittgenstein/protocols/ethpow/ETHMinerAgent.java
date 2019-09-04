package net.consensys.wittgenstein.protocols.ethpow;

import net.consensys.wittgenstein.core.BlockChainNetwork;
import net.consensys.wittgenstein.core.NodeBuilder;
import net.consensys.wittgenstein.core.RegistryNetworkLatencies;
import net.consensys.wittgenstein.core.RegistryNodeBuilders;

import java.util.ArrayList;
import java.util.List;

/**
 * To call this agent from python: 1) Install the right tools:
 *
 * <pre>{@code
 * sudo pip3 install Cython
 * sudo pip3 install pyjnius
 * }</pre>
 *
 * 2) Build the package
 *
 * <pre>{@code
 * gradle clean shadowJar
 * }</pre>
 *
 * 3) You can now use this code from python, for example with:
 *
 * <pre>{@code
 * import jnius_config
 * jnius_config.set_classpath('.', './build/libs/wittgenstein-all.jar')
 * from jnius import autoclass
 * p = autoclass('net.consensys.wittgenstein.protocols.ethpow.ETHMinerAgent').create(0.25)
 * p.init()
 * p.goNextStep()
 * p.network().printNetworkLatency()
 * p.getByzNode().head.height
 * }</pre>
 */
public class ETHMinerAgent extends ETHMiner {
  private ETHPoW.POWBlock privateMinerBlock;
  private ETHPoW.POWBlock otherMinersHead = genesis;
  public int action = 0;
  public void setAction(int action) {
    this.action = action;
  }


  public ETHMinerAgent(
      BlockChainNetwork<ETHPoW.POWBlock, ETHMiner> network,
      NodeBuilder nb,
      int hashPowerGHs,
      ETHPoW.POWBlock genesis) {
    super(network, nb, hashPowerGHs, genesis);
  }
  public int privateHeight() {
    return privateMinerBlock == null ? 0 : privateMinerBlock.height;
  }

  @Override
  protected boolean sendMinedBlock(ETHPoW.POWBlock mined) {
    if(action <= 1 && action>=3){
      return true;
    }
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
  //Modified
  private void onFoundNewBlock(ETHPoW.POWBlock mined) {
    ETHPoW.POWBlock oldHead = head;
    inMining = null;

    if (sendMinedBlock(mined)) {
      sendBlock(mined);
    } else {
      minedToSend.add(mined);
    }
    if (!super.onBlock(mined)) {
      throw new IllegalStateException("invalid mined block:" + mined);
    }

    if (mined == head) {
      onNewHead(oldHead, mined);
    }
    onMinedBlock(mined);
  }
  /** Helper function: send a mined block. */
  protected void sendBlock(ETHPoW.POWBlock mined) {
    if (mined.producer != this) {
      throw new IllegalArgumentException(
              "logic error: you're not the producer of this block" + mined);
    }
    int sendTime = network.time + 1 + extraSendDelay(mined);
    if (sendTime < 1) {
      throw new IllegalArgumentException("extraSendDelay(" + mined + ") sent a negative time");
    }
    network.sendAll(new BlockChainNetwork.SendBlock<>(mined), sendTime, this);
    minedToSend.remove(mined);
  }

  /** Helper function: send all the blocks mined not yet sent. */
  protected void sendAllMined() {
    List<ETHPoW.POWBlock> all = new ArrayList<>(minedToSend);
    minedToSend.clear();
    for (ETHPoW.POWBlock b : all) {
      sendMinedBlock(b);
    }
  }
  public static class ETHPowWithAgent extends ETHPoW {

    public ETHPowWithAgent(ETHPoWParameters params) {
      super(params);
    }

    public void goNextStep() {
      while (network.rd.nextBoolean()) {
        network.runMs(10);
      }
    }

    public ETHMinerAgent getByzNode() {
      return (ETHMinerAgent) network.allNodes.get(1);
    }
  }

  @Override
  public final boolean onBlock(ETHPoW.POWBlock b) {
    ETHPoW.POWBlock oldHead = head;
    if (!super.onBlock(b)) {
      return false;
    }

    if (b == head) {
      onNewHead(oldHead, b);
      // Someone sent us a new head, so we're going to switch
      //  our mining to it
      if (switchMining(b)) {
        inMining = null;
      }
    } else if (inMining != null) {
      // May be 'b' is not better than our current head but we
      //  can still use it as an uncle for the block we're mining?
      if (inMining.isPossibleUncle(b)) {
        if (switchMining(b)) {
          inMining = null;
        }
      }
    }

    onReceivedBlock(b);
    return true;
  }

  /** Called when the head changes. */
  protected void onNewHead(ETHPoW.POWBlock oldHead, ETHPoW.POWBlock newHead) {}

  public static ETHPowWithAgent create(double byzHashPowerShare) {
    final String bdlName = RegistryNodeBuilders.name(RegistryNodeBuilders.Location.RANDOM, true, 0);
    final String nlName = RegistryNetworkLatencies.name(RegistryNetworkLatencies.Type.FIXED, 1000);

    ETHPoW.ETHPoWParameters params =
        new ETHPoW.ETHPoWParameters(
            bdlName, nlName, 10, ETHMinerAgent.class.getName(), byzHashPowerShare);

    return new ETHPowWithAgent(params);
  }

  public static void main(String... args) {
    create(0.25);
  }
}
