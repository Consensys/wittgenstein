package net.consensys.wittgenstein.core;


import org.jetbrains.annotations.NotNull;

import java.util.*;

@SuppressWarnings({"WeakerAccess", "SameParameterValue", "FieldCanBeLocal", "unused"})
public class Network {
    public final static int OBSERVER_NODE_ID = 0;
    public final static int BYZANTINE_NODE_ID = 1;

    private final PriorityQueue<Message> msgs = new PriorityQueue<>();
    private final HashSet<Integer> partition = new HashSet<>();
    private final ArrayList<Node> allNodes = new ArrayList<>();

    public final Random rd = new Random(0);

    // Distribution taken from: https://ethstats.net/
    private final int[] distribProp = {16, 18, 17, 12, 8, 5, 4, 3, 3, 1, 1, 2, 1, 1, 8};
    public final long[] distribVal = {250, 500, 1000, 1250, 1500, 1750, 2000, 2250, 2500, 2750, 4500, 6000, 8500, 9750, 10000};
    private final long[] longDistrib = new long[100];


    public long time = 0;


    /**
     * The node we use as an observer for the final stats
     */
    private final Node observer;

    public abstract static class MessageContent {
        public abstract void action(Node from, Node to);
    }


    public class StartWork extends MessageContent {
        final long startTime;

        public StartWork(long startTime) {
            this.startTime = startTime;
        }

        @Override
        public void action(Node from, Node to) {
            StartWork nextWork = to.work(startTime);
            if (nextWork != null) {
                if (nextWork.startTime <= startTime)
                    throw new IllegalArgumentException("startTime:" + nextWork.startTime + " must be greater than time:" + time);
                msgs.add(new Message(nextWork, from, from, nextWork.startTime));
            }
        }
    }


    public static class SendBlock extends MessageContent {
        @NotNull
        final Block toSend;

        public SendBlock(@NotNull Block toSend) {
            this.toSend = toSend;
        }

        @Override
        public void action(@NotNull Node fromNode, @NotNull Node toNode) {
            toNode.onBlock(toSend);
        }

        @Override
        public String
        toString() {
            return "SendBlock{" +
                    "toSend=" + toSend.id +
                    '}';
        }
    }


    public Network(Node observer) {
        if (observer.nodeId != OBSERVER_NODE_ID) throw new IllegalArgumentException();
        this.observer = observer;
        allNodes.add(observer);
        setNetworkLatency(distribProp, distribVal);
    }

    public void setNetworkLatency(int[] dP, long[] dV) {
        int li = 0;
        int cur = 0;
        int sum = 0;
        for (int i = 0; i < dP.length; i++) {
            sum += dP[i];
            long step = (dV[i] - cur) / dP[i];
            for (int ii = 0; ii < dP[i]; ii++) {
                cur += step;
                longDistrib[li++] = cur;
            }
        }

        if (sum != 100) throw new IllegalArgumentException();
        if (li != 100) throw new IllegalArgumentException();
    }

    /**
     * Set the network latency to a min value. This allows
     * to test the protocol independently of the network variability.
     */
    public void removeNetworkLatency() {
        for (int i = 0; i < 100; i++) longDistrib[i] = 1;
    }

    public void printNetworkLatency() {
        System.out.println("Network latency: time to receive a message:");

        for (int s = 1, cur = 0, sum = 0; cur < 100; s++) {
            int size = 0;
            while (cur < longDistrib.length && longDistrib[cur] < s * 1000) {
                size++;
                cur++;
            }
            System.out.println(s + " seconds:" + size + "%, cumulative=" + cur + "%");
        }
    }

    private long getNetworkDelay() {
        return longDistrib[rd.nextInt(longDistrib.length)];
    }

    /**
     * Send a message to all nodes.
     */
    public void sendAll(@NotNull MessageContent m, long sendTime, @NotNull Node fromNode) {
        send(m, sendTime, fromNode, allNodes);
    }

    public void send(@NotNull MessageContent m, long sendTime, @NotNull Node fromNode, @NotNull ArrayList<? extends Node> dests) {
        for (Node n : dests) {
            if (n != fromNode) {
                if ((partition.contains(fromNode.nodeId) && partition.contains(n.nodeId)) ||
                        (!partition.contains(fromNode.nodeId) && !partition.contains(n.nodeId))) {
                    fromNode.msgSent++;
                    n.msgReceived++;
                    msgs.add(new Message(m, fromNode, n, sendTime + getNetworkDelay()));
                }
            }
        }
    }

