package net.consensys.wittgenstein.core;

import org.jetbrains.annotations.NotNull;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class TestNetwork {
    private Network<Node> network = new Network<>();
    private Node.NodeBuilder nb = new Node.NodeBuilder();
    private Node n0 = new Node(nb);
    private Node n1 = new Node(nb);
    private Node n2 = new Node(nb);
    private Node n3 = new Node(nb);

    @Before
    public void before() {
        network.removeNetworkLatency();
        network.addNode(n0);
        network.addNode(n1);
        network.addNode(n2);
        network.addNode(n3);
    }

    @Test
    public void testSimpleMessage() {
        AtomicInteger a1 = new AtomicInteger(-1);
        AtomicInteger a2 = new AtomicInteger(-1);

        Network.MessageContent<Node> act = new Network.MessageContent<Node>() {
            @Override
            public void action(@NotNull Node from, @NotNull Node to) {
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

        Network.MessageContent<Node> act = new Network.MessageContent<Node>() {
            @Override
            public void action(@NotNull Node from, @NotNull Node to) {
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
        Network.MessageContent<Node> act = new Network.MessageContent<Node>() {
            @Override
            public void action(@NotNull Node from, @NotNull Node to) {
                ab.incrementAndGet();
            }
        };

        network.removeNetworkLatency();
        network.send(act, 1, n0, Arrays.asList(n1, n2, n3));

        network.runMs(2);
        Assert.assertEquals(3, ab.get());

        Assert.assertEquals(0, network.msgs.size());
    }

    @Test
    public void testSortedArrivals() {
        Network.MessageContent<Node> act = new Network.MessageContent<Node>() {
            @Override
            public void action(@NotNull Node from, @NotNull Node to) {
            }
        };

        network.send(act, 1, n0, Arrays.asList(n1, n2, n3));

        Message m = network.msgs.peekFirst();
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
        network.setNetworkLatency(Network.distribProp, Network.distribVal);
        Network.MessageContent<Node> act = new Network.MessageContent<Node>() {
            @Override
            public void action(@NotNull Node from, @NotNull Node to) {
            }
        };

        network.send(act, 1, n0, Arrays.asList(n1, n2, n3));
        Message m = network.msgs.pollFirst();
        Assert.assertNotNull(m);
        Assert.assertTrue(m instanceof Message.MultipleDestMessage);
        Message.MultipleDestMessage mm = (Message.MultipleDestMessage) m;


        List<Network.MessageArrival> mas = network.createMessageArrivals(act, 1, n0, Arrays.asList(n1, n2, n3), mm.randomSeed);

        for (Network.MessageArrival ma : mas) {
            Assert.assertEquals(ma.arrival, m.nextArrivalTime(network));
            m.markRead();
        }
    }

    @Test
    public void testPartition() {
        Network<Node> net = new Network<>();
        AtomicInteger ai = new AtomicInteger(0);
        Node.NodeBuilder nb = new Node.NodeBuilder() {
            int getX() {
                return ai.addAndGet(Node.MAX_X / 10);
            }
        };
        Node n0 = new Node(nb);
        Node n1 = new Node(nb);
        Node n2 = new Node(nb);
        Node n3 = new Node(nb);

        AtomicInteger ab = new AtomicInteger(0);
        Network.MessageContent<Node> act = new Network.MessageContent<Node>() {
            @Override
            public void action(@NotNull Node from, @NotNull Node to) {
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
}