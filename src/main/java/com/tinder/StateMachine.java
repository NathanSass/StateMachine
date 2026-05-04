package com.tinder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A Java finite state machine implementation based on the Kotlin StateMachine.
 *
 * <p>This implementation is thread-safe using synchronized blocks.
 *
 * @param <STATE> the type of states
 * @param <EVENT> the type of events
 * @param <SIDE_EFFECT> the type of side effects
 */
public class StateMachine<STATE, EVENT, SIDE_EFFECT> {

    private final Graph<STATE, EVENT, SIDE_EFFECT> graph;

    private STATE state;

    private StateMachine(Graph<STATE, EVENT, SIDE_EFFECT> graph) {
        this.graph = graph;
        this.state = graph.initialState;
    }

    /**
     * Static helper for creating a transition result (for use in on() lambdas).
     */
    @SuppressWarnings("unchecked")
    public static <S, SE> Graph.State.TransitionTo<S, SE> transitionTo(S state, SE sideEffect) {
        return new Graph.State.TransitionTo<>(state, sideEffect);
    }

    public static <S, SE> Graph.State.TransitionTo<S, SE> transitionTo(S state) {
        return new Graph.State.TransitionTo<>(state, null);
    }

    @SuppressWarnings("unchecked")
    public static <S, SE> Graph.State.TransitionTo<S, SE> dontTransition(S state, SE sideEffect) {
        return new Graph.State.TransitionTo<>(state, sideEffect);
    }

    public static <S, SE> Graph.State.TransitionTo<S, SE> dontTransition(S state) {
        return new Graph.State.TransitionTo<>(state, null);
    }


    /**
     * Returns the current state.
     */
    public STATE getState() {
        return state;
    }

    /**
     * Transitions the state machine with the given event.
     *
     * @param event the event to transition with
     * @return the transition result (valid or invalid)
     */
    public Transition<STATE, EVENT, SIDE_EFFECT> transition(EVENT event) {
        // TODO: Implement the main transition method.
        // Requirements:
        // - Thread-safe: use synchronized(this) for the critical section
        // - Check if current state is terminal -> return Invalid
        // - Get the transition for the current state and event
        // - If valid and state is actually changing:
        //     * Call onCleanUp on old state (inside synchronized)
        //     * Apply factory if present on target state (inside synchronized)
        //     * Update state reference (inside synchronized)
        // - After synchronized block:
        //     * Notify transition listeners
        //     * Fire onExit on old state, onEnter on new state
        // - If dontTransition (state unchanged): fire onExit but NOT onCleanUp
        throw new UnsupportedOperationException("TODO: implement transition");
    }

    /**
     * Creates a new state machine with the same graph but a different initial state.
     *
     * @param initializer a function to configure the graph builder with a new initial state
     * @return a new state machine
     */
    public StateMachine<STATE, EVENT, SIDE_EFFECT> with(Consumer<GraphBuilder<STATE, EVENT, SIDE_EFFECT>> initializer) {
        GraphBuilder<STATE, EVENT, SIDE_EFFECT> builder = new GraphBuilder<>(graph.copy(state));
        initializer.accept(builder);
        return new StateMachine<>(builder.build());
    }

    @SuppressWarnings("unchecked")
    private Transition<STATE, EVENT, SIDE_EFFECT> getTransition(STATE fromState, EVENT event) {
        Graph.State<STATE, EVENT, SIDE_EFFECT> definition = getDefinition(fromState);

        // Check static transitions first if enabled
        if (graph.useStaticTransitions) {
            Matcher<STATE, STATE> stateMatcher = graph.findStateMatcher(fromState);
            if (stateMatcher != null) {
                Map<Class<? extends EVENT>, STATE> staticTransitions = graph.staticTransitions.get(stateMatcher);
                if (staticTransitions != null) {
                    STATE targetState = staticTransitions.get(event.getClass());
                    if (targetState != null) {
                        return new Transition.Valid<>(fromState, event, targetState, null);
                    }
                }
            }
            return new Transition.Invalid<>(fromState, event);
        }

        // Dynamic transitions
        for (Map.Entry<Matcher<EVENT, EVENT>, BiFunction<STATE, EVENT, Graph.State.TransitionTo<STATE, SIDE_EFFECT>>> entry :
                definition.transitions.entrySet()) {
            Matcher<EVENT, EVENT> eventMatcher = entry.getKey();
            if (eventMatcher.matches(event)) {
                BiFunction<STATE, EVENT, Graph.State.TransitionTo<STATE, SIDE_EFFECT>> createTransitionTo = entry.getValue();
                Graph.State.TransitionTo<STATE, SIDE_EFFECT> transitionTo = createTransitionTo.apply(fromState, event);
                return new Transition.Valid<>(fromState, event, transitionTo.toState, transitionTo.sideEffect);
            }
        }
        return new Transition.Invalid<>(fromState, event);
    }

