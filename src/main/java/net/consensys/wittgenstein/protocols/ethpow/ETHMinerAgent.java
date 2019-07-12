package net.consensys.wittgenstein.protocols.ethpow;

import net.consensys.wittgenstein.core.BlockChainNetwork;
import net.consensys.wittgenstein.core.NodeBuilder;
import net.consensys.wittgenstein.core.RegistryNetworkLatencies;
import net.consensys.wittgenstein.core.RegistryNodeBuilders;



/*
 * <pre>{@code sudo pip3 install Cython sudo pip3 install pyjnius }</pre>
 * 
 * gradle clean shadowJar
 * 
 * import jnius_config jnius_config.set_classpath('.',
 * '/home/liochon/projets/wittgenstein/build/libs/wittgenstein-all.jar') from jnius import autoclass
 * p = autoclass('net.consensys.wittgenstein.protocols.ethpow.ETHMinerAgent').create(0.25) p.init()
 * p.goNextStep() p.network().printNetworkLatency() p.getByzNode().head.height
 */
public class ETHMinerAgent extends ETHMiner {


  public ETHMinerAgent(BlockChainNetwork<ETHPoW.POWBlock, ETHMiner> network, NodeBuilder nb,
      int hashPowerGHs, ETHPoW.POWBlock genesis) {
    super(network, nb, hashPowerGHs, genesis);
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

    ETHPoW.ETHPoWParameters params = new ETHPoW.ETHPoWParameters(bdlName, nlName, 10,
        ETHMinerAgent.class.getName(), byzHashPowerShare);

    return new ETHPowWithAgent(params);
  }
}