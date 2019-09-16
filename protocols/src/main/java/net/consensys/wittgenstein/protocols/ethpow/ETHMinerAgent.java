package net.consensys.wittgenstein.protocols.ethpow;

import java.util.Collections;
import java.util.Comparator;
import net.consensys.wittgenstein.core.BlockChainNetwork;
import net.consensys.wittgenstein.core.NodeBuilder;
import net.consensys.wittgenstein.core.RegistryNetworkLatencies;
import net.consensys.wittgenstein.core.RegistryNodeBuilders;

/**
 * To call this agent from python: 1) Install the right tools:
 *
 * <pre>{@code
 * sudo pip3 install Cython
 * sudo pip3 install pyjnius
 * }</pre>
 *
 * <p>2) Build the package
 *
 * <pre>{@code
 * gradle clean shadowJar
 * }</pre>
 *
 * <p>3) You can now use this code from python, for example with:
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
  boolean actionNeeded = false;

  public ETHMinerAgent(
      BlockChainNetwork<ETHPoW.POWBlock, ETHMiner> network,
      NodeBuilder nb,
      int hashPowerGHs,
      ETHPoW.POWBlock genesis) {
    super(network, nb, hashPowerGHs, genesis);
  }

  @Override
  protected boolean sendMinedBlock(ETHPoW.POWBlock mined) {
    return false;
  }

  public void sendMinedBlocks(int howMany) {
    if (!actionNeeded) {
      throw new IllegalStateException("no action needed");
    }

    while (howMany-- > 0 && !minedToSend.isEmpty()) {
      actionSendOldestBlockMined();
    }
  }

  public boolean goNextStep() {
    actionNeeded = false;
    while (!actionNeeded) {
      network.runMs(1);
    }
    return true;
  }

  public int getSecretBlockSize() {
    return minedToSend.size();
  }

  public int getAdvance() {
    if (minedToSend.isEmpty()) {
      return 0;
    }

    ETHPoW.POWBlock privHead =
        Collections.max(minedToSend, Comparator.comparingInt(o -> o.proposalTime));

    int diff = privHead.height - head.height;
    return Math.max(diff, 0);
  }

  public double getReward() {
    ETHPoW.POWBlock best = head;
    ETHPoW.POWBlock privHead =
        minedToSend.isEmpty()
            ? null
            : Collections.max(minedToSend, Comparator.comparingInt(o -> o.proposalTime));
    if (privHead != null && privHead.height > head.height) {
      best = privHead;
    }

    return best.allRewards().getOrDefault(this, 0.0);
  }

  public static class ETHPowWithAgent extends ETHPoW {

    public ETHPowWithAgent(ETHPoWParameters params) {
      super(params);
    }

    public long getTimeInSeconds() {
      return this.network().time / 1000;
    }

    public ETHMinerAgent getByzNode() {
      return (ETHMinerAgent) network.allNodes.get(1);
    }
  }

  /** Called when the head changes. */
  protected void onNewHead(ETHPoW.POWBlock oldHead, ETHPoW.POWBlock newHead) {
    actionNeeded = true;
  }

  private void actionSendOldestBlockMined() {
    ETHPoW.POWBlock oldest =
        Collections.min(minedToSend, Comparator.comparingInt(o -> o.proposalTime));
    sendBlock(oldest);
  }

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
