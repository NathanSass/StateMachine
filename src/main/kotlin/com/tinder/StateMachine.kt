package com.tinder

import java.util.concurrent.atomic.AtomicReference

class StateMachine<STATE : Any, EVENT : Any, SIDE_EFFECT : Any> private constructor(
    private val graph: Graph<STATE, EVENT, SIDE_EFFECT>
) {

    private val stateRef = AtomicReference<STATE>(graph.initialState)

    val state: STATE
        get() = stateRef.get()

    fun transition(event: EVENT): Transition<STATE, EVENT, SIDE_EFFECT> {
        val transition = synchronized(this) {
            val fromState = stateRef.get()
            val transition = fromState.getTransition(event)
            if (transition is Transition.Valid) {
                val isRealTransition = fromState !== transition.toState
                // Clean up old state before creating new one
                if (isRealTransition) {
                    fromState.notifyOnCleanUp(event)
                }
                // Use factory if available for target state
                val toState = if (isRealTransition) {
                    resolveState(transition.toState)
                } else {
                    transition.toState
                }
                stateRef.set(toState)
                if (toState !== transition.toState) {
                    Transition.Valid(fromState, event, toState, transition.sideEffect)
                } else {
                    transition
                }
            } else {
                transition
            }
        }
        transition.notifyOnTransition()
        if (transition is Transition.Valid) {
            with(transition) {
                with(fromState) {
                    notifyOnExit(event)
                }
                with(toState) {
                    notifyOnEnter(event)
                }
            }
        }
        return transition
    }

    fun with(init: GraphBuilder<STATE, EVENT, SIDE_EFFECT>.() -> Unit): StateMachine<STATE, EVENT, SIDE_EFFECT> {
        return create(graph.copy(initialState = state), init)
    }

    private fun STATE.getTransition(event: EVENT): Transition<STATE, EVENT, SIDE_EFFECT> {
        for ((eventMatcher, createTransitionTo) in getDefinition().transitions) {
            if (eventMatcher.matches(event)) {
                val (toState, sideEffect) = createTransitionTo(this, event)
                return Transition.Valid(this, event, toState, sideEffect)
            }
        }
        return Transition.Invalid(this, event)
    }

    private fun STATE.getDefinition() = graph.stateDefinitions
        .filter { it.key.matches(this) }
        .map { it.value }
        .firstOrNull() ?: error("Missing definition for state ${this.javaClass.simpleName}!")

    private fun STATE.getDefinitionOrNull() = graph.stateDefinitions
        .filter { it.key.matches(this) }
        .map { it.value }
        .firstOrNull()

    private fun resolveState(state: STATE): STATE {
        val factory = state.getDefinitionOrNull()?.stateFactory
        return factory?.invoke(state) ?: state
    }

    private fun STATE.notifyOnCleanUp(cause: EVENT) {
        getDefinitionOrNull()?.onCleanUpListeners?.forEach { it(this, cause) }
    }

    private fun STATE.notifyOnEnter(cause: EVENT) {
        getDefinition().onEnterListeners.forEach { it(this, cause) }
    }

    private fun STATE.notifyOnExit(cause: EVENT) {
        getDefinition().onExitListeners.forEach { it(this, cause) }
    }

    private fun Transition<STATE, EVENT, SIDE_EFFECT>.notifyOnTransition() {
        graph.onTransitionListeners.forEach { it(this) }
    }

    @Suppress("UNUSED")
    sealed class Transition<out STATE : Any, out EVENT : Any, out SIDE_EFFECT : Any> {
        abstract val fromState: STATE
        abstract val event: EVENT

        data class Valid<out STATE : Any, out EVENT : Any, out SIDE_EFFECT : Any> internal constructor(
            override val fromState: STATE,
            override val event: EVENT,
            val toState: STATE,
            val sideEffect: SIDE_EFFECT?
        ) : Transition<STATE, EVENT, SIDE_EFFECT>()

        data class Invalid<out STATE : Any, out EVENT : Any, out SIDE_EFFECT : Any> internal constructor(
            override val fromState: STATE,
            override val event: EVENT
        ) : Transition<STATE, EVENT, SIDE_EFFECT>()
    }

    data class Graph<STATE : Any, EVENT : Any, SIDE_EFFECT : Any>(
        val initialState: STATE,
        val stateDefinitions: Map<Matcher<STATE, STATE>, State<STATE, EVENT, SIDE_EFFECT>>,
        val onTransitionListeners: List<(Transition<STATE, EVENT, SIDE_EFFECT>) -> Unit>
    ) {

        class State<STATE : Any, EVENT : Any, SIDE_EFFECT : Any> internal constructor() {
            val onEnterListeners = mutableListOf<(STATE, EVENT) -> Unit>()
            val onExitListeners = mutableListOf<(STATE, EVENT) -> Unit>()
            val onCleanUpListeners = mutableListOf<(STATE, EVENT) -> Unit>()
            val transitions = linkedMapOf<Matcher<EVENT, EVENT>, (STATE, EVENT) -> TransitionTo<STATE, SIDE_EFFECT>>()
            var stateFactory: ((STATE) -> STATE)? = null
            var stateClass: Class<*>? = null
            var isTerminal: Boolean = false
            val targetStateClasses = mutableSetOf<Class<*>>()

            data class TransitionTo<out STATE : Any, out SIDE_EFFECT : Any> internal constructor(
                val toState: STATE,
                val sideEffect: SIDE_EFFECT?
            )
        }
    }

    class Matcher<T : Any, out R : T> private constructor(private val clazz: Class<R>) {

        private val predicates = mutableListOf<(T) -> Boolean>({ clazz.isInstance(it) })

        fun where(predicate: R.() -> Boolean): Matcher<T, R> = apply {
            predicates.add {
                @Suppress("UNCHECKED_CAST")
                (it as R).predicate()
            }
        }

        fun matches(value: T) = predicates.all { it(value) }

        companion object {
            fun <T : Any, R : T> any(clazz: Class<R>): Matcher<T, R> = Matcher(clazz)

            inline fun <T : Any, reified R : T> any(): Matcher<T, R> = any(R::class.java)

            inline fun <T : Any, reified R : T> eq(value: R): Matcher<T, R> = any<T, R>().where { this == value }
        }
    }

    class GraphBuilder<STATE : Any, EVENT : Any, SIDE_EFFECT : Any>(
        graph: Graph<STATE, EVENT, SIDE_EFFECT>? = null
    ) {
        private var initialState = graph?.initialState
        private val stateDefinitions = LinkedHashMap(graph?.stateDefinitions ?: emptyMap())
        private val onTransitionListeners = ArrayList(graph?.onTransitionListeners ?: emptyList())

        fun initialState(initialState: STATE) {
            this.initialState = initialState
        }

        fun <S : STATE> state(
            stateMatcher: Matcher<STATE, S>,
            init: StateDefinitionBuilder<S>.() -> Unit
        ) {
            stateDefinitions[stateMatcher] = StateDefinitionBuilder<S>().apply(init).build()
        }

        @PublishedApi
        internal fun <S : STATE> registerState(
            stateMatcher: Matcher<STATE, S>,
            stateClass: Class<*>,
            init: StateDefinitionBuilder<S>.() -> Unit
        ) {
            val definition = StateDefinitionBuilder<S>().apply(init).build()
            definition.stateClass = stateClass
            stateDefinitions[stateMatcher] = definition
        }

        inline fun <reified S : STATE> state(noinline init: StateDefinitionBuilder<S>.() -> Unit) {
            registerState(Matcher.any(), S::class.java, init)
        }

        inline fun <reified S : STATE> state(state: S, noinline init: StateDefinitionBuilder<S>.() -> Unit) {
            registerState(Matcher.eq<STATE, S>(state), S::class.java, init)
        }

        @PublishedApi
        internal fun <S : STATE> registerTerminalState(
            stateMatcher: Matcher<STATE, S>,
            stateClass: Class<*>,
            init: (StateDefinitionBuilder<S>.() -> Unit)?
        ) {
            val definition = if (init != null) {
                StateDefinitionBuilder<S>().apply(init).build()
            } else {
                Graph.State<STATE, EVENT, SIDE_EFFECT>()
            }
            definition.stateClass = stateClass
            definition.isTerminal = true
            stateDefinitions[stateMatcher] = definition
        }

        inline fun <reified S : STATE> terminalState() {
            registerTerminalState<S>(Matcher.any(), S::class.java, null)
        }

        inline fun <reified S : STATE> terminalState(noinline init: StateDefinitionBuilder<S>.() -> Unit) {
            registerTerminalState(Matcher.any(), S::class.java, init)
        }

        fun onTransition(listener: (Transition<STATE, EVENT, SIDE_EFFECT>) -> Unit) {
            onTransitionListeners.add(listener)
        }

        fun build(): Graph<STATE, EVENT, SIDE_EFFECT> {
            val init = requireNotNull(initialState)

            val allTargets = stateDefinitions.values.flatMap { it.targetStateClasses }.toSet()
            if (allTargets.isNotEmpty()) {
                val registeredClasses = stateDefinitions.values.mapNotNull { it.stateClass }.toSet()
                val terminalClasses = stateDefinitions.values
                    .filter { it.isTerminal }
                    .mapNotNull { it.stateClass }
                    .toSet()

                // Cannot start in a terminal state
                require(init::class.java !in terminalClasses) {
                    "Cannot use terminal state ${init::class.java.simpleName} as initial state"
                }

                // All transition targets must have registered state definitions
                val missing = allTargets - registeredClasses
                require(missing.isEmpty()) {
                    "Missing state definitions for transition targets: ${missing.joinToString { it.simpleName ?: it.name }}"
                }

                // Initial state must have a registered definition
                require(init::class.java in registeredClasses) {
                    "Initial state ${init::class.java.simpleName} has no registered definition"
                }

                // All registered states must be reachable from the initial state
                // Terminal states are exempt — they are valid sinks reachable from any state
                val reachable = findReachableStates(init::class.java)
                val unreachable = registeredClasses - reachable - terminalClasses
                require(unreachable.isEmpty()) {
                    "Unreachable states: ${unreachable.joinToString { it.simpleName ?: it.name }}"
                }
            }

            return Graph(init, stateDefinitions.toMap(), onTransitionListeners.toList())
        }

        private fun findReachableStates(initialClass: Class<*>): Set<Class<*>> {
            val classToTargets = mutableMapOf<Class<*>, Set<Class<*>>>()
            for (definition in stateDefinitions.values) {
                val cls = definition.stateClass ?: continue
                classToTargets[cls] = definition.targetStateClasses
            }
            val visited = mutableSetOf<Class<*>>()
            val queue = ArrayDeque<Class<*>>()
            queue.add(initialClass)
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                if (!visited.add(current)) continue
                classToTargets[current]?.let { queue.addAll(it) }
            }
            return visited
        }

        inner class StateDefinitionBuilder<S : STATE> {

            private val stateDefinition = Graph.State<STATE, EVENT, SIDE_EFFECT>()

            inline fun <reified E : EVENT> any(): Matcher<EVENT, E> = Matcher.any()

            inline fun <reified R : EVENT> eq(value: R): Matcher<EVENT, R> = Matcher.eq(value)

            fun <E : EVENT> on(
                eventMatcher: Matcher<EVENT, E>,
                createTransitionTo: S.(E) -> Graph.State.TransitionTo<STATE, SIDE_EFFECT>
            ) {
                stateDefinition.transitions[eventMatcher] = { state, event ->
                    @Suppress("UNCHECKED_CAST")
                    createTransitionTo((state as S), event as E)
                }
            }

            inline fun <reified E : EVENT> on(
                noinline createTransitionTo: S.(E) -> Graph.State.TransitionTo<STATE, SIDE_EFFECT>
            ) {
                return on(any(), createTransitionTo)
            }

            inline fun <reified E : EVENT> on(
                event: E,
                noinline createTransitionTo: S.(E) -> Graph.State.TransitionTo<STATE, SIDE_EFFECT>
            ) {
                return on(eq(event), createTransitionTo)
            }

            fun onEnter(listener: S.(EVENT) -> Unit) = with(stateDefinition) {
                onEnterListeners.add { state, cause ->
                    @Suppress("UNCHECKED_CAST")
                    listener(state as S, cause)
                }
            }

            fun onExit(listener: S.(EVENT) -> Unit) = with(stateDefinition) {
                onExitListeners.add { state, cause ->
                    @Suppress("UNCHECKED_CAST")
                    listener(state as S, cause)
                }
            }

            fun onCleanUp(listener: S.(EVENT) -> Unit) = with(stateDefinition) {
                onCleanUpListeners.add { state, cause ->
                    @Suppress("UNCHECKED_CAST")
                    listener(state as S, cause)
                }
            }

            @PublishedApi
            internal fun addTargetStateClass(targetClass: Class<*>) {
                stateDefinition.targetStateClasses.add(targetClass)
            }

            inline fun <reified E : EVENT, reified T : STATE> transition(
                targetState: T,
                sideEffect: SIDE_EFFECT? = null
            ) {
                addTargetStateClass(T::class.java)
                on<E> { transitionTo(targetState, sideEffect) }
            }

            fun factory(create: (S) -> S) {
                stateDefinition.stateFactory = { state ->
                    @Suppress("UNCHECKED_CAST")
                    create(state as S)
                }
            }

            fun build() = stateDefinition

            @Suppress("UNUSED") // The unused warning is probably a compiler bug.
            fun S.transitionTo(state: STATE, sideEffect: SIDE_EFFECT? = null) =
                Graph.State.TransitionTo(state, sideEffect)

            @Suppress("UNUSED") // The unused warning is probably a compiler bug.
            fun S.dontTransition(sideEffect: SIDE_EFFECT? = null) = transitionTo(this, sideEffect)
        }
    }

    companion object {
        fun <STATE : Any, EVENT : Any, SIDE_EFFECT : Any> create(
            init: GraphBuilder<STATE, EVENT, SIDE_EFFECT>.() -> Unit
        ): StateMachine<STATE, EVENT, SIDE_EFFECT> {
            return create(null, init)
        }

        private fun <STATE : Any, EVENT : Any, SIDE_EFFECT : Any> create(
            graph: Graph<STATE, EVENT, SIDE_EFFECT>?,
            init: GraphBuilder<STATE, EVENT, SIDE_EFFECT>.() -> Unit
        ): StateMachine<STATE, EVENT, SIDE_EFFECT> {
            return StateMachine(GraphBuilder(graph).apply(init).build())
        }
    }
}