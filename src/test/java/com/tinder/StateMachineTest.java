package com.tinder;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

public class StateMachineTest {

    // Test classes for MatterStateMachine
    public static class MatterState {
        public static class Solid extends MatterState {}
        public static class Liquid extends MatterState {}
        public static class Gas extends MatterState {}

        public static final Solid SOLID = new Solid();
        public static final Liquid LIQUID = new Liquid();
        public static final Gas GAS = new Gas();
    }

    public static class MatterEvent {
        public static class OnMelted extends MatterEvent {}
        public static class OnFrozen extends MatterEvent {}
        public static class OnVaporized extends MatterEvent {}
        public static class OnCondensed extends MatterEvent {}

        public static final OnMelted ON_MELTED = new OnMelted();
        public static final OnFrozen ON_FROZEN = new OnFrozen();
        public static final OnVaporized ON_VAPORIZED = new OnVaporized();
        public static final OnCondensed ON_CONDENSED = new OnCondensed();
    }

    public static class MatterSideEffect {
        public static class LogMelted extends MatterSideEffect {}
        public static class LogFrozen extends MatterSideEffect {}
        public static class LogVaporized extends MatterSideEffect {}
        public static class LogCondensed extends MatterSideEffect {}

        public static final LogMelted LOG_MELTED = new LogMelted();
        public static final LogFrozen LOG_FROZEN = new LogFrozen();
        public static final LogVaporized LOG_VAPORIZED = new LogVaporized();
        public static final LogCondensed LOG_CONDENSED = new LogCondensed();
    }

    public interface Logger {
        void log(String message);
    }

    public static class TestLogger implements Logger {
        private final List<String> messages = new ArrayList<>();

        @Override
        public void log(String message) {
            messages.add(message);
        }

        public List<String> getMessages() {
            return messages;
        }

        public String getLastMessage() {
            return messages.isEmpty() ? null : messages.get(messages.size() - 1);
        }
    }

    private static final String ON_MELTED_MESSAGE = "I melted";
    private static final String ON_FROZEN_MESSAGE = "I froze";
    private static final String ON_VAPORIZED_MESSAGE = "I vaporized";
    private static final String ON_CONDENSED_MESSAGE = "I condensed";

    @Test
    public void initialState_shouldBeSolid() {
        TestLogger logger = new TestLogger();
        StateMachine<MatterState, MatterEvent, MatterSideEffect> stateMachine = createMatterStateMachine(logger);

        assertThat(stateMachine.getState()).isEqualTo(MatterState.SOLID);
    }

    @Test
    public void givenStateIsSolid_onMelted_shouldTransitionToLiquidStateAndLog() {
        TestLogger logger = new TestLogger();
        StateMachine<MatterState, MatterEvent, MatterSideEffect> stateMachine = createMatterStateMachine(logger);

        StateMachine.Transition<MatterState, MatterEvent, MatterSideEffect> transition =
                stateMachine.transition(MatterEvent.ON_MELTED);

        assertThat(stateMachine.getState()).isEqualTo(MatterState.LIQUID);
        assertThat(transition).isEqualTo(
                new StateMachine.Transition.Valid<>(MatterState.SOLID, MatterEvent.ON_MELTED, MatterState.LIQUID, MatterSideEffect.LOG_MELTED)
        );
        assertThat(logger.getLastMessage()).isEqualTo(ON_MELTED_MESSAGE);
    }

    @Test
    public void givenStateIsLiquid_onFroze_shouldTransitionToSolidStateAndLog() {
        TestLogger logger = new TestLogger();
        StateMachine<MatterState, MatterEvent, MatterSideEffect> stateMachine = createMatterStateMachine(logger);
        stateMachine.transition(MatterEvent.ON_MELTED); // Solid -> Liquid

        StateMachine.Transition<MatterState, MatterEvent, MatterSideEffect> transition =
                stateMachine.transition(MatterEvent.ON_FROZEN);

        assertThat(stateMachine.getState()).isEqualTo(MatterState.SOLID);
        assertThat(transition).isEqualTo(
                new StateMachine.Transition.Valid<>(MatterState.LIQUID, MatterEvent.ON_FROZEN, MatterState.SOLID, MatterSideEffect.LOG_FROZEN)
        );
        assertThat(logger.getLastMessage()).isEqualTo(ON_FROZEN_MESSAGE);
    }

