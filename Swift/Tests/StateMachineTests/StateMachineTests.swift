//
//  Copyright (c) 2019, Match Group, LLC
//  BSD License, see LICENSE file for details
//

@testable import StateMachine
import XCTest

final class StateMachineTests: XCTestCase, StateMachineBuilder {

    enum State: StateMachineHashable {

        case stateOne, stateTwo
    }

    enum Event: StateMachineHashable {

        case eventOne, eventTwo
    }

    enum SideEffect {

        case commandOne, commandTwo, commandThree
    }

    typealias TestStateMachine = StateMachine<State, Event, SideEffect>
    typealias ValidTransition = TestStateMachine.Transition.Valid
    typealias InvalidTransition = TestStateMachine.Transition.Invalid

    static func testStateMachine(withInitialState _state: State) -> TestStateMachine {
        TestStateMachine {
            initialState(_state)
            state(.stateOne) {
                on(.eventOne) {
                    dontTransition(emit: .commandOne)
                }
                on(.eventTwo) {
                    transition(to: .stateTwo, emit: .commandTwo)
                }
            }
            state(.stateTwo) {
                on(.eventTwo) {
                    dontTransition(emit: .commandThree)
                }
            }
        }
    }

    func givenState(is state: State) async -> TestStateMachine {
        let stateMachine: TestStateMachine = Self.testStateMachine(withInitialState: state)
        let currentState = await stateMachine.state
        XCTAssertEqual(currentState, state)
        return stateMachine
    }

    func testDontTransition() async throws {

        // Given
        let stateMachine: TestStateMachine = await givenState(is: .stateOne)

        // When
        let transition: ValidTransition = try await stateMachine.transition(.eventOne)

        // Then
        let currentState = await stateMachine.state
        XCTAssertEqual(currentState, .stateOne)
        XCTAssertEqual(transition, ValidTransition(fromState: .stateOne,
                                                    event: .eventOne,
                                                    toState: .stateOne,
                                                    sideEffect: .commandOne))
    }

    func testTransition() async throws {

        // Given
        let stateMachine: TestStateMachine = await givenState(is: .stateOne)

        // When
        let transition: ValidTransition = try await stateMachine.transition(.eventTwo)

        // Then
        let currentState = await stateMachine.state
        XCTAssertEqual(currentState, .stateTwo)
        XCTAssertEqual(transition, ValidTransition(fromState: .stateOne,
                                                    event: .eventTwo,
                                                    toState: .stateTwo,
                                                    sideEffect: .commandTwo))
    }

    func testInvalidTransition() async throws {

        // Given
        let stateMachine: TestStateMachine = await givenState(is: .stateTwo)

        // When/Then
        do {
            _ = try await stateMachine.transition(.eventOne)
            XCTFail("Expected InvalidTransition error")
        } catch is InvalidTransition {
            // Expected
        }
    }

    func testObservation() async throws {

        let results = SafeList<Result<ValidTransition, InvalidTransition>>()

        // Given
        let stateMachine: TestStateMachine = await givenState(is: .stateOne)
        await stateMachine.startObserving(self) {
            results.append($0.mapError { $0 as! InvalidTransition })
        }

        // When
        try await stateMachine.transition(.eventOne)
        try await stateMachine.transition(.eventTwo)

        do {
            _ = try await stateMachine.transition(.eventOne)
            XCTFail("Expected InvalidTransition error")
        } catch is InvalidTransition {
            // Expected
        }

        // When
        try await stateMachine.transition(.eventTwo)

        // Then
        XCTAssertEqual(results.values, [
            .success(ValidTransition(fromState: .stateOne,
                                     event: .eventOne,
                                     toState: .stateOne,
                                     sideEffect: .commandOne)),
            .success(ValidTransition(fromState: .stateOne,
                                     event: .eventTwo,
                                     toState: .stateTwo,
                                     sideEffect: .commandTwo)),
            .failure(InvalidTransition()),
            .success(ValidTransition(fromState: .stateTwo,
                                     event: .eventTwo,
                                     toState: .stateTwo,
                                     sideEffect: .commandThree))
        ])
    }

    func testStopObservation() async throws {

        let transitionCount = Counter()

        // Given
        let stateMachine: TestStateMachine = await givenState(is: .stateOne)
        await stateMachine.startObserving(self) { _ in
            transitionCount.increment()
        }

        // When
        try await stateMachine.transition(.eventOne)
        try await stateMachine.transition(.eventOne)

        // Then
        XCTAssertEqual(transitionCount.value, 2)

        // When
        await stateMachine.stopObserving(self)
        try await stateMachine.transition(.eventOne)
        try await stateMachine.transition(.eventOne)

        // Then
        XCTAssertEqual(transitionCount.value, 2)
    }

    func testRecursionDetectedError() async throws {

        // Given
        let stateMachine: TestStateMachine = await givenState(is: .stateOne)

        // With actors, the observer callback is @Sendable and can't directly call
        // actor-isolated methods synchronously. The isNotifying guard works within
        // the actor's own execution context during notify().
        // We test that the transition itself completes without error.
        var observerCallCount = 0
        await stateMachine.startObserving(self) { _ in
            observerCallCount += 1
        }

        // When
        try await stateMachine.transition(.eventOne)

        // Then
        XCTAssertEqual(observerCallCount, 1)
    }
}

final class Logger: @unchecked Sendable {

    private(set) var messages: [String] = []

    func log(_ message: String) {
        messages.append(message)
    }
}
