package com.tinder

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.Test
import org.junit.experimental.runners.Enclosed
import org.junit.runner.RunWith

@RunWith(Enclosed::class)
internal class GraphValidationTest {

    class ValidGraphs {

        companion object {
            sealed class State {
                object A : State()
                object B : State()
                object C : State()
            }

            sealed class Event {
                object E1 : Event()
                object E2 : Event()
                object E3 : Event()
            }

        }

        @Test
        fun validGraph_withStaticTransitions_shouldBuildSuccessfully() {
            val sm = StateMachine.create<State, Event, Nothing> {
                initialState(State.A)
                state<State.A> {
                    transition<Event.E1, State.B>(State.B)
                }
                state<State.B> {
                    transition<Event.E2, State.A>(State.A)
                }
            }

            assertThat(sm.state).isEqualTo(State.A)
        }

        @Test
        fun validGraph_threeStatesFullyConnected_shouldBuildSuccessfully() {
            val sm = StateMachine.create<State, Event, Nothing> {
                initialState(State.A)
                state<State.A> {
                    transition<Event.E1, State.B>(State.B)
                }
                state<State.B> {
                    transition<Event.E2, State.C>(State.C)
                }
                state<State.C> {
                    transition<Event.E3, State.A>(State.A)
                }
            }

            assertThat(sm.state).isEqualTo(State.A)
        }

        @Test
        fun validGraph_selfTransition_shouldBuildSuccessfully() {
            val sm = StateMachine.create<State, Event, Nothing> {
                initialState(State.A)
                state<State.A> {
                    transition<Event.E1, State.A>(State.A)
                }
            }

            assertThat(sm.state).isEqualTo(State.A)
        }

        @Test
        fun staticTransitions_shouldWorkAtRuntime() {
            val sm = StateMachine.create<State, Event, Nothing> {
                initialState(State.A)
                state<State.A> {
                    transition<Event.E1, State.B>(State.B)
                }
                state<State.B> {
                    transition<Event.E2, State.A>(State.A)
                }
            }

            val transition = sm.transition(Event.E1)

            assertThat(sm.state).isEqualTo(State.B)
            assertThat(transition).isInstanceOf(StateMachine.Transition.Valid::class.java)
        }
    }

    class MissingTargetDefinition {

        companion object {
            sealed class State {
                object A : State()
                object B : State()
                object C : State()
            }

            sealed class Event {
                object E1 : Event()
            }
        }

        @Test
        fun missingTargetDefinition_shouldThrow() {
            assertThatIllegalArgumentException().isThrownBy {
                StateMachine.create<State, Event, Nothing> {
                    initialState(State.A)
                    state<State.A> {
                        transition<Event.E1, State.B>(State.B)
                    }
                    // State.B has no definition
                }
            }
        }

        @Test
        fun missingOneOfMultipleTargets_shouldThrow() {
            assertThatIllegalArgumentException().isThrownBy {
                StateMachine.create<State, Event, Nothing> {
                    initialState(State.A)
                    state<State.A> {
                        transition<Event.E1, State.B>(State.B)
                    }
                    state<State.B> {
                        transition<Event.E1, State.C>(State.C)
                    }
                    // State.C has no definition
                }
            }
        }
    }

    class UnreachableStates {

        companion object {
            sealed class State {
                object A : State()
                object B : State()
                object C : State()
            }

            sealed class Event {
                object E1 : Event()
                object E2 : Event()
            }
        }

        @Test
        fun unreachableState_shouldThrow() {
            assertThatIllegalArgumentException().isThrownBy {
                StateMachine.create<State, Event, Nothing> {
                    initialState(State.A)
                    state<State.A> {
                        transition<Event.E1, State.A>(State.A)
                    }
                    state<State.B> {
                        transition<Event.E2, State.A>(State.A)
                    }
                    // State.B is registered but unreachable from State.A
                }
            }
        }

        @Test
        fun allStatesReachable_shouldNotThrow() {
            // A -> B -> C -> A (all reachable)
            val sm = StateMachine.create<State, Event, Nothing> {
                initialState(State.A)
                state<State.A> {
                    transition<Event.E1, State.B>(State.B)
                }
                state<State.B> {
                    transition<Event.E1, State.C>(State.C)
                }
                state<State.C> {
                    transition<Event.E1, State.A>(State.A)
                }
            }

            assertThat(sm.state).isEqualTo(State.A)
        }
    }

    class InitialStateValidation {

        companion object {
            sealed class State {
                object A : State()
                object B : State()
            }

            sealed class Event {
                object E1 : Event()
            }
        }

        @Test
        fun initialStateWithoutDefinition_whenStaticTransitionsUsed_shouldThrow() {
            assertThatIllegalArgumentException().isThrownBy {
                StateMachine.create<State, Event, Nothing> {
                    initialState(State.A)
                    // State.A has no definition, but State.B uses static transitions
                    state<State.B> {
                        transition<Event.E1, State.B>(State.B)
                    }
                }
            }
        }
    }

    class BackwardCompatibility {

        companion object {
            sealed class State {
                object A : State()
                object B : State()
                object C : State()
            }

            sealed class Event {
                object E1 : Event()
                object E2 : Event()
            }
        }

        @Test
        fun dynamicOn_withMissingTarget_shouldNotThrowAtBuildTime() {
            // Using on() — no static transitions — validation is skipped
            val sm = StateMachine.create<State, Event, Nothing> {
                initialState(State.A)
                state<State.A> {
                    on<Event.E1> {
                        transitionTo(State.B)
                    }
                }
                // State.B has no definition — but no validation since on() is used
            }

            assertThat(sm.state).isEqualTo(State.A)
        }
    }

    class IntegrationWithLifecycle {

        companion object {
            sealed class State {
                class Active(val id: Int) : State()
                object Idle : State()
            }

            sealed class Event {
                object Deactivate : Event()
                object Activate : Event()
            }
        }

        @Test
        fun staticTransitions_withFactoryAndCleanup_shouldWork() {
            val events = mutableListOf<String>()
            var factoryCallCount = 0

            val sm = StateMachine.create<State, Event, Nothing> {
                initialState(State.Active(1))
                state<State.Active> {
                    transition<Event.Deactivate, State.Idle>(State.Idle)
                    onCleanUp { events.add("cleanup:active") }
                }
                state<State.Idle> {
                    transition<Event.Activate, State.Active>(State.Active(0))
                    factory { _: State ->
                        factoryCallCount++
                        State.Idle
                    }
                    onCleanUp { events.add("cleanup:idle") }
                }
            }

            // Transition Active -> Idle
            sm.transition(Event.Deactivate)
            assertThat(sm.state).isEqualTo(State.Idle)
            assertThat(events).containsExactly("cleanup:active")

            // Transition Idle -> Active
            sm.transition(Event.Activate)
            assertThat(sm.state).isInstanceOf(State.Active::class.java)
            assertThat(events).containsExactly("cleanup:active", "cleanup:idle")
            assertThat(factoryCallCount).isEqualTo(1)
        }
    }
}