    private Graph.State<STATE, EVENT, SIDE_EFFECT> getDefinition(STATE state) {
        for (Map.Entry<Matcher<STATE, STATE>, Graph.State<STATE, EVENT, SIDE_EFFECT>> entry : graph.stateDefinitions.entrySet()) {
            if (entry.getKey().matches(state)) {
                return entry.getValue();
            }
        }
        throw new IllegalStateException("Missing definition for state " + state.getClass().getSimpleName() + "!");
    }

    private void notifyOnExit(STATE state, EVENT cause) {
        Graph.State<STATE, EVENT, SIDE_EFFECT> definition = getDefinition(state);
        for (BiConsumer<STATE, EVENT> listener : definition.onExitListeners) {
            listener.accept(state, cause);
        }
    }

    private void notifyOnEnter(STATE state, EVENT cause) {
        Graph.State<STATE, EVENT, SIDE_EFFECT> definition = getDefinition(state);
        for (BiConsumer<STATE, EVENT> listener : definition.onEnterListeners) {
            listener.accept(state, cause);
        }
    }

    private void notifyOnTransition(Transition<STATE, EVENT, SIDE_EFFECT> transition) {
        for (Consumer<Transition<STATE, EVENT, SIDE_EFFECT>> listener : graph.onTransitionListeners) {
            listener.accept(transition);
        }
    }

    /**
     * Represents a state transition result.
     */
    public static abstract class Transition<STATE, EVENT, SIDE_EFFECT> {

        private final STATE fromState;
        private final EVENT event;

        protected Transition(STATE fromState, EVENT event) {
            this.fromState = fromState;
            this.event = event;
        }

        public STATE getFromState() {
            return fromState;
        }

        public EVENT getEvent() {
            return event;
        }

        /**
         * A valid transition from one state to another.
         */
        public static class Valid<STATE, EVENT, SIDE_EFFECT> extends Transition<STATE, EVENT, SIDE_EFFECT> {

            private final STATE toState;
            private final SIDE_EFFECT sideEffect;

            public Valid(STATE fromState, EVENT event, STATE toState, SIDE_EFFECT sideEffect) {
                super(fromState, event);
                this.toState = toState;
                this.sideEffect = sideEffect;
            }

            public STATE getToState() {
                return toState;
            }

            public SIDE_EFFECT getSideEffect() {
                return sideEffect;
            }

            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (o == null || getClass() != o.getClass()) return false;
                Valid<?, ?, ?> valid = (Valid<?, ?, ?>) o;
                return Objects.equals(getFromState(), valid.getFromState()) &&
                        Objects.equals(getEvent(), valid.getEvent()) &&
                        Objects.equals(toState, valid.toState) &&
                        Objects.equals(sideEffect, valid.sideEffect);
            }

            @Override
            public int hashCode() {
                return Objects.hash(getFromState(), getEvent(), toState, sideEffect);
            }

            @Override
            public String toString() {
                return "Valid{" +
                        "fromState=" + getFromState() +
                        ", event=" + getEvent() +
                        ", toState=" + toState +
                        ", sideEffect=" + sideEffect +
                        '}';
            }
        }

        /**
         * An invalid transition (no matching event handler for the current state).
         */
        public static class Invalid<STATE, EVENT, SIDE_EFFECT> extends Transition<STATE, EVENT, SIDE_EFFECT> {

            public Invalid(STATE fromState, EVENT event) {
                super(fromState, event);
            }

            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (o == null || getClass() != o.getClass()) return false;
                Invalid<?, ?, ?> invalid = (Invalid<?, ?, ?>) o;
                return Objects.equals(getFromState(), invalid.getFromState()) &&
                        Objects.equals(getEvent(), invalid.getEvent());
            }

            @Override
            public int hashCode() {
                return Objects.hash(getFromState(), getEvent());
            }

