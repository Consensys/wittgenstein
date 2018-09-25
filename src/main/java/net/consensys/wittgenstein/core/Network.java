package net.consensys.wittgenstein.core;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * There is a single network for a simulation.
 * <p>
 * Nothing is executed in parallel, so the code does not have to be multithread safe.
 */
@SuppressWarnings("WeakerAccess")
public class Network<TN extends Node> {
    final static int duration = 60 * 1000;

    /**
     * The messages in transit. Sorted by their arrival time.
     */
    public final MessageStorage msgs = new MessageStorage();

    /**
     * In parallel of the messages, we have tasks. It's mixed with messages (some tasks
     * are managed as special messages). Conditional tasks are in a specific list.
     */
    public final List<ConditionalTask> conditionalTasks = new LinkedList<>();
    protected final Map<Integer, TN> allNodes = new HashMap<>();

    /**
     * By using a single random generator, we have repeatable runs.
     */
    public final Random rd;

    final HashSet<Integer> partition = new HashSet<>();

    /**
     * We can decide to discard messages that would take too long to arrive. This
     * limit the memory consumption of the simulator as well.
     */
    long msgDiscardTime = Long.MAX_VALUE;

    /**
     * Distribution taken from: https://ethstats.net/
     * It should be read like this:
     * 16% of the messages will be received in 250ms or less
     */
    private final int[] distribProp = {16, 18, 17, 12, 8, 5, 4, 3, 3, 1, 1, 2, 1, 1, 8};
    public final long[] distribVal = {250, 500, 1000, 1250, 1500, 1750, 2000, 2250, 2500, 2750, 4500, 6000, 8500, 9750, 10000};
    private final long[] longDistrib = new long[100];


    public long time = 0;


    public Network() {
        this(0);
    }

