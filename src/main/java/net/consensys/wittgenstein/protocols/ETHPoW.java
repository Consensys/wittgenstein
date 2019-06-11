package net.consensys.wittgenstein.protocols;

import net.consensys.wittgenstein.core.*;
import net.consensys.wittgenstein.core.messages.Message;
import net.consensys.wittgenstein.server.WParameters;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public class ETHPoW implements Protocol {

  public static class ETHPoWParameters extends WParameters {
    private int hashRate;
    private int blockConstructionTime;
    private int gasLimit; //Equates to block size
    private int blockReward;

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
  public Network<BlockChainNode> network() {
    return null;
  }

  @Override
  public Protocol copy() {
    return null;
  }

  @Override
  public void init() {

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

  abstract class ETHMiningNode extends ETHPoWNode {
    private int hashPower;

    public ETHMiningNode(Random rd, NodeBuilder nb, int hashPower, POWBlock genesis) {
      super(rd, nb, genesis);
      this.hashPower = hashPower;
    }

    void createNewBlock() {
      //TODO: set ommers list
      //TODO: Select highest payign transactions from transaction pool and create new block

    }
  }


}
