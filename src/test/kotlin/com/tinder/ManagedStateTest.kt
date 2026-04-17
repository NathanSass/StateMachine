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
                class Active : State()
                object Idle : State()
            }

            sealed class Event {
                object Deactivate : Event()
                object Activate : Event()
            }
        }

        @Test
        fun transition_shouldCallOnCleanUp() {
            // Given
            var cleanedUp = false
            val stateMachine = StateMachine.create<State, Event, Nothing> {
                initialState(State.Active())
                state<State.Active> {
                    onCleanUp { cleanedUp = true }
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
            assertThat(cleanedUp).isTrue()
        }

        @Test
        fun dontTransition_shouldNotCallOnCleanUp() {
            // Given
            var cleanedUp = false
            val stateMachine = StateMachine.create<State, Event, Nothing> {
                initialState(State.Active())
                state<State.Active> {
                    onCleanUp { cleanedUp = true }
                    on<Event.Deactivate> {
                        dontTransition()
                    }
                }
                state<State.Idle> {}
            }

            // When
            stateMachine.transition(Event.Deactivate)

            // Then
            assertThat(cleanedUp).isFalse()
        }

        @Test
        fun invalidTransition_shouldNotCallOnCleanUp() {
            // Given
            var cleanedUp = false
            val stateMachine = StateMachine.create<State, Event, Nothing> {
                initialState(State.Active())
                state<State.Active> {
                    onCleanUp { cleanedUp = true }
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
            assertThat(cleanedUp).isFalse()
        }

        @Test
        fun statesWithoutCleanUp_shouldTransitionNormally() {
            // Given — no onCleanUp registered
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
        fun onCleanUp_shouldReceiveTheCausingEvent() {
            // Given
            var receivedEvent: Event? = null
            val stateMachine = StateMachine.create<State, Event, Nothing> {
                initialState(State.Active())
                state<State.Active> {
                    onCleanUp { event -> receivedEvent = event }
                    on<Event.Deactivate> {
                        transitionTo(State.Idle)
                    }
                }
                state<State.Idle> {}
            }

            // When
            stateMachine.transition(Event.Deactivate)

            // Then
            assertThat(receivedEvent).isEqualTo(Event.Deactivate)
        }
    }

    class CleanupOrdering {

        companion object {
            sealed class State {
                class First : State()
                class Second : State()
            }

            sealed class Event {
                object Next : Event()
            }
        }

        @Test
        fun onCleanUp_shouldFireBeforeOnExit() {
            // Given
            val events = mutableListOf<String>()
            val stateMachine = StateMachine.create<State, Event, Nothing> {
                initialState(State.First())
                state<State.First> {
                    onCleanUp { events.add("cleanup") }
                    onExit { events.add("exit") }
                    on<Event.Next> {
                        transitionTo(State.Second())
                    }
                }
                state<State.Second> {}
            }

            // When
            stateMachine.transition(Event.Next)

            // Then — cleanup fires before exit
            assertThat(events).containsExactly("cleanup", "exit")
        }

        @Test
        fun onCleanUp_shouldFireBeforeOnEnterOfNewState() {
            // Given
            val events = mutableListOf<String>()
            val stateMachine = StateMachine.create<State, Event, Nothing> {
                initialState(State.First())
                state<State.First> {
                    onCleanUp { events.add("cleanup:first") }
                    on<Event.Next> {
                        transitionTo(State.Second())
                    }
                }
                state<State.Second> {
                    onEnter { events.add("enter:second") }
                }
            }

            // When
            stateMachine.transition(Event.Next)

            // Then
            assertThat(events).containsExactly("cleanup:first", "enter:second")
        }

        @Test
        fun onCleanUp_shouldFireBeforeOnTransitionListener() {
            // Given
            val events = mutableListOf<String>()
            val stateMachine = StateMachine.create<State, Event, Nothing> {
                initialState(State.First())
                state<State.First> {
                    onCleanUp { events.add("cleanup") }
                    on<Event.Next> {
                        transitionTo(State.Second())
                    }
                }
                state<State.Second> {}
                onTransition { events.add("transition") }
            }

            // When
            stateMachine.transition(Event.Next)

            // Then
            assertThat(events).containsExactly("cleanup", "transition")
        }

        @Test
        fun onExit_shouldFireOnDontTransition_butOnCleanUpShouldNot() {
            // Given
            val events = mutableListOf<String>()
            val stateMachine = StateMachine.create<State, Event, Nothing> {
                initialState(State.First())
                state<State.First> {
                    onCleanUp { events.add("cleanup") }
                    onExit { events.add("exit") }
                    on<Event.Next> {
                        dontTransition()
                    }
                }
                state<State.Second> {}
            }

            // When
            stateMachine.transition(Event.Next)

            // Then — onExit fires but onCleanUp does not
            assertThat(events).containsExactly("exit")
        }
    }

    class FactorySupport {

        companion object {
            sealed class State {
                class Connected(val connectionId: Int) : State()
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
            var factoryCallCount = 0
            val stateMachine = StateMachine.create<State, Event, Nothing> {
                initialState(State.Disconnected)
                state<State.Disconnected> {
                    on<Event.Connect> {
                        transitionTo(State.Connected(1))
                    }
                }
                state<State.Connected> {
                    factory { intended ->
                        val conn = intended as State.Connected
                        factoryCallCount++
                        State.Connected(conn.connectionId * 10)
                    }
                    on<Event.Disconnect> {
                        transitionTo(State.Disconnected)
                    }
                }
            }

            // When
            stateMachine.transition(Event.Connect)

            // Then — factory was called and produced a transformed instance
            assertThat(factoryCallCount).isEqualTo(1)
            val state = stateMachine.state as State.Connected
            assertThat(state.connectionId).isEqualTo(10)
        }

        @Test
        fun factory_shouldCreateNewInstanceEachTime() {
            // Given
            val stateMachine = StateMachine.create<State, Event, Nothing> {
                initialState(State.Disconnected)
                state<State.Disconnected> {
                    on<Event.Connect> {
                        transitionTo(State.Connected(1))
                    }
                }
                state<State.Connected> {
                    factory { intended -> State.Connected((intended as State.Connected).connectionId) }
                    on<Event.Disconnect> {
                        transitionTo(State.Disconnected)
                    }
                }
            }

            // When
            stateMachine.transition(Event.Connect)
            val firstInstance = stateMachine.state

            stateMachine.transition(Event.Disconnect)
            stateMachine.transition(Event.Connect)
            val secondInstance = stateMachine.state

            // Then — different instances each time
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
                        val conn = intended as State.Connected
                        receivedConnectionId = conn.connectionId
                        State.Connected(conn.connectionId)
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
                    on<Event.Disconnect> {
                        transitionTo(State.Disconnected)
                    }
                }
            }

            // When
            stateMachine.transition(Event.Connect)

            // Then
            val state = stateMachine.state as State.Connected
            assertThat(state.connectionId).isEqualTo(99)
        }

        @Test
        fun transitionResult_shouldReflectFactoryCreatedState() {
            // Given
            val stateMachine = StateMachine.create<State, Event, Nothing> {
                initialState(State.Disconnected)
                state<State.Disconnected> {
                    on<Event.Connect> {
                        transitionTo(State.Connected(1))
                    }
                }
                state<State.Connected> {
                    factory { _ -> State.Connected(999) }
                    on<Event.Disconnect> {
                        transitionTo(State.Disconnected)
                    }
                }
            }

            // When
            val transition = stateMachine.transition(Event.Connect)

            // Then — transition reports the factory-created state, not the transitionTo value
            val valid = transition as StateMachine.Transition.Valid
            val toState = valid.toState as State.Connected
            assertThat(toState.connectionId).isEqualTo(999)
            assertThat(stateMachine.state).isSameAs(toState)
        }
    }

    class CleanupAndFactoryTogether {

        companion object {
            sealed class State {
                class ResourceHolder(val name: String) : State()
                object Empty : State()
            }

            sealed class Event {
                object ToHolder : Event()
                object ToEmpty : Event()
                object Swap : Event()
            }
        }

        @Test
        fun cleanupAndFactory_oldStateCleaned_newStateFromFactory() {
            // Given
            val cleanedUpNames = mutableListOf<String>()
            val factoryCreatedNames = mutableListOf<String>()

            val stateMachine = StateMachine.create<State, Event, Nothing> {
                initialState(State.ResourceHolder("first"))
                state<State.ResourceHolder> {
                    onCleanUp { cleanedUpNames.add((this as State.ResourceHolder).name) }
                    factory { intended ->
                        val rh = intended as State.ResourceHolder
                        factoryCreatedNames.add(rh.name)
                        State.ResourceHolder(rh.name)
                    }
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

            // When
            stateMachine.transition(Event.Swap)

            // Then
            assertThat(cleanedUpNames).containsExactly("first")
            assertThat(factoryCreatedNames).containsExactly("second")
            val current = stateMachine.state as State.ResourceHolder
            assertThat(current.name).isEqualTo("second")
        }

        @Test
        fun multipleTransitions_shouldCleanUpEachPreviousState() {
            // Given
            val cleanedUpNames = mutableListOf<String>()
            val factoryCreatedNames = mutableListOf<String>()

            val stateMachine = StateMachine.create<State, Event, Nothing> {
                initialState(State.ResourceHolder("a"))
                state<State.ResourceHolder> {
                    onCleanUp { cleanedUpNames.add((this as State.ResourceHolder).name) }
                    factory { intended ->
                        val rh = intended as State.ResourceHolder
                        factoryCreatedNames.add(rh.name)
                        State.ResourceHolder(rh.name)
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

            // When
            stateMachine.transition(Event.Swap)
            stateMachine.transition(Event.Swap)
            stateMachine.transition(Event.ToEmpty)

            // Then — each state was cleaned up, factories were called for each entry
            assertThat(cleanedUpNames).containsExactly("a", "next", "next")
            assertThat(factoryCreatedNames).containsExactly("next", "next")
        }

        @Test
        fun cleanupFiresBeforeFactoryCreatesNewState() {
            // Given
            val events = mutableListOf<String>()
            val stateMachine = StateMachine.create<State, Event, Nothing> {
                initialState(State.ResourceHolder("old"))
                state<State.ResourceHolder> {
                    onCleanUp { events.add("cleanup:${(this as State.ResourceHolder).name}") }
                    factory { intended ->
                        val rh = intended as State.ResourceHolder
                        events.add("factory:${rh.name}")
                        State.ResourceHolder(rh.name)
                    }
                    on<Event.Swap> {
                        transitionTo(State.ResourceHolder("new"))
                    }
                    on<Event.ToEmpty> {
                        transitionTo(State.Empty)
                    }
                }
                state<State.Empty> {}
            }

            // When
            stateMachine.transition(Event.Swap)

            // Then — cleanup of old state happens before factory creates new state
            assertThat(events).containsExactly("cleanup:old", "factory:new")
        }
    }
}
