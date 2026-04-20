//
//  Copyright (c) 2019, Match Group, LLC
//  BSD License, see LICENSE file for details
//

@testable import StateMachine
import XCTest

final class StateMachine_Turnstile_Tests: XCTestCase, StateMachineBuilder {

    enum Constant {

        static let farePrice: Int = 50
    }

    @StateMachineHashable
    indirect enum State: Equatable {

        case locked(credit: Int), unlocked, broken(oldState: State)
    }

    @StateMachineHashable
    enum Event: Equatable {

        case insertCoin(Int), admitPerson, machineDidFail, machineRepairDidComplete
    }

    enum SideEffect {

        case soundAlarm, closeDoors, openDoors, orderRepair
    }

    typealias TurnstileStateMachine = StateMachine<State, Event, SideEffect>
    typealias ValidTransition = TurnstileStateMachine.Transition.Valid

    static func turnstileStateMachine(withInitialState _state: State, logger: Logger) -> TurnstileStateMachine {
        TurnstileStateMachine {
            initialState(_state)
            state(.locked) {
                on(.insertCoin) { locked, insertCoin in
                    let newCredit: Int = try locked.credit() + insertCoin.value()
                    if newCredit >= Constant.farePrice {
                        return transition(to: .unlocked, emit: .openDoors)
                    } else {
                        return transition(to: .locked(credit: newCredit))
                    }
                }
                on(.admitPerson) {
                    dontTransition(emit: .soundAlarm)
                }
                on(.machineDidFail) {
                    transition(to: .broken(oldState: $0), emit: .orderRepair)
                }
            }
            state(.unlocked) {
                on(.admitPerson) {
                    transition(to: .locked(credit: 0), emit: .closeDoors)
                }
            }
            state(.broken) {
                on(.machineRepairDidComplete) { broken in
                    transition(to: try broken.oldState())
                }
            }
        }
    }

    var logger: Logger!

    override func setUp() {
        super.setUp()
        logger = .init()
    }

    override func tearDown() {
        logger = nil
        super.tearDown()
    }

    func givenState(is state: State) async -> TurnstileStateMachine {
        let stateMachine: TurnstileStateMachine = Self.turnstileStateMachine(withInitialState: state, logger: logger)
        let currentState = await stateMachine.state
        XCTAssertEqual(currentState, state)
        return stateMachine
    }

    func test_givenStateIsLocked_whenInsertCoin_andCreditLessThanFarePrice_shouldTransitionToLockedState() async throws {

        // Given
        let stateMachine = await givenState(is: .locked(credit: 0))

        // When
        let transition = try await stateMachine.transition(.insertCoin(10))

        // Then
        let currentState = await stateMachine.state
        XCTAssertEqual(currentState, .locked(credit: 10))
        XCTAssertEqual(transition, ValidTransition(fromState: .locked(credit: 0),
                                                    event: .insertCoin(10),
                                                    toState: .locked(credit: 10),
                                                    sideEffect: nil))
    }

    func test_givenStateIsLocked_whenInsertCoin_andCreditEqualsFarePrice_shouldTransitionToUnlockedStateAndOpenDoors() async throws {

        // Given
        let stateMachine = await givenState(is: .locked(credit: 35))

        // When
        let transition = try await stateMachine.transition(.insertCoin(15))

        // Then
        let currentState = await stateMachine.state
        XCTAssertEqual(currentState, .unlocked)
        XCTAssertEqual(transition, ValidTransition(fromState: .locked(credit: 35),
                                                    event: .insertCoin(15),
                                                    toState: .unlocked,
                                                    sideEffect: .openDoors))
    }

    func test_givenStateIsLocked_whenInsertCoin_andCreditMoreThanFarePrice_shouldTransitionToUnlockedStateAndOpenDoors() async throws {

        // Given
        let stateMachine = await givenState(is: .locked(credit: 35))

        // When
        let transition = try await stateMachine.transition(.insertCoin(20))

        // Then
        let currentState = await stateMachine.state
        XCTAssertEqual(currentState, .unlocked)
        XCTAssertEqual(transition, ValidTransition(fromState: .locked(credit: 35),
                                                    event: .insertCoin(20),
                                                    toState: .unlocked,
                                                    sideEffect: .openDoors))
    }

    func test_givenStateIsLocked_whenAdmitPerson_shouldTransitionToLockedStateAndSoundAlarm() async throws {

        // Given
        let stateMachine = await givenState(is: .locked(credit: 35))

        // When
        let transition = try await stateMachine.transition(.admitPerson)

        // Then
        let currentState = await stateMachine.state
        XCTAssertEqual(currentState, .locked(credit: 35))
        XCTAssertEqual(transition, ValidTransition(fromState: .locked(credit: 35),
                                                    event: .admitPerson,
                                                    toState: .locked(credit: 35),
                                                    sideEffect: .soundAlarm))
    }

    func test_givenStateIsLocked_whenMachineDidFail_shouldTransitionToBrokenStateAndOrderRepair() async throws {

        // Given
        let stateMachine = await givenState(is: .locked(credit: 15))

        // When
        let transition = try await stateMachine.transition(.machineDidFail)

        // Then
        let currentState = await stateMachine.state
        XCTAssertEqual(currentState, .broken(oldState: .locked(credit: 15)))
        XCTAssertEqual(transition, ValidTransition(fromState: .locked(credit: 15),
                                                    event: .machineDidFail,
                                                    toState: .broken(oldState: .locked(credit: 15)),
                                                    sideEffect: .orderRepair))
    }

    func test_givenStateIsUnlocked_whenAdmitPerson_shouldTransitionToLockedStateAndCloseDoors() async throws {

        // Given
        let stateMachine = await givenState(is: .unlocked)

        // When
        let transition = try await stateMachine.transition(.admitPerson)

        // Then
        let currentState = await stateMachine.state
        XCTAssertEqual(currentState, .locked(credit: 0))
        XCTAssertEqual(transition, ValidTransition(fromState: .unlocked,
                                                    event: .admitPerson,
                                                    toState: .locked(credit: 0),
                                                    sideEffect: .closeDoors))
    }

    func test_givenStateIsBroken_whenMachineRepairDidComplete_shouldTransitionToLockedState() async throws {

        // Given
        let stateMachine = await givenState(is: .broken(oldState: .locked(credit: 15)))

        // When
        let transition = try await stateMachine.transition(.machineRepairDidComplete)

        // Then
        let currentState = await stateMachine.state
        XCTAssertEqual(currentState, .locked(credit: 15))
        XCTAssertEqual(transition, ValidTransition(fromState: .broken(oldState: .locked(credit: 15)),
                                                    event: .machineRepairDidComplete,
                                                    toState: .locked(credit: 15),
                                                    sideEffect: nil))
    }
}