    public Network(long randomSeed) {
        this.rd = new Random(randomSeed);
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
     * A desperate attempt to have something less memory consuming than a PriorityQueue or
     * a guava multimap, by using raw array. The idea is to optimize the case when there are
     * multiple messages in the same millisecond, but the time range is limited.
     * <p>
     * The second idea is to have a repeatable run when there are multiple messages arriving
     * at the same millisecond.
     */
    private static class MsgsSlot {
        final long startTime;
        final long endTime;
        @SuppressWarnings("unchecked")
        final LinkedList<Message>[] msgsByMs = new LinkedList[duration];

        public MsgsSlot(long startTime) {
            this.startTime = startTime - (startTime % duration);
            this.endTime = startTime + duration;
        }

        private int getPos(long aTime) {
            if (aTime < startTime || aTime >= startTime + duration) {
                throw new IllegalArgumentException();
            }
            return (int) (aTime % duration);
        }

        public void addMsg(@NotNull Message m) {
            int pos = getPos(m.arrivalTime);
            if (msgsByMs[pos] == null) {
                msgsByMs[pos] = new LinkedList<>();
            }
            msgsByMs[pos].addFirst(m);
        }

        public @Nullable Message peek(long time) {
            int pos = getPos(time);
            if (msgsByMs[pos] == null || msgsByMs[pos].isEmpty()) {
                return null;
            }
            return msgsByMs[pos].peekFirst();
        }

        public @Nullable Message poll(long time) {
            int pos = getPos(time);
            if (msgsByMs[pos] == null || msgsByMs[pos].isEmpty()) {
                return null;
            }
            return msgsByMs[pos].pollFirst();
        }

        public int size() {
            int size = 0;
            for (int i = 0; i < duration; i++) {
                if (msgsByMs[i] != null) {
                    size += msgsByMs[i].size();
                }
            }
            return size;
        }

        public @Nullable Message peekFirst() {
            for (int i = 0; i < duration; i++) {
                if (msgsByMs[i] != null && !msgsByMs[i].isEmpty()) {
                    return msgsByMs[i].peekFirst();
                }
            }
            return null;
        }

    }

    public class MessageStorage {
        public final ArrayList<MsgsSlot> msgsBySlot = new ArrayList<>();

        public int size() {
            int size = 0;
            for (MsgsSlot ms : msgsBySlot) {
                size += ms.size();
            }
            return size;
        }

        void cleanup() {
            while (!msgsBySlot.isEmpty() && time >= msgsBySlot.get(0).endTime) {
                msgsBySlot.remove(0);
            }
            if (msgsBySlot.isEmpty()) {
                msgsBySlot.add(new MsgsSlot(time));
            }
        }

        void ensureSize(long aTime) {
            while (msgsBySlot.get(msgsBySlot.size() - 1).endTime <= aTime) {
                msgsBySlot.add(new MsgsSlot(msgsBySlot.get(msgsBySlot.size() - 1).endTime));
            }
        }

        private @NotNull MsgsSlot findSlot(long aTime) {
            cleanup();
            ensureSize(aTime);
            int pos = (int) (aTime - msgsBySlot.get(0).startTime) / duration;
            return msgsBySlot.get(pos);
        }

        public void addMsg(@NotNull Message m) {
            findSlot(m.arrivalTime).addMsg(m);
        }

        public @Nullable Message peek(long time) {
            return findSlot(time).peek(time);
        }

        public @Nullable Message poll(long time) {
            return findSlot(time).poll(time);
        }

        public void clear() {
            msgsBySlot.clear();
            cleanup();
        }

        /**
         * @return the first message in the queue, null if the queue is empty.
         */
        public @Nullable Message peekFirst() {
            for (MsgsSlot ms : msgsBySlot) {
                Message m = ms.peekFirst();
                if (m != null) return m;
            }
            return null;
        }
    }


    /**
     * The generic message that goes on a network. Triggers an 'action' on reception.
     */
    public abstract static class MessageContent<TN extends Node> {
        public abstract void action(@NotNull TN from, @NotNull TN to);

        /**
         * We track the total size of the messages exchanged. Subclasses should
         * override this method if they want to measure the network usage.
         */
        public int size() {
            return 1;
        }
    }

    /**
     * Some protocols want some tasks to be executed at a given time
     */
    public class Task extends MessageContent<TN> {
        final @NotNull Runnable r;

        public Task(@NotNull Runnable r) {
            this.r = r;
        }

        @Override
        public int size() {
            return 0;
        }

        @Override
        public void action(@NotNull TN from, @NotNull TN to) {
            r.run();
        }
    }

    public class ConditionalTask extends Task {
        /**
         * Starts if this condition is met.
         */
        final Condition startIf;

        /**
         * Will start again when the task is finished if this condition is met.
         */
        final Condition repeatIf;

        /**
         * Time before next start.
         */
        final long duration;
        final Node sender;

        /**
         * Will start after this time.
         */
        long minStartTime;

        public ConditionalTask(Condition startIf, Condition repeatIf, Runnable r, long minStartTime, long duration, Node sender) {
            super(r);
            this.startIf = startIf;
            this.repeatIf = repeatIf;
            this.duration = duration;
            this.sender = sender;
            this.minStartTime = minStartTime;
        }
    }


    public interface Condition {
        boolean check();
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
            if (continuationCondition.check()) {
                msgs.addMsg(new Message(this, sender, sender, time + period));
            }
        }
    }

    public static class Message implements Comparable<Message> {
        public final @NotNull MessageContent messageContent;
        public final @NotNull Node fromNode;
        public final @NotNull Node toNode;
        public final long arrivalTime;

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

    /**
     * Simulate for x seconds. Can be called multiple time.
     */
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

    public void sendAll(@NotNull MessageContent m, @NotNull TN fromNode) {
        send(m, time + 1, fromNode, allNodes.values());
    }

    /**
     * Send a message to a collection of nodes. The message is considered as sent immediately, and
     *  will arrive at a time depending on the network latency.
     */
    public void send(@NotNull MessageContent m, @NotNull TN fromNode, @NotNull Collection<TN> dests) {
        send(m, time + 1, fromNode, dests);
    }

    public void send(@NotNull MessageContent m, @NotNull TN fromNode, @NotNull TN toNode) {
        send(m, time + 1, fromNode, Collections.singleton(toNode));
    }

    /**
     * Send a message to a single node.
     */
    public void send(@NotNull MessageContent m, long sendTime, @NotNull TN fromNode, @NotNull TN toNode) {
        send(m, sendTime, fromNode, Collections.singleton(toNode));
    }

