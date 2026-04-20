//
//  Copyright (c) 2018, Match Group, LLC
//  BSD License, see LICENSE file for details
//

public actor StateMachine<State: StateMachineHashable & Sendable, Event: StateMachineHashable & Sendable, SideEffect: Sendable> {

    public enum Transition {

        public typealias Result = Swift.Result<Valid, Error>
        public typealias Callback = @Sendable (_ result: Result) -> Void

        public struct Valid: CustomDebugStringConvertible, Sendable {

            public var debugDescription: String {
                guard let sideEffect: SideEffect = sideEffect
                else { return "fromState: \(fromState), event: \(event), toState: \(toState), sideEffect: nil" }
                return "fromState: \(fromState), event: \(event), toState: \(toState), sideEffect: \(sideEffect)"
            }

            public let fromState: State
            public let event: Event
            public let toState: State
            public let sideEffect: SideEffect?
        }

        public struct Invalid: Error, Equatable, Sendable {}
    }

    public enum StateMachineError: Error, Sendable {

        case recursionDetected
    }

    private struct Observer: Sendable {

        weak var object: AnyObject?

        let callback: Transition.Callback

        init(object: AnyObject?, callback: @escaping Transition.Callback) {
            self.object = object
            self.callback = callback
        }
    }

    public typealias Definition = StateMachineTypes.Definition<State, Event, SideEffect>

    private typealias DefinitionBuilder = StateMachineTypes.DefinitionBuilder
    private typealias InitialState = StateMachineTypes.InitialState<State>
    private typealias Component = StateMachineTypes.Component<State, Event, SideEffect>

    private typealias States = [State.HashableIdentifier: StateDefinition]
    private typealias Events = [Event.HashableIdentifier: Action.Factory]

    private typealias EventHandler = StateMachineTypes.EventHandler<State, Event, SideEffect>
    private typealias Action = StateMachineTypes.Action<State, Event, SideEffect>

    /// Internal state definition that holds event handlers, lifecycle callbacks, and factory
    private struct StateDefinition: Sendable {
        let events: Events
        let onCleanUpCallbacks: [@Sendable (State, Event) -> Void]
        let factory: (@Sendable (State) -> State)?
        let isTerminal: Bool
        let targetStateIdentifiers: Set<AnyHashable>
    }

    public private(set) var state: State

    private let states: States
    private let terminalStateIdentifiers: Set<AnyHashable>
    private nonisolated(unsafe) var observers: [Observer] = []

    private var isNotifying: Bool = false

    public init(@DefinitionBuilder build: () -> Definition) {
        let definition: Definition = build()
        state = definition.initialState.state

        var builtStates = States()
        var terminalIds = Set<AnyHashable>()

        // TODO: Process definition.components to build state definitions
        // - For .state components: create StateDefinition with events, onCleanUpCallbacks, factory, targetStateIdentifiers
        // - For .terminalState components: create StateDefinition with empty events, isTerminal=true, and add to terminalIds
        // - For .callback components: skip (handled below for observers)

        states = builtStates
        terminalStateIdentifiers = terminalIds

        // TODO: Implement graph validation (only when targetStateIdentifiers are used across any state)
        // - All target state identifiers must have registered definitions
        // - Cannot start in a terminal state
        // - All non-terminal registered states must be reachable from initial state (BFS/DFS)
        // - Terminal states are exempt from reachability checks (they are valid sinks)
        // - Use precondition() for validation failures

        observers = definition.callbacks.map {
            Observer(object: self, callback: $0)
        }
    }

    @discardableResult
    public func startObserving(_ observer: AnyObject?, callback: @escaping Transition.Callback) -> Self {
        guard let observer: AnyObject = observer
        else { return self }
        observers.append(Observer(object: observer, callback: callback))
        return self
    }

    public func stopObserving(_ observers: AnyObject?...) {
        stopObserving(observers)
    }

    public func stopObserving(_ observers: [AnyObject?]) {
        self.observers.removeAll {
            guard let object: AnyObject = $0.object
            else { return true }
            return observers.contains { $0 === object }
        }
    }

    @discardableResult
    public func transition(_ event: Event) throws -> Transition.Valid {
        guard !isNotifying
        else { throw StateMachineError.recursionDetected }
        let result: Transition.Result
        defer { notify(result) }
        do {
            let stateIdentifier: State.HashableIdentifier = state.hashableIdentifier
            let eventIdentifier: Event.HashableIdentifier = event.hashableIdentifier
            let stateDefinition: StateDefinition? = states[stateIdentifier]
            let factory: Action.Factory? = stateDefinition?.events[eventIdentifier]
            if let action: Action = try factory?(state, event) {
                // TODO: Implement lifecycle-aware transition:
                // 1. Determine if this is a real transition (action.toState != nil) or dontTransition
                // 2. If real transition: call onCleanUp callbacks on the OLD state BEFORE creating new state
                //    - onCleanUp should NOT fire for dontTransition
                //    - onCleanUp receives the current state and the causing event
                // 3. Resolve the target state through factory if one is registered for the target state's definition
                //    - Look up the target state's definition by its hashableIdentifier
                //    - If a factory exists, call it with the intended state value to get a fresh instance
                //    - The factory-created state should be used in the Transition.Valid AND set as current state
                // 4. Update self.state to the resolved state

                let transition: Transition.Valid = .init(fromState: state,
                                                         event: event,
                                                         toState: action.toState ?? state,
                                                         sideEffect: action.sideEffect)
                if let toState: State = action.toState {
                    state = toState
                }
                result = .success(transition)
            } else {
                result = .failure(Transition.Invalid())
            }
        } catch {
            result = .failure(error)
        }
        return try result.get()
    }

    private func notify(_ result: Transition.Result) {
        isNotifying = true
        defer { isNotifying = false }
        var observers: [Observer] = []
        for observer in self.observers {
            guard observer.object != nil
            else { continue }
            observers.append(observer)
            observer.callback(result)
        }
        self.observers = observers
    }
}

