import SOSliveCore
import SwiftUI

@MainActor
final class EventsViewModel: ObservableObject {
    @Published private(set) var events: [Event] = []
    @Published private(set) var loading = false
    @Published private(set) var error: String?

    private let service: EventService

    init(service: EventService) { self.service = service }

    func refresh() async {
        loading = true
        defer { loading = false }
        do {
            events = try await service.myEvents()
            error = nil
        } catch {
            self.error = L10n.error(error)
        }
    }
}

struct EventsView: View {
    @StateObject private var model: EventsViewModel

    init(events: EventService) {
        _model = StateObject(wrappedValue: EventsViewModel(service: events))
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
                    NavigationLink(value: Route.eventDetail(event.id)) {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("#\(event.id) · \(event.type.label)").font(.headline)
                                Text(event.createdAt.shortDateTime).font(.subheadline).foregroundStyle(.secondary)
                            }
                            Spacer()
                            StatusChip(status: event.status)
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
    @Published private(set) var event: Event?
    @Published private(set) var comments: [Comment] = []
    @Published private(set) var photos: [Photo] = []
    @Published private(set) var loading = false
    @Published private(set) var sending = false
    @Published var draft = ""
    @Published private(set) var error: String?

    let id: Int64
    private let service: EventService

    init(id: Int64, service: EventService) {
        self.id = id
        self.service = service
    }

    func refresh() async {
        loading = true
        defer { loading = false }
        async let event = service.event(id)
        async let comments = service.comments(id)
        async let photos = service.photos(id)
        do {
            self.event = try await event
            error = nil
        } catch {
            self.error = L10n.error(error)
        }
        if let page = try? await comments { self.comments = page.items }
        if let list = try? await photos { self.photos = list }
    }

    func sendComment() {
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, !sending else { return }
        sending = true
        Task {
            defer { sending = false }
            do {
                comments.append(try await service.addComment(id, message: text))
                draft = ""
            } catch {
                self.error = L10n.error(error)
            }
        }
    }
}

struct EventDetailView: View {
    @StateObject private var model: EventDetailViewModel

    init(id: Int64, events: EventService) {
        _model = StateObject(wrappedValue: EventDetailViewModel(id: id, service: events))
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let error = model.error {
                Text(error).foregroundStyle(.red).font(.callout)
            }
            if let event = model.event {
                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        Text(event.type.label).font(.title3.bold())
                        StatusChip(status: event.status)
                    }
                    Text(L10n.tr("event.started", event.createdAt.shortDateTime))
                    if let stopped = event.stoppedAt { Text(L10n.tr("event.stopped", stopped.shortDateTime)) }
                    Text(event.lastLocation.map { L10n.tr("event.location", $0.lat, $0.lng) } ?? L10n.tr("event.no_location"))
                        .font(.footnote).foregroundStyle(.secondary)
                    Text(L10n.tr("event.photos", model.photos.count)).font(.footnote).foregroundStyle(.secondary)
                }
                if !model.photos.isEmpty {
                    ScrollView(.horizontal) {
                        HStack {
                            ForEach(model.photos) { photo in
                                AsyncImage(url: URL(string: photo.url)) { image in
                                    image.resizable().scaledToFill()
                                } placeholder: {
                                    Color.gray.opacity(0.2)
                                }
                                .frame(width: 96, height: 96)
                                .clipShape(RoundedRectangle(cornerRadius: 8))
                            }
                        }
                    }
                }
                Divider()
                Text(L10n.tr("comments.title", model.comments.count)).font(.subheadline.bold())
                CommentList(comments: model.comments).frame(maxHeight: .infinity, alignment: .top)
                HStack {
                    TextField("comment.hint", text: $model.draft, axis: .vertical)
                        .lineLimit(1...3)
                        .textFieldStyle(.roundedBorder)
                    Button { model.sendComment() } label: { Image(systemName: "paperplane.fill") }
                        .disabled(model.draft.trimmingCharacters(in: .whitespaces).isEmpty || model.sending)
                        .accessibilityLabel(Text("action.send"))
                }
            } else if model.loading {
                ProgressView().frame(maxWidth: .infinity)
            }
            Spacer(minLength: 0)
        }
        .padding()
        .navigationTitle(L10n.tr("title.with_event", model.id))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if let url = model.event.flatMap({ URL(string: $0.shareUrl) }) {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Link(destination: url) { Image(systemName: "safari") }.accessibilityLabel(Text("action.open_web"))
                }
            }
        }
        .refreshable { await model.refresh() }
        .task { await model.refresh() }
    }
}

struct StatusChip: View {
    let status: EventStatus

    var body: some View {
        Text(LocalizedStringKey("status.\(status.rawValue.lowercased())"))
            .font(.caption.bold())
            .padding(.horizontal, 8).padding(.vertical, 3)
            .background(color.opacity(0.18), in: Capsule())
            .foregroundStyle(color)
    }

    private var color: Color {
        switch status {
        case .live: return .sosRed
        case .open: return .blue
        case .stopped, .unknown: return .gray
        }
    }
}

extension EventType {
    var label: String {
        switch self {
        case .sos: return L10n.tr("type.sos")
        case .live: return L10n.tr("type.live")
        case .photo: return L10n.tr("type.photo")
        }
    }
}