    public void send(@NotNull MessageContent m, long sendTime, @NotNull TN fromNode, @NotNull Collection<? extends Node> dests) {
        if (sendTime <= time) {
            throw new IllegalStateException("" + m + ", sendTime=" + sendTime + ", time=" + time);
        }
        for (Node n : dests) {
            if (n != fromNode) {
                if ((partition.contains(fromNode.nodeId) && partition.contains(n.nodeId)) ||
                        (!partition.contains(fromNode.nodeId) && !partition.contains(n.nodeId))) {
                    if (m.size() == 0) throw new IllegalStateException();
                    fromNode.msgSent++;
                    fromNode.bytesSent += m.size();
                    long nt = getNetworkDelay(fromNode, n);
                    if (nt < msgDiscardTime) {
                        msgs.addMsg(new BlockChainNetwork.Message(m, fromNode, n, sendTime + nt));
                    }
                }
            }
        }
    }


    public void registerTask(@NotNull final Runnable task, long startAt, @NotNull TN fromNode) {
        Task sw = new Task(task);
        msgs.addMsg(new Message(sw, fromNode, fromNode, startAt));
    }

    public void registerPeriodicTask(@NotNull final Runnable task, long startAt, long period, @NotNull TN fromNode) {
        PeriodicTask sw = new PeriodicTask(task, fromNode, period);
        msgs.addMsg(new Message(sw, fromNode, fromNode, startAt));
    }

    public void registerPeriodicTask(@NotNull final Runnable task, long startAt, long period, @NotNull TN fromNode, @NotNull Condition c) {
        PeriodicTask sw = new PeriodicTask(task, fromNode, period, c);
        msgs.addMsg(new Message(sw, fromNode, fromNode, startAt));
    }

    public void registerConditionalTask(@NotNull final Runnable task, long startAt, long duration,
                                        @NotNull TN fromNode, @NotNull Condition startIf, @NotNull Condition repeatIf) {
        ConditionalTask ct = new ConditionalTask(startIf, repeatIf, task, startAt, duration, fromNode);
        conditionalTasks.add(ct);
    }

    private @Nullable Message nextMessage(long until) {
        while (time <= until) {
            Message m = msgs.poll(time);
            if (m != null) {
                return m;
            } else {
                time++;
            }
        }
        return null;
    }

    void receiveUntil(long until) {
        //noinspection ConstantConditions
        long previousTime = time;
        Message next = nextMessage(until);
        while (next != null) {
            Message m = next;
            if (m.arrivalTime != previousTime) {
                if (time > m.arrivalTime) {
                    throw new IllegalStateException("time:" + time + ", m:" + m);
                }
                Iterator<ConditionalTask> it = conditionalTasks.iterator();
                while (it.hasNext()) {
                    ConditionalTask ct = it.next();
                    if (!ct.repeatIf.check()) {
                        it.remove();
                    } else {
                        if (time >= ct.minStartTime && ct.startIf.check()) {
                            ct.r.run();
                            ct.minStartTime = time + ct.duration;
                        }
                    }
                }
            }

            if ((partition.contains(m.fromNode.nodeId) && partition.contains(m.toNode.nodeId)) ||
                    (!partition.contains(m.fromNode.nodeId) && !partition.contains(m.toNode.nodeId))) {
                if (!(m.messageContent instanceof Network<?>.Task)) {
                    if (m.messageContent.size() == 0) throw new IllegalStateException();
                    m.toNode.msgReceived++;
                    m.toNode.bytesReceived += m.messageContent.size();
                }
                m.messageContent.action(m.fromNode, m.toNode);
            }

            previousTime = time;
            next = nextMessage(until);
        }
    }


    public void addNode(@NotNull TN node) {
        allNodes.put(node.nodeId, node);
    }


    /**
     * Set the network latency to a min value. This allows
     * to test the protocol independently of the network variability.
     */
    @SuppressWarnings("UnusedReturnValue")
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

    /**
     * We take into account:
     * - a fix cost: 10ms
     * - the distance between the nodes: max 200ms
     * - the latency set.
     */
    long getNetworkDelay(@NotNull Node fromNode, @NotNull Node n) {
        long rawDelay = 10 + (200 * fromNode.dist(n)) / Node.MAX_DIST;
        return rawDelay + longDistrib[rd.nextInt(longDistrib.length)];
    }

    /**
     * @see Network#distribProp
     * @see Network#distribVal
     */
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
