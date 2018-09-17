import java.util.*;

public class SlushSnowflake {
    private static final int NODES_NB = 100;
    private static final int BYZANTINE_NODES_NB = 10;
    private static final int COLOR_NB = 2;


    /**
     * (for slush only)
     * Finally, the node decides the color it ended up with at time m.
     */
    private static final int M = 3;

    /**
     * To perform a
     * query, a node picks a small, constant sized (k) sample
     * of the network uniformly at random.
     * <p>
     * We now examine how the phase shift point behaves
     * with increasing k. Whereas k = 1 is optimal for Slush
     * (i.e. the network topples the fastest when k = 1), we
     * see that Snowflake’s safety is maximally displaced from
     * its ideal position for k = 1, reducing the number of
     * feasible solutions. Luckily, small increases in k have an
     * exponential effect on sps, creating a larger set of feasible
     * solutions. More formally:
     */
    private static final int K = 7;

    /**
     * it checks if a fraction
     * ≥ αk are for the same color, where α > 0.5 is a protocol
     * parameter.
     */
    private static final int AK = 4;

    /**
     * A node accepts the current color when its counter
     * exceeds β
     */
    private static final int B = 2;

    private int actionCt = 0;

    private static final Random rd = new Random();
    private SlushNode[] nodes = new SlushNode[NODES_NB];


    private void addAction(Action a) {
        if (inProgress.isEmpty()) {
            inProgress.add(a);
        } else {
            // It's asynchronous, so the messages can arrive in any order.
            int p = rd.nextInt(inProgress.size());
            inProgress.listIterator(p).add(a);
        }
    }

    class SlushNode {
        final int myId;
        int myColor = 0;
        int cnt;
        int myQueryNonce;
        final HashMap<Integer, QueryAnswer> answerIP = new HashMap<>();


        /**
         * Upon receiving a query, an uncolored node
         * adopts the color in the query, responds with that color,
         * and initiates its own query, whereas a colored node simply
         * responds with its current color.
         */
        public void receiveQuery(QueryAction qa) {
            if (myColor == 0) {
                myColor = qa.color;
                sendQuery();
            }
            addAction(new QueryAnswerAction(qa, myColor));
        }

        void sendQuery() {
            ++myQueryNonce;
            QueryAnswer asw = new QueryAnswer();
            answerIP.put(myQueryNonce, asw);
            for (int r : getRandomRemotes()) {
                addAction(new QueryAction(this, nodes[r], myQueryNonce, myColor));
            }
        }

        /**
         * Once the
         * querying node collects k responses, it checks if a fraction
         * ≥ αk are for the same color, where α > 0.5 is a protocol
         * parameter. If the αk threshold is met and the sampled
         * color differs from the node’s own color, the node flips
         * to that color.
         */
        public void receiveQueryAnswer(QueryAnswerAction qaa) {
            QueryAnswer asw = answerIP.get(qaa.answerId);
            asw.colorsFound[qaa.color]++;

            if (asw.answerCount() == K) {
                answerIP.remove(qaa.answerId);
                if (asw.colorsFound[otherColor()] > AK) {
                    myColor = otherColor();
                }
                cnt++;
                if (cnt < M) {
                    sendQuery();
                }
            }
        }

        List<Integer> getRandomRemotes() {
            List<Integer> res = new ArrayList<>(K);

            while (res.size() != K) {
                int r = rd.nextInt(NODES_NB);
                if (r != myId) res.add(r);
            }

            return res;
        }

        int otherColor() {
            return myColor == 1 ? 2 : 1;
        }

        SlushNode(int myId) {
            this.myId = myId;
        }
    }

    class ByzantineNode extends SlushNode {

        ByzantineNode(int myId) {
            super(myId);
        }

        /**
         * the adversary can attempt to flip nodes to the opposite so
         * as to keep the network in balance.
         */
        public void receiveQuery(QueryAction qa) {
            myColor = qa.color;
            myColor = otherColor();
            //sendQuery();
            addAction(new QueryAnswerAction(qa, myColor));
        }
    }

    class SnowflakeNode extends SlushNode {
        SnowflakeNode(int myId) {
            super(myId);
        }