    @Test
    public void givenStateIsLiquid_onVaporized_shouldTransitionToGasStateAndLog() {
        TestLogger logger = new TestLogger();
        StateMachine<MatterState, MatterEvent, MatterSideEffect> stateMachine = createMatterStateMachine(logger);
        stateMachine.transition(MatterEvent.ON_MELTED); // Solid -> Liquid

        StateMachine.Transition<MatterState, MatterEvent, MatterSideEffect> transition =
                stateMachine.transition(MatterEvent.ON_VAPORIZED);

        assertThat(stateMachine.getState()).isEqualTo(MatterState.GAS);
        assertThat(transition).isEqualTo(
                new StateMachine.Transition.Valid<>(MatterState.LIQUID, MatterEvent.ON_VAPORIZED, MatterState.GAS, MatterSideEffect.LOG_VAPORIZED)
        );
        assertThat(logger.getLastMessage()).isEqualTo(ON_VAPORIZED_MESSAGE);
    }

    @Test
    public void givenStateIsGas_onCondensed_shouldTransitionToLiquidStateAndLog() {
        TestLogger logger = new TestLogger();
        StateMachine<MatterState, MatterEvent, MatterSideEffect> stateMachine = createMatterStateMachine(logger);
        stateMachine.transition(MatterEvent.ON_MELTED); // Solid -> Liquid
        stateMachine.transition(MatterEvent.ON_VAPORIZED); // Liquid -> Gas

        StateMachine.Transition<MatterState, MatterEvent, MatterSideEffect> transition =
                stateMachine.transition(MatterEvent.ON_CONDENSED);

        assertThat(stateMachine.getState()).isEqualTo(MatterState.LIQUID);
        assertThat(transition).isEqualTo(
                new StateMachine.Transition.Valid<>(MatterState.GAS, MatterEvent.ON_CONDENSED, MatterState.LIQUID, MatterSideEffect.LOG_CONDENSED)
        );
        assertThat(logger.getLastMessage()).isEqualTo(ON_CONDENSED_MESSAGE);
    }

    private StateMachine<MatterState, MatterEvent, MatterSideEffect> createMatterStateMachine(TestLogger logger) {
        return StateMachine.create(b -> {
            b.initialState(MatterState.SOLID);
            b.state(MatterState.Solid.class, sb -> {
                sb.on(MatterEvent.OnMelted.class, (state, event) ->
                        sb.transitionTo(MatterState.LIQUID, MatterSideEffect.LOG_MELTED));
            });
            b.state(MatterState.Liquid.class, sb -> {
                sb.on(MatterEvent.OnFrozen.class, (state, event) ->
                        sb.transitionTo(MatterState.SOLID, MatterSideEffect.LOG_FROZEN));
                sb.on(MatterEvent.OnVaporized.class, (state, event) ->
                        sb.transitionTo(MatterState.GAS, MatterSideEffect.LOG_VAPORIZED));
            });
            b.state(MatterState.Gas.class, sb -> {
                sb.on(MatterEvent.OnCondensed.class, (state, event) ->
                        sb.transitionTo(MatterState.LIQUID, MatterSideEffect.LOG_CONDENSED));
            });
            b.onTransition(transition -> {
                if (transition instanceof StateMachine.Transition.Valid) {
                    StateMachine.Transition.Valid<MatterState, MatterEvent, MatterSideEffect> valid =
                            (StateMachine.Transition.Valid<MatterState, MatterEvent, MatterSideEffect>) transition;
                    MatterSideEffect sideEffect = valid.getSideEffect();
                    if (sideEffect instanceof MatterSideEffect.LogMelted) {
                        logger.log(ON_MELTED_MESSAGE);
                    } else if (sideEffect instanceof MatterSideEffect.LogFrozen) {
                        logger.log(ON_FROZEN_MESSAGE);
                    } else if (sideEffect instanceof MatterSideEffect.LogVaporized) {
                        logger.log(ON_VAPORIZED_MESSAGE);
                    } else if (sideEffect instanceof MatterSideEffect.LogCondensed) {
                        logger.log(ON_CONDENSED_MESSAGE);
                    }
                }
            });
        });
    }

    // Turnstile tests
    public static class TurnstileState {
        public static class Locked extends TurnstileState {
            public final int credit;

