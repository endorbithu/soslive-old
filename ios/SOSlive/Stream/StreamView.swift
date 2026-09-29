import SOSliveCore
import SwiftUI

struct StreamView: View {
    @ObservedObject var model: StreamViewModel
    @Binding var path: [Route]
    @Environment(\.scenePhase) private var scenePhase
    @State private var confirmStop = false

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if model.cameraAllowed {
                CameraPreview(controller: model.controller).ignoresSafeArea()
            } else {
                PermissionMissingView()
            }

            VStack(spacing: 8) {
                LiveBadge(phase: model.phase)
                if model.activeEvent != nil {
                    MessagesPanel(model: model)
                }
                Spacer()
                controls
            }
            .padding()

            if let toast = model.toast {
                Text(toast)
                    .font(.callout)
                    .padding(.horizontal, 16).padding(.vertical, 10)
                    .background(.ultraThinMaterial, in: Capsule())
                    .transition(.opacity)
                    .frame(maxHeight: .infinity, alignment: .center)
            }
        }
        .animation(.default, value: model.toast)
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(.visible, for: .navigationBar)
        .toolbar {
            ToolbarItemGroup(placement: .navigationBarTrailing) {
                Button { path.append(.events) } label: { Image(systemName: "list.bullet") }
                    .accessibilityLabel(Text("menu.my_events"))
                Button { path.append(.settings) } label: { Image(systemName: "gearshape") }
                    .accessibilityLabel(Text("menu.settings"))
                Menu {
                    Button("menu.new_incident", action: model.newIncident)
                        .disabled(model.phase != .idle || model.activeEvent == nil)
                } label: { Image(systemName: "ellipsis.circle") }
            }
        }
        .task { await model.onAppear() }
        .onChange(of: scenePhase) { phase in
            if phase == .background { model.onBackground() }
            if phase == .active { model.refreshActiveEvent() }
        }
        .confirmationDialog("stream.stop_confirm", isPresented: $confirmStop, titleVisibility: .visible) {
            Button("action.stop", role: .destructive, action: model.stopLive)
        }
        .sheet(item: $model.composer) { composer in
            switch composer {
            case let .sms(recipients, body):
                MessageComposer(recipients: recipients, body: body) { model.composerFinished() }.ignoresSafeArea()
            case let .mail(recipients, subject, body):
                MailComposer(recipients: recipients, subject: subject, body: body) { model.composerFinished() }.ignoresSafeArea()
            }
        }
        .fullScreenCover(item: $model.photoRequest) { request in
            CameraPicker { image in model.photoFinished(eventFileId: request.eventFileId, image: image) }
                .ignoresSafeArea()
        }
    }

    private var title: String {
        model.activeEvent?.title ?? "SOSlive"
    }

    @ViewBuilder
    private var controls: some View {
        VStack(spacing: 16) {
            HStack(spacing: 16) {
                RoundIcon(systemName: "arrow.triangle.2.circlepath.camera", label: "action.switch_camera", action: model.switchCamera)
                RoundIcon(systemName: model.torchOn ? "bolt.fill" : "bolt.slash", label: "action.flash", action: model.toggleTorch)
            }
            .disabled(!model.cameraAllowed)

            switch model.phase {
            case .idle:
                HStack(spacing: 28) {
                    RoundIcon(systemName: "video.fill", label: "action.live", size: 64) { model.startLive(.live) }
                        .disabled(!model.cameraAllowed)
                    Button { model.startLive(.sos) } label: {
                        Text("action.sos")
                            .font(.system(size: 30, weight: .black))
                            .foregroundStyle(.white)
                            .frame(width: 112, height: 112)
                            .background(Color.sosRed, in: Circle())
                    }
                    .disabled(!model.cameraAllowed)
                    .accessibilityHint(Text("action.sos_hint"))
                    RoundIcon(systemName: model.activeEvent == nil ? "camera.fill" : "camera.badge.ellipsis",
                              label: model.activeEvent == nil ? "action.photo" : "action.add_photo", size: 64,
                              busy: model.photoBusy, action: model.takePhoto)
                }
            case .creatingEvent, .stopping:
                ProgressView().tint(.white).controlSize(.large).frame(height: 112)
            case .connecting, .live:
                Button { confirmStop = true } label: {
                    Image(systemName: "stop.fill")
                        .font(.system(size: 40))
                        .foregroundStyle(Color.sosRed)
                        .frame(width: 96, height: 96)
                        .background(.white, in: Circle())
                }
                .accessibilityLabel(Text("action.stop"))
            }
        }
    }
}

