package net.consensys.wittgenstein.core;


import org.jetbrains.annotations.NotNull;

import java.util.*;

@SuppressWarnings({"WeakerAccess", "SameParameterValue", "FieldCanBeLocal", "unused"})
public class Network {
    private final PriorityQueue<Message> msgs = new PriorityQueue<>();
    private final HashSet<Integer> partition = new HashSet<>();
    private final Set<Node> allNodes = new HashSet<>();

    public final Random rd = new Random(0);

    // Distribution taken from: https://ethstats.net/
    private final int[] distribProp = {16, 18, 17, 12, 8, 5, 4, 3, 3, 1, 1, 2, 1, 1, 8};
    public final long[] distribVal = {250, 500, 1000, 1250, 1500, 1750, 2000, 2250, 2500, 2750, 4500, 6000, 8500, 9750, 10000};
    private final long[] longDistrib = new long[100];


    public long time = 0;


    /**
     * The node we use as an observer for the final stats
     */
    public final @NotNull Node observer;

    /**
     * The generic message that goes on a network. Triggers an 'action' on reception.
     */
    public abstract static class MessageContent {
        public abstract void action(@NotNull Node from, @NotNull Node to);
    }

    /**
     * Some protocols want some tasks to be executed at a given time
     */
    public class Task extends MessageContent {
        final @NotNull Runnable r;

        public Task(@NotNull Runnable r) {
            this.r = r;
        }

        @Override
        public void action(@NotNull Node from, @NotNull Node to) {
            r.run();
        }
    }

    /**
     * Some protocols want some tasks to be executed periodically
     */
    public class PeriodicTask extends Task {
        final long period;
        final Node sender;

        public PeriodicTask(@NotNull Runnable r, @NotNull Node fromNode, long period) {
            super(r);
            this.period = period;
            this.sender = fromNode;
        }

        @Override
        public void action(@NotNull Node from, @NotNull Node to) {
            r.run();
            msgs.add(new Message(this, sender, sender, time + period));
        }
    }

    public static class SendBlock extends MessageContent {
        final @NotNull Block toSend;

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


    public Network(@NotNull Node observer) {
        this.observer = observer;
        allNodes.add(observer);
        setNetworkLatency(distribProp, distribVal);
    }

    public void setNetworkLatency(@NotNull int[] dP, @NotNull long[] dV) {
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
            System.out.println(s + " second" + (s > 1 ? "s: " : ": ") + size + "%, cumulative=" + cur + "%");
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

    public void send(@NotNull MessageContent m, long sendTime, @NotNull Node fromNode, @NotNull Set<? extends Node> dests) {
        if (sendTime <= time) {
            throw new IllegalStateException();
        }
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
        Task sw = new Task(task);
        msgs.add(new Message(sw, fromNode, fromNode, executionTime));
    }

    public void registerPeriodicTask(@NotNull final Runnable task, long executionTime, long period, @NotNull Node fromNode) {
        PeriodicTask sw = new PeriodicTask(task, fromNode, period);
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


    public void partition(float part, @NotNull List<Set<? extends Node>> nodesPerType) {
        for (Set<? extends Node> ln : nodesPerType) {
            Iterator<? extends Node> it = ln.iterator();
            for (int i = 0; i < (ln.size() * part); i++) {
                partition.add(it.next().nodeId);
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

    public void addNode(@NotNull Node node) {
        allNodes.add(node);
    }

    public static class Message implements Comparable<Message> {
        final @NotNull MessageContent messageContent;
        final @NotNull Node fromNode;
        final @NotNull Node toNode;
        final long arrivalTime;

        public Message(@NotNull MessageContent messageContent, @NotNull Node fromNode, @NotNull Node toNode, long arrivalTime) {
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

    public void run(long seconds) {
        long endAt = time + seconds * 1000;
        receiveUntil(endAt);
        time = endAt;
    }


    public void printStat(boolean small) {
        HashMap<Integer, Set<Block>> productionCount = new HashMap<>();
        Set<Node> blockProducers = new HashSet<>();

        Block cur = observer.head;
        int blocksCreated = 0;
        while (cur != observer.genesis) {
            if (!small) System.out.println("block: " + cur.toString());
            blocksCreated++;

            productionCount.putIfAbsent(cur.producer.nodeId, new HashSet<>());
            productionCount.get(cur.producer.nodeId).add(cur);
            blockProducers.add(cur.producer);

            cur = cur.parent;
        }

        if (small) {
            //System.out.println("node; block count; tx count; msg sent; msg received");
        } else {
            System.out.println("block count:" + blocksCreated + ", all tx: " + observer.head.lastTxId);
        }
        List<Node> bps = new ArrayList<>(blockProducers);
        bps.sort(Comparator.comparingInt(o -> o.nodeId));

        for (Node bp:bps) {
            int bpTx = 0;
            for (Block b : productionCount.get(bp.nodeId)) bpTx += b.txCount();
            if (small) {
                if (bp.byzantine)
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
