package com.tinder

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.experimental.runners.Enclosed
import org.junit.runner.RunWith

@RunWith(Enclosed::class)
internal class ManagedStateTest {

    class CleanupOnTransition {

        companion object {
            sealed class State {
                class Active(var cleanedUp: Boolean = false) : State(), ManagedState {
                    override fun onCleanUp() {
                        cleanedUp = true
                    }
                }

                object Idle : State()
            }

            sealed class Event {
                object Deactivate : Event()
                object Activate : Event()
            }
        }

        @Test
        fun transition_shouldCallOnCleanUpOnOldState() {
            // Given
            val activeState = State.Active()
            val stateMachine = StateMachine.create<State, Event, Nothing> {
                initialState(activeState)
                state<State.Active> {
                    on<Event.Deactivate> {
                        transitionTo(State.Idle)
                    }
                }
                state<State.Idle> {
                    on<Event.Activate> {
                        transitionTo(State.Active())
                    }
                }
            }

            // When
            stateMachine.transition(Event.Deactivate)

            // Then
            assertThat(activeState.cleanedUp).isTrue()
        }

        @Test
        fun dontTransition_shouldNotCallOnCleanUp() {
            // Given
            val activeState = State.Active()
            val stateMachine = StateMachine.create<State, Event, Nothing> {
                initialState(activeState)
                state<State.Active> {
                    on<Event.Deactivate> {
                        dontTransition()
                    }
                }
                state<State.Idle> {}
            }

            // When
            stateMachine.transition(Event.Deactivate)

            // Then
            assertThat(activeState.cleanedUp).isFalse()
        }

        @Test
        fun transition_shouldNotCallOnCleanUpForNonManagedStates() {
            // Given — states that don't implement ManagedState
            val stateMachine = StateMachine.create<String, Int, Nothing> {
                initialState("a")
                state("a") {
                    on(1) { transitionTo("b") }
                }
                state("b") {}
            }

            // When/Then — no exception, transition works normally
            val transition = stateMachine.transition(1)
            assertThat(transition).isInstanceOf(StateMachine.Transition.Valid::class.java)
            assertThat(stateMachine.state).isEqualTo("b")
        }

        @Test
        fun invalidTransition_shouldNotCallOnCleanUp() {
            // Given
            val activeState = State.Active()
            val stateMachine = StateMachine.create<State, Event, Nothing> {
                initialState(activeState)
                state<State.Active> {
                    on<Event.Deactivate> {
                        transitionTo(State.Idle)
                    }
                }
                state<State.Idle> {}
            }

            // When — Activate is not registered for Active state
            val transition = stateMachine.transition(Event.Activate)

            // Then
            assertThat(transition).isInstanceOf(StateMachine.Transition.Invalid::class.java)
            assertThat(activeState.cleanedUp).isFalse()
        }
    }

    class CleanupOrdering {

        companion object {
            val events = mutableListOf<String>()

            sealed class State {
                class First : State(), ManagedState {
                    override fun onCleanUp() {
                        events.add("cleanup:first")
                    }
                }

                class Second : State(), ManagedState {
                    override fun onCleanUp() {
                        events.add("cleanup:second")
                    }
                }
            }

            sealed class Event {
                object Next : Event()
            }
        }

        @Test
        fun cleanup_shouldHappenBeforeNewStateIsSet() {
            // Given
            events.clear()
            val first = State.First()
            var stateAtCleanupTime: State? = null

            val stateMachine = StateMachine.create<State, Event, Nothing> {
                initialState(first)
                state<State.First> {
                    on<Event.Next> {
                        transitionTo(State.Second())
                    }
                }
                state<State.Second> {}
                onTransition {
                    if (it is StateMachine.Transition.Valid) {
                        events.add("transition:${it.fromState::class.simpleName}->${it.toState::class.simpleName}")
                    }
                }
            }

            // When
            stateMachine.transition(Event.Next)

            // Then — cleanup happens, then transition notification
            assertThat(events).containsExactly(
                "cleanup:first",
                "transition:First->Second"
            )
        }
    }

