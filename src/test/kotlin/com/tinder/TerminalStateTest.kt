package com.tinder

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.Test
import org.junit.experimental.runners.Enclosed
import org.junit.runner.RunWith

@RunWith(Enclosed::class)
internal class TerminalStateTest {

    class BuilderValidation {

        companion object {
            sealed class State {
                object Active : State()
                object Idle : State()
                object Failed : State()
                object Done : State()
            }

            sealed class Event {
                object Fail : Event()
                object Complete : Event()
                object Restart : Event()
            }
        }

        @Test
        fun terminalState_shouldBuildSuccessfully() {
            val sm = StateMachine.create<State, Event, Nothing> {
                initialState(State.Active)
                state<State.Active> {
                    transition<Event.Fail>(State.Failed)
                    transition<Event.Complete>(State.Done)
                }
                terminalState<State.Failed>()
                terminalState<State.Done>()
            }

            assertThat(sm.state).isEqualTo(State.Active)
        }

        @Test
        fun terminalState_cannotBeInitialState() {
            assertThatIllegalArgumentException().isThrownBy {
                StateMachine.create<State, Event, Nothing> {
                    initialState(State.Failed)
                    state<State.Active> {
                        transition<Event.Fail>(State.Failed)
                    }
                    terminalState<State.Failed>()
                }
            }
        }

        @Test
        fun terminalState_shouldNotCountAsUnreachable() {
            // Terminal states are exempt from reachability checks.
            // Without this exemption, State.Failed would be "unreachable"
            // because no state has a static transition pointing to it,
            // but it's still valid as a terminal state.
            val sm = StateMachine.create<State, Event, Nothing> {
                initialState(State.Active)
                state<State.Active> {
                    transition<Event.Complete>(State.Idle)
                }
                state<State.Idle> {
                    transition<Event.Restart>(State.Active)
                }
                terminalState<State.Failed>()
            }

            assertThat(sm.state).isEqualTo(State.Active)
        }

        @Test
        fun nonTerminalUnreachableState_shouldStillThrow() {
            // A regular (non-terminal) unreachable state should still fail validation.
            assertThatIllegalArgumentException().isThrownBy {
                StateMachine.create<State, Event, Nothing> {
                    initialState(State.Active)
                    state<State.Active> {
                        transition<Event.Fail>(State.Failed)
                    }
                    terminalState<State.Failed>()
                    // State.Idle is registered, not terminal, and not reachable
                    state<State.Idle> {
                        transition<Event.Restart>(State.Active)
                    }
                }
            }
        }
    }

    class RuntimeBehavior {

        companion object {
            sealed class State {
                class Active(val id: Int = 0) : State()
                object Failed : State()
                object Done : State()
            }

            sealed class Event {
                object Fail : Event()
                object Complete : Event()
                object Retry : Event()
            }
        }

        @Test
        fun transitionToTerminalState_shouldWork() {
            val sm = StateMachine.create<State, Event, Nothing> {
                initialState(State.Active())
                state<State.Active> {
                    transition<Event.Fail>(State.Failed)
                }
                terminalState<State.Failed>()
            }

            val transition = sm.transition(Event.Fail)

            assertThat(sm.state).isEqualTo(State.Failed)
            assertThat(transition).isInstanceOf(StateMachine.Transition.Valid::class.java)
        }

        @Test
        fun transitionFromTerminalState_shouldReturnInvalid() {
            val sm = StateMachine.create<State, Event, Nothing> {
                initialState(State.Active())
                state<State.Active> {
                    transition<Event.Fail>(State.Failed)
                }
                terminalState<State.Failed>()
            }

            sm.transition(Event.Fail)
            assertThat(sm.state).isEqualTo(State.Failed)

            // Any event from a terminal state should be Invalid
            val transition = sm.transition(Event.Retry)
            assertThat(transition).isInstanceOf(StateMachine.Transition.Invalid::class.java)
            assertThat(sm.state).isEqualTo(State.Failed)
        }

