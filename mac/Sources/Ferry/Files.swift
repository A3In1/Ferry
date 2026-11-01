import AppKit
import FerryCore
import FerryLogic
import SwiftUI

/// Phase 6: one folder of the phone's shared storage at a time, as the phone last listed it.
@MainActor @Observable final class PhoneFiles {
    var path = "" // the folder shown (or being asked for); always from FerryLogic, so canonical
    var entries: [FfiFsEntry] = []
    var error = ""
    var loading = false
    var silent = false // asked, nothing came back within 5 s
    var note: String?
    var back: [String] = []
    @ObservationIgnored var heard = 0
}

extension FfiFsEntry: Identifiable {
    public var id: String { name }
}

extension AppModel {
    func files(_ peer: String) -> PhoneFiles { phoneFiles.value(peer, orInsert: PhoneFiles.init) }

    func openFiles(_ peer: FfiPeer) {
        showWindow(\.fileWindows, for: peer) { FilesWindow(model: self, peer: peer) }
    }

    /// Entries are cleared first: their names only make sense joined to the folder they came from.
    func listFiles(_ path: String, peer: String, remember: Bool = true) {
        let f = files(peer)
        guard let path = normalizedPhonePath(path) else { return }
        if remember, path != f.path { f.back.append(f.path) }
        f.path = path
        f.entries = []
        f.error = ""
        f.loading = true
        ifUnanswered({ f.heard }) {
            guard f.path == path else { return }
            f.silent = true
            f.loading = false
        }
        broadcast(.fsListRequest(path: path), to: [peer])
    }

    func goBack(peer: String) {
        guard let prev = files(peer).back.popLast() else { return }
        listFiles(prev, peer: peer, remember: false)
    }

    func downloadFiles(_ names: Set<String>, peer: String) {
        let f = files(peer)
        let picked = f.entries.filter { names.contains($0.name) && !$0.dir }
            .compactMap { e in phonePath(f.path, e.name).map { (name: e.name, path: $0) } }
        guard !picked.isEmpty else { return }
        broadcast(.fsGet(paths: picked.map(\.path)), to: [peer], what: "the download request")
        f.note = "Requested \(describe(picked.map(\.name))), arriving in Downloads…"
    }

    func uploadFiles(_ urls: [URL], to peer: FfiPeer) {
        let urls = urls.filter(\.isFileURL)
        guard !urls.isEmpty else { return }
        send(urls, to: peer)
        files(peer.id).note = "Sending \(describe(urls.map(\.lastPathComponent))): they land in Download/Ferry on the phone"
    }

    /// From the phone (Sync.swift). Answers for a folder we've since left are dropped.
    func handleFiles(path: String, entries: [FfiFsEntry], error: String, from peer: String) {
        let f = files(peer)
        guard normalizedPhonePath(path) == f.path else { return }
        f.heard += 1
        f.silent = false
        f.loading = false
        f.error = error
        f.entries = entries.sorted { folderFirst(($0.dir, $0.name), ($1.dir, $1.name)) }
    }
}

/// "Browse <phone>": path bar, folder listing, Download / Upload (or drop Mac files on the window).
final class FilesWindow: PeerWindow {
    init(model: AppModel, peer: FfiPeer) {
        super.init(model: model, peer: peer, title: "Browse \(peer.name)", size: NSSize(width: 720, height: 520))
        host(FilesView(model: model, peer: peer, files: model.files(peer.id)))
        model.listFiles(model.files(peer.id).path, peer: peer.id, remember: false)
        present()
    }
}

struct FilesView: View {
    let model: AppModel
    let peer: FfiPeer
    @Bindable var files: PhoneFiles
    @State private var selection = Set<String>()
    @State private var dropping = false

