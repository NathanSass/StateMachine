//
//  Thread-safe helpers for tests with actor-isolated callbacks
//

import Foundation

/// Thread-safe mutable box for use in @Sendable closures
final class Box<T>: @unchecked Sendable {
    private var _value: T
    private let lock = NSLock()

    init(_ value: T) { _value = value }

    var value: T {
        get { lock.lock(); defer { lock.unlock() }; return _value }
        set { lock.lock(); defer { lock.unlock() }; _value = newValue }
    }
}

/// Thread-safe mutable list for use in @Sendable closures
final class SafeList<T>: @unchecked Sendable {
    private var _values: [T] = []
    private let lock = NSLock()

    var values: [T] {
        lock.lock(); defer { lock.unlock() }; return _values
    }

    func append(_ value: T) {
        lock.lock(); defer { lock.unlock() }; _values.append(value)
    }
}

/// Thread-safe counter for use in @Sendable closures
final class Counter: @unchecked Sendable {
    private var _value: Int = 0
    private let lock = NSLock()

    var value: Int {
        lock.lock(); defer { lock.unlock() }; return _value
    }

    func increment() {
        lock.lock(); defer { lock.unlock() }; _value += 1
    }
}
