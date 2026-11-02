import UniversalAiConnector

public func candidateVersion() -> String {
    let client = UniversalAiConnector()
    defer { client.close() }
    return client.version
}
