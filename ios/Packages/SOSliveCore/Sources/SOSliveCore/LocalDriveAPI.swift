import Foundation

/// Simulated Drive for development without a Google Cloud project: the same operations,
/// stored in a local directory. Links built from these ids cannot be opened by anyone else.
public final class LocalDriveAPI: DriveAPI {
    private struct Meta: Codable {
        var id: String
        var name: String
        var tag: String
        var parentId: String?
        var folder: Bool
        var created: Date
        var trashed = false
        var shared = false
    }

    private let root: URL
    private let now: () -> Date
    private let lock = NSLock()

    public init(root: URL, now: @escaping () -> Date = Date.init) {
        self.root = root
        self.now = now
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }

    private var indexURL: URL { root.appendingPathComponent("index.json") }

    private func withIndex<T>(_ body: (inout [Meta]) throws -> T) throws -> T {
        lock.lock(); defer { lock.unlock() }
        var index = (try? JSONDecoder().decode([Meta].self, from: Data(contentsOf: indexURL))) ?? []
        let result = try body(&index)
        try JSONEncoder().encode(index).write(to: indexURL, options: .atomic)
        return result
    }

    private static func require(_ index: [Meta], _ id: String) throws -> Meta {
        guard let meta = index.first(where: { $0.id == id && !$0.trashed }) else { throw DriveError.notFound("File not found: \(id)") }
        return meta
    }

    private static func file(_ meta: Meta) -> DriveFile {
        DriveFile(id: meta.id, name: meta.name, createdTime: meta.created, trashed: meta.trashed)
    }

    public func find(tag: DriveTag, parentId: String?, newestFirst: Bool, folderOnly: Bool) async throws -> [DriveFile] {
        try withIndex { index in
            let sorted = index
                .filter { $0.tag == tag.rawValue && !$0.trashed && (!folderOnly || $0.folder) && (parentId == nil || $0.parentId == parentId) }
                .sorted { $0.created < $1.created }
            return (newestFirst ? sorted.reversed() : sorted).map(Self.file)
        }
    }

    public func file(id: String) async throws -> DriveFile? {
        try withIndex { index in index.first { $0.id == id }.map(Self.file) }
    }

    public func createFolder(name: String, tag: DriveTag) async throws -> DriveFile {
        try withIndex { index in
            let meta = Meta(id: UUID().uuidString, name: name, tag: tag.rawValue, parentId: nil, folder: true, created: now())
            index.append(meta)
            return Self.file(meta)
        }
    }

    public func createFile(name: String, mimeType: String, parentId: String, tag: DriveTag, content: Data) async throws -> DriveFile {
        try withIndex { index in
            _ = try Self.require(index, parentId)
            let meta = Meta(id: UUID().uuidString, name: name, tag: tag.rawValue, parentId: parentId, folder: false, created: now())
            try content.write(to: root.appendingPathComponent(meta.id), options: .atomic)
            index.append(meta)
            return Self.file(meta)
        }
    }

    public func updateContent(id: String, mimeType: String, content: Data) async throws {
        try withIndex { index in
            _ = try Self.require(index, id)
            try content.write(to: root.appendingPathComponent(id), options: .atomic)
        }
    }

    public func download(id: String) async throws -> Data {
        try withIndex { index in
            _ = try Self.require(index, id)
            return try Data(contentsOf: root.appendingPathComponent(id))
        }
    }

    public func shareAnyoneReader(id: String) async throws {
        try withIndex { index in
            guard let position = index.firstIndex(where: { $0.id == id }) else { throw DriveError.notFound(id) }
            index[position].shared = true
        }
    }

    public func trash(id: String) async throws {
        try withIndex { index in
            guard let position = index.firstIndex(where: { $0.id == id }) else { throw DriveError.notFound(id) }
            index[position].trashed = true
        }
    }

    public func publicImageURL(id: String) -> String {
        root.appendingPathComponent(id).absoluteString
    }
}
