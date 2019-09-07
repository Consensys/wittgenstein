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

  public ETHMinerAgent(
      BlockChainNetwork<ETHPoW.POWBlock, ETHMiner> network,
      NodeBuilder nb,
      int hashPowerGHs,
      ETHPoW.POWBlock genesis) {
    super(network, nb, hashPowerGHs, genesis);
  }

  protected boolean sendMinedBlock(ETHPoW.POWBlock mined) {
    return false; // C'est depuis Python qu'on décide d'envoyer un block ou non.
  }

  public int blocksToSend() {
    return minedToSend.size();
  }

  public void actionSendOldestBlockMined() {
    ETHPoW.POWBlock oldest =
        Collections.min(
            minedToSend,
            new Comparator<ETHPoW.POWBlock>() {
              @Override
              public int compare(ETHPoW.POWBlock o1, ETHPoW.POWBlock o2) {
                return Integer.compare(o1.proposalTime, o2.proposalTime);
              }
            });
    sendBlock(oldest);
  }

  public void actionSwitchToOfficialHead() {
    startNewMining(head);
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