            public Locked(int credit) {
                this.credit = credit;
            }

            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (o == null || getClass() != o.getClass()) return false;
                Locked locked = (Locked) o;
                return credit == locked.credit;
            }

            @Override
            public int hashCode() {
                return credit;
            }
        }

        public static class Unlocked extends TurnstileState {
            private static final Unlocked INSTANCE = new Unlocked();

            private Unlocked() {}

            public static Unlocked getInstance() {
                return INSTANCE;
            }

            @Override
            public boolean equals(Object o) {
                return o instanceof Unlocked;
            }

            @Override
            public int hashCode() {
                return Unlocked.class.hashCode();
            }
        }

        public static class Broken extends TurnstileState {
            public final TurnstileState oldState;

            public Broken(TurnstileState oldState) {
                this.oldState = oldState;
            }

            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (o == null || getClass() != o.getClass()) return false;
                Broken broken = (Broken) o;
                return oldState.equals(broken.oldState);
            }

            @Override
            public int hashCode() {
                return oldState.hashCode();
            }
        }
    }

    public static class TurnstileEvent {
        public static class InsertCoin extends TurnstileEvent {
            public final int value;

            public InsertCoin(int value) {
                this.value = value;
            }

            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (o == null || getClass() != o.getClass()) return false;
                InsertCoin that = (InsertCoin) o;
                return value == that.value;
            }

            @Override
            public int hashCode() {
                return value;
            }
        }

        public static class AdmitPerson extends TurnstileEvent {
            private static final AdmitPerson INSTANCE = new AdmitPerson();

            private AdmitPerson() {}

            public static AdmitPerson getInstance() {
                return INSTANCE;
            }

            @Override
            public boolean equals(Object o) {
                return o instanceof AdmitPerson;
            }

            @Override
            public int hashCode() {
                return AdmitPerson.class.hashCode();
            }
        }

        public static class MachineDidFail extends TurnstileEvent {
            private static final MachineDidFail INSTANCE = new MachineDidFail();

            private MachineDidFail() {}

            public static MachineDidFail getInstance() {
                return INSTANCE;
            }

            @Override
            public boolean equals(Object o) {
                return o instanceof MachineDidFail;
            }

            @Override
            public int hashCode() {
                return MachineDidFail.class.hashCode();
            }
        }

        public static class MachineRepairDidComplete extends TurnstileEvent {
            private static final MachineRepairDidComplete INSTANCE = new MachineRepairDidComplete();

            private MachineRepairDidComplete() {}

            public static MachineRepairDidComplete getInstance() {
                return INSTANCE;
            }

            @Override
            public boolean equals(Object o) {
                return o instanceof MachineRepairDidComplete;
            }

            @Override
            public int hashCode() {
                return MachineRepairDidComplete.class.hashCode();
            }
        }
    }

    public static class TurnstileCommand {
        public static class SoundAlarm extends TurnstileCommand {
            private static final SoundAlarm INSTANCE = new SoundAlarm();

            private SoundAlarm() {}

            public static SoundAlarm getInstance() {
                return INSTANCE;
            }

            @Override
            public boolean equals(Object o) {
                return o instanceof SoundAlarm;
            }

            @Override
            public int hashCode() {
                return SoundAlarm.class.hashCode();
            }
        }

        public static class CloseDoors extends TurnstileCommand {
            private static final CloseDoors INSTANCE = new CloseDoors();

            private CloseDoors() {}

            public static CloseDoors getInstance() {
                return INSTANCE;
            }

            @Override
            public boolean equals(Object o) {
                return o instanceof CloseDoors;
            }

            @Override
            public int hashCode() {
                return CloseDoors.class.hashCode();
            }
        }

        public static class OpenDoors extends TurnstileCommand {
            private static final OpenDoors INSTANCE = new OpenDoors();

            private OpenDoors() {}

            public static OpenDoors getInstance() {
                return INSTANCE;
            }

            @Override
            public boolean equals(Object o) {
                return o instanceof OpenDoors;
            }

            @Override
            public int hashCode() {
                return OpenDoors.class.hashCode();
            }
        }

        public static class OrderRepair extends TurnstileCommand {
            private static final OrderRepair INSTANCE = new OrderRepair();

            private OrderRepair() {}

            public static OrderRepair getInstance() {
                return INSTANCE;
            }

            @Override
            public boolean equals(Object o) {
                return o instanceof OrderRepair;
            }

            @Override
            public int hashCode() {
                return OrderRepair.class.hashCode();
            }
        }
    }

    private static final int FARE_PRICE = 50;

    @Test
    public void initialState_shouldBeLocked() {
        StateMachine<TurnstileState, TurnstileEvent, TurnstileCommand> stateMachine = createTurnstileStateMachine();

        assertThat(stateMachine.getState()).isEqualTo(new TurnstileState.Locked(0));
    }

    @Test
    public void givenStateIsLocked_whenInsertCoin_andCreditLessThanFairPrice_shouldTransitionToLockedState() {
        StateMachine<TurnstileState, TurnstileEvent, TurnstileCommand> stateMachine = createTurnstileStateMachine();

        StateMachine.Transition<TurnstileState, TurnstileEvent, TurnstileCommand> transition =
                stateMachine.transition(new TurnstileEvent.InsertCoin(10));

        assertThat(stateMachine.getState()).isEqualTo(new TurnstileState.Locked(10));
        assertThat(transition).isEqualTo(
                new StateMachine.Transition.Valid<>(
                        new TurnstileState.Locked(0),
                        new TurnstileEvent.InsertCoin(10),
                        new TurnstileState.Locked(10),
                        null
                )
        );
    }

    private StateMachine<TurnstileState, TurnstileEvent, TurnstileCommand> createTurnstileStateMachine() {
        return StateMachine.create(b -> {
            b.initialState(new TurnstileState.Locked(0));
            b.state(TurnstileState.Locked.class, sb -> {
                sb.on(TurnstileEvent.InsertCoin.class, (state, event) -> {
                    int newCredit = state.credit + event.value;
                    if (newCredit >= FARE_PRICE) {
                        return sb.transitionTo(TurnstileState.Unlocked.getInstance(), TurnstileCommand.OpenDoors.getInstance());
                    } else {
                        return sb.transitionTo(new TurnstileState.Locked(newCredit));
                    }
                });
                sb.on(TurnstileEvent.AdmitPerson.getInstance(), (state, event) ->
                        sb.transitionTo(state, TurnstileCommand.SoundAlarm.getInstance()));
                sb.on(TurnstileEvent.MachineDidFail.getInstance(), (state, event) ->
                        sb.transitionTo(new TurnstileState.Broken(state), TurnstileCommand.OrderRepair.getInstance()));
            });
            b.state(TurnstileState.Unlocked.class, sb -> {
                sb.on(TurnstileEvent.AdmitPerson.getInstance(), (state, event) ->
                        sb.transitionTo(new TurnstileState.Locked(0), TurnstileCommand.CloseDoors.getInstance()));
            });
            b.state(TurnstileState.Broken.class, sb -> {
                sb.on(TurnstileEvent.MachineRepairDidComplete.getInstance(), (state, event) ->
                        sb.transitionTo(state.oldState));
            });
        });
    }

    // Object state machine tests
    public static class SimpleState {
        public static final String A = "a";
        public static final String B = "b";
        public static final String C = "c";
        public static final String D = "d";
    }

    public static class SimpleEvent {
        public static final Integer E1 = 1;
        public static final Integer E2 = 2;
        public static final Integer E3 = 3;
        public static final Integer E4 = 4;
    }

    public static class SimpleSideEffect {
        public static final String SE1 = "alpha";
        public static final String SE2 = "beta";
        public static final String SE3 = "gamma";
    }

    @Test
    public void state_shouldReturnInitialState() {
        StateMachine<String, Integer, String> stateMachine = StateMachine.create(b -> {
            b.initialState(SimpleState.A);
            b.state(SimpleState.A, sb -> {
                sb.on(SimpleEvent.E1, (state, event) -> sb.transitionTo(SimpleState.B));
            });
        });

        assertThat(stateMachine.getState()).isEqualTo(SimpleState.A);
    }

    @Test
    public void transition_givenValidEvent_shouldReturnTransition() {
        StateMachine<String, Integer, String> stateMachine = StateMachine.create(b -> {
            b.initialState(SimpleState.A);
            b.state(SimpleState.A, sb -> {
                sb.on(SimpleEvent.E1, (state, event) -> sb.transitionTo(SimpleState.B));
            });
            b.state(SimpleState.B, sb -> {
                sb.on(SimpleEvent.E3, (state, event) -> sb.transitionTo(SimpleState.C, SimpleSideEffect.SE1));
            });
            b.state(SimpleState.C, sb -> {});
        });

        StateMachine.Transition<String, Integer, String> transition1 = stateMachine.transition(SimpleEvent.E1);
        assertThat(transition1).isEqualTo(
                new StateMachine.Transition.Valid<>(SimpleState.A, SimpleEvent.E1, SimpleState.B, null)
        );

        StateMachine.Transition<String, Integer, String> transition2 = stateMachine.transition(SimpleEvent.E3);
        assertThat(transition2).isEqualTo(
                new StateMachine.Transition.Valid<>(SimpleState.B, SimpleEvent.E3, SimpleState.C, SimpleSideEffect.SE1)
        );
    }

    @Test
    public void transition_givenValidEvent_shouldCreateAndSetNewState() {
        StateMachine<String, Integer, String> stateMachine = StateMachine.create(b -> {
            b.initialState(SimpleState.A);
            b.state(SimpleState.A, sb -> {
                sb.on(SimpleEvent.E1, (state, event) -> sb.transitionTo(SimpleState.B));
            });
            b.state(SimpleState.B, sb -> {
                sb.on(SimpleEvent.E3, (state, event) -> sb.transitionTo(SimpleState.C, SimpleSideEffect.SE1));
            });
            b.state(SimpleState.C, sb -> {});
        });

        stateMachine.transition(SimpleEvent.E1);
        assertThat(stateMachine.getState()).isEqualTo(SimpleState.B);

        stateMachine.transition(SimpleEvent.E3);
        assertThat(stateMachine.getState()).isEqualTo(SimpleState.C);
    }

    @Test
    public void transition_givenInvalidEvent_shouldReturnInvalidTransition() {
        StateMachine<String, Integer, String> stateMachine = StateMachine.create(b -> {
            b.initialState(SimpleState.A);
            b.state(SimpleState.A, sb -> {
                sb.on(SimpleEvent.E1, (state, event) -> sb.transitionTo(SimpleState.B));
            });
            b.state(SimpleState.B, sb -> {});
        });

        String fromState = stateMachine.getState();
        StateMachine.Transition<String, Integer, String> transition = stateMachine.transition(SimpleEvent.E3);

        assertThat(transition).isEqualTo(
                new StateMachine.Transition.Invalid<String, Integer, String>(SimpleState.A, SimpleEvent.E3)
        );
        assertThat(stateMachine.getState()).isEqualTo(fromState);
    }

    @Test
    public void transition_givenUndeclaredState_shouldThrowIllegalStateException() {
        StateMachine<String, Integer, String> stateMachine = StateMachine.create(b -> {
            b.initialState(SimpleState.A);
            b.state(SimpleState.A, sb -> {
                sb.on(SimpleEvent.E4, (state, event) -> sb.transitionTo(SimpleState.D));
            });
            // Note: State D is not defined
        });

        assertThatIllegalStateException().isThrownBy(() -> {
            stateMachine.transition(SimpleEvent.E4);
        });
    }

    @Test
    public void create_givenNoInitialState_shouldThrowIllegalArgumentException() {
        assertThatIllegalArgumentException().isThrownBy(() -> {
            StateMachine.<String, Integer, String>create(b -> {
                // No initial state
            });
        });
    }

    @Test
    public void with_shouldCreateNewStateMachineWithNewInitialState() {
        StateMachine<String, Integer, String> stateMachine = StateMachine.create(b -> {
            b.initialState(SimpleState.A);
            b.state(SimpleState.A, sb -> {
                sb.on(SimpleEvent.E1, (state, event) -> sb.transitionTo(SimpleState.B));
            });
            b.state(SimpleState.B, sb -> {});
        });

        StateMachine<String, Integer, String> newStateMachine = stateMachine.with(b -> {
            b.initialState(SimpleState.B);
        });

        assertThat(newStateMachine.getState()).isEqualTo(SimpleState.B);
        assertThat(stateMachine.getState()).isEqualTo(SimpleState.A); // Original unchanged
    }
}