    var body: some View {
        VStack(spacing: 0) {
            pathBar.padding(.horizontal, 10).padding(.vertical, 6)
            Divider()
            Table(files.entries, selection: $selection) {
                TableColumn("Name") { e in
                    Label(e.name, systemImage: e.dir ? "folder.fill" : "doc").lineLimit(1)
                }
                TableColumn("Size") { e in
                    Text(e.dir ? "" : byteCount(e.size))
                        .foregroundStyle(.secondary)
                }
                .width(min: 60, ideal: 80, max: 120)
                TableColumn("Modified") { e in
                    Text(e.modifiedMs > 0 ? Date(timeIntervalSince1970: Double(e.modifiedMs) / 1000)
                        .formatted(date: .abbreviated, time: .shortened) : "")
                        .foregroundStyle(.secondary)
                }
                .width(min: 120, ideal: 160, max: 220)
            }
            .contextMenu(forSelectionType: String.self) { _ in } primaryAction: { open($0) }
            .overlay { placeholder }
            Divider()
            HStack {
                Text(files.note ?? "Drop Mac files here to send them to the phone").font(.callout).foregroundStyle(.secondary)
                    .lineLimit(1)
                Spacer()
                Button("Upload…") { model.chooseFiles("Send to \(peer.name)").map { model.uploadFiles($0, to: peer) } }
                Button("Download") { model.downloadFiles(selection, peer: peer.id) }
                    .keyboardShortcut("d", modifiers: .command)
                    .disabled(!files.entries.contains { selection.contains($0.name) && !$0.dir })
            }
            .padding(10)
        }
        .overlay {
            if dropping { RoundedRectangle(cornerRadius: 8).stroke(Color.accentColor, lineWidth: 3).padding(2) }
        }
        .dropDestination(for: URL.self) { urls, _ in
            model.uploadFiles(urls, to: peer)
            return true
        } isTargeted: { dropping = $0 }
        .toolbar {
            ToolbarItemGroup(placement: .navigation) {
                Button { model.goBack(peer: peer.id) } label: { Label("Back", systemImage: "chevron.left") }
                    .disabled(files.back.isEmpty)
                Button { model.listFiles(parentPhonePath(files.path), peer: peer.id) } label: {
                    Label("Up", systemImage: "arrow.up")
                }
                .disabled(files.path.isEmpty)
            }
            ToolbarItem {
                Button { model.listFiles(files.path, peer: peer.id, remember: false) } label: {
                    Label("Refresh", systemImage: "arrow.clockwise")
                }
            }
        }
        .onChange(of: files.path) { selection = [] }
        .frame(minWidth: 520, minHeight: 360)
    }

    private var pathBar: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 4) {
                crumb(peer.name, path: "", icon: "iphone")
                ForEach(breadcrumbs(files.path), id: \.path) { c in
                    Image(systemName: "chevron.right").font(.caption2).foregroundStyle(.tertiary)
                    crumb(c.name, path: c.path, icon: nil)
                }
            }
        }
    }

    private func crumb(_ name: String, path: String, icon: String?) -> some View {
        Button { if path != files.path { model.listFiles(path, peer: peer.id) } } label: {
            if let icon { Label(name, systemImage: icon) } else { Text(name) }
        }
        .buttonStyle(.plain)
        .fontWeight(path == files.path ? .semibold : .regular)
    }

    @ViewBuilder private var placeholder: some View {
        if files.silent {
            hint("Turn on Files in Ferry on your phone")
        } else if !files.error.isEmpty {
            hint(files.error.prefix(1).uppercased() + files.error.dropFirst())
        } else if files.loading {
            ProgressView()
        } else if files.entries.isEmpty {
            hint("Empty folder")
        }
    }

    private func hint(_ text: String) -> some View {
        Text(text).font(.callout).foregroundStyle(.secondary).multilineTextAlignment(.center).padding()
    }

    /// Double-click: a folder opens, files download.
    private func open(_ names: Set<String>) {
        if names.count == 1, let e = files.entries.first(where: { names.contains($0.name) }), e.dir {
            if let path = phonePath(files.path, e.name) { model.listFiles(path, peer: peer.id) }
        } else {
            model.downloadFiles(names, peer: peer.id)
        }
    }
}
