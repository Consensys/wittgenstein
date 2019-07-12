package net.consensys.wittgenstein.protocols;

import net.consensys.wittgenstein.core.NetworkLatency;
import org.junit.Assert;
import org.junit.Test;

public class CasperByzantineTest {
  private final CasperIMD ci =
      new CasperIMD(new CasperIMD.CasperParemeters(1, false, 2, 2, 1000, 1, null, null));

  @Test
  public void testByzantineWF() {
    ci.network.networkLatency = new NetworkLatency.NetworkNoLatency();

    CasperIMD.ByzBlockProducerWF byz = ci.new ByzBlockProducerWF(0, ci.genesis);
    ci.init(byz);

    ci.network.run(9);
    Assert.assertEquals(ci.genesis, ci.network.observer.head);

    ci.network.run(1); // 10 seconds: 8 for start + 1 for build time + 1 for network delay
    Assert.assertNotEquals(ci.genesis, ci.network.observer.head);
    Assert.assertEquals(1, ci.network.observer.head.height);
    Assert.assertEquals(byz, ci.network.observer.head.producer);

    ci.network.run(8); // 18s : 16s to start + build time + network delay
    Assert.assertNotEquals(ci.genesis, ci.network.observer.head);
    Assert.assertEquals(2, ci.network.observer.head.height);
    Assert.assertNotEquals(byz, ci.network.observer.head.producer);

    ci.network.run(8); // 26s :  24 (because of the delay) + build  +  network
    Assert.assertNotEquals(ci.genesis, ci.network.observer.head);
    Assert.assertEquals(3, ci.network.observer.head.height);
    Assert.assertEquals(byz, ci.network.observer.head.producer);
  }

  @Test
  public void testByzantineWFWithDelay() {
    ci.network.networkLatency = new NetworkLatency.NetworkNoLatency();

    CasperIMD.ByzBlockProducerWF byz = ci.new ByzBlockProducerWF(-2000, ci.genesis);
    ci.init(byz);

    ci.network.run(5);
    Assert.assertEquals(0, byz.head.height);

    ci.network.run(1);
    Assert.assertEquals(1, byz.head.height);
    Assert.assertEquals(0, ci.network.observer.head.height);

    ci.network.run(2); // The observer will wait for the right time, hence 8 seconds
    Assert.assertEquals(1, ci.network.observer.head.height);

    ci.network.run(9); // 18s : 16s to start + build time + network delay
    Assert.assertEquals(1, ci.network.observer.head.height);
    ci.network.run(1); // 18s : 16s to start + build time + network delay
    Assert.assertEquals(2, byz.head.height);
    assert byz.head.producer != null;
    Assert.assertNotEquals(byz, byz.head.producer);

    ci.network.run(3);
    Assert.assertEquals(2, byz.head.height);
    ci.network.run(1); // 22s: 24 -2 seconds delay
    Assert.assertEquals(3, byz.head.height);
  }
}