            @Override
            public String toString() {
                return "Invalid{" +
                        "fromState=" + getFromState() +
                        ", event=" + getEvent() +
                        '}';
            }
        }
    }

    /**
     * The graph definition for a state machine.
     */
    public static class Graph<STATE, EVENT, SIDE_EFFECT> {

        private final STATE initialState;
        private final Map<Matcher<STATE, STATE>, State<STATE, EVENT, SIDE_EFFECT>> stateDefinitions;
        private final List<Consumer<Transition<STATE, EVENT, SIDE_EFFECT>>> onTransitionListeners;
        private final Set<Matcher<STATE, STATE>> terminalStateMatchers;
        private final boolean useStaticTransitions;
        private final Map<Matcher<STATE, STATE>, Map<Class<? extends EVENT>, STATE>> staticTransitions;

        public Graph(
                STATE initialState,
                Map<Matcher<STATE, STATE>, State<STATE, EVENT, SIDE_EFFECT>> stateDefinitions,
                List<Consumer<Transition<STATE, EVENT, SIDE_EFFECT>>> onTransitionListeners,
                Set<Matcher<STATE, STATE>> terminalStateMatchers,
                boolean useStaticTransitions,
                Map<Matcher<STATE, STATE>, Map<Class<? extends EVENT>, STATE>> staticTransitions) {
            this.initialState = initialState;
            this.stateDefinitions = stateDefinitions;
            this.onTransitionListeners = onTransitionListeners;
            this.terminalStateMatchers = terminalStateMatchers;
            this.useStaticTransitions = useStaticTransitions;
            this.staticTransitions = staticTransitions;
        }

        public STATE getInitialState() {
            return initialState;
        }

        public Map<Matcher<STATE, STATE>, State<STATE, EVENT, SIDE_EFFECT>> getStateDefinitions() {
            return stateDefinitions;
        }

        public List<Consumer<Transition<STATE, EVENT, SIDE_EFFECT>>> getOnTransitionListeners() {
            return onTransitionListeners;
        }

        public Graph<STATE, EVENT, SIDE_EFFECT> copy(STATE initialState) {
            return new Graph<>(initialState, stateDefinitions, onTransitionListeners, terminalStateMatchers, useStaticTransitions, staticTransitions);
        }

        boolean isTerminalState(STATE state) {
            // TODO: Check if the given state matches any terminal state matcher
            throw new UnsupportedOperationException("TODO: implement isTerminalState");
        }

        public Matcher<STATE, STATE> findStateMatcher(STATE state) {
            for (Matcher<STATE, STATE> matcher : stateDefinitions.keySet()) {
                if (matcher.matches(state)) {
                    return matcher;
                }
            }
            return null;
        }

        /**
         * State definition containing transitions and listeners.
         */
        public static class State<STATE, EVENT, SIDE_EFFECT> {

            private final List<BiConsumer<STATE, EVENT>> onEnterListeners = new ArrayList<>();
            private final List<BiConsumer<STATE, EVENT>> onExitListeners = new ArrayList<>();
            private final List<BiConsumer<STATE, EVENT>> onCleanUpListeners = new ArrayList<>();
            private final Map<Matcher<EVENT, EVENT>, BiFunction<STATE, EVENT, TransitionTo<STATE, SIDE_EFFECT>>> transitions = new LinkedHashMap<>();
            private Function<STATE, STATE> factory;

            public List<BiConsumer<STATE, EVENT>> getOnEnterListeners() {
                return onEnterListeners;
            }

            public List<BiConsumer<STATE, EVENT>> getOnExitListeners() {
                return onExitListeners;
            }

            public List<BiConsumer<STATE, EVENT>> getOnCleanUpListeners() {
                return onCleanUpListeners;
            }

            public Map<Matcher<EVENT, EVENT>, BiFunction<STATE, EVENT, TransitionTo<STATE, SIDE_EFFECT>>> getTransitions() {
                return transitions;
            }

            public Function<STATE, STATE> getFactory() {
                return factory;
            }

            public void setFactory(Function<STATE, STATE> factory) {
                this.factory = factory;
            }

            /**
             * Transition target with optional side effect.
             */
            public static class TransitionTo<STATE, SIDE_EFFECT> {

                private final STATE toState;
                private final SIDE_EFFECT sideEffect;

                public TransitionTo(STATE toState, SIDE_EFFECT sideEffect) {
                    this.toState = toState;
                    this.sideEffect = sideEffect;
                }

                public STATE getToState() {
                    return toState;
                }

                public SIDE_EFFECT getSideEffect() {
                    return sideEffect;
                }

                @Override
                public boolean equals(Object o) {
                    if (this == o) return true;
                    if (o == null || getClass() != o.getClass()) return false;
                    TransitionTo<?, ?> that = (TransitionTo<?, ?>) o;
                    return Objects.equals(toState, that.toState) &&
                            Objects.equals(sideEffect, that.sideEffect);
                }

                @Override
                public int hashCode() {
                    return Objects.hash(toState, sideEffect);
                }

                @Override
                public String toString() {
                    return "TransitionTo{" +
                            "toState=" + toState +
                            ", sideEffect=" + sideEffect +
                            '}';
                }
            }
        }
    }

    /**
     * Matcher for states and events.
     */
    public static class Matcher<T, R extends T> {

        private final Class<R> clazz;
        private final List<Predicate<T>> predicates = new ArrayList<>();

        private Matcher(Class<R> clazz) {
            this.clazz = clazz;
            this.predicates.add(clazz::isInstance);
        }

        /**
         * Adds a predicate filter.
         */
        public Matcher<T, R> where(Predicate<R> predicate) {
            predicates.add(value -> predicate.test(clazz.cast(value)));
            return this;
        }

        /**
         * Checks if a value matches this matcher.
         */
        public boolean matches(T value) {
            for (Predicate<T> predicate : predicates) {
                if (!predicate.test(value)) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Creates a matcher for any instance of the given class.
         */
        public static <T, R extends T> Matcher<T, R> any(Class<R> clazz) {
            return new Matcher<>(clazz);
        }

        /**
         * Creates a matcher for a specific value.
         */
        public static <T, R extends T> Matcher<T, R> eq(R value) {
            @SuppressWarnings("unchecked")
            Class<R> clazz = (Class<R>) value.getClass();
            return new Matcher<T, R>(clazz).where(value::equals);
        }
    }

    /**
     * Builder for creating state machine graphs.
     */
    public static class GraphBuilder<STATE, EVENT, SIDE_EFFECT> {

        private STATE initialState;
        private final Map<Matcher<STATE, STATE>, Graph.State<STATE, EVENT, SIDE_EFFECT>> stateDefinitions = new LinkedHashMap<>();
        private final List<Consumer<Transition<STATE, EVENT, SIDE_EFFECT>>> onTransitionListeners = new ArrayList<>();
        private final Set<Matcher<STATE, STATE>> terminalStateMatchers = new LinkedHashSet<>();
        private boolean useStaticTransitions = false;
        private final Map<Matcher<STATE, STATE>, Map<Class<? extends EVENT>, STATE>> staticTransitions = new LinkedHashMap<>();

        public GraphBuilder() {
        }

        GraphBuilder(Graph<STATE, EVENT, SIDE_EFFECT> graph) {
            if (graph != null) {
                this.initialState = graph.initialState;
                this.stateDefinitions.putAll(graph.stateDefinitions);
                this.onTransitionListeners.addAll(graph.onTransitionListeners);
                this.terminalStateMatchers.addAll(graph.terminalStateMatchers);
                this.useStaticTransitions = graph.useStaticTransitions;
                this.staticTransitions.putAll(graph.staticTransitions);
            }
        }

        /**
         * Sets the initial state.
         */
        public void initialState(STATE initialState) {
            this.initialState = initialState;
        }

        /**
         * Defines a state with a matcher and initialization block.
         */
        public <S extends STATE> void state(
                Matcher<STATE, S> stateMatcher,
                Consumer<StateDefinitionBuilder<S>> init) {
            StateDefinitionBuilder<S> builder = new StateDefinitionBuilder<>();
            init.accept(builder);
            @SuppressWarnings("unchecked")
            Matcher<STATE, STATE> matcher = (Matcher<STATE, STATE>) stateMatcher;
            stateDefinitions.put(matcher, builder.build());
        }

        /**
         * Defines a state for any instance of the given class.
         */
        public <S extends STATE> void state(
                Class<S> clazz,
                Consumer<StateDefinitionBuilder<S>> init) {
            state(Matcher.any(clazz), init);
        }

        /**
         * Defines a state by class (chaining style).
         * Returns the builder for method chaining.
         */
        public <S extends STATE> StateDefinitionBuilder<S> state(Class<S> clazz) {
            StateDefinitionBuilder<S> builder = new StateDefinitionBuilder<>();
            @SuppressWarnings("unchecked")
            Matcher<STATE, STATE> matcher = (Matcher<STATE, STATE>) Matcher.any(clazz);
            stateDefinitions.put(matcher, builder.stateDefinition);
            return builder;
        }

        /**
         * Defines a state for a specific value (chaining style).
         * Returns the builder for method chaining.
         */
        public StateDefinitionBuilder<STATE> state(STATE state) {
            StateDefinitionBuilder<STATE> builder = new StateDefinitionBuilder<>();
            @SuppressWarnings("unchecked")
            Matcher<STATE, STATE> matcher = (Matcher<STATE, STATE>) Matcher.eq(state);
            stateDefinitions.put(matcher, builder.stateDefinition);
            return builder;
        }

        /**
         * Defines a state for a specific value with initialization block.
         */
        public void state(
                STATE state,
                Consumer<StateDefinitionBuilder<STATE>> init) {
            state(Matcher.eq(state), init);
        }

        /**
         * Defines a terminal state by class (chaining style).
         */
        public <S extends STATE> StateDefinitionBuilder<S> terminalState(Class<S> clazz) {
            // TODO: Register a terminal state by class.
            // Create a StateDefinitionBuilder, add the state definition,
            // and mark it as terminal.
            throw new UnsupportedOperationException("TODO: implement terminalState(Class)");
        }

        /**
         * Defines a terminal state for a specific value (chaining style).
         */
        public StateDefinitionBuilder<STATE> terminalState(STATE state) {
            // TODO: Register a terminal state by value.
            // Same as terminalState(Class) but for specific instances.
            throw new UnsupportedOperationException("TODO: implement terminalState(STATE)");
        }

        /**
         * Adds a transition listener.
         */
        public void onTransition(Consumer<Transition<STATE, EVENT, SIDE_EFFECT>> listener) {
            onTransitionListeners.add(listener);
        }

        void addStaticTransition(Matcher<STATE, STATE> stateMatcher, Class<? extends EVENT> eventClass, STATE targetState) {
            useStaticTransitions = true;
            staticTransitions.computeIfAbsent(stateMatcher, k -> new LinkedHashMap<>())
                    .put(eventClass, targetState);
        }

        /**
         * Builds the graph.
         */
        public Graph<STATE, EVENT, SIDE_EFFECT> build() {
            // TODO: Build the Graph.
            // 1. If initial state is terminal, throw IllegalArgumentException
            // 2. If using static transitions, validate the graph
            // 3. Construct and return the Graph with all state definitions,
            //    transition listeners, terminal state matchers, and static transitions
            throw new UnsupportedOperationException("TODO: implement build");
        }

        private void validateGraph() {
            // TODO: Validate the state machine graph.
            // 1. Check that all static transition targets have definitions
            //    (or are terminal states). Throw IllegalArgumentException if not.
            // 2. Check for unreachable states using BFS from initial state.
            //    Terminal states are exempt from reachability checks.
            //    Throw IllegalArgumentException for unreachable non-terminal states.
            throw new UnsupportedOperationException("TODO: implement validateGraph");
        }

        /**
         * Builder for state definitions.
         */
        public class StateDefinitionBuilder<S extends STATE> {

            private final Graph.State<STATE, EVENT, SIDE_EFFECT> stateDefinition = new Graph.State<>();

            /**
             * Creates a matcher for any event of the given class.
             */
            public <E extends EVENT> Matcher<EVENT, E> any(Class<E> clazz) {
                return Matcher.any(clazz);
            }

            /**
             * Creates a matcher for a specific event value.
             */
            public <E extends EVENT> Matcher<EVENT, E> eq(E value) {
                return Matcher.eq(value);
            }

            /**
             * Defines an event handler with a matcher.
             */
            public <E extends EVENT> StateDefinitionBuilder<S> on(
                    Matcher<EVENT, E> eventMatcher,
                    BiFunction<S, E, Graph.State.TransitionTo<STATE, SIDE_EFFECT>> createTransitionTo) {
                @SuppressWarnings("unchecked")
                Matcher<EVENT, EVENT> matcher = (Matcher<EVENT, EVENT>) eventMatcher;
                stateDefinition.transitions.put(matcher, (state, event) -> {
                    @SuppressWarnings("unchecked")
                    S s = (S) state;
                    @SuppressWarnings("unchecked")
                    E e = (E) event;
                    return createTransitionTo.apply(s, e);
                });
                return this;
            }

            /**
             * Defines an event handler for any event of the given class.
             */
            public <E extends EVENT> StateDefinitionBuilder<S> on(
                    Class<E> clazz,
                    BiFunction<S, E, Graph.State.TransitionTo<STATE, SIDE_EFFECT>> createTransitionTo) {
                on(any(clazz), createTransitionTo);
                return this;
            }

            /**
             * Defines an event handler for a specific event value.
             */
            public <E extends EVENT> StateDefinitionBuilder<S> on(
                    E event,
                    BiFunction<S, E, Graph.State.TransitionTo<STATE, SIDE_EFFECT>> createTransitionTo) {
                on(eq(event), createTransitionTo);
                return this;
            }

            /**
             * Defines a static transition for graph validation.
             */
            public <E extends EVENT> StateDefinitionBuilder<S> transition(Class<E> eventClass, STATE targetState) {
                // Find the matcher for this state definition
                Matcher<STATE, STATE> stateMatcher = null;
                for (Map.Entry<Matcher<STATE, STATE>, Graph.State<STATE, EVENT, SIDE_EFFECT>> entry : stateDefinitions.entrySet()) {
                    if (entry.getValue() == stateDefinition) {
                        stateMatcher = entry.getKey();
                        break;
                    }
                }
                if (stateMatcher != null) {
                    addStaticTransition(stateMatcher, eventClass, targetState);
                }
                return this;
            }

            /**
             * Adds an onEnter listener.
             */
            public StateDefinitionBuilder<S> onEnter(BiConsumer<S, EVENT> listener) {
                stateDefinition.onEnterListeners.add((state, event) -> {
                    @SuppressWarnings("unchecked")
                    S s = (S) state;
                    listener.accept(s, event);
                });
                return this;
            }

            /**
             * Adds an onExit listener.
             */
            public StateDefinitionBuilder<S> onExit(BiConsumer<S, EVENT> listener) {
                stateDefinition.onExitListeners.add((state, event) -> {
                    @SuppressWarnings("unchecked")
                    S s = (S) state;
                    listener.accept(s, event);
                });
                return this;
            }

            /**
             * Adds an onCleanUp listener.
             */
            public StateDefinitionBuilder<S> onCleanUp(BiConsumer<S, EVENT> listener) {
                stateDefinition.onCleanUpListeners.add((state, event) -> {
                    @SuppressWarnings("unchecked")
                    S s = (S) state;
                    listener.accept(s, event);
                });
                return this;
            }

            /**
             * Sets a factory function for creating fresh state instances.
             */
            public StateDefinitionBuilder<S> factory(Function<STATE, STATE> factory) {
                stateDefinition.setFactory(factory);
                return this;
            }

            Graph.State<STATE, EVENT, SIDE_EFFECT> build() {
                return stateDefinition;
            }

            /**
             * Creates a transition to the given state with an optional side effect.
             */
            public Graph.State.TransitionTo<STATE, SIDE_EFFECT> transitionTo(STATE state, SIDE_EFFECT sideEffect) {
                return new Graph.State.TransitionTo<>(state, sideEffect);
            }

            /**
             * Creates a transition to the given state with no side effect.
             */
            public Graph.State.TransitionTo<STATE, SIDE_EFFECT> transitionTo(STATE state) {
                return transitionTo(state, null);
            }

            /**
             * Creates a transition that stays in the same state with an optional side effect.
             * Note: In Java, you need to pass the current state explicitly.
             * In Kotlin, this is an extension function on S so 'this' refers to the state.
             */
            public Graph.State.TransitionTo<STATE, SIDE_EFFECT> dontTransition(S state, SIDE_EFFECT sideEffect) {
                return transitionTo(state, sideEffect);
            }

            /**
             * Creates a transition that stays in the same state with no side effect.
             */
            public Graph.State.TransitionTo<STATE, SIDE_EFFECT> dontTransition(S state) {
                return dontTransition(state, null);
            }
        }
    }

    /**
     * Creates a new state machine with the given initializer.
     */
    public static <STATE, EVENT, SIDE_EFFECT> StateMachine<STATE, EVENT, SIDE_EFFECT> create(
            Consumer<GraphBuilder<STATE, EVENT, SIDE_EFFECT>> init) {
        return create(null, init);
    }

    private static <STATE, EVENT, SIDE_EFFECT> StateMachine<STATE, EVENT, SIDE_EFFECT> create(
            Graph<STATE, EVENT, SIDE_EFFECT> graph,
            Consumer<GraphBuilder<STATE, EVENT, SIDE_EFFECT>> init) {
        GraphBuilder<STATE, EVENT, SIDE_EFFECT> builder = new GraphBuilder<>(graph);
        init.accept(builder);
        return new StateMachine<>(builder.build());
    }
}
