//! Generated Ferry wire messages (see `proto/ferry.proto`).
pub mod v1 {
    include!(concat!(env!("OUT_DIR"), "/ferry.v1.rs"));
}

/// Field numbers are the wire format: a renumbered or reused field silently breaks every
/// installed app. This pins them (a cheap stand-in for `buf breaking`); add new ones, never edit.
#[cfg(test)]
mod frozen {
    use super::v1::{event::Kind, *};
    use prost::Message;

    /// Field number of the first key in `bytes`.
    fn first_field(bytes: &[u8]) -> u64 {
        let (mut key, mut shift) = (0u64, 0);
        for b in bytes {
            key |= u64::from(b & 0x7f) << shift;
            if b & 0x80 == 0 {
                break;
            }
            shift += 7;
        }
        key >> 3
    }

    #[test]
    fn event_kinds_keep_their_numbers() {
        let kinds = [
            (1, Kind::Notification(Default::default())),
            (2, Kind::NotificationRemoved(Default::default())),
            (3, Kind::NotificationAction(Default::default())),
            (4, Kind::Clipboard(Default::default())),
            (5, Kind::NowPlaying(Default::default())),
            (6, Kind::MediaCommand(Default::default())),
            (7, Kind::Battery(Default::default())),
            (8, Kind::Ring(Default::default())),
            (9, Kind::Link(Default::default())),
            (10, Kind::SmsThreadsRequest(Default::default())),
            (11, Kind::SmsThreads(Default::default())),
            (12, Kind::SmsMessagesRequest(Default::default())),
            (13, Kind::SmsMessages(Default::default())),
            (14, Kind::SmsNew(Default::default())),
            (15, Kind::SmsSend(Default::default())),
            (16, Kind::Call(Default::default())),
            (17, Kind::CallCommand(Default::default())),
            (18, Kind::CallLogRequest(Default::default())),
            (19, Kind::CallLog(Default::default())),
            (20, Kind::FsListRequest(Default::default())),
            (21, Kind::FsList(Default::default())),
            (22, Kind::FsGet(Default::default())),
            (23, Kind::WifiNetwork(Default::default())),
            (24, Kind::Dnd(Default::default())),
            (25, Kind::SmsMarkRead(Default::default())),
            (26, Kind::SmsRead(Default::default())),
            (27, Kind::PhoneRunning(Default::default())),
            (28, Kind::CallDial(Default::default())),
        ];
        for (n, kind) in kinds {
            let bytes = Event {
                kind: Some(kind.clone()),
            }
            .encode_to_vec();
            assert_eq!(first_field(&bytes), n, "{kind:?}");
        }
    }

    #[test]
    fn hello_and_pairing_keep_their_numbers() {
        let one = |m: &dyn Fn() -> Vec<u8>| first_field(&m());
        assert_eq!(
            one(&|| Hello {
                protocol_version: 1,
                ..Default::default()
            }
            .encode_to_vec()),
            1
        );
        assert_eq!(
            one(&|| Hello {
                device_name: "a".into(),
                ..Default::default()
            }
            .encode_to_vec()),
            2
        );
        assert_eq!(
            one(&|| Hello {
                channels: vec![1],
                ..Default::default()
            }
            .encode_to_vec()),
            3
        );
        assert_eq!(
            one(&|| Hello {
                addrs: vec!["a".into()],
                ..Default::default()
            }
            .encode_to_vec()),
            4
        );
        assert_eq!(
            one(&|| PairHello {
                proof: vec![1],
                ..Default::default()
            }
            .encode_to_vec()),
            2
        );
        assert_eq!(
            one(&|| PairHello {
                presence_key: vec![1],
                ..Default::default()
            }
            .encode_to_vec()),
            3
        );
        assert_eq!(
            one(&|| SmsThread {
                unread: 1,
                ..Default::default()
            }
            .encode_to_vec()),
            6
        );
        assert_eq!(
            one(&|| FileItem {
                mtime_unix_ms: 1,
                ..Default::default()
            }
            .encode_to_vec()),
            3
        );
        assert_eq!(
            one(&|| FileItem {
                folder: "a".into(),
                ..Default::default()
            }
            .encode_to_vec()),
            4
        );
        assert_eq!(
            one(&|| DataHeader {
                offset: 1,
                ..Default::default()
            }
            .encode_to_vec()),
            3
        );
        assert_eq!(
            one(&|| ItemAccept {
                offset: 1,
                ..Default::default()
            }
            .encode_to_vec()),
            2
        );
    }
}
