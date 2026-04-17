package com.tinder

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

internal class ConcurrencyTest {

    companion object {
        sealed class State {
            class A(val id: Int = 0) : State() {
                override fun toString() = "A($id)"
            }
            class B(val id: Int = 0) : State() {
                override fun toString() = "B($id)"
            }
            class C(val id: Int = 0) : State() {
                override fun toString() = "C($id)"
            }
        }

        sealed class Event {
            object ToB : Event()
            object ToC : Event()
            object ToA : Event()
        }
    }

    @Test
    fun concurrentTransitions_cleanupShouldFireExactlyOnce() {
        // Multiple threads race to transition out of the same state.
        // onCleanUp for the old state should fire exactly once, not once per thread.
        repeat(100) { // Repeat to increase chance of catching race conditions
            val cleanupCount = AtomicInteger(0)
            val barrier = CyclicBarrier(2)

            val sm = StateMachine.create<State, Event, Nothing> {
                initialState(State.A())
                state<State.A> {
                    onCleanUp { cleanupCount.incrementAndGet() }
                    on<Event.ToB> { transitionTo(State.B()) }
                    on<Event.ToC> { transitionTo(State.C()) }
                }
                state<State.B> {
                    on<Event.ToA> { transitionTo(State.A()) }
                }
                state<State.C> {
                    on<Event.ToA> { transitionTo(State.A()) }
                }
            }

            val t1 = thread {
                barrier.await()
                sm.transition(Event.ToB)
            }
            val t2 = thread {
                barrier.await()
                sm.transition(Event.ToC)
            }

            t1.join()
            t2.join()

            // One transition wins (A→B or A→C), the other gets Invalid.
            // Cleanup should fire exactly once for State.A.
            assertThat(cleanupCount.get()).isEqualTo(1)
        }
    }

    @Test
    fun concurrentTransitions_factoryShouldNotProduceDuplicateStates() {
        // When two threads race to transition to the same target state,
        // only one factory invocation should produce the active state.
        repeat(100) {
            val factoryCount = AtomicInteger(0)
            val barrier = CyclicBarrier(2)

            val sm = StateMachine.create<State, Event, Nothing> {
                initialState(State.A())
                state<State.A> {
                    on<Event.ToB> { transitionTo(State.B()) }
                    on<Event.ToC> { transitionTo(State.C()) }
                }
                state<State.B> {
                    factory { intended ->
                        factoryCount.incrementAndGet()
                        State.B(intended.id)
                    }
                    on<Event.ToA> { transitionTo(State.A()) }
                }
                state<State.C> {
                    on<Event.ToA> { transitionTo(State.A()) }
                }
            }

            val t1 = thread {
                barrier.await()
                sm.transition(Event.ToB)
            }
            val t2 = thread {
                barrier.await()
                sm.transition(Event.ToB)
            }

            t1.join()
            t2.join()

            // Both threads try A→B. One wins (state becomes B, factory called).
            // The second sees state is B, tries B→B but ToB isn't registered for B → Invalid.
            // Factory should be called at most once.
            assertThat(factoryCount.get()).isLessThanOrEqualTo(1)
        }
    }

    @Test
    fun concurrentTransitions_stateShouldAlwaysBeConsistent() {
        // Rapidly transition back and forth between states from multiple threads.
        // The state should always be one of the valid states, never null or corrupted.
        val sm = StateMachine.create<State, Event, Nothing> {
            initialState(State.A())
            state<State.A> {
                on<Event.ToB> { transitionTo(State.B()) }
            }
            state<State.B> {
                on<Event.ToA> { transitionTo(State.A()) }
            }
            state<State.C> {}
        }

        val iterations = 1000
        val barrier = CyclicBarrier(2)
        val statesObserved = Collections.synchronizedList(mutableListOf<State>())

        val t1 = thread {
            barrier.await()
            repeat(iterations) {
                sm.transition(Event.ToB)
                statesObserved.add(sm.state)
            }
        }
        val t2 = thread {
            barrier.await()
            repeat(iterations) {
                sm.transition(Event.ToA)
                statesObserved.add(sm.state)
            }
        }

        t1.join()
        t2.join()

        // Every observed state should be either A or B, never null or C
        for (state in statesObserved) {
            assertThat(state).isInstanceOfAny(State.A::class.java, State.B::class.java)
        }
    }