    public void registerTask(@NotNull final Runnable task, long executionTime, @NotNull Node fromNode) {
        StartWork sw = new StartWork(executionTime) {
            @Override
            public void action(Node from, Node to) {
                task.run();
            }
        };
        msgs.add(new Message(sw, fromNode, fromNode, executionTime));
    }

    void receiveUntil(long until) {
        //noinspection ConstantConditions
        while (!msgs.isEmpty() && msgs.peek().arrivalTime <= until) {
            Message m = msgs.poll();
            assert m != null;
            if (time > m.arrivalTime) {
                throw new IllegalStateException("time:" + time + ", m:" + m);
            }
            time = m.arrivalTime;
            //if (! (m.messageContent instanceof StartWork))  System.out.println(m.fromNode+ "->" + m.toNode+": " + m.messageContent);
            if ((partition.contains(m.fromNode.nodeId) && partition.contains(m.toNode.nodeId)) ||
                    (!partition.contains(m.fromNode.nodeId) && !partition.contains(m.toNode.nodeId))) {
                m.messageContent.action(m.fromNode, m.toNode);
            }
        }
    }


    public void partition(float part, List<List<? extends Node>> nodesPerType) {
        for (List<? extends Node> ln : nodesPerType) {
            for (int i = 0; i < (ln.size() * part); i++) {
                partition.add(ln.get(i).nodeId);
            }
        }
    }

    public void endPartition() {
        partition.clear();

        // On a p2p network all the blocks are exchanged all the time. We simulate this
        //  with a full resent after each partition.
        for (Node n : allNodes) {
            sendAll(new SendBlock(n.head), time + 1, n);
        }
    }

    public void addNode(Node node) {
        allNodes.add(node);
    }

    public static class Message implements Comparable<Message> {
        final MessageContent messageContent;
        @NotNull
        final Node fromNode;
        @NotNull
        final Node toNode;
        final long arrivalTime;

        public Message(MessageContent messageContent, @NotNull Node fromNode, @NotNull Node toNode, long arrivalTime) {
            this.messageContent = messageContent;
            this.fromNode = fromNode;
            this.toNode = toNode;
            this.arrivalTime = arrivalTime;
        }

        @Override
        public int compareTo(@NotNull Message o) {
            return Long.compare(arrivalTime, o.arrivalTime);
        }

        @Override
        public String toString() {
            return "Message{" +
                    "messageContent=" + messageContent +
                    ", fromNode=" + fromNode +
                    ", toNode=" + toNode +
                    ", arrivalTime=" + arrivalTime +
                    '}';
        }
    }

    private void init() {
        for (Node n : allNodes) {
            StartWork sw = n.firstWork();
            if (sw != null) {
                msgs.add(new Message(sw, n, n, sw.startTime));
            }
        }
    }

    public void run(long howLong) {
        if (time == 0) init();
        time++;

        long endAt = time + howLong * 1000;
        receiveUntil(endAt);
        time = endAt;
    }


    public void printStat(boolean small) {
        HashMap<Integer, HashSet<Block>> productionCount = new HashMap<>();

        Block cur = observer.head;
        int blocksCreated = 0;
        while (cur != observer.genesis) {
            if (!small) System.out.println("block: " + cur.toString());
            blocksCreated++;

            productionCount.putIfAbsent(cur.producer.nodeId, new HashSet<>());
            productionCount.get(cur.producer.nodeId).add(cur);

            cur = cur.parent;
        }

        if (small) {
            //System.out.println("node; block count; tx count; msg sent; msg received");
        } else {
            System.out.println("block count:" + blocksCreated + ", all tx: " + observer.head.lastTxId);
        }
        List<Integer> producerIds = new ArrayList<>(productionCount.keySet());
        Collections.sort(producerIds);
        for (Integer pId : producerIds) {
            Node bp = allNodes.get(pId);
            int bpTx = 0;
            for (Block b : productionCount.get(bp.nodeId)) bpTx += b.txCount();
            if (small) {
                if (bp.nodeId == BYZANTINE_NODE_ID)
                    System.out.println(
                            bp + "; " +
                                    productionCount.get(bp.nodeId).size() + "; " +
                                    bpTx + "; " +
                                    bp.msgSent + "; " +
                                    bp.msgReceived
                    );

            } else {
                System.out.println(bp + ": " + productionCount.get(bp.nodeId).size() +
                        ", tx count: " + bpTx +
                        ", msg count:");
            }
        }
    }

}
