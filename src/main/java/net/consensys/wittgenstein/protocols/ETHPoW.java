package net.consensys.wittgenstein.protocols;

import net.consensys.wittgenstein.core.*;
import net.consensys.wittgenstein.core.messages.Message;
import net.consensys.wittgenstein.server.WParameters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public class ETHPoW implements Protocol {
  final BlockChainNetwork<POWBlock, ETHMiningNode> network;
  final NodeBuilder nb;
  final POWBlock genesis = new POWBlock();
  ETHPoWParameters params;

  public static class ETHPoWParameters extends WParameters {
    private int hashRate;
    private int blockConstructionTime;
    private int gasLimit; //Equates to block size
    private int blockReward;
    private String nodeBuilderName;
    private String networkLatencyName;
    private int numberOfMiners;


  }

  ETHPoW(ETHPoWParameters params) {
    this.network = new BlockChainNetwork<>();
    // Change Singleton to IC3
    this.nb = RegistryNodeBuilders.singleton.getByName(params.nodeBuilderName);
    this.network
        .setNetworkLatency(RegistryNetworkLatencies.singleton.getByName(params.networkLatencyName));
  }

  //Block with transactions
  //Multiple blocks can exists at
  class BlockSent extends Message<ETHPoWNode> {
    int minerId;
    int blockHeight;
    int currentHeight;
    List<Transactions> transactionPool;

    @Override
    public void action(Network<ETHPoWNode> network, ETHPoWNode from, ETHPoWNode to) {

    }
  }

  @Override
  public BlockChainNetwork<POWBlock, ETHMiningNode> network() {
    return network;
  }

  @Override
  public Protocol copy() {
    return new ETHPoW(params);
  }

  @Override
  public void init() {
    for (int i = 0; i < 65; i++) {
      ETHMiningNode ethN = new ETHMiningNode(network.rd, nb, 4, this.genesis);
      network.addNode(new ETHMiningNode(network.rd, nb, 4, this.genesis));
    }
  }

  class POWBlock extends Block<POWBlock> {
    int minerId;
    int blockHeight;
    int currentHeight;
    List<Transactions> transInBlock;

    POWBlock(List<Transactions> transInBlock, ETHMiningNode ethMiner, int height, POWBlock father,
        int time) {
      super(ethMiner, height, father, true, time);
      this.transInBlock = transInBlock;
      this.minerId = ethMiner.nodeId;
      this.blockHeight = height;
      this.currentHeight = father.blockHeight;


    }

    POWBlock() {
      this.transInBlock = Collections.emptyList();
    }
  }

  public class Transactions {
    int gasLimit;
    String type;
    int gastCost = 0;
    boolean inABlock = false;

    Transactions(int gasLimit, String type) {
      this.gasLimit = gasLimit;
      this.type = type;
    }

    public void setGasCost(int gasSpent) {
      this.gastCost = gasSpent;
    }
  }

  abstract class ETHPoWNode extends BlockChainNode<POWBlock> {

    public ETHPoWNode(Random rd, NodeBuilder nb, POWBlock genesis) {
      super(rd, nb, false, genesis);

    }

    void createTransaction() {
      //TODO: Create transactions with different types, gas cost and limit to be added to pool at different rates
    }
  }

  protected Runnable periodicTask() {
    return null;
  }

  public boolean onBlock(final POWBlock p) {
    //Compare Ommers to select 
    return false;
  }

  private void setHashPower() {
    List<Double> hashDistribution = new ArrayList<>();
    double[] miningPools =
        {0.26, 0.23, .12, .115, .0575, .024, .0185, .0161, 0.013, .013, .0129, .012};
    double remaining = 1.0;
    for (int i = 0; i < this.params.numberOfMiners; i++) {
      if (i < miningPools.length) {
        hashDistribution.add(miningPools[i]);
        remaining -= miningPools[i];
      } else {
        hashDistribution.add(remaining / (params.numberOfMiners - miningPools.length));
      }

    }

  }

  class ETHMiningNode extends ETHPoWNode {
    private int hashPower;

    public ETHMiningNode(Random rd, NodeBuilder nb, int hashPower, POWBlock genesis) {
      super(rd, nb, genesis);
      this.hashPower = hashPower;
    }

    POWBlock createNewBlock(POWBlock base, int height) {
      //TODO: set ommers list
      //TODO: Select highest paying transactions from transaction pool and create new block
      return new POWBlock(null, this, height, base, network.time);

    }

    void sendNewBlock(int height) {
      head = createNewBlock(head, height);
      network.sendAll(new BlockChainNetwork.SendBlock<>(head), network.time, this);
    }

    //When competing blocks at height h (at most 3) are received select mainchain block using GHOST- Rule
    @Override
    public POWBlock best(POWBlock cur, POWBlock alt) {
      return null;
    }
  }



}
