final class ThrowingStreamIteratorBox<Element: Sendable>: @unchecked Sendable {
    private var iterator: AsyncThrowingStream<Element, Error>.Iterator

    init(stream: AsyncThrowingStream<Element, Error>) {
        iterator = stream.makeAsyncIterator()
    }

    func next() async throws -> Element? {
        try await iterator.next()
    }
}
