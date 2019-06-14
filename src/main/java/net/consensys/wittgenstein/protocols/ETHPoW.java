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
  final ETHPoWParameters params;

  public static class ETHPoWParameters extends WParameters {
    private int hashRate;
    private int gasLimit;
    private int blockReward;
    private String nodeBuilderName;
    private String networkLatencyName;
    private int numberOfMiners;
  }

  public ETHPoW(ETHPoWParameters params) {
    this.params = params;
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
      network.addNode(new ETHMiningNode(network.rd, nb, 4, this.genesis));
    }
  }

  static class POWBlock extends Block<POWBlock> {
    final long difficulty;
    final List<Transactions> transInBlock;

    POWBlock(List<Transactions> transInBlock, ETHMiningNode ethMiner, POWBlock father, int time) {
      super(ethMiner, father.height + 1, father, true, time);
      this.transInBlock = transInBlock;
      this.difficulty = calculateDifficulty(father, time);
    }

    POWBlock() {
      super(null, 7951081, null, true, 1);
      this.transInBlock = Collections.emptyList();
      this.difficulty = 1949482043446410L;
    }

    static public POWBlock createGenesis() {
      return new POWBlock();
    }

    public static long calculateDifficulty(POWBlock father, int ts) {
      long gap = (ts - father.proposalTime) / 9;
      long y = 1;
      long ugap = Math.max(-99, y - gap);
      long nd = (father.difficulty / 2048) * ugap;
      long bomb = 0; // 2**((block.number // 100000) - 2)

      nd += father.difficulty;
      nd += bomb;

      return nd;
    }

  }

  static class Transactions {
    int gasLimit;
    String type;
    int gastCost = 0;

    Transactions(int gasLimit, String type) {
      this.gasLimit = gasLimit;
      this.type = type;
    }

    public void setGasCost(int gasSpent) {
      this.gastCost = gasSpent;
    }
  }

  abstract class ETHPoWNode extends BlockChainNode<POWBlock> {

    ETHPoWNode(Random rd, NodeBuilder nb, POWBlock genesis) {
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
      return new POWBlock(null, this, base, network.time);

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
