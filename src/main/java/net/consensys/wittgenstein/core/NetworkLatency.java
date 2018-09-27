package net.consensys.wittgenstein.core;

import org.jetbrains.annotations.NotNull;

abstract class NetworkLatency {
    /**
     * @param delta - a number between 0 & 99. Used to randomize the result
     */
    public abstract int getDelay(@NotNull Node from, @NotNull Node to, int delta);

    @Override
    public String toString() {
        return this.getClass().getSimpleName();
    }


    /**
     * Latency with: max distance = 200ms +/- 50%, linear
     */
    static class NetworkLatencyByDistance extends NetworkLatency {
        @Override
        public int getDelay(@NotNull Node from, @NotNull Node to, int delta) {
            int rawDelay = 10 + (200 * from.dist(to)) / Node.MAX_DIST;
            return rawDelay * (50 - delta);
        }
    }

    static class NetworkNoLatency extends NetworkLatency {
        public int getDelay(@NotNull Node from, @NotNull Node to, int delta) {
            return 1;
        }
    }

    static class MeasuredNetworkLatency extends NetworkLatency {
        private final int[] longDistrib = new int[100];

        /**
         * @see Network#distribProp
         * @see Network#distribVal
         */
        private void setLatency(@NotNull int[] dP, @NotNull int[] dV) {
            int li = 0;
            int cur = 0;
            int sum = 0;
            for (int i = 0; i < dP.length; i++) {
                sum += dP[i];
                int step = (dV[i] - cur) / dP[i];
                for (int ii = 0; ii < dP[i]; ii++) {
                    cur += step;
                    longDistrib[li++] = cur;
                }
            }

            if (sum != 100) throw new IllegalArgumentException();
            if (li != 100) throw new IllegalArgumentException();
        }


        public MeasuredNetworkLatency(@NotNull int[] distribProp, @NotNull int[] distribVal) {
            setLatency(distribProp, distribVal);
        }

        @Override
        public int getDelay(@NotNull Node from, @NotNull Node to, int delta) {
            return longDistrib[delta];
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("BlockChainNetwork latency: time to receive a message:");

            for (int s = 1, cur = 0; cur < 100; s++) {
                int size = 0;
                while (cur < longDistrib.length && longDistrib[cur] < s * 1000) {
                    size++;
                    cur++;
                }
                sb.append(s).append(" second").append(s > 1 ? "s: " : ": ").append(size).append("%, cumulative=");
                sb.append(cur).append("%");
            }

            return sb.toString();
        }
    }
}
