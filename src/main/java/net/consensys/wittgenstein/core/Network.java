package net.consensys.wittgenstein.core;

import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

@SuppressWarnings("WeakerAccess")
public class Network<TN extends Node> {
    public final PriorityQueue<Message> msgs = new PriorityQueue<>();
    protected final Map<Integer, TN> allNodes = new HashMap<>();

    public final Random rd = new Random(0);
    public final AtomicInteger ids = new AtomicInteger();

    final HashSet<Integer> partition = new HashSet<>();
    long msgDiscardTime = Long.MAX_VALUE;

    // Distribution taken from: https://ethstats.net/
    private final int[] distribProp = {16, 18, 17, 12, 8, 5, 4, 3, 3, 1, 1, 2, 1, 1, 8};
    public final long[] distribVal = {250, 500, 1000, 1250, 1500, 1750, 2000, 2250, 2500, 2750, 4500, 6000, 8500, 9750, 10000};
    private final long[] longDistrib = new long[100];


    public long time = 0;


    public Network() {
        setNetworkLatency(distribProp, distribVal);
    }


    public TN getNodeById(int id) {
        return allNodes.get(id);
    }

    public Network<TN> setMsgDiscardTime(long l) {
        this.msgDiscardTime = l;
        return this;
    }


    /**
     * The generic message that goes on a network. Triggers an 'action' on reception.
     */
    public abstract static class MessageContent<TN extends Node> {
        public abstract void action(@NotNull TN from, @NotNull TN to);
    }

    /**
     * Some protocols want some tasks to be executed at a given time
     */
    public class Task<TN extends Node> extends MessageContent<TN> {
        final @NotNull Runnable r;

        public Task(@NotNull Runnable r) {
            this.r = r;
        }

        @Override
        public void action(@NotNull TN from, @NotNull TN to) {
            r.run();
        }
    }


    public interface Condition {
        boolean cont();
    }

    /**
     * Some protocols want some tasks to be executed periodically
     */
    public class PeriodicTask extends Task {
        final long period;
        final Node sender;
        final Condition continuationCondition;

        public PeriodicTask(@NotNull Runnable r, @NotNull Node fromNode, long period, @NotNull Condition condition) {
            super(r);
            this.period = period;
            this.sender = fromNode;
            this.continuationCondition = condition;
        }

        public PeriodicTask(@NotNull Runnable r, @NotNull Node fromNode, long period) {
            this(r, fromNode, period, () -> true);
        }


        @Override
        public void action(@NotNull Node from, @NotNull Node to) {
            r.run();
            if (continuationCondition.cont()) {
                msgs.add(new Message(this, sender, sender, time + period));
            }
        }
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


    /**
     * Send a message to all nodes.
     */
    public void sendAll(@NotNull MessageContent m, long sendTime, @NotNull TN fromNode) {
        send(m, sendTime, fromNode, allNodes.values());
    }

    public void send(@NotNull MessageContent m, @NotNull TN fromNode, @NotNull Collection<TN> dests) {
        send(m, time + 1, fromNode, dests);
    }

    public void send(@NotNull MessageContent m, long sendTime, @NotNull TN fromNode, @NotNull Collection<? extends Node> dests) {
        if (sendTime <= time) {
            throw new IllegalStateException("" + m + ", sendTime=" + sendTime + ", time=" + time);
        }
        for (Node n : dests) {
            if (n != fromNode) {
                if ((partition.contains(fromNode.nodeId) && partition.contains(n.nodeId)) ||
                        (!partition.contains(fromNode.nodeId) && !partition.contains(n.nodeId))) {
                    fromNode.msgSent++;
                    long nt = getNetworkDelay(fromNode, n);
                    if (nt < msgDiscardTime) {
                        msgs.add(new BlockChainNetwork.Message(m, fromNode, n, sendTime + nt));
                    }
                }
            }
        }
    }

    public void registerTask(@NotNull final Runnable task, long executionTime, @NotNull TN fromNode) {
        Task sw = new Task(task);
        msgs.add(new Message(sw, fromNode, fromNode, executionTime));
    }

    public void registerPeriodicTask(@NotNull final Runnable task, long executionTime, long period, @NotNull TN fromNode) {
        PeriodicTask sw = new PeriodicTask(task, fromNode, period);
        msgs.add(new Message(sw, fromNode, fromNode, executionTime));
    }

    public void registerPeriodicTask(@NotNull final Runnable task, long executionTime, long period, @NotNull TN fromNode, Condition c) {
        PeriodicTask sw = new PeriodicTask(task, fromNode, period, c);
        msgs.add(new Message(sw, fromNode, fromNode, executionTime));
    }


    long receiveUntil(long until) {
        //noinspection ConstantConditions
        while (!msgs.isEmpty() && msgs.peek().arrivalTime <= until) {
            Message m = msgs.poll();
            assert m != null;
            if (time > m.arrivalTime) {
                throw new IllegalStateException("time:" + time + ", m:" + m);
            }
            time = m.arrivalTime;
            if ((partition.contains(m.fromNode.nodeId) && partition.contains(m.toNode.nodeId)) ||
                    (!partition.contains(m.fromNode.nodeId) && !partition.contains(m.toNode.nodeId))) {
                if (!(m.messageContent instanceof Task)) m.toNode.msgReceived++;
                m.messageContent.action(m.fromNode, m.toNode);
            }
        }
        return time;
    }


    public void addNode(@NotNull TN node) {
        allNodes.put(node.nodeId, node);
    }


    /**
     * Set the network latency to a min value. This allows
     * to test the protocol independently of the network variability.
     */
    public @NotNull Network<TN> removeNetworkLatency() {
        for (int i = 0; i < 100; i++) {
            longDistrib[i] = 1;
        }
        return this;
    }


    public void printNetworkLatency() {
        System.out.println("BlockChainNetwork latency: time to receive a message:");

        for (int s = 1, cur = 0; cur < 100; s++) {
            int size = 0;
            while (cur < longDistrib.length && longDistrib[cur] < s * 1000) {
                size++;
                cur++;
            }
            System.out.println(s + " second" + (s > 1 ? "s: " : ": ") + size + "%, cumulative=" + cur + "%");
        }
    }

    long getNetworkDelay(@NotNull Node fromNode, @NotNull Node n) {
        long rawDelay = 10 + (200 * fromNode.dist(n)) / Node.MAX_DIST;
        return rawDelay + longDistrib[rd.nextInt(longDistrib.length)];
    }

    public @NotNull Network<TN> setNetworkLatency(@NotNull int[] dP, @NotNull long[] dV) {
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
        return this;
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
    }

}
