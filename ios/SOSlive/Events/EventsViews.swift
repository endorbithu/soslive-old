import SOSliveCore
import SwiftUI

@MainActor
final class EventsViewModel: ObservableObject {
    @Published private(set) var events: [EventSummary] = []
    @Published private(set) var loading = false
    @Published private(set) var error: String?

    private let drive: SosliveDrive

    init(drive: SosliveDrive) { self.drive = drive }

    func refresh() async {
        loading = true
        defer { loading = false }
        do {
            events = try await drive.listEvents()
            error = nil
        } catch {
            self.error = L10n.error(error)
        }
    }
}

/// The event files in the user's SOSlive folder, newest first.
struct EventsView: View {
    @StateObject private var model: EventsViewModel

    init(drive: SosliveDrive) {
        _model = StateObject(wrappedValue: EventsViewModel(drive: drive))
    }

    var body: some View {
        Group {
            if model.events.isEmpty {
                VStack {
                    if model.loading {
                        ProgressView()
                    } else {
                        Text(model.error ?? L10n.tr("events.empty")).foregroundStyle(.secondary).multilineTextAlignment(.center)
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .padding()
            } else {
                List(model.events) { event in
                    NavigationLink(value: Route.eventDetail(fileId: event.fileId, title: event.title)) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(event.title).font(.headline)
                            Text("events.utc_hint").font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
                .listStyle(.plain)
            }
        }
        .navigationTitle(Text("menu.my_events"))
        .refreshable { await model.refresh() }
        .task { await model.refresh() }
    }
}

@MainActor
final class EventDetailViewModel: ObservableObject {
    @Published private(set) var document: EventDocument?
    @Published private(set) var loading = false
    @Published private(set) var error: String?

    let fileId: String
    let link: String
    private let drive: SosliveDrive

    init(fileId: String, drive: SosliveDrive) {
        self.fileId = fileId
        self.drive = drive
        link = drive.link(fileId: fileId)
    }

    func refresh() async {
        loading = true
        defer { loading = false }
        do {
            document = try await drive.readEvent(fileId: fileId)
            error = nil
        } catch {
            self.error = L10n.error(error)
        }
    }
}

struct EventDetailView: View {
    @StateObject private var model: EventDetailViewModel
    private let title: String

    init(fileId: String, title: String, drive: SosliveDrive) {
        self.title = title
        _model = StateObject(wrappedValue: EventDetailViewModel(fileId: fileId, drive: drive))
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let error = model.error {
                Text(error).foregroundStyle(.red).font(.callout)
            }
            Text(model.link).font(.footnote).foregroundStyle(.secondary).textSelection(.enabled)
            if let document = model.document {
                Text(document.stream.isEmpty ? L10n.tr("event.no_stream") : L10n.tr("event.stream", document.stream))
                    .font(.footnote)
                Divider()
                EntryList(entries: document.entries).frame(maxHeight: .infinity, alignment: .top)
            } else if model.loading {
                ProgressView().frame(maxWidth: .infinity)
            }
            Spacer(minLength: 0)
        }
        .padding()
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if let url = URL(string: model.link) {
                ToolbarItemGroup(placement: .navigationBarTrailing) {
                    ShareLink(item: url) { Image(systemName: "square.and.arrow.up") }
                    Link(destination: url) { Image(systemName: "safari") }.accessibilityLabel(Text("action.open_web"))
                }
            }
        }
        .refreshable { await model.refresh() }
        .task { await model.refresh() }
    }
}
