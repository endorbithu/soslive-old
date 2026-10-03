import Foundation
@testable import SOSliveCore

/// In-memory DriveAPI with failure injection for updateContent.
final class FakeDriveAPI: DriveAPI {
    struct Item {
        var file: DriveFile
        var tag: DriveTag
        var parentId: String?
        var folder: Bool
        var content = Data()
        var shared = false
        var viewers: [DrivePermission] = []
    }

    private let lock = NSLock()
    private var _items: [Item] = []
    private var _uploads: [Data] = []
    private var _updateFailures: [Error] = []
    var shareFailure: Error?
    private var seq = 0
    private var time = Date(timeIntervalSince1970: 1_767_225_600) // 2026-01-01

    var items: [Item] { lock.lock(); defer { lock.unlock() }; return _items }
    var uploads: [Data] { lock.lock(); defer { lock.unlock() }; return _uploads }

    func failNextUpdates(_ errors: Error...) { lock.lock(); _updateFailures += errors; lock.unlock() }
    func clear() { lock.lock(); _items = []; lock.unlock() }
    func item(_ id: String) -> Item { items.first { $0.file.id == id }! }

    func addFolder(_ id: String, created: Date) {
        lock.lock(); defer { lock.unlock() }
        _items.append(Item(file: DriveFile(id: id, name: DriveNames.folder, createdTime: created), tag: .root, parentId: nil, folder: true))
    }

    private func next() -> (String, Date) {
        seq += 1
        time = time.addingTimeInterval(1)
        return ("id\(seq)", time)
    }

    func find(tag: DriveTag, parentId: String?, newestFirst: Bool, folderOnly: Bool) async throws -> [DriveFile] {
        let sorted = items
            .filter { $0.tag == tag && !$0.file.trashed && (!folderOnly || $0.folder) && (parentId == nil || $0.parentId == parentId) }
            .sorted { ($0.file.createdTime ?? .distantPast) < ($1.file.createdTime ?? .distantPast) }
            .map(\.file)
        return newestFirst ? sorted.reversed() : sorted
    }

    func file(id: String) async throws -> DriveFile? { items.first { $0.file.id == id }?.file }

    func createFolder(name: String, tag: DriveTag, parentId: String?) async throws -> DriveFile {
        lock.lock(); defer { lock.unlock() }
        let (id, created) = next()
        let file = DriveFile(id: id, name: name, createdTime: created)
        _items.append(Item(file: file, tag: tag, parentId: parentId, folder: true))
        return file
    }

    func addFile(_ id: String, tag: DriveTag, parentId: String) {
        lock.lock(); defer { lock.unlock() }
        let (_, created) = next()
        _items.append(Item(file: DriveFile(id: id, name: "\(id).json", createdTime: created), tag: tag, parentId: parentId, folder: false))
    }

    func moveFile(id: String, from fromParentId: String, to toParentId: String) async throws {
        lock.lock(); defer { lock.unlock() }
        guard let index = _items.firstIndex(where: { $0.file.id == id }), _items[index].parentId == fromParentId else {
            throw DriveError.notFound(id)
        }
        _items[index].parentId = toParentId
    }

    func listUserPermissions(id: String) async throws -> [DrivePermission] {
        guard let item = items.first(where: { $0.file.id == id && !$0.file.trashed }) else { throw DriveError.notFound(id) }
        return item.viewers
    }

    func shareWithUser(id: String, email: String) async throws -> DrivePermission {
        lock.lock(); defer { lock.unlock() }
        seq += 1
        let permission = DrivePermission(id: "p\(seq)", email: email)
        guard let index = _items.firstIndex(where: { $0.file.id == id }) else { throw DriveError.notFound(id) }
        _items[index].viewers.append(permission)
        return permission
    }

    func removePermission(id: String, permissionId: String) async throws {
        lock.lock(); defer { lock.unlock() }
        guard let index = _items.firstIndex(where: { $0.file.id == id }),
              _items[index].viewers.contains(where: { $0.id == permissionId }) else { throw DriveError.notFound(permissionId) }
        _items[index].viewers.removeAll { $0.id == permissionId }
    }

    func createFile(name: String, mimeType: String, parentId: String, tag: DriveTag, content: Data) async throws -> DriveFile {
        lock.lock(); defer { lock.unlock() }
        guard _items.contains(where: { $0.file.id == parentId && !$0.file.trashed }) else { throw DriveError.notFound(parentId) }
        let (id, created) = next()
        let file = DriveFile(id: id, name: name, createdTime: created)
        _items.append(Item(file: file, tag: tag, parentId: parentId, folder: false, content: content))
        return file
    }

    func updateContent(id: String, mimeType: String, content: Data) async throws {
        lock.lock(); defer { lock.unlock() }
        if !_updateFailures.isEmpty { throw _updateFailures.removeFirst() }
        guard let index = _items.firstIndex(where: { $0.file.id == id && !$0.file.trashed }) else { throw DriveError.notFound(id) }
        _items[index].content = content
        _uploads.append(content)
    }

    func download(id: String) async throws -> Data {
        guard let item = items.first(where: { $0.file.id == id && !$0.file.trashed }) else { throw DriveError.notFound(id) }
        return item.content
    }

    func shareAnyoneReader(id: String) async throws {
        if let shareFailure { throw shareFailure }
        lock.lock(); defer { lock.unlock() }
        if let index = _items.firstIndex(where: { $0.file.id == id }) { _items[index].shared = true }
    }

    func trash(id: String) async throws {
        lock.lock(); defer { lock.unlock() }
        if let index = _items.firstIndex(where: { $0.file.id == id }) { _items[index].file.trashed = true }
    }
}

final class MemoryDriveCache: DriveCache {
    var folderId: String?
    var configData: Data?
}
