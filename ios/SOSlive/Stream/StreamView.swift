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
                    CommentsPanel(model: model)
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
                Button { path.append(.profile) } label: { Image(systemName: "person.crop.circle") }
                    .accessibilityLabel(Text("menu.profile"))
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
        .sheet(item: $model.smsRequest) { request in
            MessageComposer(recipients: request.recipients, body: request.body) { model.smsRequest = nil }
                .ignoresSafeArea()
        }
        .fullScreenCover(item: $model.photoRequest) { request in
            CameraPicker { image in model.photoFinished(eventId: request.eventId, image: image) }
                .ignoresSafeArea()
        }
    }

    private var title: String {
        if let id = model.liveEventId ?? model.activeEvent?.id { return L10n.tr("title.with_event", id) }
        return "SOSlive"
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

struct CommentsPanel: View {
    @ObservedObject var model: StreamViewModel

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Button { model.commentsExpanded.toggle() } label: {
                HStack {
                    Text(L10n.tr("comments.title", model.comments.count)).font(.subheadline.bold())
                    Spacer()
                    if model.unreadComments > 0 {
                        Text("\(model.unreadComments)")
                            .font(.caption.bold()).foregroundStyle(.white)
                            .padding(.horizontal, 7).padding(.vertical, 2)
                            .background(Color.sosRed, in: Capsule())
                    }
                    Image(systemName: model.commentsExpanded ? "chevron.up" : "chevron.down")
                }
            }
            .buttonStyle(.plain)

            if model.commentsExpanded {
                CommentList(comments: model.comments).frame(maxHeight: 220)
                HStack {
                    TextField("comment.hint", text: $model.commentDraft, axis: .vertical)
                        .lineLimit(1...3)
                        .textFieldStyle(.roundedBorder)
                    Button { model.sendComment() } label: { Image(systemName: "paperplane.fill") }
                        .disabled(model.commentDraft.trimmingCharacters(in: .whitespaces).isEmpty || model.sendingComment)
                        .accessibilityLabel(Text("action.send"))
                }
            }
        }
        .padding(12)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
    }
}

struct CommentList: View {
    let comments: [Comment]

    var body: some View {
        if comments.isEmpty {
            Text("comments.empty").font(.footnote).foregroundStyle(.secondary).frame(maxWidth: .infinity, alignment: .leading)
        } else {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 8) {
                        ForEach(comments) { comment in
                            VStack(alignment: .leading, spacing: 2) {
                                Text("\(comment.authorName) · \(comment.createdAt.shortDateTime)")
                                    .font(.caption2)
                                    .fontWeight(comment.fromOwner ? .regular : .bold)
                                Text(comment.message).font(.callout)
                            }
                            .id(comment.id)
                        }
                    }
                }
                .onAppear { scrollToLast(proxy) }
                .onChange(of: comments.count) { _ in scrollToLast(proxy) }
            }
        }
    }

    private func scrollToLast(_ proxy: ScrollViewProxy) {
        if let id = comments.last?.id { proxy.scrollTo(id, anchor: .bottom) }
    }
}