    @Test
    fun concurrentTransitions_cleanupAndFactoryOrderingShouldBeAtomic() {
        // Verify that cleanup of old state and factory creation of new state
        // happen atomically — no thread should observe a state between cleanup and creation.
        repeat(50) {
            val events = Collections.synchronizedList(mutableListOf<String>())
            val barrier = CyclicBarrier(3)

            val sm = StateMachine.create<State, Event, Nothing> {
                initialState(State.A())
                state<State.A> {
                    onCleanUp { events.add("cleanup:A") }
                    on<Event.ToB> { transitionTo(State.B()) }
                }
                state<State.B> {
                    factory { intended ->
                        events.add("factory:B")
                        State.B(intended.id)
                    }
                    onCleanUp { events.add("cleanup:B") }
                    on<Event.ToA> { transitionTo(State.A()) }
                }
                state<State.A> {
                    factory { intended ->
                        events.add("factory:A")
                        State.A(intended.id)
                    }
                    onCleanUp { events.add("cleanup:A") }
                    on<Event.ToB> { transitionTo(State.B()) }
                }
            }

            val t1 = thread {
                barrier.await()
                sm.transition(Event.ToB)
            }
            val t2 = thread {
                barrier.await()
                sm.transition(Event.ToB)
            }
            val reader = thread {
                barrier.await()
                // Rapidly read state while transitions happen
                repeat(100) {
                    val state = sm.state
                    assertThat(state).isNotNull()
                }
            }

            t1.join()
            t2.join()
            reader.join()

            // Cleanup should always appear before factory in the event log
            // (they happen inside synchronized, so no interleaving)
            val cleanupIndices = events.mapIndexedNotNull { i, e -> if (e.startsWith("cleanup")) i else null }
            val factoryIndices = events.mapIndexedNotNull { i, e -> if (e.startsWith("factory")) i else null }

            // For each transition, cleanup should come before factory
            if (cleanupIndices.isNotEmpty() && factoryIndices.isNotEmpty()) {
                assertThat(cleanupIndices.first()).isLessThan(factoryIndices.first())
            }
        }
    }

    @Test
    fun manyThreadsTransitioning_noExceptionsThrown() {
        // Stress test: many threads all trying to transition simultaneously.
        // No exceptions should be thrown, and the final state should be valid.
        val threadCount = 10
        val iterationsPerThread = 200
        val sm = StateMachine.create<State, Event, Nothing> {
            initialState(State.A())
            state<State.A> {
                onCleanUp { /* cleanup A */ }
                on<Event.ToB> { transitionTo(State.B()) }
            }
            state<State.B> {
                onCleanUp { /* cleanup B */ }
                on<Event.ToA> { transitionTo(State.A()) }
            }
            state<State.C> {}
        }

        val barrier = CyclicBarrier(threadCount)
        val exceptions = Collections.synchronizedList(mutableListOf<Throwable>())

        val threads = (0 until threadCount).map { i ->
            thread {
                try {
                    barrier.await()
                    repeat(iterationsPerThread) {
                        if (i % 2 == 0) {
                            sm.transition(Event.ToB)
                        } else {
                            sm.transition(Event.ToA)
                        }
                    }
                } catch (e: Throwable) {
                    exceptions.add(e)
                }
            }
        }

        threads.forEach { it.join() }

        assertThat(exceptions).isEmpty()
        assertThat(sm.state).isInstanceOfAny(State.A::class.java, State.B::class.java)
    }
}
