package com.nts.rft;

import java.util.*;

/**
 * @author Nikolay Tsankov.
 */
enum State {
    leader,
    candidate,
    follower;

    private static EnumMap<State, Set<State>> transitions = new EnumMap<>(State.class);

    static {
        transitions.put(follower, Collections.singleton(candidate));
        transitions.put(candidate, EnumSet.of(leader, follower));
        transitions.put(leader, Collections.singleton(follower));
    }

    boolean canTransitionTo(State next) {
        return transitions.get(this).contains(next);
    }
}