    class FactorySupport {

        companion object {
            var factoryCallCount = 0

            sealed class State {
                class Connected(val connectionId: Int) : State(), ManagedState {
                    var cleanedUp = false
                    override fun onCleanUp() {
                        cleanedUp = true
                    }
                }

                object Disconnected : State()
            }

            sealed class Event {
                object Connect : Event()
                object Disconnect : Event()
            }
        }

        @Test
        fun factory_shouldCreateFreshInstanceOnTransition() {
            // Given
            factoryCallCount = 0
            val stateMachine = StateMachine.create<State, Event, Nothing> {
                initialState(State.Disconnected)
                state<State.Disconnected> {
                    on<Event.Connect> {
                        transitionTo(State.Connected(1))
                    }
                }
                state<State.Connected> {
                    factory { intended ->
                        factoryCallCount++
                        State.Connected(intended.connectionId * 10)
                    }
                    on<Event.Disconnect> {
                        transitionTo(State.Disconnected)
                    }
                }
            }

            // When
            stateMachine.transition(Event.Connect)

            // Then — factory was called and produced a different instance
            assertThat(factoryCallCount).isEqualTo(1)
            val state = stateMachine.state as State.Connected
            assertThat(state.connectionId).isEqualTo(10) // factory multiplied by 10
        }

        @Test
        fun factory_shouldCreateNewInstanceEachTime() {
            // Given
            factoryCallCount = 0
            val stateMachine = StateMachine.create<State, Event, Nothing> {
                initialState(State.Disconnected)
                state<State.Disconnected> {
                    on<Event.Connect> {
                        transitionTo(State.Connected(1))
                    }
                }
                state<State.Connected> {
                    factory { intended -> State.Connected(intended.connectionId) }
                    on<Event.Disconnect> {
                        transitionTo(State.Disconnected)
                    }
                }
            }

            // When — transition to Connected, back to Disconnected, then to Connected again
            stateMachine.transition(Event.Connect)
            val firstInstance = stateMachine.state

            stateMachine.transition(Event.Disconnect)
            stateMachine.transition(Event.Connect)
            val secondInstance = stateMachine.state

            // Then — each transition created a new instance
            assertThat(firstInstance).isNotSameAs(secondInstance)
        }

        @Test
        fun factory_shouldReceiveIntendedStateFromTransitionTo() {
            // Given
            var receivedConnectionId: Int? = null
            val stateMachine = StateMachine.create<State, Event, Nothing> {
                initialState(State.Disconnected)
                state<State.Disconnected> {
                    on<Event.Connect> {
                        transitionTo(State.Connected(42))
                    }
                }
                state<State.Connected> {
                    factory { intended ->
                        receivedConnectionId = intended.connectionId
                        State.Connected(intended.connectionId)
                    }
                    on<Event.Disconnect> {
                        transitionTo(State.Disconnected)
                    }
                }
            }

            // When
            stateMachine.transition(Event.Connect)

            // Then
            assertThat(receivedConnectionId).isEqualTo(42)
        }

        @Test
        fun noFactory_shouldUseTransitionToValueDirectly() {
            // Given — no factory registered
            val stateMachine = StateMachine.create<State, Event, Nothing> {
                initialState(State.Disconnected)
                state<State.Disconnected> {
                    on<Event.Connect> {
                        transitionTo(State.Connected(99))
                    }
                }
                state<State.Connected> {
                    // no factory() call
                    on<Event.Disconnect> {
                        transitionTo(State.Disconnected)
                    }
                }
            }

            // When
            stateMachine.transition(Event.Connect)

            // Then — the exact value from transitionTo is used
            val state = stateMachine.state as State.Connected
            assertThat(state.connectionId).isEqualTo(99)
        }
    }

    class CleanupAndFactoryTogether {

