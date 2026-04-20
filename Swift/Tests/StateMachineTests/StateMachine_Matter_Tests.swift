//
//  Copyright (c) 2019, Match Group, LLC
//  BSD License, see LICENSE file for details
//

@testable import StateMachine
import XCTest

final class StateMachine_Matter_Tests: XCTestCase, StateMachineBuilder {

    @StateMachineHashable
    enum State {

        case solid, liquid, gas
    }

    @StateMachineHashable
    enum Event {

        case melt, freeze, vaporize, condense
    }

    enum SideEffect {

        case logMelted, logFrozen, logVaporized, logCondensed
    }

    typealias MatterStateMachine = StateMachine<State, Event, SideEffect>
    typealias ValidTransition = MatterStateMachine.Transition.Valid
    typealias InvalidTransition = MatterStateMachine.Transition.Invalid

    enum Message {

        static let melted: String = "I melted"
        static let frozen: String = "I froze"
        static let vaporized: String = "I vaporized"
        static let condensed: String = "I condensed"
    }

    static func matterStateMachine(withInitialState _state: State, logger: Logger) -> MatterStateMachine {
        MatterStateMachine {
            initialState(_state)
            state(.solid) {
                on(.melt) {
                    transition(to: .liquid, emit: .logMelted)
                }
            }
            state(.liquid) {
                on(.freeze) {
                    transition(to: .solid, emit: .logFrozen)
                }
                on(.vaporize) {
                    transition(to: .gas, emit: .logVaporized)
                }
            }
            state(.gas) {
                on(.condense) {
                    transition(to: .liquid, emit: .logCondensed)
                }
            }
            onTransition {
                guard case let .success(transition) = $0, let sideEffect = transition.sideEffect else { return }
                switch sideEffect {
                case .logMelted: logger.log(Message.melted)
                case .logFrozen: logger.log(Message.frozen)
                case .logVaporized: logger.log(Message.vaporized)
                case .logCondensed: logger.log(Message.condensed)
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

    func givenState(is state: State) async -> MatterStateMachine {
        let stateMachine: MatterStateMachine = Self.matterStateMachine(withInitialState: state, logger: logger)
        let currentState = await stateMachine.state
        XCTAssertEqual(currentState, state)
        return stateMachine
    }

    func test_givenStateIsSolid_whenMelted_shouldTransitionToLiquidState() async throws {

        // Given
        let stateMachine: MatterStateMachine = await givenState(is: .solid)

        // When
        let transition: ValidTransition = try await stateMachine.transition(.melt)

        // Then
        let currentState = await stateMachine.state
        XCTAssertEqual(currentState, .liquid)
        XCTAssertEqual(transition, ValidTransition(fromState: .solid,
                                                    event: .melt,
                                                    toState: .liquid,
                                                    sideEffect: .logMelted))
        XCTAssertEqual(logger.messages, [Message.melted])
    }

    func test_givenStateIsSolid_whenFrozen_shouldThrowInvalidTransitionError() async throws {

        // Given
        let stateMachine: MatterStateMachine = await givenState(is: .solid)

        // When/Then
        do {
            _ = try await stateMachine.transition(.freeze)
            XCTFail("Expected InvalidTransition error")
        } catch is InvalidTransition {
            // Expected
        }
    }

    func test_givenStateIsLiquid_whenFrozen_shouldTransitionToSolidState() async throws {

        // Given
        let stateMachine: MatterStateMachine = await givenState(is: .liquid)

        // When
        let transition: ValidTransition = try await stateMachine.transition(.freeze)

        // Then
        let currentState = await stateMachine.state
        XCTAssertEqual(currentState, .solid)
        XCTAssertEqual(transition, ValidTransition(fromState: .liquid,
                                                    event: .freeze,
                                                    toState: .solid,
                                                    sideEffect: .logFrozen))
        XCTAssertEqual(logger.messages, [Message.frozen])
    }

    func test_givenStateIsLiquid_whenVaporized_shouldTransitionToGasState() async throws {

        // Given
        let stateMachine: MatterStateMachine = await givenState(is: .liquid)

        // When
        let transition: ValidTransition = try await stateMachine.transition(.vaporize)

        // Then
        let currentState = await stateMachine.state
        XCTAssertEqual(currentState, .gas)
        XCTAssertEqual(transition, ValidTransition(fromState: .liquid,
                                                    event: .vaporize,
                                                    toState: .gas,
                                                    sideEffect: .logVaporized))
        XCTAssertEqual(logger.messages, [Message.vaporized])
    }

    func test_givenStateIsGas_whenCondensed_shouldTransitionToLiquidState() async throws {

        // Given
        let stateMachine: MatterStateMachine = await givenState(is: .gas)

        // When
        let transition: ValidTransition = try await stateMachine.transition(.condense)

        // Then
        let currentState = await stateMachine.state
        XCTAssertEqual(currentState, .liquid)
        XCTAssertEqual(transition, ValidTransition(fromState: .gas,
                                                    event: .condense,
                                                    toState: .liquid,
                                                    sideEffect: .logCondensed))
        XCTAssertEqual(logger.messages, [Message.condensed])
    }
}