extension StateMachine.Transition.Valid: Equatable where State: Equatable,
                                                         Event: Equatable,
                                                         SideEffect: Equatable {}

public protocol StateMachineBuilder {

    associatedtype State: StateMachineHashable & Sendable
    associatedtype Event: StateMachineHashable & Sendable

    associatedtype SideEffect: Sendable

    typealias InitialState = StateMachineTypes.InitialState<State>
    typealias Component = StateMachineTypes.Component<State, Event, SideEffect>

    typealias EventHandlerArrayBuilder = StateMachineTypes.EventHandlerArrayBuilder

    typealias EventHandler = StateMachineTypes.EventHandler<State, Event, SideEffect>
    typealias Action = StateMachineTypes.Action<State, Event, SideEffect>
}

extension StateMachineBuilder {

    public static func initialState(
        _ state: State
    ) -> InitialState {
        InitialState(state: state)
    }

    public static func state(
        _ state: State.HashableIdentifier
    ) -> Component {
        .state(state: state, events: [], onCleanUpCallbacks: [], factory: nil, targetStateIdentifiers: Set())
    }

    public static func state(
        _ state: State.HashableIdentifier,
        @EventHandlerArrayBuilder build: () -> [EventHandler]
    ) -> Component {
        .state(state: state, events: build(), onCleanUpCallbacks: [], factory: nil, targetStateIdentifiers: Set())
    }

    public static func state(
        _ state: State.HashableIdentifier,
        onCleanUp: [@Sendable (State, Event) -> Void] = [],
        factory: (@Sendable (State) -> State)? = nil,
        targetStateIdentifiers: Set<AnyHashable> = Set(),
        @EventHandlerArrayBuilder build: () -> [EventHandler]
    ) -> Component {
        .state(state: state, events: build(), onCleanUpCallbacks: onCleanUp, factory: factory, targetStateIdentifiers: targetStateIdentifiers)
    }

    public static func terminalState(
        _ state: State.HashableIdentifier
    ) -> Component {
        .terminalState(state: state)
    }

    public static func on(
        _ event: Event.HashableIdentifier,
        perform: @escaping @Sendable (State, Event) throws -> Action
    ) -> [EventHandler] {
        [EventHandler(event: event, action: perform)]
    }

    public static func on(
        _ event: Event.HashableIdentifier,
        perform: @escaping @Sendable (State) throws -> Action
    ) -> [EventHandler] {
        [EventHandler(event: event) { state, _ in try perform(state) }]
    }