        /**
         * 1: procedure snowflakeLoop(u, col0 ∈ {R, B, ⊥})
         * 2: col := col0, cnt := 0
         * 3: while undecided do
         * 4:   if col = ⊥ then continue
         * 5:   K := sample(N \ u, k)
         * 6:   P := [query(v, col) for v ∈ K]
         * 7:   for col' ∈ {R, B} do
         * 8:     if P.count(col') ≥ α · k then
         * 9:       if col' != col then
         * 10:        col := col'
         * ,          cnt := 0
         * 11:      else
         * 12:        if ++cnt > β then accept(col)
         * <p>
         * What's not very clear is what happens during the process: are we returning the color in progress.
         */
        public void receiveQueryAnswer(QueryAnswerAction qaa) {
            QueryAnswer asw = answerIP.get(qaa.answerId);
            asw.colorsFound[qaa.color]++;

            if (asw.answerCount() == K) {
                answerIP.remove(qaa.answerId);
                if (asw.colorsFound[otherColor()] > AK) {
                    myColor = otherColor();
                    cnt = 0;
                } else {
                    if (asw.colorsFound[myColor] > AK) {
                        cnt++;
                    }
                }
                if (cnt <= B) {
                    sendQuery();
                }
            }
        }
    }


    class QueryAnswer {
        private final int[] colorsFound = new int[COLOR_NB + 1];

        int answerCount() {
            int sum = 0;
            for (int i : colorsFound) sum += i;
            return sum;
        }
    }

    abstract class Action {
        protected final SlushNode orig;
        protected final SlushNode dest;

        abstract void run();

        Action(SlushNode orig, SlushNode dest) {
            this.orig = orig;
            this.dest = dest;
        }
    }

    class QueryAnswerAction extends Action {
        final int color;
        final int answerId;
        final QueryAction cause;


        @Override
        public void run() {
            if (dest != null) dest.receiveQueryAnswer(this);
        }

        QueryAnswerAction(QueryAction cause, int color) {
            super(cause.dest, cause.orig);
            this.cause = cause;
            this.answerId = cause.answerId;
            this.color = color;
        }
    }

    class QueryAction extends Action {
        final int answerId;
        final int color;

        @Override
        public void run() {
            dest.receiveQuery(this);
        }

        public QueryAction(SlushNode orig, SlushNode dest, int answerId, int color) {
            super(orig, dest);
            this.answerId = answerId;
            this.color = color;
        }

    }

    private LinkedList<Action> inProgress = new LinkedList<>();

    private void runActions() {
        while (!inProgress.isEmpty()) {
            actionCt++;
            inProgress.removeFirst().run();
        }
    }


    void play() {
        int badNodes = BYZANTINE_NODES_NB;
        for (int i = 0; i < NODES_NB; i++) {
            SlushNode n = badNodes-- > 0 ? new ByzantineNode(i) : new SnowflakeNode(i);
            nodes[i] = n;
        }

        // We put two contradicting queries
        inProgress.add(new QueryAction(null, nodes[NODES_NB - 2], -1, 1));
        inProgress.add(new QueryAction(null, nodes[NODES_NB - 1], -1, 2));

        runActions();

        stats();
    }

    void stats() {
        int std = 0;
        int byz = 0;
        int bft = 0;
        for (SlushNode n : nodes) {
            if (n instanceof ByzantineNode) byz++;
            else if (n instanceof SnowflakeNode) bft++;
            else std++;

        }
        System.out.println("N=" + nodes.length + ", K=" + K + ", AK=" + AK + ", B=" + B);
        System.out.println("Nodes: standards=" + std + ", adversary=" + byz + ", bft=" + bft);


        int[] res = new int[COLOR_NB + 1];
        for (SlushNode n : nodes) {
            res[n.myColor]++;
        }

        System.out.println("actions count:" + actionCt);
        for (int i = 0; i < COLOR_NB + 1; i++) {
            System.out.println(i + ":" + res[i]);
        }
    }

    public static void main(String... args) {
        new SlushSnowflake().play();
    }

}