private struct RoundIcon: View {
    let systemName: String
    let label: LocalizedStringKey
    var size: CGFloat = 48
    var busy = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            ZStack {
                if busy { ProgressView() } else { Image(systemName: systemName).font(.system(size: size * 0.4)) }
            }
            .frame(width: size, height: size)
            .background(.thinMaterial, in: Circle())
        }
        .disabled(busy)
        .accessibilityLabel(Text(label))
    }
}

private struct LiveBadge: View {
    let phase: StreamViewModel.Phase

    var body: some View {
        switch phase {
        case .idle:
            EmptyView()
        case .creatingEvent, .connecting:
            badge("stream.connecting", color: .orange)
        case .live:
            badge("stream.live", color: .sosRed)
        case .stopping:
            badge("stream.stopping", color: .gray)
        }
    }

    private func badge(_ text: LocalizedStringKey, color: Color) -> some View {
        Text(text)
            .font(.subheadline.bold())
            .foregroundStyle(.white)
            .padding(.horizontal, 12).padding(.vertical, 4)
            .background(color, in: Capsule())
    }
}

private struct PermissionMissingView: View {
    var body: some View {
        VStack(spacing: 16) {
            Text("permission.camera_needed").foregroundStyle(.white).multilineTextAlignment(.center)
            Button("action.open_settings") {
                if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
            }
            .buttonStyle(.borderedProminent)
        }
        .padding(32)
    }
}

/// Open incident: share its link and add the owner's own messages to the event page.
struct MessagesPanel: View {
    @ObservedObject var model: StreamViewModel

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Button { model.messagesExpanded.toggle() } label: {
                    HStack {
                        Text(L10n.tr("messages.title", model.messages.count)).font(.subheadline.bold())
                        Spacer()
                        Image(systemName: model.messagesExpanded ? "chevron.up" : "chevron.down")
                    }
                }
                .buttonStyle(.plain)
                if let link = model.activeEvent?.link, let url = URL(string: link) {
                    ShareLink(item: url) { Image(systemName: "square.and.arrow.up") }
                        .accessibilityLabel(Text("action.share_link"))
                }
            }

            if model.messagesExpanded {
                EntryList(entries: model.messages).frame(maxHeight: 200)
                HStack {
                    TextField("message.hint", text: $model.messageDraft, axis: .vertical)
                        .lineLimit(1...3)
                        .textFieldStyle(.roundedBorder)
                    Button { model.sendMessage() } label: { Image(systemName: "paperplane.fill") }
                        .disabled(model.messageDraft.trimmingCharacters(in: .whitespaces).isEmpty || model.sendingMessage)
                        .accessibilityLabel(Text("action.send"))
                }
            }
        }
        .padding(12)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
    }
}

/// Entries of an event file (position / message / image; unknown types are skipped, like on the web).
struct EntryList: View {
    let entries: [EventEntry]

    private var known: [(offset: Int, element: EventEntry)] {
        Array(entries.enumerated()).filter { ["pos", "msg", "img"].contains($0.element.type ?? "") }
    }

    var body: some View {
        if known.isEmpty {
            Text("entries.empty").font(.footnote).foregroundStyle(.secondary).frame(maxWidth: .infinity, alignment: .leading)
        } else {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 8) {
                        ForEach(known, id: \.offset) { item in
                            row(item.element).id(item.offset)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .onAppear { scrollToLast(proxy) }
                .onChange(of: entries.count) { _ in scrollToLast(proxy) }
            }
        }
    }

    private func scrollToLast(_ proxy: ScrollViewProxy) {
        if let last = known.last?.offset { proxy.scrollTo(last, anchor: .bottom) }
    }

    @ViewBuilder
    private func row(_ entry: EventEntry) -> some View {
        let time = entry.time.flatMap(parseISO)?.shortDateTime ?? ""
        switch entry.type ?? "" {
        case "msg":
            VStack(alignment: .leading, spacing: 2) {
                Text("\(entry.string("name") ?? "") · \(time)").font(.caption2)
                Text(entry.string("text") ?? "").font(.callout)
            }
        case "pos":
            Text(L10n.tr("entry.position", time, entry.double("lat") ?? 0, entry.double("lng") ?? 0)).font(.caption)
        default:
            Text(L10n.tr("entry.image", time)).font(.caption)
        }
    }
}