    public static func on(
        _ event: Event.HashableIdentifier,
        perform: @escaping @Sendable () throws -> Action
    ) -> [EventHandler] {
        [EventHandler(event: event) { _, _ in try perform() }]
    }

    public static func transition(
        to state: State,
        emit sideEffect: SideEffect? = nil
    ) -> Action {
        Action(toState: state, sideEffect: sideEffect)
    }

    public static func dontTransition(
        emit sideEffect: SideEffect? = nil
    ) -> Action {
        Action(toState: nil, sideEffect: sideEffect)
    }

    public static func onTransition(
        _ callback: @escaping StateMachine<State, Event, SideEffect>.Transition.Callback
    ) -> Component {
        .callback(callback: callback)
    }
}

public enum StateMachineTypes {

    public struct Definition<State: StateMachineHashable & Sendable, Event: StateMachineHashable & Sendable, SideEffect: Sendable> {

        let initialState: InitialState<State>
        let components: [Component<State, Event, SideEffect>]

        typealias Callbacks = [StateMachine<State, Event, SideEffect>.Transition.Callback]

        var callbacks: Callbacks {
            components.compactMap {
                guard case let .callback(callback) = $0
                else { return nil }
                return callback
            }
        }
    }

    @resultBuilder
    public struct DefinitionBuilder {

        public static func buildBlock<State, Event, SideEffect>(
            _ initialState: InitialState<State>,
            _ components: Component<State, Event, SideEffect>...
        ) -> Definition<State, Event, SideEffect> {
            Definition(initialState: initialState, components: components)
        }
    }

    public struct InitialState<State> {

        let state: State
    }

    public enum Component<State: StateMachineHashable & Sendable, Event: StateMachineHashable & Sendable, SideEffect: Sendable> {

        case state(
            state: State.HashableIdentifier,
            events: [EventHandler<State, Event, SideEffect>],
            onCleanUpCallbacks: [@Sendable (State, Event) -> Void],
            factory: (@Sendable (State) -> State)?,
            targetStateIdentifiers: Set<AnyHashable>
        )
        case terminalState(state: State.HashableIdentifier)
        case callback(callback: StateMachine<State, Event, SideEffect>.Transition.Callback)
    }

    @resultBuilder
    public struct EventHandlerArrayBuilder {

        public static func buildBlock<State, Event, SideEffect>(
            _ events: [EventHandler<State, Event, SideEffect>]...
        ) -> [EventHandler<State, Event, SideEffect>] {
            Array(events.joined())
        }
    }

    public struct EventHandler<State: StateMachineHashable & Sendable, Event: StateMachineHashable & Sendable, SideEffect: Sendable>: Sendable {

        let event: Event.HashableIdentifier
        let action: Action<State, Event, SideEffect>.Factory
    }

    public struct Action<State: StateMachineHashable & Sendable, Event: StateMachineHashable & Sendable, SideEffect: Sendable>: Sendable {

        typealias Factory = @Sendable (State, Event) throws -> Self

        let toState: State?
        let sideEffect: SideEffect?
    }

    public struct IncorrectTypeError: Error, CustomDebugStringConvertible {

        public var debugDescription: String {
            "Incorrect Type: expected `\(expectedType)`, encountered `\(encounteredType)`"
        }

        public let expectedType: Any.Type
        public let encounteredType: Any.Type
    }
}

@dynamicMemberLookup
public protocol StateMachineHashable {

    associatedtype HashableIdentifier: Hashable

    typealias IncorrectTypeError = StateMachineTypes.IncorrectTypeError

    var hashableIdentifier: HashableIdentifier { get }
    var associatedValue: Any { get }
}

extension StateMachineHashable where Self: Hashable {

    public var hashableIdentifier: Self { self }
}

extension StateMachineHashable {

    public var associatedValue: Any { () }

    // TODO: [CF] Return T (instead of closure) once Swift supports throwing subscript
    public subscript<T>(dynamicMember member: String) -> () throws -> T {
        { [associatedValue] in
            guard let value: T = associatedValue as? T
            else { throw IncorrectTypeError(expectedType: T.self, encounteredType: type(of: associatedValue)) }
            return value
        }
    }
}

public protocol AutoStateMachineHashable {}
