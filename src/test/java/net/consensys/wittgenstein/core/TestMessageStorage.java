package net.consensys.wittgenstein.core;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class TestMessageStorage {
  private Network<Node> network = new Network<>();
  private Node.NodeBuilder nb = new Node.NodeBuilder();
  private Node n0 = new Node(nb);
  private Node n1 = new Node(nb);
  private Node n2 = new Node(nb);
  private Node n3 = new Node(nb);

  private Network.MessageContent<Node> dummy = new Network.MessageContent<Node>() {
    @Override
    public void action(Node from, Node to) {}
  };

  @Before
  public void before() {
    network.addNode(n0);
    network.addNode(n1);
    network.addNode(n2);
    network.addNode(n3);
  }

  @Test
  public void testWorkflow() {
    Message<Node> m1 = new Message.SingleDestMessage<>(dummy, n0, n1, 1);
    Message<Node> m2 = new Message.SingleDestMessage<>(dummy, n0, n1, 1);

    network.msgs.addMsg(m1);
    network.msgs.addMsg(m2);

    Assert.assertNull(network.msgs.peek(2));
    Assert.assertEquals(m2, network.msgs.peek(1));
    Assert.assertEquals(m2, network.msgs.poll(1));
    Assert.assertEquals(m1, network.msgs.poll(1));
    Assert.assertNull(network.msgs.peek(1));

    Message<Node> m3 = new Message.SingleDestMessage<>(dummy, n0, n1, Network.duration + 1);
    network.msgs.addMsg(m3);
    Assert.assertEquals(2, network.msgs.msgsBySlot.size());

    network.time = Network.duration + 1;
    network.msgs.addMsg(m3);
    Assert.assertEquals(1, network.msgs.msgsBySlot.size());

    network.msgs.clear();
    network.run(1);
  }

  @Test
  public void testAction() {
    AtomicBoolean ab = new AtomicBoolean(false);
    Network.MessageContent<Node> act = new Network.MessageContent<Node>() {
      @Override
      public void action(Node from, Node to) {
        ab.set(true);
      }
    };

    Message<Node> m = new Message.SingleDestMessage<>(act, n0, n1, 7 * 1000 + 1);
    network.msgs.addMsg(m);
    network.run(7);
    Assert.assertFalse(ab.get());

    network.run(1);
    Assert.assertTrue(ab.get());

    ab.set(false);
    network.msgs.addMsg(new Message.SingleDestMessage<>(act, n0, n1, 8 * 1000));
    network.run(1);
    Assert.assertTrue(ab.get());
  }


  @Test
  public void testMsgArrival() {
    AtomicLong ab = new AtomicLong(0);
    Network.MessageContent<Node> act = new Network.MessageContent<Node>() {
      @Override
      public void action(Node from, Node to) {
        ab.set(network.time);
      }
    };

    Message<Node> m = new Message.SingleDestMessage<>(act, n0, n1, 5);

    network.msgs.addMsg(m);
    network.run(1);

    Assert.assertEquals(5, ab.get());
    Assert.assertEquals(0, network.msgs.size());
  }

  @Test
  public void testEdgeCase1() {
    Assert.assertNull(network.msgs.peek(0));
    Assert.assertNull(network.msgs.peek(10 * 60 * 1000 + 1));
    Message<Node> m1 = new Message.SingleDestMessage<>(dummy, n0, n1, 10 * 60 * 1000 + 1);
    network.msgs.addMsg(m1);
    Assert.assertNotNull(network.msgs.peek(10 * 60 * 1000 + 1));
  }

  @Test
  public void testEdgeCase2() {
    Assert.assertNull(network.msgs.peek(Network.duration));
    Message<Node> m1 = new Message.SingleDestMessage<>(dummy, n0, n1, Network.duration);
    network.msgs.addMsg(m1);
    Assert.assertNotNull(network.msgs.peek(Network.duration));
    Assert.assertEquals(2, network.msgs.msgsBySlot.size());
  }
}
