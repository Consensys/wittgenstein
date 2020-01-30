package net.consensys.wittgenstein.core;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.consensys.wittgenstein.core.messages.Message;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class NetworkTest {
  private Network<Node> network = new Network<>();
  private NodeBuilder nb = new NodeBuilder();
  private Node n0 = new Node(network.rd, nb);
  private Node n1 = new Node(network.rd, nb);
  private Node n2 = new Node(network.rd, nb);
  private Node n3 = new Node(network.rd, nb);

  private Message<Node> m =
      new Message<>() {
        @Override
        public void action(Network<Node> network, Node from, Node to) {}
      };

  @Before
  public void before() {
    network.setNetworkLatency(new NetworkLatency.NetworkNoLatency());
    network.addNode(n0);
    network.addNode(n1);
    network.addNode(n2);
    network.addNode(n3);
  }

  @Test
  public void testSimpleMessage() {
    AtomicInteger a1 = new AtomicInteger(-1);
    AtomicInteger a2 = new AtomicInteger(-1);

    Message<Node> act =
        new Message<>() {
          @Override
          public void action(Network<Node> network, Node from, Node to) {
            a1.set(from.nodeId);
            a2.set(to.nodeId);
          }
        };

    network.send(act, 1, n1, n2);
    Assert.assertEquals(1, network.msgs.size());
    Assert.assertEquals(-1, a1.get());

    network.run(5);
    Assert.assertEquals(1, a1.get());
    Assert.assertEquals(2, a2.get());
  }

  @Test
  public void testRegisterTask() {
    AtomicBoolean ab = new AtomicBoolean();
    network.registerTask(() -> ab.set(true), 100, n0);

    network.runMs(99);
    Assert.assertFalse(ab.get());

    network.runMs(1);
    Assert.assertTrue(ab.get());
    Assert.assertEquals(0, network.msgs.size());
  }

  @Test
  public void testAllFavorsOfSend() {
    AtomicInteger a1 = new AtomicInteger(0);
    AtomicInteger a2 = new AtomicInteger(0);

    Message<Node> act =
        new Message<>() {
          @Override
          public void action(Network<Node> network, Node from, Node to) {
            a1.addAndGet(from.nodeId);
            a2.addAndGet(to.nodeId);
          }
        };
    List<Node> dests = new ArrayList<>();
    dests.add(n2);
    dests.add(n3);

    network.send(act, n1, n2);
    network.send(act, 1, n1, n2);
    network.send(act, 1, n1, dests);
    network.send(act, n1, dests);

    Assert.assertEquals(4, network.msgs.size());
    network.run(1);
    Assert.assertEquals(0, network.msgs.size());
    Assert.assertEquals(6, a1.get());
    Assert.assertEquals(14, a2.get());
  }

  @Test
  public void testMultipleMessage() {
    AtomicInteger ab = new AtomicInteger(0);
    Message<Node> act =
        new Message<>() {
          @Override
          public void action(Network<Node> network, Node from, Node to) {
            ab.incrementAndGet();
          }
        };

    network.networkLatency = new NetworkLatency.NetworkNoLatency();
    network.send(act, 1, n0, Arrays.asList(n1, n2, n3));

    network.runMs(2);
    Assert.assertEquals(3, ab.get());

    Assert.assertEquals(0, network.msgs.size());
  }

  @Test
  public void testMultipleMessageWithDelays() {
    AtomicInteger ab = new AtomicInteger(0);
    Message<Node> act =
        new Message<>() {
          @Override
          public void action(Network<Node> network, Node from, Node to) {
            ab.incrementAndGet();
          }
        };

    network.networkLatency = new NetworkLatency.NetworkNoLatency();
    network.send(act, 1, n0, Arrays.asList(n1, n2, n3), 10);

    network.runMs(2);
    Assert.assertEquals(1, ab.get());

    network.runMs(11);
    Assert.assertEquals(2, ab.get());

    network.runMs(11);
    Assert.assertEquals(3, ab.get());

    Assert.assertEquals(0, network.msgs.size());
    Assert.assertEquals(3, ab.get());
  }

  @Test
  public void testMultipleMessageWithDelaysAcrossSlots() {
    AtomicInteger ab = new AtomicInteger(0);
    Message<Node> act =
        new Message<>() {
          @Override
          public void action(Network<Node> network, Node from, Node to) {
            ab.incrementAndGet();
          }
        };

    network.networkLatency = new NetworkLatency.NetworkNoLatency();
    network.send(act, 59000, n0, Arrays.asList(n1, n2, n3), 55000);

    network.runMs(200000);
    Assert.assertEquals(0, network.msgs.size());
    Assert.assertEquals(3, ab.get());
  }

  @Test
  public void testMultipleMessageWithDelaysEndOfSlot() {
    AtomicInteger ab = new AtomicInteger(0);
    Message<Node> act =
        new Message<>() {
          @Override
          public void action(Network<Node> network, Node from, Node to) {
            ab.incrementAndGet();
          }
        };

    network.networkLatency = new NetworkLatency.NetworkNoLatency();
    network.send(act, 58998, n0, Arrays.asList(n1, n2, n3), 1000);

    Assert.assertEquals(1, network.msgs.size());
    network.runMs(59000);
    Assert.assertEquals(1, network.msgs.size());
    network.runMs(3000);
    Assert.assertEquals(0, network.msgs.size());
    Assert.assertEquals(3, ab.get());
  }

  @Test
  public void testMsgArrival() {
    List<Network.MessageArrival> mas =
        network.createMessageArrivals(m, 1, n0, List.of(n1, n2, n3), 1, 10);
    Assert.assertEquals(3, mas.size());
    Collections.sort(mas);
    Assert.assertEquals(2, mas.get(0).arrival);
    Assert.assertEquals(13, mas.get(1).arrival);
    Assert.assertEquals(24, mas.get(2).arrival);

    Envelope.MultipleDestWithDelayEnvelope<Node> e =
        new Envelope.MultipleDestWithDelayEnvelope<>(m, n0, mas, 1);
    Assert.assertEquals(2, e.nextArrivalTime(network));
    e.markRead();
    Assert.assertEquals(13, e.nextArrivalTime(network));
    e.markRead();
    Assert.assertEquals(24, e.nextArrivalTime(network));
    Assert.assertTrue(e.hasNextReader());
    e.markRead();
    Assert.assertFalse(e.hasNextReader());
  }

  @Test
  public void testMsgArrivalWithRandomNoDelay() {
    Network<Node> network = new Network<>();
    NodeBuilder nb = new NodeBuilder.NodeBuilderWithRandomPosition();
    Node n0 = new Node(network.rd, nb);
    Node n1 = new Node(network.rd, nb);
    Node n2 = new Node(network.rd, nb);
    Node n3 = new Node(network.rd, nb);
    network.setNetworkLatency(new NetworkLatency.NetworkLatencyByDistanceWJitter());
    network.addNode(n0);
    network.addNode(n1);
    network.addNode(n2);
    network.addNode(n3);

    network.networkLatency = new NetworkLatency.NetworkLatencyByDistanceWJitter();
    List<Network.MessageArrival> mas =
        network.createMessageArrivals(m, 1, n0, List.of(n1, n2, n3), 2, 0);
    Assert.assertEquals(3, mas.size());
    Collections.sort(mas);

    Envelope.MultipleDestEnvelope<Node> e = new Envelope.MultipleDestEnvelope<>(m, n0, mas, 1, 2);
    Assert.assertEquals(2, e.randomSeed);
    Assert.assertEquals(mas.get(0).arrival, e.nextArrivalTime(network));
    e.markRead();
    Assert.assertEquals(mas.get(1).arrival, e.nextArrivalTime(network));
    e.markRead();
    Assert.assertEquals(mas.get(2).arrival, e.nextArrivalTime(network));
    Assert.assertTrue(e.hasNextReader());
    e.markRead();
    Assert.assertFalse(e.hasNextReader());
  }

  @Test
  public void testMsgArrivalWithRandom() {
    Network<Node> network = new Network<>();
    NodeBuilder nb = new NodeBuilder.NodeBuilderWithRandomPosition();
    Node n0 = new Node(network.rd, nb);
    Node n1 = new Node(network.rd, nb);
    Node n2 = new Node(network.rd, nb);
    Node n3 = new Node(network.rd, nb);
    network.setNetworkLatency(new NetworkLatency.NetworkLatencyByDistanceWJitter());
    network.addNode(n0);
    network.addNode(n1);
    network.addNode(n2);
    network.addNode(n3);

    network.networkLatency = new NetworkLatency.NetworkLatencyByDistanceWJitter();
    List<Network.MessageArrival> mas =
        network.createMessageArrivals(m, 1, n0, List.of(n1, n2, n3), 1, 20);
    Assert.assertEquals(3, mas.size());
    Collections.sort(mas);

    Envelope.MultipleDestWithDelayEnvelope<Node> e =
        new Envelope.MultipleDestWithDelayEnvelope<>(m, n0, mas, 1);
    Assert.assertEquals(mas.get(0).arrival, e.nextArrivalTime(network));
    e.markRead();
    Assert.assertEquals(mas.get(1).arrival, e.nextArrivalTime(network));
    e.markRead();
    Assert.assertEquals(mas.get(2).arrival, e.nextArrivalTime(network));
    Assert.assertTrue(e.hasNextReader());
    e.markRead();
    Assert.assertFalse(e.hasNextReader());
  }

  @Test
  public void testStats() {
    network.send(m, n0, Arrays.asList(n1, n2, n3));
    network.send(m, n0, n1);
    network.runMs(2);

    Assert.assertEquals(0, n0.getMsgReceived());
    Assert.assertEquals(0, n0.getBytesReceived());
    Assert.assertEquals(4, n0.getMsgSent());
    Assert.assertEquals(4, n0.getBytesSent());

    Assert.assertEquals(2, n1.getMsgReceived());
    Assert.assertEquals(2, n1.getBytesReceived());
    Assert.assertEquals(0, n1.getMsgSent());
    Assert.assertEquals(0, n1.getBytesSent());

    Assert.assertEquals(1, n2.getMsgReceived());
    Assert.assertEquals(1, n2.getBytesReceived());
    Assert.assertEquals(0, n2.getMsgSent());
    Assert.assertEquals(0, n2.getBytesSent());

    Assert.assertEquals(1, n3.getMsgReceived());
    Assert.assertEquals(1, n3.getBytesReceived());
    Assert.assertEquals(0, n3.getMsgSent());
    Assert.assertEquals(0, n3.getBytesSent());
  }

  @Test
  public void testSortedArrivals() {
    network.send(m, 1, n0, Arrays.asList(n1, n2, n3));

    Envelope<?> m = network.msgs.peekFirst();
    Assert.assertNotNull(m);

    HashSet<Integer> dests = new HashSet<>(Arrays.asList(1, 2, 3));

    int l = m.nextArrivalTime(network);
    Assert.assertTrue(dests.contains(m.getNextDestId()));
    dests.remove(m.getNextDestId());

    m.markRead();
    Assert.assertTrue(m.hasNextReader());
    Assert.assertTrue(m.nextArrivalTime(network) >= l);
    Assert.assertTrue(dests.contains(m.getNextDestId()));
    dests.remove(m.getNextDestId());
    l = m.nextArrivalTime(network);

    m.markRead();
    Assert.assertTrue(m.hasNextReader());
    Assert.assertTrue(m.nextArrivalTime(network) >= l);
    Assert.assertTrue(dests.contains(m.getNextDestId()));

    m.markRead();
    Assert.assertFalse(m.hasNextReader());
  }

  @Test
  public void testDelays() {
    network.setNetworkLatency(new NetworkLatency.EthScanNetworkLatency());

    network.send(m, 1, n0, Arrays.asList(n1, n2, n3));
    Envelope<?> e = network.msgs.pollFirst();
    Assert.assertNotNull(e);
    Assert.assertTrue(e instanceof Envelope.MultipleDestEnvelope);
    Envelope.MultipleDestEnvelope mm = (Envelope.MultipleDestEnvelope) e;

    List<Network.MessageArrival> mas =
        network.createMessageArrivals(m, 1, n0, Arrays.asList(n1, n2, n3), mm.randomSeed, 0);

    for (Network.MessageArrival ma : mas) {
      Assert.assertEquals(ma.arrival, e.nextArrivalTime(network));
      e.markRead();
    }
  }

  @Test
  public void testPartition() {
    Network<Node> net = new Network<>();
    AtomicInteger ai = new AtomicInteger(0);
    NodeBuilder nb =
        new NodeBuilder() {
          @Override
          protected int getX(int rdi) {
            return ai.addAndGet(Node.MAX_X / 10);
          }
        };
    Node n0 = new Node(network.rd, nb);
    Node n1 = new Node(network.rd, nb);
    Node n2 = new Node(network.rd, nb);
    Node n3 = new Node(network.rd, nb);
    net.addNode(n0);
    net.addNode(n1);
    net.addNode(n2);
    net.addNode(n3);

    AtomicInteger ab = new AtomicInteger(0);
    Message<Node> act =
        new Message<>() {
          @Override
          public void action(Network<Node> network, Node from, Node to) {
            ab.incrementAndGet();
          }
        };

    net.partition(0.25f);
    int bound = (int) (0.25f * Node.MAX_X);
    Assert.assertTrue(net.partitionsInX.contains(bound));

    Assert.assertEquals(0, net.partitionId(n0));
    Assert.assertEquals(0, net.partitionId(n1));
    Assert.assertEquals(1, net.partitionId(n2));
    Assert.assertEquals(1, net.partitionId(n3));

    net.send(act, n0, n1);
    Assert.assertNotNull(net.msgs.peekFirst());
    net.msgs.clear();

    net.send(act, n1, n2);
    Assert.assertNull(net.msgs.peekFirst());

    net.send(act, n2, n3);
    Assert.assertNotNull(net.msgs.peekFirst());
    net.msgs.clear();

    // As above but with another partition
    net.partition(0.35f);
    int bound2 = (int) (0.35f * Node.MAX_X);
    Assert.assertTrue(net.partitionsInX.contains(bound));
    Assert.assertTrue(net.partitionsInX.contains(bound2));

    Assert.assertEquals(0, net.partitionId(n0));
    Assert.assertEquals(0, net.partitionId(n1));
    Assert.assertEquals(1, net.partitionId(n2));
    Assert.assertEquals(2, net.partitionId(n3));

    net.send(act, n0, n1);
    Assert.assertNotNull(net.msgs.peekFirst());
    net.msgs.clear();

    net.send(act, n1, n2);
    Assert.assertNull(net.msgs.peekFirst());

    net.send(act, n2, n3);
    Assert.assertNull(net.msgs.peekFirst());
    net.msgs.clear();

    net.send(act, n3, n0);
    Assert.assertNull(net.msgs.peekFirst());
    net.msgs.clear();
  }

  @Test
  public void testLongRunning() {
    Message<Node> act =
        new Message<>() {
          @Override
          public void action(Network<Node> network, Node from, Node to) {}
        };
    while (network.time < 100_000_000) {
      network.runMs(10_000);
      network.send(act, n0, n1);
    }
  }

  @Test
  public void testTask() {
    final AtomicInteger ai = new AtomicInteger(0);
    network.registerTask(ai::getAndIncrement, 1000, n0);

    network.runMs(500);
    Assert.assertEquals(0, ai.get());
    network.runMs(500);
    Assert.assertEquals(1, ai.get());
    network.runMs(100);
    Assert.assertEquals(1, ai.get());
    network.runMs(5000);
    Assert.assertEquals(1, ai.get());
  }

  @Test
  public void testTaskOnStoppedNode() {
    final AtomicInteger ai = new AtomicInteger(0);
    network.registerTask(ai::getAndIncrement, 1000, n0);

    n0.stop();
    network.runMs(5000);
    Assert.assertEquals(0, ai.get());
  }

  @Test
  public void testPeriodicTask() {
    final AtomicInteger ai = new AtomicInteger(0);
    network.registerPeriodicTask(ai::getAndIncrement, 1000, 100, n0);

    network.runMs(500);
    Assert.assertEquals(0, ai.get());
    network.runMs(500);
    Assert.assertEquals(1, ai.get());
    network.runMs(100);
    Assert.assertEquals(2, ai.get());
    network.runMs(50);
    Assert.assertEquals(2, ai.get());

    n0.stop();
    network.runMs(1000);
    Assert.assertEquals(2, ai.get());
  }

  @Test
  public void testConditionalTask() {
    final AtomicBoolean ab = new AtomicBoolean(false);
    final AtomicInteger ai = new AtomicInteger(0);
    network.registerConditionalTask(ai::getAndIncrement, 1000, 100, n0, ab::get, () -> true);

    network.runMs(500);
    Assert.assertEquals(0, ai.get());

    network.runMs(500);
    Assert.assertEquals(0, ai.get());

    ab.set(true);
    network.runMs(1);
    Assert.assertEquals(1, ai.get());

    network.runMs(99);
    Assert.assertEquals(1, ai.get());

    network.runMs(1);
    Assert.assertEquals(2, ai.get());

    n0.stop();
    network.runMs(1000);
    Assert.assertEquals(2, ai.get());
  }
}
