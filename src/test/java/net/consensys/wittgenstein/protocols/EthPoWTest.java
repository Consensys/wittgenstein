package net.consensys.wittgenstein.protocols;

import net.consensys.wittgenstein.core.BlockChainNetwork;
import net.consensys.wittgenstein.core.NetworkLatency;
import net.consensys.wittgenstein.core.NodeBuilder;
import net.consensys.wittgenstein.core.RegistryNodeBuilders;
import org.junit.Assert;
import org.junit.Test;
import java.util.Random;

public class EthPoWTest {
  private ETHPoW.POWBlock gen = ETHPoW.POWBlock.createGenesis();
  private String nlName = NetworkLatency.NetworkLatencyByDistance.class.getSimpleName();
  private String builderName =
      RegistryNodeBuilders.name(RegistryNodeBuilders.Location.RANDOM, true, 0);
  private ETHPoW ep = new ETHPoW(new ETHPoW.ETHPoWParameters(0, 0, 0, builderName, nlName, 1));
  private Random rd = new Random();
  private NodeBuilder nb = new NodeBuilder.NodeBuilderWithRandomPosition();
  private ETHPoW.ETHMiningNode m1 = ep.new ETHMiningNode(rd, nb, 1024, gen);

  @Test
  public void testDifficulty() {
    ETHPoW.POWBlock b1 = gen;
    ETHPoW.POWBlock b2 = new ETHPoW.POWBlock(null, b1, b1.proposalTime + 13000);
    Assert.assertEquals(1949482177664138L, b2.difficulty);

    ETHPoW.POWBlock b3 = new ETHPoW.POWBlock(null, b2, b2.proposalTime + 7000);
    Assert.assertEquals(1950434207476428L, b3.difficulty);

    ETHPoW.POWBlock b4 = new ETHPoW.POWBlock(null, b3, b3.proposalTime + 4000);
    Assert.assertEquals(1951386702147025L, b4.difficulty);

    ETHPoW.POWBlock b5 = new ETHPoW.POWBlock(null, b4, b4.proposalTime + 39000);
    Assert.assertEquals(1948528359750282L, b5.difficulty);
  }

  @Test
  public void testFindHash() {
    double p = m1.solveByMs(1);
    Assert.assertEquals(1, p, 0.00001);

    p = m1.solveByMs(gen.difficulty);
    Assert.assertEquals(6.103513762178991E-7, p, 0.00001);
  }

  @Test
  public void testBlockDuration() {
    ETHPoW.ETHMiningNode m = ep.new ETHMiningNode(rd, nb, 150 * 1024, gen);
    ETHPoW.POWBlock cur = gen;
    double curProba = m.solveByMs(cur.difficulty);
    int tot = 0;
    for (int t = gen.proposalTime; cur.height - gen.height < 10000; t++) {
      if (rd.nextDouble() < curProba) {
        int foundIn = t / 1000 - cur.proposalTime / 1000;
        tot += foundIn;
        //System.out.println("Found a new block at time " + (t/1000)+" in "+foundIn+"s, difficulty was " + cur.difficulty);
        cur = new ETHPoW.POWBlock(m, cur, t);
        curProba = m.solveByMs(cur.difficulty);
      }
    }
    double found = cur.height - gen.height;
    if (found > 0) {
      System.out.println("Found " + (int) found + " blocks, avg time=" + (tot / found) + "s");
      Assert.assertEquals(13.0, (tot / found), 0.5); // todo: does it makes sense?
    }
  }

  @Test
  public void test2Miners() {
    ETHPoW.ETHPoWParameters params = new ETHPoW.ETHPoWParameters(0, 0, 0, builderName, nlName, 5);
    ETHPoW p = new ETHPoW(params);
    p.init();
    p.network().run(100000);
    p.network().printStat(false);
  }

  @Test
  public void testUncles(){
    ETHPoW.ETHPoWParameters params = new ETHPoW.ETHPoWParameters(0, 0, 0, builderName, nlName, 5);
    ETHPoW p = new ETHPoW(params);
    p.init();
    p.network().run(50000);
    ETHPoW.ETHMiningNode m = p.new ETHMiningNode(rd, nb, 150 * 1024, gen);
    //ETHMiningNode ethMiner, POWBlock father, int time
    int timestamp = p.network().time+50;
    ETHPoW.POWBlock brother =p.network.observer.blocksReceivedByHeight.get(gen.height+1);
    //ETHPoW.POWBlock brother = p.network.allNodes.get(0).blocksReceivedByHeight.get(m.head.height-2);
    ETHPoW.POWBlock uncle = new ETHPoW.POWBlock(m,brother,timestamp);
    p.network.sendAll(new BlockChainNetwork.SendBlock<>(uncle), m);
   // p.network().run(1000);
    Assert.assertTrue(p.network.allNodes.get(1).blocksReceivedByHeight.containsKey(uncle.height));

  }
}
