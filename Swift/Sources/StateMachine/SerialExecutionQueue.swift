//
//  SerialExecutionQueue.swift
//  Ensures serial execution of async operations within an actor.
//
//  Swift actors guarantee single-threaded access, but they are RE-ENTRANT:
//  when an actor method hits an `await`, other calls can interleave.
//  This queue ensures that async operations complete fully before the next begins.
//
//  See: https://blog.jacobstechtavern.com/p/advanced-swift-actors-re-entrancy
//

public actor SerialExecutionQueue {
    private var queue: [() async -> Void] = []

    public init() {}

    public func push(_ operation: @escaping () async -> Void) async {
        let wasEmpty = queue.isEmpty
        queue.append(operation)

        guard wasEmpty else {
            return
        }

        while let oldestOperation = queue.first {
            await oldestOperation()
            queue.removeFirst()
        }
    }
}
