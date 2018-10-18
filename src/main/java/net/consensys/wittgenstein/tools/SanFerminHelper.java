package net.consensys.wittgenstein.tools;

import net.consensys.wittgenstein.core.Node;
import net.consensys.wittgenstein.core.utils.MoreMath;
import net.consensys.wittgenstein.protocol.SanFerminSignature;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * SanFerminHelper is a helper that computes some useful information needed in
 * San Fermin-like protocols. A SanFerminHelper can keep track at which level
 * one node is to propose a new candidate set. It can also compute the set of
 * candidates to contact at a given level, and computes the set of a given node
 * at a given level.
 */
public class SanFerminHelper {

    final Node n;
    /**
     * The id of the node in binary
     */
    final String binaryId;
    /**
     * List of all nodes
     */
    final List<Node> allNodes;
    /**
     * List of nodes we already selected at different levels
     */
    final HashMap<Integer, BitSet> usedNodes;


    public SanFerminHelper(Node n, List<Node> allNodes) {
        this.n = n;
        this.binaryId = leftPadWithZeroes(Integer.toBinaryString(n.nodeId),
                MoreMath.log2(allNodes.size()));
        this.allNodes = allNodes;
        this.usedNodes = new HashMap<Integer,BitSet>();
    }

    /**
     * getOwnSet returns the set of nodes at a given level that contains the
     * node given to the helper
     * @param level
     * @return
     */
    public List<Node> getOwnSet(int level) {
        int min = 0;
        int max = allNodes.size();
        for (int currLevel = 0; currLevel <= level && min <= max; currLevel++) {
            int m = Math.floorDiv((max + min), 2);
            if (binaryId.charAt(currLevel) == '0') {
                // reduce the interval to the left
                max = m;
            } else if (binaryId.charAt(currLevel) == '1') {
                // reduce interval to the right
                min = m;
            }
            if (max == min)
                break;

            if (max - 1 == 0 || min == allNodes.size())
                break;
        }

        return allNodes.subList(min, max);
    }

    /**
     * getCandidateSet returns the whole set of nodes at a given level that
     * should
     * be contacted by the node given to the helper
     * @param level
     * @return
     */
    public List<Node> getCandidateSet(int level) {
        int min = 0;
        int max = allNodes.size();
        int currLevel = 0;
        for (currLevel = 0; currLevel <= level && min <= max; currLevel++) {
            int m = Math.floorDiv((max + min), 2);
            if (binaryId.charAt(currLevel) == '0') {
                if (currLevel == level) {
                    // when we are at the right level, swap the order
                    min = m;
                } else {
                    max = m;
                }

            } else if (binaryId.charAt(currLevel) == '1') {
                if (currLevel == level) {
                    // when we are at the right level, swap the order
                    max = m;
                } else {
                    min = m;
                }
            }
            if (max == min)
                break;

            if (max - 1 == 0 || min == allNodes.size())
                break;
        }
        return allNodes.subList(min, max);
    }

    /**
     * getExactCandidateNode selects deterministically the node from the
     * candidate set at the given level that should be contacted.
     * @param level
     * @return
     */
    public Node getExactCandidateNode(int level) {
        List<Node> own = this.getOwnSet(level);
        int idx = own.indexOf(this.n);
        if (idx == -1)
            throw new IllegalStateException("that should not happen");

        List<Node> candidates = this.getCandidateSet(level);
        if (idx >= candidates.size())
            throw new IllegalStateException("that also should not happen");

        return candidates.get(idx);
    }


    /**
     * pickNextNodes returns the nodes that should be contacted at a given
     * level. The difference with getCandidateSet is that saves which nodes
     * have been selected already, and returns only new or no nodes at each
     * call for the same level.
     * @param level
     * @param howMany
     * @return
     */
    public List<Node> pickNextNodes(int level, int howMany) {
        List<Node> candidateSet = new ArrayList<>(getCandidateSet(level));
        int idx = candidateSet.indexOf(this.n);
        if (idx == -1)
            throw new IllegalStateException("that should not happen");

        List<Node> newList = new ArrayList<>();
        BitSet set = usedNodes.getOrDefault(level, new BitSet());
        // add the "correct" one first if not already
        if (set.get(idx)) {
            newList.add(candidateSet.get(idx));
            candidateSet.remove(idx);
            set.set(idx);
        }

        if (candidateSet.size() == 0) {
            return newList;
        }
        // add the rest if not taken already
        List<Node> availableNodes =
                IntStream.range(0,candidateSet.size()).filter(node -> !set.get(node))
                        .mapToObj(i -> {
                            set.set(i); // register it
                            return candidateSet.get(i);
                        }).collect(Collectors.toList());



        Collections.shuffle(availableNodes);
        return newList;
    }

    /**
     * Simply pads the binary string id to the exact length = n where N = 2^n
     */
    private static String leftPadWithZeroes(String originalString, int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append("0");
        }
        String padding = sb.toString();
        String paddedString = padding.substring(originalString.length()) + originalString;
        return paddedString;
    }

}