        companion object {
            sealed class State {
                class ResourceHolder(val name: String) : State(), ManagedState {
                    var resource: String? = "active-resource"
                    var cleanedUp = false

                    override fun onCleanUp() {
                        resource = null
                        cleanedUp = true
                    }
                }

                object Empty : State()
            }

            sealed class Event {
                object ToHolder : Event()
                object ToEmpty : Event()
                object Swap : Event()
            }
        }

        @Test
        fun oldState_shouldBeCleanedUp_andNewState_shouldBeFresh() {
            // Given
            val stateMachine = StateMachine.create<State, Event, Nothing> {
                initialState(State.ResourceHolder("first"))
                state<State.ResourceHolder> {
                    factory { intended -> State.ResourceHolder(intended.name) }
                    on<Event.Swap> {
                        transitionTo(State.ResourceHolder("second"))
                    }
                    on<Event.ToEmpty> {
                        transitionTo(State.Empty)
                    }
                }
                state<State.Empty> {
                    on<Event.ToHolder> {
                        transitionTo(State.ResourceHolder("revived"))
                    }
                }
            }

            // Capture initial state
            val firstState = stateMachine.state as State.ResourceHolder
            assertThat(firstState.resource).isEqualTo("active-resource")

            // When — swap to a new ResourceHolder
            stateMachine.transition(Event.Swap)

            // Then — old state cleaned up, new state is fresh
            assertThat(firstState.cleanedUp).isTrue()
            assertThat(firstState.resource).isNull()

            val secondState = stateMachine.state as State.ResourceHolder
            assertThat(secondState.name).isEqualTo("second")
            assertThat(secondState.resource).isEqualTo("active-resource")
            assertThat(secondState.cleanedUp).isFalse()
            assertThat(secondState).isNotSameAs(firstState)
        }

        @Test
        fun transitionResult_shouldReflectFactoryCreatedState() {
            // Given
            val stateMachine = StateMachine.create<State, Event, Nothing> {
                initialState(State.Empty)
                state<State.Empty> {
                    on<Event.ToHolder> {
                        transitionTo(State.ResourceHolder("original"))
                    }
                }
                state<State.ResourceHolder> {
                    factory { _ -> State.ResourceHolder("from-factory") }
                    on<Event.ToEmpty> {
                        transitionTo(State.Empty)
                    }
                }
            }

            // When
            val transition = stateMachine.transition(Event.ToHolder)

            // Then — transition.toState should be the factory-created instance
            val valid = transition as StateMachine.Transition.Valid
            val toState = valid.toState as State.ResourceHolder
            assertThat(toState.name).isEqualTo("from-factory")
            assertThat(stateMachine.state).isSameAs(toState)
        }

        @Test
        fun multipleTransitions_shouldCleanUpEachPreviousState() {
            // Given
            val allStates = mutableListOf<State.ResourceHolder>()

            val stateMachine = StateMachine.create<State, Event, Nothing> {
                initialState(State.ResourceHolder("a"))
                state<State.ResourceHolder> {
                    factory { intended ->
                        val state = State.ResourceHolder(intended.name)
                        allStates.add(state)
                        state
                    }
                    on<Event.Swap> {
                        transitionTo(State.ResourceHolder("next"))
                    }
                    on<Event.ToEmpty> {
                        transitionTo(State.Empty)
                    }
                }
                state<State.Empty> {}
            }

            // Capture initial state (not factory-created)
            val initial = stateMachine.state as State.ResourceHolder

            // When — transition through multiple states
            stateMachine.transition(Event.Swap)
            stateMachine.transition(Event.Swap)
            stateMachine.transition(Event.ToEmpty)

            // Then — initial state and each factory-created state was cleaned up
            assertThat(initial.cleanedUp).isTrue()
            assertThat(allStates).hasSize(2)
            assertThat(allStates[0].cleanedUp).isTrue()
            assertThat(allStates[1].cleanedUp).isTrue()
        }
    }
}