        @Test
        fun cleanupFiresOnOldState_whenTransitioningToTerminal() {
            var cleanedUp = false
            val sm = StateMachine.create<State, Event, Nothing> {
                initialState(State.Active())
                state<State.Active> {
                    onCleanUp { cleanedUp = true }
                    transition<Event.Fail>(State.Failed)
                }
                terminalState<State.Failed>()
            }

            sm.transition(Event.Fail)

            assertThat(cleanedUp).isTrue()
        }

        @Test
        fun onEnterFiresOnTerminalState() {
            var enteredFailed = false
            val sm = StateMachine.create<State, Event, Nothing> {
                initialState(State.Active())
                state<State.Active> {
                    transition<Event.Fail>(State.Failed)
                }
                terminalState<State.Failed> {
                    onEnter { enteredFailed = true }
                }
            }

            sm.transition(Event.Fail)

            assertThat(enteredFailed).isTrue()
        }

        @Test
        fun cleanupAndOnEnter_orderingWithTerminalState() {
            val events = mutableListOf<String>()
            val sm = StateMachine.create<State, Event, Nothing> {
                initialState(State.Active())
                state<State.Active> {
                    onCleanUp { events.add("cleanup:active") }
                    onExit { events.add("exit:active") }
                    transition<Event.Fail>(State.Failed)
                }
                terminalState<State.Failed> {
                    onEnter { events.add("enter:failed") }
                }
            }

            sm.transition(Event.Fail)

            // cleanup (inside sync) → exit (outside sync) → enter (outside sync)
            assertThat(events).containsExactly("cleanup:active", "exit:active", "enter:failed")
        }
    }

    class TerminalWithFactory {

        companion object {
            sealed class State {
                object Active : State()
                class Error(val reason: String) : State()
            }

            sealed class Event {
                object Fail : Event()
            }
        }

        @Test
        fun terminalState_withFactory_shouldUseFreshInstance() {
            var factoryCallCount = 0
            val sm = StateMachine.create<State, Event, Nothing> {
                initialState(State.Active)
                state<State.Active> {
                    transition<Event.Fail>(State.Error("default"))
                }
                terminalState<State.Error> {
                    factory { intended ->
                        factoryCallCount++
                        State.Error("factory:${(intended as State.Error).reason}")
                    }
                }
            }

            sm.transition(Event.Fail)

            assertThat(factoryCallCount).isEqualTo(1)
            val state = sm.state as State.Error
            assertThat(state.reason).isEqualTo("factory:default")
        }
    }

    class MultipleTerminalStates {

        companion object {
            sealed class State {
                object Running : State()
                object Paused : State()
                object Completed : State()
                object Failed : State()
                object Cancelled : State()
            }

            sealed class Event {
                object Pause : Event()
                object Resume : Event()
                object Complete : Event()
                object Fail : Event()
                object Cancel : Event()
            }
        }

        @Test
        fun multipleTerminalStates_validGraph() {
            val sm = StateMachine.create<State, Event, Nothing> {
                initialState(State.Running)
                state<State.Running> {
                    transition<Event.Pause>(State.Paused)
                    transition<Event.Complete>(State.Completed)
                    transition<Event.Fail>(State.Failed)
                    transition<Event.Cancel>(State.Cancelled)
                }
                state<State.Paused> {
                    transition<Event.Resume>(State.Running)
                    transition<Event.Cancel>(State.Cancelled)
                }
                terminalState<State.Completed>()
                terminalState<State.Failed>()
                terminalState<State.Cancelled>()
            }

            assertThat(sm.state).isEqualTo(State.Running)

            // Running → Paused → Cancelled (terminal)
            sm.transition(Event.Pause)
            assertThat(sm.state).isEqualTo(State.Paused)

            sm.transition(Event.Cancel)
            assertThat(sm.state).isEqualTo(State.Cancelled)

            // Can't leave terminal state
            val invalid = sm.transition(Event.Resume)
            assertThat(invalid).isInstanceOf(StateMachine.Transition.Invalid::class.java)
            assertThat(sm.state).isEqualTo(State.Cancelled)
        }
    }
}
