package net.consensys.wittgenstein.protocols;

import net.consensys.wittgenstein.core.BlockChainNetwork;
import net.consensys.wittgenstein.core.NetworkLatency;
import net.consensys.wittgenstein.core.NodeBuilder;
import net.consensys.wittgenstein.core.RegistryNodeBuilders;
import org.junit.Assert;
import org.junit.Test;
import java.util.Collections;
import java.util.Random;
import java.util.Set;

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

    ETHPoW.POWBlock b6 = new ETHPoW.POWBlock(null, b5, b5.proposalTime + 3000);
    Assert.assertEquals(1949479923831169L, b6.difficulty);

    ETHPoW.POWBlock b7 = new ETHPoW.POWBlock(null, b6, b6.proposalTime + 15000);
    Assert.assertEquals(1949480058048897L, b7.difficulty);

    /*
    ETHPoW.POWBlock u1 = new ETHPoW.POWBlock(null, b5, b5.proposalTime + 11000);
    ETHPoW.POWBlock b8 = new ETHPoW.POWBlock(null, b7, b7.proposalTime + 11000 , Collections.singleton(u1));
    Assert.assertEquals(1949480192266625L, b8.difficulty);
    
    ETHPoW.POWBlock b9 =
        new ETHPoW.POWBlock(null, b8, b8.proposalTime + 3000, Collections.singleton(u1));
    Assert.assertEquals(1951384115734613L, b9.difficulty);*/
  }

  @Test
  public void testInitialDifficulty() {
    // Difficulties & hashrate at block 7999565
    // We should get an avg block generation time of 13s
    ETHPoW.ETHMiningNode m = ep.new ETHMiningNode(rd, nb, 162 * 1024, gen);
    long avgD = 2031093808891300L + 2028116957207141L + 2032085740451229L;
    avgD += 2033078320257064L + 2032085956568356L + 2032085822350628L;
    avgD /= 6;
    double curProba = m.solveByMs(avgD);
    int found = 0;
    int time = 100_000_000;
    for (int t = 0; t < time; t++) {
      if (rd.nextDouble() < curProba) {
        found++;
      }
    }
    Assert.assertEquals(13.0, (time / (1000.0 * found)), 0.5);
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
    Assert.assertEquals(13.0, (tot / found), 0.5);
  }

  @Test
  public void test2Miners() {
    ETHPoW.ETHPoWParameters params = new ETHPoW.ETHPoWParameters(0, 0, 0, builderName, nlName, 5);
    ETHPoW p = new ETHPoW(params);
    p.init();
    p.network().run(30000);
    int c0 = p.network.getNodeById(0).head.height - gen.height;
    int c1 = p.network.getNodeById(1).head.height - gen.height;
    int diff = Math.abs(c0 - c1);
    int th = (c0 + c1) / 20;
    Assert.assertTrue(diff < th);
  }

  //Create a new block with same parent as another block and checks block is included in chain as uncle
  @Test
  public void testUncles() {
    ETHPoW.ETHPoWParameters params = new ETHPoW.ETHPoWParameters(0, 0, 0, builderName, nlName, 5);
    ETHPoW p = new ETHPoW(params);
    p.init();
    p.network().run(10000);
    ETHPoW.ETHMiningNode m = p.network.observer;
    int timestamp = p.network().time;
    Set<ETHPoW.POWBlock> main = p.network.observer.blocksReceivedByHeight.get(gen.height + 2);
    ETHPoW.POWBlock father = main.iterator().next().parent;
    //New block created with same father as existing block
    ETHPoW.POWBlock uncle = new ETHPoW.POWBlock(m, father, timestamp);

    p.network.sendAll(new BlockChainNetwork.SendBlock<>(uncle), m);
    p.network().run(1000);

    Assert.assertTrue(
        p.network.allNodes.get(1).blocksReceivedByHeight.get(uncle.height).contains(uncle));

  }
}
