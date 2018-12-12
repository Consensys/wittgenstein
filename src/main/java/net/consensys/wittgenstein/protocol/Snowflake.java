package net.consensys.wittgenstein.protocol;

import net.consensys.wittgenstein.core.Network;
import net.consensys.wittgenstein.core.Node;
import net.consensys.wittgenstein.core.ProgressPerTime;
import net.consensys.wittgenstein.core.Protocol;
import net.consensys.wittgenstein.core.utils.StatsHelper;

import java.util.*;
import java.util.function.Predicate;

public class Snowflake implements Protocol {
    private static final int NODES_AV = 100;
    private Network<SnowflakeNode> network = new Network<>();
    final Node.NodeBuilder nb;
    private static final int COLOR_NB = 2;

    /**
     * M is the number of rounds. "Finally, the node decides the color it ended up with at time m []
     * we will show that m grows logarithmically with n."
     */
    private final int M;

    /**
     * K is the sample size you take
     */
    private final int K;

    /**
     * A stands for the alpha threshold
     */
    private final double A;
    private double AK;
    private final int B;

    private Snowflake(int M, int K, double A, int B){
        this.M = M;
        this.K = K;
        this.A = A;
        this.B = B;
        this.AK = A * K;
        this.nb = new Node.NodeBuilderWithRandomPosition();
    }
    @Override
    public Protocol copy() {
        return new Snowflake(M,K,A,B);
    }

    @Override
    public void init() {
        for(int i = 0; i <NODES_AV; i++){
            network.addNode(new SnowflakeNode(network.rd,nb));
        }
        SnowflakeNode uncolored1 = network().getNodeById(0);
        SnowflakeNode uncolored2 = network().getNodeById(1);
        uncolored1.myColor = 1;
        uncolored1.sendQuery(1);

        uncolored2.myColor = 2;
        uncolored2.sendQuery(1);
    }

    @Override
    public Network<SnowflakeNode> network() {
        return network;
    }

    static class Query extends Network.Message<SnowflakeNode> {
        final int id;
        final int color;

        Query(int id, int color) {
            this.id = id;
            this.color = color;
        }

        @Override
        public void action(SnowflakeNode from, SnowflakeNode to) {
            to.onQuery(this, from);
        }
    }

    static class AnswerQuery extends Network.Message<SnowflakeNode> {
        final Query originalQuery;
        final int color;

        AnswerQuery(Query originalQuery, int color) {
            this.originalQuery = originalQuery;
            this.color = color;
        }

        @Override
        public void action(SnowflakeNode from, SnowflakeNode to) {
            to.onAnswer(originalQuery.id, color);
        }
    }

    class SnowflakeNode extends Node {

        int myColor = 0;
        int myQueryNonce;
        int cnt = 0;
        final Map<Integer, Answer> answerIP = new HashMap<>();

        SnowflakeNode(Random rd, NodeBuilder nb) {
           super(rd,nb);
        }

        List<SnowflakeNode> getRandomRemotes() {
            List<SnowflakeNode> res = new ArrayList<>(K);

            while (res.size() != K) {
                int r = network.rd.nextInt(NODES_AV);
                if (r != nodeId && !res.contains(network.getNodeById(r))) {
                    res.add(network.getNodeById(r));
                }
            }

            return res;
        }

        private int otherColor() {
            return myColor == 1 ? 2 : 1;
        }

        void onQuery(Query qa, SnowflakeNode from) {
            if (myColor == 0) {
                myColor = qa.color;
                sendQuery(1);
            }
            network.send(new AnswerQuery(qa, myColor), this, from);
        }

        /**
         * Once the querying node collects k responses, it checks if a fraction ≥ αk are for the same
         * color, where α > 0.5 is a protocol parameter. If the αk threshold is met and the sampled
         * color differs from the node’s own color, the node flips to that color.
         */
        void onAnswer(int queryId, int color) {
            Answer asw = answerIP.get(queryId);
            asw.colorsFound[color]++;
            // in this case we assume that messages received correspond to the query answers
            if (asw.answerCount() == K) {
                answerIP.remove(queryId);
                if (asw.colorsFound[otherColor()] > AK) {
                    myColor = otherColor();
                    cnt = 0;
                }else{
                    if(asw.colorsFound[myColor]> AK){
                        cnt++;
                    }
                }
                if (cnt <= B) {
                    sendQuery(asw.round + 1);
                }
            }
        }

        void sendQuery(int countInM) {
            Query q = new Query(++myQueryNonce, myColor);
            answerIP.put(q.id, new Answer(countInM));
            network.send(q, this, getRandomRemotes());
        }

    }
    static class Answer {
        final int round;
        private final int[] colorsFound = new int[COLOR_NB + 1];

        Answer(int round) {
            this.round = round;
        }

        private int answerCount() {
            int sum = 0;
            for (int i : colorsFound) {
                sum += i;
            }
            return sum;
        }
    }

    private void play() {

        String desc = "Slush Protocol color metastasis by time periods in ms with K=" + this.K
                + " rounds M= " + this.M;

        // sl.network.setNetworkLatency(nl);
        StatsHelper.StatsGetter stats = new StatsHelper.StatsGetter() {
            final List<String> fields = new StatsHelper.SimpleStats(0, 0, 0).fields();

            @Override
            public List<String> fields() {
                return fields;
            }

            @Override
            public StatsHelper.Stat get(List<? extends Node> liveNodes) {

                int[] colors = getDominantColor(liveNodes);
                System.out.println("Colored nodes by the numbers: " + colors[0] + " remain uncolored "
                        + colors[1] + " are red " + colors[2] + " are blue.");
                return StatsHelper.getStatsOn(liveNodes, n -> colors[((SnowflakeNode) n).myColor]);
            }
        };
        ProgressPerTime ppt = new ProgressPerTime(this, desc, "Number of y-Colored Nodes", stats, 10);

        Predicate<Protocol> contIf = p1 -> {
            int[] colors;
            for (Node n : p1.network().allNodes) {
                SnowflakeNode gn = (SnowflakeNode) n;
                colors = getDominantColor(p1.network().allNodes);
                if ((gn.cnt < B && colors[1] != 100) || (gn.cnt < B && colors[2] != 100)) {
                    return true;
                }
            }

            return false;
        };
        ppt.run(contIf);
    }

    private static int[] getDominantColor(List<? extends Node> ps) {
        int[] colors = new int[3];
        for (Node n : ps) {
            SnowflakeNode sn = (SnowflakeNode) n;
            colors[sn.myColor]++;
        }
        return colors;
    }
    public static void main(String...args){
        new Snowflake(5, 7, 4.0 / 7.0,3).play();
    }
}
